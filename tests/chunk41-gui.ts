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
  getWindowRect,
  getClientRect,
  clientToScreen,
  getClassName,
  getWindowText,
  getControlText,
  getWindowPid,
  enumWindows,
  enumChildWindows,
  realMouseMove,
  realMouseClick,
  realKeyPress,
  sendText,
  postMessage,
  captureWindowDCToPng,
  readWindowDCColumn,
  isWindowVisible,
  SW_RESTORE,
  VK_ESCAPE,
  VK_RETURN,
  WM_CLOSE,
  sleep,
} from "./winapi.ts";
import { findCanvas, waitForFrame } from "./win-automation.ts";

const ROOT = process.cwd();
const EXE = join(ROOT, "out", "dbg64", "SumatraPDF.exe");
const DATA = join(ROOT, "out", "dbg64");
const CHATTERBOX = resolve(ROOT, "..", "Chatterbox-TTS-Extended-main");
const PYCACHE = join(CHATTERBOX, "audiobook", "cache", "library");
const PORT = 7863;
const MODE = process.env.CH_MODE ?? "import";
const OUT = process.env.CH_OUT ?? join(ROOT, "scratchpad", "chunk41-" + MODE);
const PICK = process.env.CH_FILE ?? "";
const NEW_TITLE = process.env.CH_TITLE ?? "";
const WANT_KIND = process.env.CH_KIND ?? "";
const SCAN_S = Number(process.env.CH_SCAN_S ?? "900");

mkdirSync(OUT, { recursive: true });
const traceFile = join(OUT, "trace.txt");
writeFileSync(traceFile, "");

function say(line: string): void {
  console.log(line);
  appendFileSync(traceFile, line + "\n", "utf8");
}

function readJson(p: string): any {
  try {
    return JSON.parse(readFileSync(p, "utf8"));
  } catch (e) {
    return null;
  }
}

function counts(): any {
  const i = readJson(join(PYCACHE, "library.json"));
  const d = readJson(join(PYCACHE, "deskpan.json")) ?? {};
  const l = readJson(join(PYCACHE, "sorting_examples.json")) ?? { examples: [] };
  const r = readJson(join(PYCACHE, "renames.json")) ?? { books: {} };
  return {
    books: (i?.books ?? []).length,
    documents: (i?.documents ?? []).length,
    ignored: (i?.ignored ?? []).length,
    kinds: Object.keys(d.kinds ?? {}).length,
    excluded: (d.excludedFingerprints ?? []).length,
    examples: (l.examples ?? []).length,
    overrides: Object.keys(r.books ?? {}).length,
  };
}

function pileOf(needle: string): string {
  const i = readJson(join(PYCACHE, "library.json"));
  if (!i || needle.length === 0) {
    return "unset";
  }
  for (const name of ["books", "documents", "ignored"]) {
    for (const b of i[name] ?? []) {
      for (const one of [b.path, ...(b.editions ?? []).map((e: any) => e.path)]) {
        if (String(one ?? "").toLowerCase() === needle) {
          return name;
        }
      }
    }
  }
  return "absent";
}

function bookRow(needle: string): any {
  const i = readJson(join(PYCACHE, "library.json"));
  for (const name of ["books", "documents", "ignored"]) {
    for (const b of i?.[name] ?? []) {
      if (String(b.path ?? "").toLowerCase() === needle) {
        return {
          pile: name,
          id: b.id,
          title: b.title,
          title_source: b.title_source,
          author: b.author,
          author_source: b.author_source,
          series: b.series,
          series_source: b.series_source,
          year: b.year,
          genre: b.genre,
          subgenre: b.subgenre,
          kind: b.kind,
          pages: b.pages,
        };
      }
    }
  }
  return null;
}

function lastExample(): any {
  const l = readJson(join(PYCACHE, "sorting_examples.json")) ?? { examples: [] };
  const rows = l.examples ?? [];
  return rows.length ? rows[rows.length - 1] : null;
}

function deskKind(needle: string): string {
  const d = readJson(join(PYCACHE, "deskpan.json")) ?? {};
  for (const [p, k] of Object.entries(d.kinds ?? {})) {
    if (String(p).toLowerCase() === needle) {
      return String(k);
    }
  }
  return "untold";
}

function isExcluded(needle: string): boolean {
  const d = readJson(join(PYCACHE, "deskpan.json")) ?? {};
  const i = readJson(join(PYCACHE, "library.json"));
  const marks = new Set<string>((d.excludedFingerprints ?? []).map((m: any) => String(m).toLowerCase()));
  if (marks.size === 0 || needle.length === 0) {
    return false;
  }
  for (const name of ["books", "documents", "ignored"]) {
    for (const b of (i ?? {})[name] ?? []) {
      const mark = String(b.fingerprint ?? "").split(":")[1] ?? "";
      for (const one of [b.path, ...(b.editions ?? []).map((e: any) => e.path)]) {
        if (String(one ?? "").toLowerCase() === needle && marks.has(mark.toLowerCase())) {
          return true;
        }
      }
    }
  }
  return false;
}

function overrideOf(id: string): any {
  const r = readJson(join(PYCACHE, "renames.json")) ?? { books: {} };
  return (r.books ?? {})[id] ?? null;
}

async function waitForPicker(pid: number, timeoutMs: number): Promise<number> {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    let found = 0;
    enumWindows((h) => {
      if (getClassName(h) === "#32770" && getWindowPid(h) === pid && isWindowVisible(h)) {
        found = h;
        return false;
      }
      return true;
    });
    if (found) {
      return found;
    }
    await sleep(150);
  }
  return 0;
}

async function waitForWindowTitled(title: string, timeoutMs: number): Promise<number> {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    let found = 0;
    enumWindows((h) => {
      if (getWindowText(h) === title && isWindowVisible(h)) {
        found = h;
        return false;
      }
      return true;
    });
    if (found) {
      return found;
    }
    await sleep(150);
  }
  return 0;
}

function findChildOfClass(parent: number, cls: string): number {
  let found = 0;
  enumChildWindows(parent, (h) => {
    if (getClassName(h) === cls) {
      found = h;
      return false;
    }
    return true;
  });
  return found;
}

function editsOf(dlg: number): Array<{ h: number; text: string; rect: any }> {
  const out: Array<{ h: number; text: string; rect: any }> = [];
  enumChildWindows(dlg, (h) => {
    if (getClassName(h) === "Edit") {
      out.push({ h, text: getControlText(h), rect: getWindowRect(h) });
    }
    return true;
  });
  return out;
}

function combosOf(dlg: number): Array<{ h: number; text: string; rect: any }> {
  const out: Array<{ h: number; text: string; rect: any }> = [];
  enumChildWindows(dlg, (h) => {
    if (getClassName(h) === "ComboBox") {
      out.push({ h, text: getControlText(h), rect: getWindowRect(h) });
    }
    return true;
  });
  return out;
}

async function importState(client: any): Promise<Record<string, string>> {
  const raw = String((await client.request(ControlCommand.TestLibImportState, []))[1] ?? "");
  const out: Record<string, string> = {};
  for (const line of raw.split(/\r?\n/)) {
    const i = line.indexOf("=");
    if (i > 0) {
      out[line.slice(0, i)] = line.slice(i + 1);
    }
  }
  return out;
}

function scanStatus(raw: string): Record<string, number> {
  const out: Record<string, number> = {};
  for (const m of String(raw).matchAll(/(\w+)=(-?\d+)/g)) {
    out[m[1]] = Number(m[2]);
  }
  return out;
}

async function runScan(client: any): Promise<number> {
  say("asking for a real Library rescan");
  const started = Date.now();
  await client.request(ControlCommand.TestLibRescan, []);
  const deadline = Date.now() + SCAN_S * 1000;
  let seen = false;
  while (Date.now() < deadline) {
    const st = scanStatus(String((await client.request(ControlCommand.TestLibScanStatus, []))[1] ?? ""));
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
  return secs;
}

const report: any = { mode: MODE, file: PICK };
const TARGET = PICK.toLowerCase();

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

report.before = counts();
report.beforePile = pileOf(TARGET);
report.beforeDesk = deskKind(TARGET);
report.beforeExcluded = isExcluded(TARGET);
say("BEFORE " + JSON.stringify(report.before));
say("BEFORE pile=" + report.beforePile + " desk=" + report.beforeDesk + " excluded=" + report.beforeExcluded);

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
    const deadline = Date.now() + 120000;
    let st: Record<string, number> = {};
    while (Date.now() < deadline) {
      st = scanStatus(String((await client.request(ControlCommand.TestLibScanStatus, []))[1] ?? ""));
      if ((st.visible ?? 0) > 0) {
        break;
      }
      await sleep(600);
    }
    say("model: visible=" + st.visible);
    await sleep(2500);
    const canvas = findCanvas(hwnd);
    captureWindowDCToPng(hwnd, join(OUT, "01-library.png"));

    if (MODE === "restart") {
      report.after = counts();
      report.afterPile = pileOf(TARGET);
      report.afterDesk = deskKind(TARGET);
      report.row = bookRow(TARGET);
      say("AFTER RESTART " + JSON.stringify(report.after));
      say("row " + JSON.stringify(report.row));
      if (process.env.CH_RESCAN === "1") {
        report.scanSeconds = await runScan(client);
        report.afterScanPile = pileOf(TARGET);
        report.afterScanRow = bookRow(TARGET);
        say("AFTER RESCAN pile=" + report.afterScanPile);
        say("row " + JSON.stringify(report.afterScanRow));
      }
      writeFileSync(join(OUT, "report.json"), JSON.stringify(report, null, 2));
      return;
    }

    let runner: any = null;
    if (MODE === "link") {
      const wr = getWindowRect(hwnd);
      const cv = getWindowRect(canvas);
      const prime = clientToScreen(canvas, 40, 60);
      realMouseMove(prime.x, prime.y);
      await sleep(400);
      realMouseClick("left");
      await sleep(2500);
      const p = clientToScreen(canvas, 85 - (cv.left - wr.left), 1101 - (cv.top - wr.top));
      say("clicking the rail link at screen " + p.x + "," + p.y);
      realMouseMove(p.x, p.y);
      await sleep(500);
      realMouseClick("left");
      runner = Promise.resolve([]);
    } else {
      say("running Manually add book to library");
      runner = client.request(ControlCommand.TestLibImportBook, []);
    }
    const dlg = await waitForPicker(pid, 20000);
    report.pickerOpened = dlg !== 0;
    say("file picker opened: " + report.pickerOpened);
    if (!dlg) {
      throw new Error("the file picker did not open");
    }
    await sleep(1200);
    report.pickerTitle = getWindowText(dlg);
    say("picker title: " + JSON.stringify(report.pickerTitle));
    captureWindowDCToPng(dlg, join(OUT, "02-picker.png"));

    if (MODE === "cancelpicker") {
      postMessage(dlg, WM_CLOSE, 0, 0);
      await sleep(3000);
      await runner;
      report.after = counts();
      say("AFTER " + JSON.stringify(report.after));
      writeFileSync(join(OUT, "report.json"), JSON.stringify(report, null, 2));
      return;
    }

    const nameBox = findChildOfClass(dlg, "Edit");
    if (!nameBox) {
      postMessage(dlg, WM_CLOSE, 0, 0);
      throw new Error("the file picker has no name box");
    }
    sendText(nameBox, PICK);
    await sleep(500);
    realKeyPress(VK_RETURN);
    for (let i = 0; i < 30 && isWindowVisible(dlg); i++) {
      await sleep(300);
    }
    report.pickerClosed = !isWindowVisible(dlg);
    say("picker closed: " + report.pickerClosed);
    await runner;

    const form = await waitForWindowTitled("Manually add book to library", 180000);
    report.formOpened = form !== 0;
    say("import form opened: " + report.formOpened);
    if (!form) {
      throw new Error("the import form did not open");
    }
    await sleep(1500);
    captureWindowDCToPng(form, join(OUT, "03-form.png"));
    report.formState = await importState(client);
    say("form state: " + JSON.stringify(report.formState, null, 1));
    report.duringForm = counts();
    say("DURING FORM " + JSON.stringify(report.duringForm));

    const boxes = editsOf(form);
    report.formEdits = boxes.map((b) => b.text);
    say("form edit boxes: " + JSON.stringify(report.formEdits));
    const drops = combosOf(form);
    report.formCombos = drops.map((d) => d.text);
    say("form drop-downs: " + JSON.stringify(report.formCombos));

    if (MODE === "cancel") {
      realKeyPress(VK_ESCAPE);
      await sleep(4000);
      report.formClosed = !isWindowVisible(form);
      say("form closed: " + report.formClosed);
      await sleep(6000);
      report.after = counts();
      report.afterPile = pileOf(TARGET);
      report.afterDesk = deskKind(TARGET);
      report.afterExcluded = isExcluded(TARGET);
      say("AFTER CANCEL " + JSON.stringify(report.after));
      say("AFTER CANCEL pile=" + report.afterPile + " desk=" + report.afterDesk +
          " excluded=" + report.afterExcluded);
      writeFileSync(join(OUT, "report.json"), JSON.stringify(report, null, 2));
      return;
    }

    if (NEW_TITLE.length > 0 && boxes.length > 0) {
      const box = boxes[0];
      const mid = {
        x: Math.floor((box.rect.left + box.rect.right) / 2),
        y: Math.floor((box.rect.top + box.rect.bottom) / 2),
      };
      realMouseMove(mid.x, mid.y);
      await sleep(200);
      realMouseClick("left");
      await sleep(300);
      sendText(box.h, NEW_TITLE);
      await sleep(500);
      report.typedTitle = getControlText(box.h);
      say("typed a new title: " + JSON.stringify(report.typedTitle));
      captureWindowDCToPng(form, join(OUT, "04-form-edited.png"));
    }

    if (WANT_KIND.length > 0 && drops.length > 0) {
      say("leaving the classification at " + WANT_KIND);
    }

    if (process.env.CH_PART === "1" && drops.length > 1) {
      const combo = drops[1];
      const mid = {
        x: Math.floor((combo.rect.left + combo.rect.right) / 2),
        y: Math.floor((combo.rect.top + combo.rect.bottom) / 2),
      };
      realMouseMove(mid.x, mid.y);
      await sleep(300);
      realMouseClick("left");
      await sleep(900);
      realKeyPress(0x28);
      await sleep(400);
      realKeyPress(VK_RETURN);
      await sleep(700);
      report.pickedCategory = getControlText(combo.h);
      say("picked the category: " + JSON.stringify(report.pickedCategory));
      captureWindowDCToPng(form, join(OUT, "04-category.png"));
    }

    let addBtn = { x: 0, y: 0 };
    {
      const fr = getWindowRect(form);
      addBtn = { x: fr.right - 90, y: fr.bottom - 34 };
    }
    say("clicking Add to library at " + addBtn.x + "," + addBtn.y);
    realMouseMove(addBtn.x, addBtn.y);
    await sleep(400);
    realMouseClick("left");
    for (let i = 0; i < 40 && isWindowVisible(form); i++) {
      await sleep(300);
    }
    report.formClosedAfterAdd = !isWindowVisible(form);
    say("form closed after Add: " + report.formClosedAfterAdd);

    // wait for the Library page to redraw from the refreshed catalogue
    const storeHas = () => {
      try {
        return readFileSync(join(DATA, "SumatraLibrary.txt"), "utf8").toLowerCase().includes(TARGET);
      } catch (e) {
        return false;
      }
    };
    const pageDeadline = Date.now() + 180000;
    let shown = false;
    while (Date.now() < pageDeadline) {
      await sleep(2000);
      if (storeHas()) {
        shown = true;
        break;
      }
    }
    report.storeShowsBook = shown;
    report.storeWaitSeconds = Math.round((180000 - (pageDeadline - Date.now())) / 1000);
    say("the Library page lists the file after " + report.storeWaitSeconds + " s: " + shown);
    await sleep(3000);

    report.after = counts();
    report.afterPile = pileOf(TARGET);
    report.afterDesk = deskKind(TARGET);
    report.afterExcluded = isExcluded(TARGET);
    report.row = bookRow(TARGET);
    report.example = lastExample();
    if (report.row?.id) {
      report.override = overrideOf(report.row.id);
    }
    say("AFTER " + JSON.stringify(report.after));
    say("AFTER pile=" + report.afterPile + " desk=" + report.afterDesk + " excluded=" + report.afterExcluded);
    say("row " + JSON.stringify(report.row));
    say("override " + JSON.stringify(report.override));
    say("training example " + JSON.stringify(report.example));

    await client.request(ControlCommand.TestLibAllBooks, []);
    await sleep(3000);
    captureWindowDCToPng(hwnd, join(OUT, "05-library-after.png"));
    try {
      const text = readFileSync(join(DATA, "SumatraLibrary.txt"), "utf8");
      const at = text.toLowerCase().indexOf(TARGET);
      report.storeCard = at >= 0 ? text.slice(Math.max(0, at - 420), at + 120) : null;
      say("store card: " + JSON.stringify(report.storeCard));
    } catch (e) {
      say("could not read the store");
    }
    writeFileSync(join(OUT, "report.json"), JSON.stringify(report, null, 2));
  },
  ["-window-pos", posArg, "-log-to-file", join(OUT, "sumlog.txt")],
  {},
);

say("wrote " + join(OUT, "report.json"));
