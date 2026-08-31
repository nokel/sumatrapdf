import { existsSync, readFileSync, writeFileSync, appendFileSync, mkdirSync } from "node:fs";
import { join, resolve } from "node:path";
import { Database } from "bun:sqlite";
import { ControlCommand, withControlledSumatra } from "./control.ts";
import {
  setProcessDpiAware,
  primaryMonitor,
  monitorOfWindow,
  moveWindow,
  showWindow,
  isIconic,
  forceForeground,
  captureWindowDCToPng,
  SW_RESTORE,
  sleep,
} from "./winapi.ts";
import { waitForFrame } from "./win-automation.ts";

const ROOT = process.cwd();
const EXE = join(ROOT, "out", "dbg64", "SumatraPDF.exe");
const CHATTERBOX = resolve(ROOT, "..", "Chatterbox-TTS-Extended-main");
const PYCACHE = join(CHATTERBOX, "audiobook", "cache", "library");
const OUT = process.env.ET_OUT ?? join(ROOT, "scratchpad", "chunk40ze2t");
const MODE = process.env.ET_MODE ?? "interrupt";
const WANT = Number(process.env.ET_WANT ?? "60");
const WATCH_S = Number(process.env.ET_WATCH_S ?? "180");
const SHOT = process.env.ET_SHOT ?? "";
const DOC = process.env.ET_DOC ?? "";
const POLL = Number(process.env.ET_POLL_MS ?? "0");
const PORT = 7863;

mkdirSync(OUT, { recursive: true });
const traceFile = join(OUT, `trace-${MODE}.txt`);
writeFileSync(traceFile, "");

function say(line: string): void {
  console.log(line);
  appendFileSync(traceFile, line + "\n", "utf8");
}

function journal(): { rows: number; books: number } {
  const dbFile = join(PYCACHE, "scan_checkpoint.sqlite3");
  if (!existsSync(dbFile)) {
    return { rows: 0, books: 0 };
  }
  try {
    const db = new Database(dbFile, { readonly: true });
    const rows = (db.query("SELECT count(*) AS n FROM completed").get() as any).n;
    const books = (db.query("SELECT count(*) AS n FROM completed WHERE kind = 'books'").get() as any).n;
    const manifest = (db.query("SELECT count(*) AS n FROM manifest").get() as any).n;
    db.close();
    return { rows, books, manifest } as any;
  } catch (e) {
    return { rows: -1, books: -1 };
  }
}

function checkpoint(): any {
  const f = join(PYCACHE, "scan_checkpoint.json");
  if (!existsSync(f)) {
    return null;
  }
  try {
    return JSON.parse(readFileSync(f, "utf8"));
  } catch (e) {
    return { unreadable: String(e) };
  }
}

function indexMeta(): any {
  try {
    const idx = JSON.parse(readFileSync(join(PYCACHE, "library.json"), "utf8"));
    return { generation: idx.generation, books: (idx.books ?? []).length, documents: (idx.documents ?? []).length };
  } catch (e) {
    return { generation: null, books: -1, documents: -1 };
  }
}

async function serviceStatus(): Promise<any> {
  try {
    const rsp = await fetch(`http://127.0.0.1:${PORT}/status`, { signal: AbortSignal.timeout(6000) });
    return await rsp.json();
  } catch (e) {
    return { down: String(e) };
  }
}

function scanStatus(raw: string): Record<string, number> {
  const out: Record<string, number> = {};
  for (const m of String(raw).matchAll(/(\w+)=(-?\d+)/g)) {
    out[m[1]] = Number(m[2]);
  }
  return out;
}

const report: any = { mode: MODE, samples: [] };

setProcessDpiAware();
const prim = primaryMonitor()!;
const winW = Math.min(2000, prim.work.right - prim.work.left - 120);
const winH = Math.min(1350, prim.work.bottom - prim.work.top - 120);
const winX = prim.work.left + 40;
const winY = prim.work.top + 30;
const posArg = `${winW}x${winH}@${winX}x${winY}`;

report.beforeLaunch = {
  checkpoint: checkpoint(),
  journal: journal(),
  index: indexMeta(),
  serviceStatus: await serviceStatus(),
};
say(`before launch: checkpoint=${JSON.stringify(report.beforeLaunch.checkpoint)}`);
say(`before launch: journal=${JSON.stringify(report.beforeLaunch.journal)} index=${JSON.stringify(report.beforeLaunch.index)}`);

await withControlledSumatra(
  EXE,
  async (client, proc) => {
    const launched = Date.now();
    const hwnd = await waitForFrame(proc.pid!, 25000);
    if (!hwnd) {
      throw new Error("no SumatraPDF frame window");
    }
    if (isIconic(hwnd)) {
      showWindow(hwnd, SW_RESTORE);
    }
    const mon = monitorOfWindow(hwnd)!;
    if (!mon.primary) {
      moveWindow(hwnd, winX, winY, winW, winH, true);
      await sleep(400);
    }
    await forceForeground(hwnd, 8000);

    let first: Record<string, number> = {};
    const drawDeadline = Date.now() + (DOC ? 4000 : 120000);
    while (Date.now() < drawDeadline) {
      first = scanStatus(String((await client.request(ControlCommand.TestLibScanStatus, []))[1] ?? ""));
      if ((first.visible ?? 0) > 0) {
        break;
      }
      await sleep(400);
    }
    if (DOC) {
      say(`started with a document open: ${DOC}`);
    }
    report.booksVisibleAfterMs = Date.now() - launched;
    report.booksVisibleFirst = first.visible ?? 0;
    say(`library drawn: visible=${first.visible} after ${report.booksVisibleAfterMs} ms`);

    if (MODE === "interrupt") {
      say("asking for a library scan");
      await client.request(ControlCommand.TestLibRescan, []);
    } else {
      say("NOT asking for a rescan; watching for an automatic resume");
    }

    const deadline = Date.now() + (MODE === "interrupt" ? 1800000 : WATCH_S * 1000);
    let last = "";
    let resumedAt = 0;
    let firstDone = -1;
    let maxDone = 0;
    let maxTotal = 0;
    let traversals = 0;
    let sawFinish = false;
    while (Date.now() < deadline) {
      const s = scanStatus(String((await client.request(ControlCommand.TestLibScanStatus, []))[1] ?? ""));
      const j = journal();
      const ck = checkpoint();
      const line =
        `t=${Math.round((Date.now() - launched) / 1000)}s scanning=${s.scanning} native=${s.native} ` +
        `done=${s.done}/${s.total} traversals=${s.traversals} discovered=${s.discovered} ` +
        `visible=${s.visible} journal=${j.rows} ckDone=${ck ? ck.done : "-"}`;
      if (line.replace(/t=\d+s /, "") !== last) {
        say(`  ${line}`);
        last = line.replace(/t=\d+s /, "");
        report.samples.push({ ms: Date.now() - launched, ...s, journal: j.rows, ckDone: ck ? ck.done : null });
      }
      if ((s.native ?? 0) === 1 && resumedAt === 0) {
        resumedAt = Date.now() - launched;
        say(`  a scan is running in this process ${resumedAt} ms after launch, with no Rescan command`);
      }
      if ((s.native ?? 0) === 1 && (s.total ?? 0) > 0 && firstDone < 0) {
        firstDone = s.done ?? 0;
        say(`  first reading progress seen: done=${firstDone}/${s.total}`);
        if (SHOT) {
          try {
            captureWindowDCToPng(hwnd, join(OUT, `during-${SHOT}`));
            say(`  screenshot while the resumed scan runs: ${join(OUT, `during-${SHOT}`)}`);
          } catch (e) {
            say(`  screenshot failed: ${e}`);
          }
        }
      }
      if ((s.native ?? 0) === 1 && (s.total ?? 0) > 0) {
        report.readingSamples = report.readingSamples ?? [];
        report.readingSamples.push([Date.now() - launched, s.done ?? 0, s.total ?? 0]);
      }
      if (SHOT && firstDone >= 0 && !report.midShot && (s.done ?? 0) >= firstDone + 25) {
        report.midShot = true;
        try {
          captureWindowDCToPng(hwnd, join(OUT, `mid-${SHOT}`));
          say(`  screenshot at done=${s.done}/${s.total}: ${join(OUT, `mid-${SHOT}`)}`);
        } catch (e) {
          say(`  screenshot failed: ${e}`);
        }
      }
      maxDone = Math.max(maxDone, s.done ?? 0);
      maxTotal = Math.max(maxTotal, s.total ?? 0);
      traversals = Math.max(traversals, s.traversals ?? 0);
      if (MODE === "interrupt" && j.books >= WANT) {
        say(`the journal holds ${j.books} completed books; closing the app now`);
        break;
      }
      if (MODE !== "interrupt" && resumedAt > 0 && (s.native ?? 0) === 0 && (s.scanning ?? 0) === 0 && maxDone > 0) {
        sawFinish = true;
        say("the resumed scan is no longer running");
        break;
      }
      await sleep(POLL > 0 ? POLL : MODE === "interrupt" ? 2000 : 3000);
    }
    if (SHOT) {
      try {
        captureWindowDCToPng(hwnd, join(OUT, SHOT));
        say(`screenshot: ${join(OUT, SHOT)}`);
      } catch (e) {
        say(`screenshot failed: ${e}`);
      }
    }
    report.resumedAtMs = resumedAt;
    report.firstReadingDone = firstDone;
    report.maxDone = maxDone;
    report.maxTotal = maxTotal;
    report.traversals = traversals;
    report.sawFinish = sawFinish;
    report.duringStatus = await serviceStatus();
  },
  DOC
    ? ["-window-pos", posArg, "-log-to-file", join(OUT, `sumlog-${MODE}.txt`), DOC]
    : ["-window-pos", posArg, "-log-to-file", join(OUT, `sumlog-${MODE}.txt`)],
  {},
);

for (let i = 0; i < 120; i++) {
  try {
    const r = await fetch(`http://127.0.0.1:${PORT}/status`, { signal: AbortSignal.timeout(3000) });
    if (!r.ok) {
      break;
    }
  } catch (e) {
    break;
  }
  await sleep(1000);
}

report.afterClose = { checkpoint: checkpoint(), journal: journal(), index: indexMeta() };
say(`after close: checkpoint=${JSON.stringify(report.afterClose.checkpoint)}`);
say(`after close: journal=${JSON.stringify(report.afterClose.journal)} index=${JSON.stringify(report.afterClose.index)}`);

const log = join(OUT, `sumlog-${MODE}.txt`);
if (existsSync(log)) {
  const text = readFileSync(log, "utf8");
  const rescanLines = text.split(/\r?\n/).filter((l) => /RescanThread|libRescan|scan resume|auto-sweep/i.test(l));
  report.rescanLogLines = rescanLines;
  for (const l of rescanLines) {
    say(`  log: ${l}`);
  }
}

writeFileSync(join(OUT, `report-${MODE}.json`), JSON.stringify(report, null, 2));
say(`wrote ${join(OUT, `report-${MODE}.json`)}`);
