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
const CHATTERBOX = resolve(ROOT, "..", "Chatterbox-TTS-Extended-main");
const PYCACHE = join(CHATTERBOX, "audiobook", "cache", "library");
const STORE = join(PYCACHE, "deskpan.json");
const PORT = 7863;
const MODE = process.env.RD4_MODE ?? "scan";
const OUT = process.env.RD4_OUT ?? join(ROOT, "scratchpad", "chunk19rd4-" + MODE);
const SCAN_S = Number(process.env.RD4_SCAN_S ?? "900");
const A = process.env.RD4_A ?? "";
const B = process.env.RD4_B ?? "";
const MARK = (process.env.RD4_ID ?? "").toLowerCase();

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
  return readJson(STORE) ?? { kinds: {}, pending: {}, excludedFingerprints: [] };
}

function marks(): string[] {
  return (desk().excludedFingerprints ?? []).map((m: any) => String(m).toLowerCase());
}

function facts(path: string): any {
  if (path.length === 0) {
    return null;
  }
  const d = desk();
  const k = key(path);
  return {
    kind: (d.kinds ?? {})[k] ?? "untold",
    pending: (d.pending ?? {})[k] ?? null,
    onDisk: existsSync(path),
    pile: pileOf(path),
  };
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

async function postJson(path: string, body: any): Promise<any> {
  try {
    const rsp = await fetch("http://127.0.0.1:" + PORT + path, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify(body),
      signal: AbortSignal.timeout(30000),
    });
    return { status: rsp.status, body: await rsp.json() };
  } catch (e) {
    return { status: -1, body: { down: String(e) } };
  }
}

async function ignoredTotal(): Promise<number> {
  const d = await getJson("/deskpan?show=ignored&limit=500");
  return d.total ?? -1;
}

async function serviceView(): Promise<any> {
  const lib = await getJson("/library?limit=5000");
  const rows = lib.books ?? [];
  const paths = JSON.stringify(rows).toLowerCase();
  return {
    books: lib.total ?? rows.length,
    hasA: A.length > 0 ? paths.includes(key(A).replace(/\\/g, "\\\\")) : null,
    hasB: B.length > 0 ? paths.includes(key(B).replace(/\\/g, "\\\\")) : null,
  };
}

async function snapshot(label: string): Promise<any> {
  const d = desk();
  const s = {
    service: await serviceView(),
    index: indexCounts(),
    counts: {
      kinds: Object.keys(d.kinds ?? {}).length,
      pending: Object.keys(d.pending ?? {}).length,
    },
    excludedFingerprints: d.excludedFingerprints ?? [],
    markExcluded: MARK.length > 0 ? marks().includes(MARK) : null,
    ignoredTotal: await ignoredTotal(),
    A: facts(A),
    B: facts(B),
  };
  say(label + ": " + JSON.stringify(s));
  return s;
}

function writeStore(data: any): void {
  writeFileSync(STORE, JSON.stringify(data, null, 1), "utf8");
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
  const perf = (await getJson("/status")).scan_perf;
  say("scan perf: " + JSON.stringify(perf));
  return { seconds: secs, final: last, perf };
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

const report: any = { mode: MODE, exe: EXE, A, B, mark: MARK };
if (MODE === "expire") {
  report.aged = ageOut(A);
}
copyFileSync(STORE, join(OUT, "deskpan-before.json"));

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
    const ready = Date.now() + 90000;
    let answers = false;
    while (Date.now() < ready) {
      const s = await getJson("/status");
      if (!s.down) {
        answers = true;
        break;
      }
      await sleep(1000);
    }
    say("the library service answers: " + answers);
    await sleep(2000);
    const canvas = findCanvas(hwnd);

    if (MODE === "expire") {
      const until = Date.now() + Number(process.env.RD4_WAIT_S ?? "60") * 1000;
      while (Date.now() < until) {
        if (MARK.length > 0 && marks().includes(MARK)) {
          break;
        }
        await sleep(1000);
      }
    }
    report.after = await snapshot("AFTER LAUNCH");
    copyFileSync(STORE, join(OUT, "deskpan-after.json"));

    if (MODE === "stale") {
      const item = readJson(process.env.RD4_ITEM ?? "");
      const roots = readJson(join(PYCACHE, "library.json"))?.roots ?? [];
      const rsp = await postJson("/book", { roots, scope: 0, book: item });
      report.staleBook = rsp;
      say("stale POST /book: " + JSON.stringify(rsp));
    }

    if (MODE === "scan" || MODE === "stale" || process.env.RD4_SCAN === "1") {
      report.scan = await runScan(client);
      report.afterScan = await snapshot("AFTER RESCAN");
      copyFileSync(STORE, join(OUT, "deskpan-after-scan.json"));
    } else {
      report.scanStatus = await libState(client);
      say("scan counters with no rescan asked for: " + JSON.stringify(report.scanStatus));
    }

    await client.request(ControlCommand.TestLibAllBooks, []);
    await sleep(2500);
    captureWindowDCToPng(hwnd, join(OUT, "01-library.png"));
    if (MODE === "expire") {
      report.ignoredViewOpened = await openIgnoredView(hwnd, canvas, OUT);
    }
    report.final = await snapshot("FINAL");
    writeFileSync(join(OUT, "report.json"), JSON.stringify(report, null, 2));
  },
  ["-window-pos", posArg, "-log-to-file", join(OUT, "sumlog.txt")],
);

say("wrote " + join(OUT, "report.json"));
