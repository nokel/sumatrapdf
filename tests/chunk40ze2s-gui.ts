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
const DATA = join(ROOT, "out", "dbg64");
const CHATTERBOX = resolve(ROOT, "..", "Chatterbox-TTS-Extended-main");
const PYCACHE = join(CHATTERBOX, "audiobook", "cache", "library");
const OUT = process.env.EZ_OUT ?? join(ROOT, "scratchpad", "chunk40ze2s");
const PORT = 7863;
const WAIT_SCAN_S = Number(process.env.EZ_SCAN_WAIT_S ?? "0");
const NEW_A = "chunk40z-the-quiet-river.pdf";
const NEW_B = "chunk40z-the-long-bridge.pdf";
const NEW_C = "chunk40z-the-second-bridge.pdf";

mkdirSync(OUT, { recursive: true });
const traceFile = join(OUT, "trace.txt");
writeFileSync(traceFile, "");

function say(line: string): void {
  console.log(line);
  appendFileSync(traceFile, line + "\n", "utf8");
}

const report: any = { before: {}, discovery: {}, final: {} };

async function getJson(path: string): Promise<any> {
  const rsp = await fetch(`http://127.0.0.1:${PORT}${path}`, { signal: AbortSignal.timeout(6000) });
  if (!rsp.ok) {
    throw new Error(`${path}: HTTP ${rsp.status}`);
  }
  return await rsp.json();
}

// The book list the Library page itself reads while the scan runs.
async function serviceBooks(): Promise<string> {
  try {
    const lib = await getJson("/library?limit=5000");
    return JSON.stringify(lib.books ?? []).toLowerCase();
  } catch (e) {
    return "";
  }
}

async function serviceStatus(): Promise<any> {
  try {
    return await getJson("/status");
  } catch (e) {
    return { down: String(e) };
  }
}

function readIndex(): any {
  try {
    return JSON.parse(readFileSync(join(PYCACHE, "library.json"), "utf8"));
  } catch (e) {
    return null;
  }
}

function indexCounts(): { books: number; documents: number; ignored: number } {
  const idx = readIndex();
  if (!idx) {
    return { books: -1, documents: -1, ignored: -1 };
  }
  return {
    books: (idx.books ?? []).length,
    documents: (idx.documents ?? []).length,
    ignored: (idx.ignored ?? []).length,
  };
}

function indexPileOf(name: string): string {
  const idx = readIndex();
  if (!idx) {
    return "no-index";
  }
  for (const pile of ["books", "documents", "ignored"]) {
    for (const b of idx[pile] ?? []) {
      if (String(b.path ?? "").toLowerCase().includes(name)) {
        return pile;
      }
      for (const e of b.editions ?? []) {
        if (String(e.path ?? "").toLowerCase().includes(name)) {
          return `${pile} (edition)`;
        }
      }
    }
  }
  return "absent";
}

// What the scan itself discovered: the manifest rows it wrote for this run.
function manifest(): { rows: number; hasA: boolean; hasB: boolean } {
  const dbFile = join(PYCACHE, "scan_checkpoint.sqlite3");
  const out = { rows: 0, hasA: false, hasB: false };
  if (!existsSync(dbFile)) {
    return out;
  }
  try {
    const db = new Database(dbFile, { readonly: true });
    for (const r of db.query("SELECT path FROM manifest").all() as any[]) {
      out.rows++;
      const p = String(r.path).toLowerCase();
      if (p.includes(NEW_A)) {
        out.hasA = true;
      }
      if (p.includes(NEW_B)) {
        out.hasB = true;
      }
    }
    db.close();
  } catch (e) {
    return out;
  }
  return out;
}

function journal(): { rows: number; hasA: boolean; hasB: boolean } {
  const dbFile = join(PYCACHE, "scan_checkpoint.sqlite3");
  const out = { rows: 0, hasA: false, hasB: false };
  if (!existsSync(dbFile)) {
    return out;
  }
  try {
    const db = new Database(dbFile, { readonly: true });
    for (const r of db.query("SELECT path FROM completed").all() as any[]) {
      out.rows++;
      const p = String(r.path).toLowerCase();
      if (p.includes(NEW_A)) {
        out.hasA = true;
      }
      if (p.includes(NEW_B)) {
        out.hasB = true;
      }
    }
    db.close();
  } catch (e) {
    return out;
  }
  return out;
}

function checkpointMeta(): any {
  try {
    return JSON.parse(readFileSync(join(PYCACHE, "scan_checkpoint.json"), "utf8"));
  } catch (e) {
    return null;
  }
}

function storeTotal(): number {
  try {
    const m = readFileSync(join(DATA, "SumatraLibrary.txt"), "utf8").match(/^Total = (\d+)/m);
    return m ? Number(m[1]) : -1;
  } catch (e) {
    return -1;
  }
}

function storeHas(name: string): boolean {
  try {
    return readFileSync(join(DATA, "SumatraLibrary.txt"), "utf8").toLowerCase().includes(name);
  } catch (e) {
    return false;
  }
}

function scanStatus(raw: string): Record<string, number> {
  const out: Record<string, number> = {};
  for (const m of String(raw).matchAll(/(\w+)=(-?\d+)/g)) {
    out[m[1]] = Number(m[2]);
  }
  return out;
}

async function libState(client: any): Promise<Record<string, number>> {
  const r = await client.request(ControlCommand.TestLibScanStatus, []);
  return scanStatus(String(r[1] ?? ""));
}

setProcessDpiAware();
const s0 = await serviceStatus();
say(`service before launch: ${JSON.stringify(s0)}`);
if (s0.ok) {
  say("a library service is already running; it must be the one the app starts");
  process.exit(1);
}

const prim = primaryMonitor()!;
const winW = Math.min(2000, prim.work.right - prim.work.left - 120);
const winH = Math.min(1350, prim.work.bottom - prim.work.top - 120);
const winX = prim.work.left + 40;
const winY = prim.work.top + 30;
const posArg = `${winW}x${winH}@${winX}x${winY}`;

report.before = {
  index: indexCounts(),
  storeTotal: storeTotal(),
  checkpoint: checkpointMeta(),
  manifest: manifest(),
  journal: journal(),
  pileA: indexPileOf(NEW_A),
  pileB: indexPileOf(NEW_B),
};
say(`BEFORE index=${JSON.stringify(report.before.index)} store=${report.before.storeTotal}`);
say(`BEFORE checkpoint=${JSON.stringify(report.before.checkpoint)}`);
say(`BEFORE manifest=${JSON.stringify(report.before.manifest)} journal=${JSON.stringify(report.before.journal)}`);
say(`BEFORE new book A pile=${report.before.pileA} new book B pile=${report.before.pileB}`);

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
    showWindow(hwnd, SW_RESTORE);
    await forceForeground(hwnd, 8000);

    let st: Record<string, number> = {};
    const drawn = Date.now() + 90000;
    while (Date.now() < drawn) {
      st = await libState(client);
      if ((st.visible ?? 0) > 0) {
        break;
      }
      await sleep(500);
    }
    say(`library drawn: visible=${st.visible} scanning=${st.scanning} native=${st.native}`);
    captureWindowDCToPng(hwnd, join(OUT, "01-before.png"));

    // The Library scan starts by itself when a scan is pending. If it does not,
    // ask for the same rescan the Library menu runs. No setting is touched.
    let started = false;
    const startBy = Date.now() + 60000;
    while (Date.now() < startBy) {
      const s = await libState(client);
      if ((s.native ?? 0) === 1) {
        started = true;
        report.discovery.autoStarted = true;
        break;
      }
      await sleep(1000);
    }
    if (!started) {
      say("no scan started by itself; asking for a rescan");
      report.discovery.autoStarted = false;
      await client.request(ControlCommand.TestLibRescan, []);
      const startBy2 = Date.now() + 60000;
      while (Date.now() < startBy2) {
        if (((await libState(client)).native ?? 0) === 1) {
          started = true;
          break;
        }
        await sleep(1000);
      }
    }
    report.discovery.scanStarted = started;
    say(`scan started: ${started}`);
    if (!started) {
      throw new Error("the library scan never started");
    }

    // The manifest is what discovery produced. It is posted before any file is
    // read, so this is a cheap and exact answer to "did the scan look there".
    const before = report.before.manifest.rows;
    let m = manifest();
    const manifestBy = Date.now() + 240000;
    while (Date.now() < manifestBy) {
      m = manifest();
      const s = await libState(client);
      if (m.rows !== before || (s.discovered ?? 0) > 0) {
        if (m.hasA || m.hasB || m.rows !== before) {
          break;
        }
      }
      if ((s.done ?? 0) > 5) {
        break;
      }
      await sleep(2000);
    }
    await sleep(4000);
    m = manifest();
    report.discovery.manifest = m;
    report.discovery.state = await libState(client);
    say(`DISCOVERY manifest rows=${m.rows} hasNewBookA=${m.hasA} hasNewBookB=${m.hasB}`);
    say(`DISCOVERY state=${JSON.stringify(report.discovery.state)}`);

    if (WAIT_SCAN_S > 0) {
      say(`waiting up to ${WAIT_SCAN_S}s for the scan to finish`);
      let last = "";
      let sawA = false;
      let sawB = false;
      let sawC = false;
      const deadline = Date.now() + WAIT_SCAN_S * 1000;
      while (Date.now() < deadline) {
        const s = await libState(client);
        const line = `scanning=${s.scanning} native=${s.native} done=${s.done}/${s.total} visible=${s.visible}`;
        if (line !== last) {
          say(`  ${line}`);
          last = line;
        }
        if (!sawC) {
          const lib = await serviceBooks();
          if (lib.includes(NEW_C)) {
            sawC = true;
            report.discovery.bookCInServedListWhileScanning = { at: line, visible: s.visible };
            say(`  new book C is in the list the page reads, while the scan is still running: ${line}`);
          }
        }
        if (!sawA && indexPileOf(NEW_A) !== "absent") {
          sawA = true;
          report.discovery.bookASeenWhileScanning = { at: line, visible: s.visible };
          say(`  new book A is in the index while the scan is still running: ${line}`);
        }
        if (!sawB && indexPileOf(NEW_B) !== "absent") {
          sawB = true;
          report.discovery.bookBSeenWhileScanning = { at: line, visible: s.visible };
          say(`  new book B is in the index while the scan is still running: ${line}`);
        }
        if ((s.native ?? 0) === 0 && (s.done ?? 0) > 0) {
          break;
        }
        await sleep(3000);
      }
      await sleep(8000);
      const s = await libState(client);
      report.final.scanFinished = (s.native ?? 0) === 0;
      report.final.model = s;
      report.final.serviceStatus = await serviceStatus();
      report.final.index = indexCounts();
      report.final.storeTotal = storeTotal();
      report.final.storeHasA = storeHas(NEW_A);
      report.final.storeHasB = storeHas(NEW_B);
      report.final.pileA = indexPileOf(NEW_A);
      report.final.pileB = indexPileOf(NEW_B);
      report.final.pileC = indexPileOf(NEW_C);
      say(`FINAL new book C pile=${report.final.pileC} in store=${storeHas(NEW_C)}`);
      report.final.manifest = manifest();
      report.final.journal = journal();
      report.final.checkpoint = checkpointMeta();
      captureWindowDCToPng(hwnd, join(OUT, "02-after-scan.png"));
      say(`FINAL index=${JSON.stringify(report.final.index)} store=${report.final.storeTotal}`);
      say(`FINAL new book A pile=${report.final.pileA} in store=${report.final.storeHasA}`);
      say(`FINAL new book B pile=${report.final.pileB} in store=${report.final.storeHasB}`);
      say(`FINAL scan_perf=${JSON.stringify(report.final.serviceStatus.scan_perf ?? {})}`);
      say(`FINAL model visible=${s.visible} scanFinished=${report.final.scanFinished}`);
    }
  },
  ["-window-pos", posArg, "-log-to-file", join(OUT, "sumlog.txt")],
  {},
);

writeFileSync(join(OUT, "report.json"), JSON.stringify(report, null, 2), "utf8");
say(`wrote ${join(OUT, "report.json")}`);
