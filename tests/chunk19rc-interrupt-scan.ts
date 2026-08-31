import { existsSync, writeFileSync, appendFileSync, mkdirSync, readFileSync } from "node:fs";
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
  SW_RESTORE,
  sleep,
} from "./winapi.ts";
import { waitForFrame } from "./win-automation.ts";

const ROOT = process.cwd();
const EXE = join(ROOT, "out", "dbg64", "SumatraPDF.exe");
const CHATTERBOX = resolve(ROOT, "..", "Chatterbox-TTS-Extended-main");
const PYCACHE = join(CHATTERBOX, "audiobook", "cache", "library");
const OUT = process.env.RC_OUT ?? join(ROOT, "scratchpad", "chunk19rc-interrupt");
const WANT = Number(process.env.RC_WANT_ROWS ?? "400");
const PORT = 7863;

mkdirSync(OUT, { recursive: true });
const traceFile = join(OUT, "trace.txt");
writeFileSync(traceFile, "");

function say(line: string): void {
  console.log(line);
  appendFileSync(traceFile, line + "\n", "utf8");
}

function journalRows(): { rows: number; books: number } {
  const dbFile = join(PYCACHE, "scan_checkpoint.sqlite3");
  if (!existsSync(dbFile)) {
    return { rows: 0, books: 0 };
  }
  try {
    const db = new Database(dbFile, { readonly: true });
    const rows = (db.query("SELECT count(*) AS n FROM completed").get() as any).n;
    const books = (db.query("SELECT count(*) AS n FROM completed WHERE kind = 'books'").get() as any).n;
    db.close();
    return { rows, books };
  } catch (e) {
    return { rows: -1, books: -1 };
  }
}

function scanStatus(raw: string): Record<string, number> {
  const out: Record<string, number> = {};
  for (const m of String(raw).matchAll(/(\w+)=(-?\d+)/g)) {
    out[m[1]] = Number(m[2]);
  }
  return out;
}

setProcessDpiAware();
const prim = primaryMonitor()!;
const winW = Math.min(2000, prim.work.right - prim.work.left - 120);
const winH = Math.min(1350, prim.work.bottom - prim.work.top - 120);
const winX = prim.work.left + 40;
const winY = prim.work.top + 30;
const posArg = `${winW}x${winH}@${winX}x${winY}`;

await withControlledSumatra(
  EXE,
  async (client, proc) => {
    const hwnd = await waitForFrame(proc.pid!, 20000);
    if (!hwnd) {
      throw new Error("no SumatraPDF frame window");
    }
    if (isIconic(hwnd)) {
      showWindow(hwnd, SW_RESTORE);
    }
    let mon = monitorOfWindow(hwnd)!;
    if (!mon.primary) {
      moveWindow(hwnd, winX, winY, winW, winH, true);
      await sleep(400);
    }
    await forceForeground(hwnd, 8000);
    let st: Record<string, number> = {};
    const deadline = Date.now() + 90000;
    while (Date.now() < deadline) {
      st = scanStatus(String((await client.request(ControlCommand.TestLibScanStatus, []))[1] ?? ""));
      if ((st.visible ?? 0) > 0) {
        break;
      }
      await sleep(500);
    }
    say(`library drawn: visible=${st.visible}`);
    await sleep(2000);

    say("starting a library scan");
    await client.request(ControlCommand.TestLibRescan, []);

    const scanDeadline = Date.now() + 1800000;
    let last = "";
    while (Date.now() < scanDeadline) {
      const s = scanStatus(String((await client.request(ControlCommand.TestLibScanStatus, []))[1] ?? ""));
      const j = journalRows();
      const line = `scanning=${s.scanning} native=${s.native} done=${s.done}/${s.total} journal=${j.rows} books=${j.books}`;
      if (line !== last) {
        say(`  ${line}`);
        last = line;
      }
      if (j.books >= WANT) {
        say(`the journal holds ${j.books} books; closing the app to interrupt the scan`);
        break;
      }
      if ((s.native ?? 0) === 0 && (s.done ?? 0) > 0 && (s.done ?? 0) >= (s.total ?? 1)) {
        say("the scan finished before the journal reached the wanted size");
        break;
      }
      await sleep(4000);
    }
  },
  ["-window-pos", posArg, "-log-to-file", join(OUT, "sumlog.txt")],
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
const ck = existsSync(join(PYCACHE, "scan_checkpoint.json"))
  ? JSON.parse(readFileSync(join(PYCACHE, "scan_checkpoint.json"), "utf8"))
  : null;
say(`after the interrupted scan: checkpoint=${JSON.stringify(ck)} journal=${JSON.stringify(journalRows())}`);
