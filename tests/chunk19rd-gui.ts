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
  getForegroundWindow,
  getWindowRect,
  getClientRect,
  clientToScreen,
  getClassName,
  getWindowText,
  getControlText,
  enumWindows,
  enumChildWindows,
  realMouseMove,
  realMouseClick,
  realKeyPress,
  getPopupMenuHandle,
  readMenuItemsFlat,
  getMenuItemRect,
  getMenuItemState,
  captureWindowDCToPng,
  captureWindowDCRegionToPng,
  isWindowVisible,
  topLevelWindowFromPoint,
  readWindowDCColumn,
  MFS_HILITE,
  SW_RESTORE,
  VK_ESCAPE,
  sleep,
} from "./winapi.ts";
import { findCanvas, waitForFrame, waitForContextMenu } from "./win-automation.ts";

const VK_MENU = 0x12;

const ROOT = process.cwd();
const EXE = join(ROOT, "out", "dbg64", "SumatraPDF.exe");
const DATA = join(ROOT, "out", "dbg64");
const CHATTERBOX = resolve(ROOT, "..", "Chatterbox-TTS-Extended-main");
const PYCACHE = join(CHATTERBOX, "audiobook", "cache", "library");
const PORT = 7863;
const MODE = process.env.RD_MODE ?? "remove";
const OUT = process.env.RD_OUT ?? join(ROOT, "scratchpad", "chunk19rd-" + MODE);
const SCAN_S = Number(process.env.RD_SCAN_S ?? "900");
const WANT_DAYS = process.env.RD_DAYS ?? "";
const MENU = process.env.RD_MENU ?? "remove";
const MENU_RE = MENU === "ignore" ? /^ignore file$/i : /^remove from library$/i;
const MENU_NAME = MENU === "ignore" ? "Ignore file" : "Remove from library";
let TARGET = (process.env.RD_TARGET ?? "").toLowerCase();

mkdirSync(OUT, { recursive: true });
const traceFile = join(OUT, "trace.txt");
writeFileSync(traceFile, "");

function say(line: string): void {
  console.log(line);
  appendFileSync(traceFile, line + "\n", "utf8");
}

function readJson(path: string): any {
  try {
    return JSON.parse(readFileSync(path, "utf8"));
  } catch (e) {
    return null;
  }
}

function readIndex(): any {
  return readJson(join(PYCACHE, "library.json"));
}

function desk(): any {
  return readJson(join(PYCACHE, "deskpan.json")) ?? { kinds: {}, pending: {}, excluded: {} };
}

function indexCounts(): any {
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

function pileOf(needle: string): string {
  const idx = readIndex();
  if (!idx || needle.length === 0) {
    return "unset";
  }
  for (const name of ["books", "documents", "ignored"]) {
    for (const b of idx[name] ?? []) {
      if (String(b.path ?? "").toLowerCase().includes(needle)) {
        return name;
      }
      for (const e of b.editions ?? []) {
        if (String(e.path ?? "").toLowerCase().includes(needle)) {
          return name + " (edition)";
        }
      }
    }
  }
  return "absent";
}

function deskFacts(needle: string): any {
  const d = desk();
  const out: any = { kind: "untold", pending: null, excluded: null, pendingCount: 0, excludedCount: 0 };
  out.pendingCount = Object.keys(d.pending ?? {}).length;
  out.excludedCount = Object.keys(d.excluded ?? {}).length;
  if (needle.length === 0) {
    return out;
  }
  for (const [p, k] of Object.entries(d.kinds ?? {})) {
    if (String(p).toLowerCase().includes(needle)) {
      out.kind = String(k);
    }
  }
  for (const [p, rec] of Object.entries(d.pending ?? {})) {
    if (String(p).toLowerCase().includes(needle)) {
      out.pending = rec;
    }
  }
  for (const [p, rec] of Object.entries(d.excluded ?? {})) {
    if (String(p).toLowerCase().includes(needle)) {
      out.excluded = rec;
    }
  }
  return out;
}

function storeCounts(): any {
  try {
    const text = readFileSync(join(DATA, "SumatraLibrary.txt"), "utf8");
    const num = (re: RegExp) => {
      const m = text.match(re);
      return m ? Number(m[1]) : -1;
    };
    return {
      total: num(/^Total = (\d+)/m),
      documents: num(/^Documents = (\d+)/m),
      hasTarget: TARGET.length > 0 && text.toLowerCase().includes(TARGET),
    };
  } catch (e) {
    return { total: -1, documents: -1, hasTarget: false };
  }
}

function settingsIgnoreDays(): string {
  try {
    const text = readFileSync(join(DATA, "SumatraPDF-settings.txt"), "utf8");
    const m = text.match(/^\s*LibraryIgnoreDays = (\d+)/m);
    return m ? m[1] : "absent";
  } catch (e) {
    return "unreadable";
  }
}

async function getJson(path: string): Promise<any> {
  try {
    const rsp = await fetch("http://127.0.0.1:" + PORT + path, { signal: AbortSignal.timeout(8000) });
    return await rsp.json();
  } catch (e) {
    return { down: String(e) };
  }
}

async function serviceHasTarget(): Promise<any> {
  const lib = await getJson("/library?limit=5000");
  const text = JSON.stringify(lib.books ?? []).toLowerCase();
  return { books: lib.total ?? (lib.books ?? []).length, hasTarget: TARGET.length > 0 && text.includes(TARGET) };
}

async function ignoredList(): Promise<any> {
  const d = await getJson("/deskpan?show=ignored&limit=500");
  const files = (d.files ?? []).map((f: any) => String(f.path ?? ""));
  return { total: d.total ?? -1, hasTarget: TARGET.length > 0 && files.join("|").toLowerCase().includes(TARGET) };
}

async function snapshot(label: string): Promise<any> {
  const s = {
    index: indexCounts(),
    store: storeCounts(),
    pile: pileOf(TARGET),
    desk: deskFacts(TARGET),
    fileOnDisk: TARGET.length > 0 && FULLTARGET.length > 0 ? existsSync(FULLTARGET) : null,
    service: await serviceHasTarget(),
    ignored: await ignoredList(),
  };
  say(label + ": " + JSON.stringify(s));
  return s;
}

let FULLTARGET = process.env.RD_FULLTARGET ?? "";

function captureScreenRect(r: any, outPath: string): boolean {
  const w = r.right - r.left;
  const h = r.bottom - r.top;
  if (w <= 0 || h <= 0) {
    return false;
  }
  return captureWindowDCRegionToPng(0, r.left, r.top, w, h, outPath);
}

function findWindowByTitle(title: string): number {
  let found = 0;
  enumWindows((h) => {
    if (getWindowText(h) === title) {
      found = h;
      return false;
    }
    return true;
  });
  return found;
}

async function waitForWindowTitled(title: string, timeoutMs = 8000): Promise<number> {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const h = findWindowByTitle(title);
    if (h) {
      return h;
    }
    await sleep(120);
  }
  return 0;
}

function visiblePopup(): number {
  let found = 0;
  enumWindows((h) => {
    if (getClassName(h) === "#32768" && isWindowVisible(h)) {
      const r = getWindowRect(h);
      if (r.right > r.left && r.bottom > r.top) {
        found = h;
        return false;
      }
    }
    return true;
  });
  return found;
}

async function waitForVisiblePopup(timeoutMs: number): Promise<number> {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const h = visiblePopup();
    if (h) {
      return h;
    }
    await sleep(60);
  }
  return 0;
}

async function clickMenuItem(frame: number, hmenu: bigint, pos: number, what: string): Promise<boolean> {
  const r = getMenuItemRect(frame, hmenu, pos);
  if (!r) {
    say("  could not measure the menu item " + what);
    return false;
  }
  const x = Math.floor((r.left + r.right) / 2);
  const y = Math.floor((r.top + r.bottom) / 2);
  say("  clicking " + what + " at " + x + "," + y);
  realMouseMove(x, y);
  await sleep(220);
  realMouseClick("left");
  await sleep(450);
  return true;
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

// Open the Deskpan page and switch it to the Ignored list, without any DPI
// arithmetic: read a pixel column of the rail, find the highlighted row, and
// step down one row height at a time.
async function openDeskRow(hwnd: number, canvas: number, step: number): Promise<boolean> {
  const cr = getClientRect(canvas);
  const col = readWindowDCColumn(canvas, 14, 0, Math.min(360, cr.bottom));
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
    say("could not find the selected rail row");
    return false;
  }
  const gap = Math.max(2, Math.round((bestLen * 4) / 26));
  const y = bestTop + step * (bestLen + gap) + Math.floor(bestLen / 2);
  const p = clientToScreen(canvas, 40, y);
  say("clicking rail row +" + step + " at canvas y " + y + " (screen " + p.x + "," + p.y + ")");
  realMouseMove(p.x, p.y);
  await sleep(300);
  realMouseClick("left");
  await sleep(2500);
  return true;
}

// Click a point given in the coordinates of a captureWindowDCToPng image of
// the frame window, so a spot read off a screenshot can be clicked again.
async function clickWindowPoint(hwnd: number, x: number, y: number, what: string): Promise<void> {
  const wr = getWindowRect(hwnd);
  const canvas = findCanvas(hwnd);
  const cvr = getWindowRect(canvas);
  const p = clientToScreen(canvas, x - (cvr.left - wr.left), y - (cvr.top - wr.top));
  const sx = p.x;
  const sy = p.y;
  say("clicking " + what + " at window point " + x + "," + y + " (screen " + sx + "," + sy + ")");
  realMouseMove(sx, sy);
  await sleep(300);
  realMouseClick("left");
  await sleep(2500);
}

// Open Deskpan > Ignored with real clicks. The rail rows are static links
// drawn on the canvas, so the row is found by clicking down the rail at x=40
// (always the rail, never a card) until the content area changes.
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
  const wr = getWindowRect(hwnd);
  const cv = getWindowRect(canvas);
  const dx = cv.left - wr.left;
  const dy = cv.top - wr.top;
  const tab = clientToScreen(canvas, 327 - dx, 144 - dy);
  realMouseMove(tab.x, tab.y);
  await sleep(300);
  realMouseClick("left");
  await sleep(2500);
  captureWindowDCToPng(hwnd, join(out, "ignored.png"));
  return true;
}

async function deskRowMenu(
  hwnd: number,
  canvas: number,
  want: RegExp,
): Promise<{ popup: number; hmenu: bigint; items: any[] } | null> {
  const cr2 = getClientRect(canvas);
  const xs: number[] = [];
  for (let f = 0.16; f <= 0.6; f += 0.08) {
    xs.push(Math.round(cr2.right * f));
  }
  for (let y = 150; y < Math.min(560, cr2.bottom - 60); y += 40) {
    for (const x of xs) {
      const s = clientToScreen(canvas, x, y);
      realMouseMove(s.x, s.y);
      await sleep(140);
      if (topLevelWindowFromPoint(s.x, s.y) !== hwnd) {
        continue;
      }
      realMouseClick("right");
      const m = await waitForVisiblePopup(1500);
      if (!m) {
        continue;
      }
      const hm = getPopupMenuHandle(m);
      const flat = hm ? readMenuItemsFlat(hm) : [];
      const texts = flat.map((i) => i.text);
      if (texts.some((t) => want.test(t))) {
        return { popup: m, hmenu: hm, items: flat };
      }
      realKeyPress(VK_ESCAPE);
      await sleep(250);
    }
  }
  return null;
}

async function clickPopupItem(popup: number, hmenu: bigint, items: any[], re: RegExp): Promise<boolean> {
  const item = items.find((i) => re.test(i.text));
  if (!item) {
    return false;
  }
  const ir = getMenuItemRect(popup, hmenu, item.pos);
  if (!ir) {
    return false;
  }
  realMouseMove(Math.round((ir.left + ir.right) / 2), Math.round((ir.top + ir.bottom) / 2));
  await sleep(300);
  realMouseClick("left");
  for (let i = 0; i < 80; i++) {
    if (visiblePopup() === 0) {
      break;
    }
    await sleep(100);
  }
  return true;
}

const report: any = { mode: MODE };

if (!existsSync(EXE)) {
  say("missing " + EXE);
  process.exit(1);
}

setProcessDpiAware();
const prim = primaryMonitor()!;
const winW = Math.min(1700, prim.work.right - prim.work.left - 160);
const winH = Math.min(1150, prim.work.bottom - prim.work.top - 160);
const winX = prim.work.left + 60;
const winY = prim.work.top + 40;
const posArg = winW + "x" + winH + "@" + winX + "x" + winY;

report.settingsIgnoreDaysBefore = settingsIgnoreDays();
say("SumatraPDF-settings.txt LibraryIgnoreDays before: " + report.settingsIgnoreDaysBefore);

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
    let mon = monitorOfWindow(hwnd)!;
    if (!mon.primary) {
      moveWindow(hwnd, winX, winY, winW, winH, true);
      await sleep(400);
      mon = monitorOfWindow(hwnd)!;
    }
    showWindow(hwnd, SW_RESTORE);
    const fg = await forceForeground(hwnd, 8000);
    say("frame 0x" + hwnd.toString(16) + " pid=" + pid + " foreground=" + fg);
    if (!fg) {
      throw new Error("SumatraPDF did not come to the front");
    }

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
    if ((st.visible ?? 0) <= 0) {
      throw new Error("the Library never showed a book");
    }
    await sleep(2000);
    const canvas = findCanvas(hwnd);
    const cr = getClientRect(canvas);

    if (MODE === "options") {
      let popup = 0;
      for (let attempt = 0; attempt < 5 && !popup; attempt++) {
        popup = await waitForContextMenu(250);
        if (popup) {
          break;
        }
        realKeyPress(VK_MENU);
        popup = await waitForContextMenu(2500);
        if (!popup) {
          await sleep(900);
        }
      }
      if (!popup) {
        throw new Error("the hamburger menu did not open");
      }
      const hmenu = getPopupMenuHandle(popup);
      const top = readMenuItemsFlat(hmenu);
      report.hamburgerItems = top.map((i) => i.text);
      const sIdx = top.findIndex((i) => i.text.replace(/&/g, "") === "Settings");
      if (sIdx < 0) {
        throw new Error("no Settings item in the hamburger menu");
      }
      await clickMenuItem(hwnd, hmenu, sIdx, "Settings");
      const sub = top[sIdx].sub;
      const subItems = readMenuItemsFlat(sub);
      report.settingsItems = subItems.map((i) => i.text);
      say("Settings menu: " + JSON.stringify(report.settingsItems));
      const oIdx = subItems.findIndex((i) => /^Options\.\.\.$/.test(i.text.replace(/&/g, "")));
      if (oIdx < 0) {
        throw new Error("no Options... item in the Settings menu");
      }
      await clickMenuItem(hwnd, sub, oIdx, "Options...");

      const dlg = await waitForWindowTitled("SumatraPDF Options", 15000);
      report.optionsOpened = dlg !== 0;
      if (!dlg) {
        throw new Error("the SumatraPDF Options window did not open");
      }
      captureWindowDCToPng(dlg, join(OUT, "options.png"));
      say("screenshot of the Options window: " + join(OUT, "options.png"));

      const edits: Array<{ h: number; text: string; rect: any }> = [];
      enumChildWindows(dlg, (h) => {
        if (getClassName(h) === "Edit") {
          edits.push({ h, text: getControlText(h), rect: getWindowRect(h) });
        }
        return true;
      });
      report.editFields = edits.map((e) => e.text);
      say("edit fields in the Options window: " + JSON.stringify(report.editFields));
      const field = edits.find((e) => /^\d+$/.test(e.text.trim()));
      report.hasDaysField = !!field;
      if (!field) {
        say("the Options window has no numeric days field");
        if (process.env.RD_BASELINE !== "1") {
          throw new Error("no numeric field in the Options window");
        }
        realKeyPress(VK_ESCAPE);
        await sleep(800);
        report.settingsIgnoreDaysAfter = settingsIgnoreDays();
        writeFileSync(join(OUT, "report.json"), JSON.stringify(report, null, 2));
        return;
      }
      report.fieldBefore = field.text;
      say("the days field reads " + JSON.stringify(field.text));
      captureScreenRect(
        {
          left: field.rect.left - 420,
          top: field.rect.top - 14,
          right: field.rect.right + 90,
          bottom: field.rect.bottom + 14,
        },
        join(OUT, "options-row.png"),
      );

      if (WANT_DAYS.length > 0) {
        const mid = {
          x: Math.floor((field.rect.left + field.rect.right) / 2),
          y: Math.floor((field.rect.top + field.rect.bottom) / 2),
        };
        realMouseMove(mid.x, mid.y);
        await sleep(200);
        realMouseClick("left");
        await sleep(200);
        realMouseClick("left");
        await sleep(200);
        for (let i = 0; i < 8; i++) {
          realKeyPress(0x08);
          await sleep(40);
        }
        for (const ch of WANT_DAYS) {
          realKeyPress(0x30 + Number(ch));
          await sleep(60);
        }
        await sleep(300);
        report.fieldTyped = getControlText(field.h);
        say("the days field now reads " + JSON.stringify(report.fieldTyped));
        captureWindowDCToPng(dlg, join(OUT, "options-typed.png"));

        let ok = 0;
        enumChildWindows(dlg, (h) => {
          if (/^(Button)$/.test(getClassName(h)) && /^OK$/i.test(getControlText(h))) {
            ok = h;
            return false;
          }
          return true;
        });
        if (ok) {
          const r = getWindowRect(ok);
          realMouseMove(Math.floor((r.left + r.right) / 2), Math.floor((r.top + r.bottom) / 2));
          await sleep(200);
          realMouseClick("left");
        } else {
          realKeyPress(0x0d);
        }
        await sleep(2500);
        report.optionsClosed = findWindowByTitle("SumatraPDF Options") === 0;
        say("the Options window closed: " + report.optionsClosed);
      } else {
        realKeyPress(VK_ESCAPE);
        await sleep(800);
      }
      report.settingsIgnoreDaysAfter = settingsIgnoreDays();
      say("SumatraPDF-settings.txt LibraryIgnoreDays after: " + report.settingsIgnoreDaysAfter);
      writeFileSync(join(OUT, "report.json"), JSON.stringify(report, null, 2));
      return;
    }

    if (MODE === "remove") {
      report.before = await snapshot("BEFORE");
      captureWindowDCToPng(hwnd, join(OUT, "01-books-before.png"));

      const xs: number[] = [];
      for (let f = 0.12; f <= 0.9; f += 0.13) {
        xs.push(Math.round(cr.right * f));
      }
      const ys: number[] = [];
      for (let y = 150; y < cr.bottom - 60; y += 90) {
        ys.push(y);
      }
      let hit: { sx: number; sy: number } | null = null;
      let popup = 0;
      let hmenu = 0n;
      let items: Array<{ pos: number; text: string; id: number; sub: bigint }> = [];
      outer: for (const y of ys) {
        for (const x of xs) {
          const s = clientToScreen(canvas, x, y);
          realMouseMove(s.x, s.y);
          await sleep(140);
          if (topLevelWindowFromPoint(s.x, s.y) !== hwnd) {
            continue;
          }
          realMouseClick("right");
          const menu = await waitForVisiblePopup(1500);
          if (!menu) {
            continue;
          }
          const hm = getPopupMenuHandle(menu);
          const flat = hm ? readMenuItemsFlat(hm) : [];
          const texts = flat.map((i) => i.text);
          const isCard =
            texts.some((t) => MENU_RE.test(t)) && texts.some((t) => /play as audio/i.test(t));
          if (isCard) {
            hit = { sx: s.x, sy: s.y };
            popup = menu;
            hmenu = hm;
            items = flat;
            break outer;
          }
          realKeyPress(VK_ESCAPE);
          await sleep(250);
        }
      }
      if (!hit) {
        throw new Error("no Library card produced a book context menu");
      }
      report.menuItems = items.map((i) => i.text);
      say("book card menu: " + JSON.stringify(report.menuItems));
      report.hasRemove = items.some((i) => /^remove from library$/i.test(i.text));
      report.hasIgnore = items.some((i) => /^ignore file$/i.test(i.text));
      say("menu has Remove from library=" + report.hasRemove + " and Ignore file=" + report.hasIgnore);
      captureWindowDCToPng(popup, join(OUT, "02-card-menu.png"));

      const item = items.find((i) => MENU_RE.test(i.text))!;
      const ir = getMenuItemRect(popup, hmenu, item.pos)!;
      realMouseMove(Math.round((ir.left + ir.right) / 2), Math.round((ir.top + ir.bottom) / 2));
      await sleep(300);
      report.hilited = (getMenuItemState(hmenu, item.pos) & MFS_HILITE) !== 0;
      captureWindowDCToPng(popup, join(OUT, "03-menu-hilite.png"));
      if (!report.hilited) {
        throw new Error(MENU_NAME + " did not highlight");
      }
      realMouseClick("left");
      say("clicked " + MENU_NAME);

      for (let i = 0; i < 80; i++) {
        if (visiblePopup() === 0) {
          break;
        }
        await sleep(100);
      }
      // learn which book the menu acted on: it is the one that left the books
      const beforePaths = new Set<string>(
        ((readIndex() ?? {}).books ?? []).flatMap((b: any) => [
          String(b.path ?? "").toLowerCase(),
          ...(b.editions ?? []).map((e: any) => String(e.path ?? "").toLowerCase()),
        ]),
      );
      let learned = "";
      const learnDeadline = Date.now() + 90000;
      while (Date.now() < learnDeadline && learned.length === 0) {
        await sleep(1000);
        if (MENU === "ignore") {
          for (const b of (readIndex() ?? {}).ignored ?? []) {
            if (beforePaths.has(String(b.path ?? "").toLowerCase())) {
              learned = String(b.path);
              break;
            }
          }
          continue;
        }
        const d = desk();
        for (const [p, rec] of Object.entries(d.pending ?? {})) {
          if (beforePaths.has(String(p).toLowerCase())) {
            learned = String((rec as any).path ?? p);
            break;
          }
        }
      }
      if (learned.length === 0 && process.env.RD_BASELINE === "1") {
        const idx = readIndex() ?? {};
        for (const b of idx.documents ?? []) {
          if (beforePaths.has(String(b.path ?? "").toLowerCase())) {
            learned = String(b.path);
            break;
          }
        }
        say("baseline: the card the menu acted on moved to the documents pile");
      }
      if (learned.length === 0) {
        throw new Error("nothing changed after " + MENU_NAME);
      }
      FULLTARGET = learned;
      TARGET = learned.toLowerCase();
      report.target = learned;
      say("the menu acted on " + learned);
      await sleep(9000);
      report.afterRemove = await snapshot("AFTER REMOVE");
      captureWindowDCToPng(hwnd, join(OUT, "04-books-after.png"));

      await client.request(ControlCommand.TestLibAllBooks, []);
      await sleep(2500);
      captureWindowDCToPng(hwnd, join(OUT, "05-everything.png"));
      mkdirSync(join(OUT, "after-scan"), { recursive: true });
      report.ignoredViewOpened = await openIgnoredView(hwnd, canvas, OUT);

      report.scan = await runScan(client);
      report.afterScan = await snapshot("AFTER RESCAN");
      await client.request(ControlCommand.TestLibAllBooks, []);
      await sleep(2500);
      captureWindowDCToPng(hwnd, join(OUT, "08-everything-after-scan.png"));
      report.ignoredViewAfterScan = await openIgnoredView(hwnd, canvas, join(OUT, "after-scan"));
      writeFileSync(join(OUT, "report.json"), JSON.stringify(report, null, 2));
      return;
    }

    if (MODE === "deskignore" || MODE === "deskbulk") {
      report.before = await snapshot("BEFORE");
      report.deskpanOpened = await openDeskpanView(hwnd, canvas, OUT);
      if (!report.deskpanOpened) {
        throw new Error("the Deskpan page did not open");
      }
      const beforeDocs = new Set<string>(
        ((readIndex() ?? {}).documents ?? []).map((b: any) => String(b.path ?? "").toLowerCase()),
      );
      if (MODE === "deskbulk") {
        const pick = await deskRowMenu(hwnd, canvas, /^select$/i);
        if (!pick) {
          throw new Error("no Deskpan row produced a file menu");
        }
        report.deskMenuBefore = pick.items.map((i: any) => i.text);
        say("Deskpan row menu: " + JSON.stringify(report.deskMenuBefore));
        captureWindowDCToPng(pick.popup, join(OUT, "02-desk-menu.png"));
        if (!(await clickPopupItem(pick.popup, pick.hmenu, pick.items, /^select$/i))) {
          throw new Error("could not click Select");
        }
        await sleep(1500);
        captureWindowDCToPng(hwnd, join(OUT, "03-selecting.png"));
      }
      const menu = await deskRowMenu(hwnd, canvas, /^ignore (file|\d+ files)$/i);
      if (!menu) {
        throw new Error("no Deskpan row offered Ignore file");
      }
      report.deskMenu = menu.items.map((i: any) => i.text);
      say("Deskpan row menu: " + JSON.stringify(report.deskMenu));
      captureWindowDCToPng(menu.popup, join(OUT, "04-ignore-menu.png"));
      if (!(await clickPopupItem(menu.popup, menu.hmenu, menu.items, /^ignore (file|\d+ files)$/i))) {
        throw new Error("could not click Ignore file");
      }
      say("clicked Ignore file on the Deskpan");
      let learned = "";
      const learnDeadline = Date.now() + 90000;
      while (Date.now() < learnDeadline && learned.length === 0) {
        await sleep(1000);
        for (const b of (readIndex() ?? {}).ignored ?? []) {
          if (beforeDocs.has(String(b.path ?? "").toLowerCase())) {
            learned = String(b.path);
            break;
          }
        }
      }
      if (learned.length === 0) {
        throw new Error("nothing moved into the ignored pile");
      }
      FULLTARGET = learned;
      TARGET = learned.toLowerCase();
      report.target = learned;
      say("the menu acted on " + learned);
      await sleep(8000);
      report.afterIgnore = await snapshot("AFTER IGNORE");
      captureWindowDCToPng(hwnd, join(OUT, "05-after.png"));
      writeFileSync(join(OUT, "report.json"), JSON.stringify(report, null, 2));
      return;
    }

    if (MODE === "shot" || MODE === "ignoredmenu") {
      report.before = await snapshot("BEFORE");
      await client.request(ControlCommand.TestLibAllBooks, []);
      await sleep(2500);
      captureWindowDCToPng(hwnd, join(OUT, "01-everything.png"));
      report.ignoredViewOpened = await openIgnoredView(hwnd, canvas, OUT);
      if (MODE === "ignoredmenu" && report.ignoredViewOpened) {
        const cr2 = getClientRect(canvas);
        const xs2: number[] = [];
        for (let f = 0.16; f <= 0.6; f += 0.08) {
          xs2.push(Math.round(cr2.right * f));
        }
        let got = 0;
        outer2: for (let y = 120; y < 420; y += 40) {
          for (const x of xs2) {
            const s2 = clientToScreen(canvas, x, y);
            realMouseMove(s2.x, s2.y);
            await sleep(140);
            if (topLevelWindowFromPoint(s2.x, s2.y) !== hwnd) {
              continue;
            }
            realMouseClick("right");
            const m = await waitForVisiblePopup(1500);
            if (!m) {
              continue;
            }
            const hm = getPopupMenuHandle(m);
            const flat = hm ? readMenuItemsFlat(hm) : [];
            const texts = flat.map((i) => i.text);
            if (texts.some((t) => /move to library/i.test(t))) {
              report.ignoredCardMenu = texts;
              say("Ignored card menu: " + JSON.stringify(texts));
              captureWindowDCToPng(m, join(OUT, "ignored-card-menu.png"));
              got = m;
              break outer2;
            }
            realKeyPress(VK_ESCAPE);
            await sleep(250);
          }
        }
        report.ignoredMenuFound = got !== 0;
        if (got) {
          realKeyPress(VK_ESCAPE);
          await sleep(400);
        }
      }
      writeFileSync(join(OUT, "report.json"), JSON.stringify(report, null, 2));
      return;
    }

    // "expire" and "restart" both look at the same target and rescan
    report.before = await snapshot("BEFORE");
    captureWindowDCToPng(hwnd, join(OUT, "01-start.png"));
    report.scan = await runScan(client);
    report.after = await snapshot("AFTER RESCAN");
    await client.request(ControlCommand.TestLibAllBooks, []);
    await sleep(3500);
    captureWindowDCToPng(hwnd, join(OUT, "02-everything.png"));
    report.ignoredViewOpened = await openIgnoredView(hwnd, canvas, OUT);
    if (!report.ignoredViewOpened) {
      await client.request(ControlCommand.TestLibAllBooks, []);
      await sleep(3500);
      report.ignoredViewOpened = await openIgnoredView(hwnd, canvas, OUT);
    }
    writeFileSync(join(OUT, "report.json"), JSON.stringify(report, null, 2));
  },
  ["-window-pos", posArg, "-log-to-file", join(OUT, "sumlog.txt")],
  MODE === "options" && WANT_DAYS.length > 0 ? { saveSettings: true } : {},
);

say("wrote " + join(OUT, "report.json"));
