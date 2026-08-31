import { existsSync, readFileSync, writeFileSync, appendFileSync, mkdirSync } from "node:fs";
import { join, resolve } from "node:path";
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
  getClientRect,
  readWindowDCColumn,
  clientToScreen,
  realMouseMove,
  realMouseClick,
  SW_RESTORE,
  sleep,
} from "./winapi.ts";
import { waitForFrame, findCanvas } from "./win-automation.ts";

const ROOT = process.cwd();
const EXE = join(ROOT, "out", "dbg64", "SumatraPDF.exe");
const STORE = join(ROOT, "out", "dbg64", "SumatraLibrary.txt");
const OUT = process.env.ET_OUT ?? join(ROOT, "scratchpad", "chunk40ze2w");
const MODE = process.env.ET_MODE ?? "look";
const SERIES = process.env.ET_SERIES ?? "series:chunk36work";
const SCAN_S = Number(process.env.ET_SCAN_S ?? "3000");
const PORT = 7863;
const PYCACHE = resolve(ROOT, "..", "Chatterbox-TTS-Extended-main", "audiobook", "cache", "library");

const SEP = String.fromCharCode(92);
const LETTER = ["C:", "Users", "Nokel", "Desktop", "20260827 - Nicholas Calleja.pdf"].join(SEP);

mkdirSync(OUT, { recursive: true });
const traceFile = join(OUT, "trace-" + MODE + ".txt");
writeFileSync(traceFile, "");

function say(line: string): void {
  console.log(line);
  appendFileSync(traceFile, line + "\n", "utf8");
}

function pileOf(idx: any, path: string): string {
  const want = path.toLowerCase();
  for (const name of ["books", "documents", "ignored"]) {
    for (const b of idx[name] ?? []) {
      if (String(b.path ?? "").toLowerCase() === want) {
        return name;
      }
      for (const e of b.editions ?? []) {
        if (String(e.path ?? "").toLowerCase() === want) {
          return name + " (an edition of " + b.title + ")";
        }
      }
    }
  }
  return "absent";
}

function indexFacts(): any {
  try {
    const idx = JSON.parse(readFileSync(join(PYCACHE, "library.json"), "utf8"));
    const shortTitles: any[] = [];
    const deskLike: any[] = [];
    const pending: any = { books: 0, documents: 0 };
    for (const name of ["books", "documents", "ignored"]) {
      for (const b of idx[name] ?? []) {
        const t = String(b.title ?? "").trim();
        if (t.length <= 2) {
          shortTitles.push({
            pile: name,
            title: t,
            path: b.path,
            source: b.title_source,
            editions: (b.editions ?? []).map((e: any) => e.path),
          });
        }
        if (b.details_pending && (name === "books" || name === "documents")) {
          pending[name]++;
        }
        if (name === "books" && String(b.path ?? "").toLowerCase().includes("desktop")) {
          deskLike.push({
            title: b.title,
            path: b.path,
            pages: b.pages,
            kind: b.kind,
            details_pending: !!b.details_pending,
          });
        }
      }
    }
    return {
      books: (idx.books ?? []).length,
      documents: (idx.documents ?? []).length,
      ignored: (idx.ignored ?? []).length,
      letterPile: pileOf(idx, LETTER),
      shortTitles,
      desktopBooks: deskLike,
      detailsPending: pending,
    };
  } catch (e) {
    return { error: String(e) };
  }
}

function storeTitles(): any {
  if (!existsSync(STORE)) {
    return { error: "no store" };
  }
  const lines = readFileSync(STORE, "utf8").split(/\r?\n/);
  const out: string[] = [];
  let title = "";
  for (const l of lines) {
    const t = l.match(/^\s*Title = (.*)$/);
    if (t) {
      title = t[1];
    }
    const p = l.match(/^\s*Path = (.*)$/);
    if (p && title.trim().length <= 2) {
      out.push(JSON.stringify(title) + " <- " + p[1]);
    }
  }
  const letter = lines.findIndex((l) => l.trim() === "Path = " + LETTER);
  return { shortTitleCards: out, letterLineInStore: letter };
}

async function serviceFacts(): Promise<any> {
  try {
    const rsp = await fetch("http://127.0.0.1:" + PORT + "/status", { signal: AbortSignal.timeout(8000) });
    const j: any = await rsp.json();
    return { books: j.books, documents: j.documents, scan_perf: j.scan_perf };
  } catch (e) {
    return { down: String(e) };
  }
}

function railRows(canvas: number): { selTop: number; selHeight: number } | null {
  const cr = getClientRect(canvas);
  const col = readWindowDCColumn(canvas, 14, 0, Math.min(320, cr.bottom));
  const counts = new Map<number, number>();
  for (const c of col) {
    counts.set(c, (counts.get(c) ?? 0) + 1);
  }
  let base = -1;
  let most = -1;
  for (const [c, n] of counts) {
    if (n > most) {
      most = n;
      base = c;
    }
  }
  let bestTop = -1;
  let bestLen = 0;
  let top = -1;
  for (let y = 0; y < col.length; y++) {
    if (col[y] !== base) {
      if (top < 0) {
        top = y;
      }
    } else if (top >= 0) {
      if (y - top > bestLen) {
        bestLen = y - top;
        bestTop = top;
      }
      top = -1;
    }
  }
  if (bestTop < 0 || bestLen < 8) {
    return null;
  }
  return { selTop: bestTop, selHeight: bestLen };
}

const report: any = { mode: MODE };

setProcessDpiAware();
const prim = primaryMonitor()!;
const winW = Math.min(1700, prim.work.right - prim.work.left - 160);
const winH = Math.min(1150, prim.work.bottom - prim.work.top - 160);
const winX = prim.work.left + 60;
const winY = prim.work.top + 40;
const posArg = winW + "x" + winH + "@" + winX + "x" + winY;

report.indexBefore = indexFacts();
say("index before: " + JSON.stringify(report.indexBefore, null, 1));
report.storeBefore = storeTitles();
say("store before: " + JSON.stringify(report.storeBefore));

await withControlledSumatra(
  EXE,
  async (client, proc) => {
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
    await sleep(2500);

    if (MODE === "scan") {
      say("asking for a library scan");
      const started = Date.now();
      await client.request(ControlCommand.TestLibRescan, []);
      const deadline = Date.now() + SCAN_S * 1000;
      let last = "";
      while (Date.now() < deadline) {
        const s = String((await client.request(ControlCommand.TestLibScanStatus, []))[1] ?? "").trim();
        if (s !== last) {
          say("  " + s);
          last = s;
        }
        if (/scanning=0 /.test(s) && /native=0/.test(s) && report.scanSeen) {
          break;
        }
        if (/native=1/.test(s)) {
          report.scanSeen = true;
        }
        await sleep(3000);
      }
      report.scanSeconds = Math.round((Date.now() - started) / 1000);
      report.scanFinal = last;
      report.scanPerf = await serviceFacts();
      say("scan took " + report.scanSeconds + " s");
      say("scan perf: " + JSON.stringify(report.scanPerf));
      await sleep(5000);
    }

    const canvas = findCanvas(hwnd);
    report.canvas = canvas;
    say("canvas " + canvas + " client " + JSON.stringify(getClientRect(canvas)));

    await client.request(ControlCommand.TestLibAllBooks, []);
    await sleep(2000);
    captureWindowDCToPng(hwnd, join(OUT, "allbooks-" + MODE + ".png"));
    say("screenshot of All books: " + join(OUT, "allbooks-" + MODE + ".png"));

    const rows = railRows(canvas);
    report.railRows = rows;
    say("rail selected row: " + JSON.stringify(rows));
    if (rows) {
      const gap = Math.max(2, Math.round((rows.selHeight * 4) / 26));
      const deskCentre = rows.selTop + rows.selHeight + gap + Math.floor(rows.selHeight / 2);
      const p = clientToScreen(canvas, 40, deskCentre);
      say("clicking the Deskpan row at canvas y " + deskCentre + " (screen " + p.x + "," + p.y + ")");
      realMouseMove(p.x, p.y);
      await sleep(300);
      realMouseClick("left");
      await sleep(2500);
      captureWindowDCToPng(hwnd, join(OUT, "deskpan-" + MODE + ".png"));
      say("screenshot of Deskpan: " + join(OUT, "deskpan-" + MODE + ".png"));
    }

    await client.request(ControlCommand.TestLibAllBooks, []);
    await sleep(1200);
    await client.request(ControlCommand.TestLibSeries, [SERIES]);
    await sleep(2500);
    captureWindowDCToPng(hwnd, join(OUT, "series-" + MODE + ".png"));
    say("screenshot of the series " + SERIES + ": " + join(OUT, "series-" + MODE + ".png"));
  },
  ["-window-pos", posArg, "-log-to-file", join(OUT, "sumlog-" + MODE + ".txt")],
  {},
);

report.indexAfter = indexFacts();
say("index after: " + JSON.stringify(report.indexAfter, null, 1));
report.storeAfter = storeTitles();
say("store after: " + JSON.stringify(report.storeAfter));
writeFileSync(join(OUT, "report-" + MODE + ".json"), JSON.stringify(report, null, 2));
say("wrote " + join(OUT, "report-" + MODE + ".json"));
