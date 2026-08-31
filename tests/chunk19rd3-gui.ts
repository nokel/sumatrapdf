import { existsSync, readFileSync, writeFileSync, appendFileSync, mkdirSync, copyFileSync } from "node:fs";
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
  getClientRect,
  clientToScreen,
  realMouseMove,
  realMouseClick,
  captureWindowDCToPng,
  readWindowDCColumn,
  SW_RESTORE,
  sleep,
} from "./winapi.ts";
import { findCanvas, waitForFrame } from "./win-automation.ts";

const ROOT = process.cwd();
const EXE = join(ROOT, "out", "dbg64", "SumatraPDF.exe");
const DATA = join(ROOT, "out", "dbg64");
const CHATTERBOX = resolve(ROOT, "..", "Chatterbox-TTS-Extended-main");
const PYCACHE = join(CHATTERBOX, "audiobook", "cache", "library");
const STORE = join(PYCACHE, "deskpan.json");
const PORT = 7863;
const MODE = process.env.RD3_MODE ?? "migrate";
const OUT = process.env.RD3_OUT ?? join(ROOT, "scratchpad", "chunk19rd3-" + MODE);
const SCAN_S = Number(process.env.RD3_SCAN_S ?? "900");
const A =
  process.env.RD3_A ??
  "C:\\Users\\Nokel\\Documents\\ebooks\\manga_novels\\Animorphs\\Animorphs\\03-The Encounter.pdf";

mkdirSync(OUT, { recursive: true });
const traceFile = join(OUT, "trace.txt");
writeFileSync(traceFile, "");

function say(line: string): void {
  console.log(line);
  appendFileSync(traceFile, line + "\n", "utf8");
}

function key(path: string): string {
  return path.replace(/\//g, "\\").toLowerCase();
}

function readJson(path: string): any {
  try {
    return JSON.parse(readFileSync(path, "utf8"));
  } catch (e) {
    return null;
  }
}

function desk(): any {
  return readJson(STORE) ?? { kinds: {}, pending: {}, excluded: {} };
}

function facts(path: string): any {
  const d = desk();
  const k = key(path);
  return {
    kind: (d.kinds ?? {})[k] ?? "untold",
    pending: (d.pending ?? {})[k] ?? null,
    excluded: (d.excluded ?? {})[k] ?? null,
    onDisk: existsSync(path),
  };
}

function identityExcluded(mark: string): string[] {
  const d = desk();
  const out: string[] = [];
  for (const [p, rec] of Object.entries(d.excluded ?? {})) {
    if (String((rec as any).identity ?? "") === mark) {
      out.push(p);
    }
  }
  return out;
}

function indexCounts(): any {
  const idx = readJson(join(PYCACHE, "library.json"));
  if (!idx) {
    return { books: -1, documents: -1, ignored: -1 };
  }
  return {
    books: (idx.books ?? []).length,
    documents: (idx.documents ?? []).length,
    ignored: (idx.ignored ?? []).length,
  };
}

function pileOf(path: string): string {
  const idx = readJson(join(PYCACHE, "library.json"));
  const k = key(path);
  if (!idx) {
    return "unset";
  }
  for (const name of ["books", "documents", "ignored"]) {
    for (const b of idx[name] ?? []) {
      if (key(String(b.path ?? "")) === k) {
        return name;
      }
      for (const e of b.editions ?? []) {
        if (key(String(e.path ?? "")) === k) {
          return name + " (edition)";
        }
      }
    }
  }
  return "absent";
}

async function getJson(path: string): Promise<any> {
  try {
    const rsp = await fetch("http://127.0.0.1:" + PORT + path, { signal: AbortSignal.timeout(8000) });
    return await rsp.json();
  } catch (e) {
    return { down: String(e) };
  }
}

async function ignoredList(): Promise<{ total: number; has: boolean }> {
  const d = await getJson("/deskpan?show=ignored&limit=500");
  const files = (d.files ?? []).map((f: any) => key(String(f.path ?? "")));
  return { total: d.total ?? files.length, has: files.includes(key(A)) };
}

async function snapshot(label: string): Promise<any> {
  const list = await ignoredList();
  const d = desk();
  const f = facts(A);
  const s = {
    index: indexCounts(),
    counts: {
      kinds: Object.keys(d.kinds ?? {}).length,
      pending: Object.keys(d.pending ?? {}).length,
      excluded: (d.excludedFingerprints ?? []).length,
    },
    A: {
      ...f,
      pile: pileOf(A),
      inIgnoredList: list.has,
      ignoredTotal: list.total,
      pendingIdentity: f.pending ? f.pending.identity : null,
      excludedIdentity: f.excluded ? f.excluded.identity : null,
    },
  };
  say(label + ": " + JSON.stringify(s));
  return s;
}

function writeStore(data: any): void {
  writeFileSync(STORE, JSON.stringify(data, null, 1), "utf8");
}

function prepare(): any {
  const d = desk();
  d.kinds = d.kinds ?? {};
  d.pending = d.pending ?? {};
  d.excluded = d.excluded ?? {};
  d.kinds[key(A)] = "ignored";
  delete d.pending[key(A)];
  writeStore(d);
  copyFileSync(STORE, join(OUT, "deskpan-before.json"));
  say("prepared a pre-timer Ignored record with a path and nothing else: " + A);
  return facts(A);
}

function ageOut(path: string): any {
  const d = desk();
  const rec = (d.pending ?? {})[key(path)];
  if (!rec) {
    throw new Error("no pending record to age for " + path);
  }
  const was = { since: rec.since, days: rec.days, expires: rec.expires, identity: rec.identity };
  rec.expires = Date.now() / 1000 - 60;
  writeStore(d);
  say("moved the timer into the past: " + JSON.stringify(was));
  return was;
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

async function runScan(client: any): Promise<any> {
  say("asking for a real Library rescan");
  const started = Date.now();
  await client.request(ControlCommand.TestLibRescan, []);
  const deadline = Date.now() + SCAN_S * 1000;
  let seen = false;
  let last = "";
  while (Date.now() < deadline) {
    const raw = String((await client.request(ControlCommand.TestLibScanStatus, []))[1] ?? "").trim();
    if (raw !== last) {
      say("  " + raw);
      last = raw;
    }
    const st = scanStatus(raw);
    if ((st.native ?? 0) === 1) {
      seen = true;
    }
    if (seen && (st.native ?? 0) === 0 && (st.scanning ?? 0) === 0) {
      break;
    }
    await sleep(3000);
  }
  const secs = Math.round((Date.now() - started) / 1000);
  say("the scan took " + secs + " s");
  await sleep(6000);
  return { seconds: secs, final: last };
}

async function openDeskpanView(hwnd: number, canvas: number, out: string): Promise<boolean> {
  const sample = () => readWindowDCColumn(canvas, 700, 0, Math.min(600, getClientRect(canvas).bottom));
  const differs = (a: number[], b: number[]) => {
    let n = 0;
    for (let i = 0; i < Math.min(a.length, b.length); i++) {
      if (a[i] !== b[i]) {
        n++;
      }
    }
    return n / Math.max(1, Math.min(a.length, b.length));
  };
  const prime = clientToScreen(canvas, 40, 60);
  realMouseMove(prime.x, prime.y);
  await sleep(300);
  realMouseClick("left");
  await sleep(2500);
  let seen = sample();
  for (let i = 0; i < 10; i++) {
    await sleep(700);
    const again = sample();
    const same = differs(seen, again) === 0;
    seen = again;
    if (same) {
      break;
    }
  }
  let opened = false;
  for (let y = 66; y <= 156 && !opened; y += 6) {
    const pt = clientToScreen(canvas, 40, y);
    realMouseMove(pt.x, pt.y);
    await sleep(220);
    realMouseClick("left");
    await sleep(1600);
    const now = sample();
    const d = differs(seen, now);
    seen = now;
    if (d > 0.25) {
      say("  the Deskpan rail row is at canvas y " + y);
      opened = true;
    }
  }
  if (!opened) {
    say("  the Deskpan page did not open");
    return false;
  }
  captureWindowDCToPng(hwnd, join(out, "deskpan.png"));
  return true;
}

async function openIgnoredView(hwnd: number, canvas: number, out: string): Promise<boolean> {
  if (!(await openDeskpanView(hwnd, canvas, out))) {
    return false;
  }
  const tab = clientToScreen(canvas, 327, 104);
  realMouseMove(tab.x, tab.y);
  await sleep(300);
  realMouseClick("left");
  await sleep(2500);
  captureWindowDCToPng(hwnd, join(out, "ignored.png"));
  return true;
}

const report: any = { mode: MODE, exe: EXE, A };
if (MODE === "migrate") {
  report.prepared = prepare();
} else if (MODE === "expire") {
  report.aged = ageOut(A);
  copyFileSync(STORE, join(OUT, "deskpan-before.json"));
} else {
  copyFileSync(STORE, join(OUT, "deskpan-before.json"));
  report.beforeLaunch = facts(A);
}
say("BEFORE LAUNCH: " + JSON.stringify(report.prepared ?? report.beforeLaunch ?? report.aged));

setProcessDpiAware();
const prim = primaryMonitor()!;
const winW = Math.min(1700, prim.work.right - prim.work.left - 160);
const winH = Math.min(1150, prim.work.bottom - prim.work.top - 160);
const winX = prim.work.left + 60;
const winY = prim.work.top + 40;
const posArg = winW + "x" + winH + "@" + winX + "x" + winY;

await withControlledSumatra(
  EXE,
  async (client, proc) => {
    const pid = proc.pid!;
    const hwnd = await waitForFrame(pid, 25000);
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
    showWindow(hwnd, SW_RESTORE);
    const fg = await forceForeground(hwnd, 8000);
    say("frame 0x" + hwnd.toString(16) + " pid=" + pid + " foreground=" + fg);

    say("waiting for the Library to draw");
    let st: Record<string, number> = {};
    const deadline = Date.now() + 120000;
    while (Date.now() < deadline) {
      st = await libState(client);
      if ((st.visible ?? 0) > 0) {
        break;
      }
      await sleep(600);
    }
    say("model: " + JSON.stringify(st));
    await sleep(2000);
    const canvas = findCanvas(hwnd);

    const wait = Number(process.env.RD3_WAIT_S ?? "45");
    const want = MODE === "expire" ? "excluded" : "pending";
    const until = Date.now() + wait * 1000;
    while (Date.now() < until) {
      const f = facts(A);
      const ready =
        want === "pending" ? f.pending !== null && pileOf(A) === "ignored" : f.excluded !== null;
      if (ready) {
        break;
      }
      await sleep(1000);
    }
    report.after = await snapshot("AFTER LAUNCH");
    copyFileSync(STORE, join(OUT, "deskpan-after.json"));

    if (MODE === "scan" || process.env.RD3_SCAN === "1") {
      report.scan = await runScan(client);
      report.afterScan = await snapshot("AFTER RESCAN");
      copyFileSync(STORE, join(OUT, "deskpan-after-scan.json"));
    } else {
      report.scanStatus = await libState(client);
      say("scan counters with no rescan asked for: " + JSON.stringify(report.scanStatus));
      report.rescanInvoked = false;
    }

    await client.request(ControlCommand.TestLibAllBooks, []);
    await sleep(2500);
    captureWindowDCToPng(hwnd, join(OUT, "01-library.png"));
    report.ignoredViewOpened = await openIgnoredView(hwnd, canvas, OUT);
    if (process.env.RD3_IDENTITY) {
      report.excludedByIdentity = identityExcluded(process.env.RD3_IDENTITY);
      say("exclusions holding identity " + process.env.RD3_IDENTITY + ": " + JSON.stringify(report.excludedByIdentity));
    }
    report.final = await snapshot("FINAL");
    if (!report.rescanInvoked) {
      report.scanStatusFinal = await libState(client);
      say("scan counters at the end: " + JSON.stringify(report.scanStatusFinal));
    }
    writeFileSync(join(OUT, "report.json"), JSON.stringify(report, null, 2));
  },
  ["-window-pos", posArg, "-log-to-file", join(OUT, "sumlog.txt")],
);

say("wrote " + join(OUT, "report.json"));
