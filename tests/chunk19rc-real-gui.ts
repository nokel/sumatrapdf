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
  getForegroundWindow,
  getWindowRect,
  getClientRect,
  clientToScreen,
  getWindowPid,
  getClassName,
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
  enumWindows,
  topLevelWindowFromPoint,
  MFS_HILITE,
  SW_RESTORE,
  VK_ESCAPE,
  sleep,
} from "./winapi.ts";
import { findCanvas, waitForFrame } from "./win-automation.ts";

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

const ROOT = process.cwd();
const EXE = join(ROOT, "out", "dbg64", "SumatraPDF.exe");
const DATA = join(ROOT, "out", "dbg64");
const CHATTERBOX = resolve(ROOT, "..", "Chatterbox-TTS-Extended-main");
const PYCACHE = join(CHATTERBOX, "audiobook", "cache", "library");
const OUT = process.env.RC_OUT ?? join(ROOT, "scratchpad", "chunk19rc");
const PORT = 7863;
// "remove" is Remove from library (book -> document). "ignore" is Ignore file
// (book -> ignored). Both are real items of the book card context menu.
const MENU = process.env.RC_MENU ?? "remove";
const MENU_RE = MENU === "ignore" ? /^ignore file$/i : /^remove from library$/i;
const MENU_NAME = MENU === "ignore" ? "Ignore file" : "Remove from library";
// Seconds to wait for the resumed scan in the second session. 0 skips the wait.
const SCAN_WAIT_S = Number(process.env.RC_SCAN_WAIT_S ?? "0");
// When RC_TARGET is empty the harness learns the target from the card the
// menu actually acted on, and then checks how the scan journal holds it.
let TARGET = (process.env.RC_TARGET ?? "").toLowerCase();

mkdirSync(OUT, { recursive: true });
const traceFile = join(OUT, "trace.txt");
writeFileSync(traceFile, "");

function say(line: string): void {
  console.log(line);
  appendFileSync(traceFile, line + "\n", "utf8");
}

const report: any = {
  harness: {},
  menu: {},
  before: {},
  command: {},
  afterCommand: {},
  shutdown: {},
  reopen: {},
  final: {},
};

async function getJson(path: string): Promise<any> {
  const rsp = await fetch(`http://127.0.0.1:${PORT}${path}`, { signal: AbortSignal.timeout(4000) });
  if (!rsp.ok) {
    throw new Error(`${path}: HTTP ${rsp.status}`);
  }
  return await rsp.json();
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

// Which pile of the service index holds a path right now.
function indexPileOf(pathLower: string): string {
  const idx = readIndex();
  if (!idx || pathLower.length === 0) {
    return "unset";
  }
  for (const name of ["books", "documents", "ignored"]) {
    for (const b of idx[name] ?? []) {
      if (String(b.path ?? "").toLowerCase().includes(pathLower)) {
        return name;
      }
    }
  }
  return "absent";
}

function indexBookPaths(): string[] {
  const idx = readIndex();
  return idx ? (idx.books ?? []).map((b: any) => String(b.path ?? "")) : [];
}

// What the desk store (the record of explicit user classification) says.
function deskKindOf(pathLower: string): string {
  if (pathLower.length === 0) {
    return "unset";
  }
  try {
    const d = JSON.parse(readFileSync(join(PYCACHE, "deskpan.json"), "utf8"));
    for (const [p, k] of Object.entries(d.kinds ?? {})) {
      if (String(p).toLowerCase().includes(pathLower)) {
        return String(k);
      }
    }
  } catch (e) {
    return "unreadable";
  }
  return "untold";
}

// The completed-scan journal the resume replays.
function journal(): { rows: number; byKind: Record<string, number>; target: string; manifest: number } {
  const out = { rows: 0, byKind: {} as Record<string, number>, target: "absent", manifest: 0 };
  const dbFile = join(PYCACHE, "scan_checkpoint.sqlite3");
  if (!existsSync(dbFile)) {
    return out;
  }
  try {
    const db = new Database(dbFile, { readonly: true });
    for (const r of db.query("SELECT kind, path FROM completed").all() as any[]) {
      out.rows++;
      out.byKind[r.kind] = (out.byKind[r.kind] ?? 0) + 1;
      if (TARGET.length > 0 && String(r.path).toLowerCase().includes(TARGET)) {
        out.target = r.kind;
      }
    }
    out.manifest = (db.query("SELECT count(*) AS n FROM manifest").get() as any).n;
    db.close();
  } catch (e) {
    return out;
  }
  return out;
}

// The scan result the scan itself recorded for the target, exactly as the
// journal holds it. Replaying it is what a late scan result looks like.
function journalScanJson(): string | null {
  const dbFile = join(PYCACHE, "scan_checkpoint.sqlite3");
  if (!existsSync(dbFile)) {
    return null;
  }
  try {
    const db = new Database(dbFile, { readonly: true });
    let found: string | null = null;
    for (const r of db.query("SELECT path, scan_json FROM completed").all() as any[]) {
      if (TARGET.length > 0 && String(r.path).toLowerCase().includes(TARGET)) {
        found = String(r.scan_json);
      }
    }
    db.close();
    return found;
  } catch (e) {
    return null;
  }
}

function checkpointMeta(): any {
  try {
    return JSON.parse(readFileSync(join(PYCACHE, "scan_checkpoint.json"), "utf8"));
  } catch (e) {
    return null;
  }
}

function storeCounts(): { total: number; documents: number; entries: number; version: number; series: number } {
  try {
    const text = readFileSync(join(DATA, "SumatraLibrary.txt"), "utf8");
    const num = (re: RegExp) => {
      const m = text.match(re);
      return m ? Number(m[1]) : -1;
    };
    return {
      version: num(/^﻿?Version = (\d+)/m),
      series: (text.match(/^\t\tName = /gm) ?? []).length,
      total: num(/^Total = (\d+)/m),
      documents: num(/^Documents = (\d+)/m),
      entries: (text.match(/^\t\t\tId = /gm) ?? []).length + (text.match(/^\t\tId = /gm) ?? []).length,
    };
  } catch (e) {
    return { total: -1, documents: -1, entries: -1, version: -1, series: -1 };
  }
}

const APPLOG = join(OUT, "sumlog.txt");

function appLogLines(file: string): string[] {
  if (!existsSync(file)) {
    return [];
  }
  return readFileSync(file, "utf8").split(String.fromCharCode(10));
}

function kindPosts(): string[] {
  return appLogLines(APPLOG).filter((l) => l.includes("ServicePost:") && l.includes("path=/kind"));
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

async function loadCompleteCount(client: any): Promise<number> {
  const r = await client.request(ControlCommand.TestLoadCompleteCount, []);
  const m = String(r[1] ?? "").match(/count=(\d+)/);
  return m ? Number(m[1]) : -1;
}

// What the running service is serving to the page right now: does its book
// list still carry the file the user took out of the Library?
async function serviceBooksHaveTarget(): Promise<{ books: number; hasTarget: boolean }> {
  try {
    const lib = await getJson("/library?limit=5000");
    const text = JSON.stringify(lib.books ?? []);
    return { books: lib.total ?? (lib.books ?? []).length, hasTarget: text.toLowerCase().includes(TARGET) };
  } catch (e) {
    return { books: -1, hasTarget: false };
  }
}

async function snapshot(): Promise<any> {
  return {
    index: indexCounts(),
    store: storeCounts(),
    targetPile: indexPileOf(TARGET),
    targetDesk: deskKindOf(TARGET),
    journal: journal(),
    checkpoint: checkpointMeta(),
    serviceBooks: await serviceBooksHaveTarget(),
  };
}

if (!existsSync(EXE)) {
  say(`missing ${EXE}`);
  process.exit(1);
}

setProcessDpiAware();

const status0 = await serviceStatus();
say(`service on ${PORT} before launch: ${JSON.stringify(status0)}`);
if (status0.ok) {
  say("a library service is already running; it must be the one the app starts");
  process.exit(1);
}

const prim = primaryMonitor()!;
const winW = Math.min(2000, prim.work.right - prim.work.left - 120);
const winH = Math.min(1350, prim.work.bottom - prim.work.top - 120);
const winX = prim.work.left + 40;
const winY = prim.work.top + 30;
const posArg = `${winW}x${winH}@${winX}x${winY}`;
say(`launching ${EXE} -window-pos ${posArg}`);

await withControlledSumatra(
  EXE,
  async (client, proc) => {
    const pid = proc.pid!;
    const hwnd = await waitForFrame(pid, 20000);
    if (!hwnd) {
      throw new Error("no SumatraPDF frame window");
    }
    report.harness.pid = pid;
    report.harness.hwnd = `0x${hwnd.toString(16)}`;
    say(`frame hwnd=0x${hwnd.toString(16)} pid=${pid}`);

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
    if (!mon.primary) {
      throw new Error("could not place the window on the primary monitor");
    }
    const fg = await forceForeground(hwnd, 8000);
    report.harness.foreground = fg;
    say(`foreground=${fg} GetForegroundWindow=0x${getForegroundWindow().toString(16)}`);
    if (!fg) {
      throw new Error("SumatraPDF window did not become foreground");
    }

    say("waiting for the library to draw");
    let st: Record<string, number> = {};
    const deadline = Date.now() + 90000;
    while (Date.now() < deadline) {
      st = await libState(client);
      if ((st.visible ?? 0) > 0) {
        break;
      }
      await sleep(500);
    }
    const loadWait = Date.now() + 120000;
    let firstLoads = await loadCompleteCount(client);
    while (firstLoads < 1 && Date.now() < loadWait) {
      await sleep(1000);
      firstLoads = await loadCompleteCount(client);
    }
    say(`model state: ${JSON.stringify(st)}; completed loads=${firstLoads}`);
    if ((st.visible ?? 0) <= 0) {
      throw new Error("library never showed a book");
    }
    await sleep(1500);

    const canvas = findCanvas(hwnd);
    const cr = getClientRect(canvas);
    captureWindowDCToPng(hwnd, join(OUT, "01-before.png"));

    report.before = { ...(await snapshot()), model: st, service: await serviceStatus(), loadsCompleted: firstLoads };
    say(`BEFORE index=${JSON.stringify(report.before.index)} store=${JSON.stringify(report.before.store)}`);
    say(`BEFORE target pile=${report.before.targetPile} desk=${report.before.targetDesk}`);
    say(`BEFORE journal=${JSON.stringify(report.before.journal)}`);
    say(`BEFORE checkpoint=${JSON.stringify(report.before.checkpoint)}`);
    say(`BEFORE model visible=${st.visible} scanning=${st.scanning} native=${st.native}`);
    writeFileSync(join(OUT, "before-book-paths.json"), JSON.stringify(indexBookPaths(), null, 1), "utf8");

    // hunt for a real card: right-click candidate points until a popup carries
    // the per-book commands, which only exist when BookPathAtTemp found a book
    const xs: number[] = [];
    for (let f = 0.12; f <= 0.9; f += 0.13) {
      xs.push(Math.round(cr.right * f));
    }
    const ys: number[] = [];
    for (let y = 150; y < cr.bottom - 60; y += 90) {
      ys.push(y);
    }
    let hit: { x: number; y: number; sx: number; sy: number } | null = null;
    let popup = 0;
    let hmenu = 0n;
    let items: Array<{ pos: number; text: string; id: number; sub: bigint }> = [];
    const probes: any[] = [];
    outer: for (const y of ys) {
      for (const x of xs) {
        const s = clientToScreen(canvas, x, y);
        realMouseMove(s.x, s.y);
        await sleep(140);
        const under = topLevelWindowFromPoint(s.x, s.y);
        if (under !== hwnd) {
          probes.push({ x, y, skipped: `window under cursor 0x${under.toString(16)}` });
          continue;
        }
        realMouseClick("right");
        const menu = await waitForVisiblePopup(1500);
        if (!menu) {
          probes.push({ x, y, popup: false });
          continue;
        }
        const hm = getPopupMenuHandle(menu);
        const flat = hm ? readMenuItemsFlat(hm) : [];
        const texts = flat.map((i) => i.text);
        const wantSeries = process.env.RC_WANT_SERIES ?? "";
        const rightBook =
          wantSeries.length === 0 ||
          texts.some((t) => t.toLowerCase() === `take out of ${wantSeries.toLowerCase()}`);
        const isCard =
          texts.some((t) => MENU_RE.test(t)) && texts.some((t) => /play as audio/i.test(t)) && rightBook;
        probes.push({ x, y, popup: true, items: texts, isCard });
        if (isCard) {
          hit = { x, y, sx: s.x, sy: s.y };
          popup = menu;
          hmenu = hm;
          items = flat;
          break outer;
        }
        realKeyPress(VK_ESCAPE);
        await sleep(250);
      }
    }
    writeFileSync(join(OUT, "probes.json"), JSON.stringify(probes, null, 1), "utf8");
    if (!hit) {
      throw new Error("no library card produced a book context menu");
    }
    say(`card hit at canvas (${hit.x},${hit.y}) screen (${hit.sx},${hit.sy})`);
    captureWindowDCToPng(popup, join(OUT, "02-popup.png"));
    captureWindowDCRegionToPng(
      canvas,
      Math.max(0, hit.x - 200),
      Math.max(0, hit.y - 200),
      400,
      400,
      join(OUT, "03-card.png"),
      1,
    );
    realKeyPress(VK_ESCAPE);
    await sleep(400);

    let ready = false;
    let attempt = 0;
    while (attempt < 6 && !ready) {
      attempt++;
      const up = ((await serviceStatus()).ok ?? false) as boolean;
      say(`attempt ${attempt}: service up = ${up}`);
      if (!up) {
        await sleep(2000);
        continue;
      }
      realMouseMove(hit.sx, hit.sy);
      await sleep(120);
      realMouseClick("right");
      const menu2 = await waitForVisiblePopup(2500);
      if (!menu2) {
        say(`attempt ${attempt}: the popup did not open`);
        continue;
      }
      popup = menu2;
      hmenu = getPopupMenuHandle(menu2);
      items = hmenu ? readMenuItemsFlat(hmenu) : [];
      const t2 = items.find((i) => MENU_RE.test(i.text));
      if (!t2) {
        say(`attempt ${attempt}: ${MENU_NAME} is not in the popup`);
        realKeyPress(VK_ESCAPE);
        await sleep(300);
        continue;
      }
      const ir2 = getMenuItemRect(popup, hmenu, t2.pos);
      if (!ir2) {
        realKeyPress(VK_ESCAPE);
        await sleep(300);
        continue;
      }
      realMouseMove(Math.round((ir2.left + ir2.right) / 2), Math.round((ir2.top + ir2.bottom) / 2));
      await sleep(300);
      const st2 = getMenuItemState(hmenu, t2.pos);
      report.menu = {
        targetPos: t2.pos,
        targetId: t2.id,
        targetText: t2.text,
        itemRect: ir2,
        hilited: (st2 & MFS_HILITE) !== 0,
        popup: `0x${popup.toString(16)}`,
        popupClass: getClassName(popup),
        items: items.map((i) => ({ pos: i.pos, text: i.text, id: i.id })),
      };
      say(
        `attempt ${attempt}: popup=0x${popup.toString(16)} class=${getClassName(popup)} item pos=${t2.pos} id=${t2.id} fState=0x${st2.toString(16)} hilite=${report.menu.hilited}`,
      );
      for (const i of items) {
        say(`  menu[${i.pos}] id=${i.id} ${JSON.stringify(i.text)}`);
      }
      captureWindowDCToPng(popup, join(OUT, "04-popup-hilite.png"));
      if (!report.menu.hilited) {
        realKeyPress(VK_ESCAPE);
        await sleep(300);
        continue;
      }
      ready = true;
    }
    if (!ready) {
      throw new Error("could not open the menu while the library service was answering");
    }

    const scanAtClick = await libState(client);
    report.command.scanAtClick = scanAtClick;
    say(`scan state at the click: scanning=${scanAtClick.scanning} native=${scanAtClick.native}`);

    const kindBefore = kindPosts().length;
    realMouseClick("left");
    say(`clicked ${MENU_NAME}`);
    report.menu.selected = true;

    let gone = false;
    for (let i = 0; i < 60; i++) {
      if (visiblePopup() === 0) {
        gone = true;
        break;
      }
      await sleep(100);
    }
    say(`popup closed=${gone}`);

    let kindSeen: string | null = null;
    const kindDeadline = Date.now() + 60000;
    while (Date.now() < kindDeadline) {
      const posts = kindPosts();
      if (posts.length > kindBefore) {
        kindSeen = posts[posts.length - 1];
        break;
      }
      await sleep(250);
    }
    report.command.kindLog = kindSeen;
    if (kindSeen) {
      say(`app log: ${kindSeen.trim()}`);
      const m = kindSeen.match(/body=(\{.*\})\s*$/);
      if (m) {
        report.command.body = m[1];
        try {
          report.command.path = JSON.parse(m[1]).paths[0];
        } catch (e) {}
      }
    } else {
      say("the app never logged a /kind POST");
      throw new Error("no /kind POST");
    }
    if (TARGET.length === 0) {
      TARGET = String(report.command.path ?? "").toLowerCase();
      if (TARGET.length === 0) {
        throw new Error("could not learn which file the menu acted on");
      }
      say(`target learned from the command: ${TARGET}`);
    } else if (!String(report.command.path ?? "").toLowerCase().includes(TARGET)) {
      throw new Error(`the menu acted on ${report.command.path}, not on ${TARGET}`);
    }
    report.command.journalKindOfTarget = journal().target;
    say(`the scan journal holds the target as: ${report.command.journalKindOfTarget}`);
    if (process.env.RC_NEED_JOURNAL === "1" && report.command.journalKindOfTarget !== "books") {
      throw new Error(`the target is not in the scan journal as a book (${report.command.journalKindOfTarget})`);
    }

    const wantBooks = report.before.index.books - 1;
    const moveDeadline = Date.now() + 90000;
    while (Date.now() < moveDeadline) {
      if (indexCounts().books === wantBooks) {
        break;
      }
      await sleep(500);
    }
    await sleep(6000);
    const stAfter = await libState(client);
    captureWindowDCToPng(hwnd, join(OUT, "05-after-command.png"));
    report.afterCommand = { ...(await snapshot()), model: stAfter, service: await serviceStatus() };
    say(`AFTER COMMAND index=${JSON.stringify(report.afterCommand.index)} store=${JSON.stringify(report.afterCommand.store)}`);
    say(`AFTER COMMAND target pile=${report.afterCommand.targetPile} desk=${report.afterCommand.targetDesk} model visible=${stAfter.visible}`);
    say(`AFTER COMMAND journal=${JSON.stringify(report.afterCommand.journal)}`);
    writeFileSync(join(OUT, "after-command-book-paths.json"), JSON.stringify(indexBookPaths(), null, 1), "utf8");

    if (process.env.RC_STALE === "1") {
      const scanJson = journalScanJson();
      const ck = checkpointMeta();
      if (!scanJson || !ck) {
        throw new Error("no recorded scan result for the target");
      }
      const body = `{"roots":${JSON.stringify(ck.roots ?? [])},"scope":${Number(ck.scope ?? 0)},"book":${scanJson}}`;
      say("sending the recorded scan result for the target as a late /book");
      const rsp = await fetch(`http://127.0.0.1:${PORT}/book`, {
        method: "POST",
        body,
        signal: AbortSignal.timeout(30000),
      });
      const text = await rsp.text();
      report.stale = { status: rsp.status, response: text, bodyLen: body.length };
      say(`stale /book: HTTP ${rsp.status} ${text}`);
      await sleep(4000);
      report.stale.after = await snapshot();
      say(`STALE AFTER index=${JSON.stringify(report.stale.after.index)} target pile=${report.stale.after.targetPile}`);
      say(`STALE AFTER serviceBooks=${JSON.stringify(report.stale.after.serviceBooks)}`);
    }
  },
  ["-window-pos", posArg, "-log-to-file", APPLOG],
  {},
);

say("app closed normally; waiting for the library service to follow its parent");
let svcDownAfter = -1;
for (let i = 0; i < 120; i++) {
  const s = await serviceStatus();
  if (!s.ok) {
    svcDownAfter = i;
    break;
  }
  await sleep(1000);
}
report.shutdown.serviceStoppedAfterSeconds = svcDownAfter;
report.shutdown.state = await snapshot();
say(`library service stopped after about ${svcDownAfter}s`);
say(`AFTER CLOSE index=${JSON.stringify(report.shutdown.state.index)} target pile=${report.shutdown.state.targetPile}`);
if (svcDownAfter < 0) {
  throw new Error("the library service did not stop when the app closed");
}
await sleep(3000);

await withControlledSumatra(
  EXE,
  async (client, proc) => {
    const hwnd = await waitForFrame(proc.pid!, 20000);
    if (!hwnd) {
      throw new Error("no SumatraPDF frame window on restart");
    }
    await forceForeground(hwnd, 8000);
    let st: Record<string, number> = {};
    const deadline = Date.now() + 90000;
    while (Date.now() < deadline) {
      st = await libState(client);
      if ((st.visible ?? 0) > 0) {
        break;
      }
      await sleep(500);
    }
    await sleep(6000);
    st = await libState(client);
    captureWindowDCToPng(hwnd, join(OUT, "06-reopen.png"));
    report.reopen = { ...(await snapshot()), model: st, service: await serviceStatus() };
    say(`REOPEN index=${JSON.stringify(report.reopen.index)} store=${JSON.stringify(report.reopen.store)}`);
    say(`REOPEN target pile=${report.reopen.targetPile} desk=${report.reopen.targetDesk} model visible=${st.visible}`);
    say(`REOPEN service=${JSON.stringify(report.reopen.service)}`);

    if (SCAN_WAIT_S > 0) {
      say(`waiting up to ${SCAN_WAIT_S}s for the resumed scan`);
      let sawScan = false;
      const scanDeadline = Date.now() + SCAN_WAIT_S * 1000;
      let last = "";
      while (Date.now() < scanDeadline) {
        const s = await libState(client);
        const line = `scan scanning=${s.scanning} native=${s.native} done=${s.done}/${s.total} discovered=${s.discovered} processed=${s.processed} visible=${s.visible}`;
        if (line !== last) {
          say(`  ${line}`);
          last = line;
        }
        if ((s.native ?? 0) === 1) {
          sawScan = true;
        }
        if (sawScan && (s.native ?? 0) === 0 && (s.scanning ?? 0) === 0) {
          break;
        }
        await sleep(5000);
      }
      report.final.sawScan = sawScan;
      report.final.scanFinished = sawScan && ((await libState(client)).native ?? 0) === 0;
      say(`scan seen=${sawScan} finished=${report.final.scanFinished}`);
      await sleep(8000);
    }

    const stFinal = await libState(client);
    captureWindowDCToPng(hwnd, join(OUT, "07-final.png"));
    report.final = { ...report.final, ...(await snapshot()), model: stFinal, service: await serviceStatus() };
    say(`FINAL index=${JSON.stringify(report.final.index)} store=${JSON.stringify(report.final.store)}`);
    say(`FINAL target pile=${report.final.targetPile} desk=${report.final.targetDesk} model visible=${stFinal.visible}`);
    say(`FINAL journal=${JSON.stringify(report.final.journal)} checkpoint=${JSON.stringify(report.final.checkpoint)}`);
    writeFileSync(join(OUT, "final-book-paths.json"), JSON.stringify(indexBookPaths(), null, 1), "utf8");
  },
  ["-window-pos", posArg, "-log-to-file", join(OUT, "sumlog-restart.txt")],
  {},
);

if (process.env.RC_THIRD === "1") {
  say("closing again and reopening a third time to test persistence");
  for (let i = 0; i < 120; i++) {
    if (!(await serviceStatus()).ok) {
      break;
    }
    await sleep(1000);
  }
  await sleep(3000);
  await withControlledSumatra(
    EXE,
    async (client, proc) => {
      const hwnd = await waitForFrame(proc.pid!, 20000);
      if (!hwnd) {
        throw new Error("no SumatraPDF frame window on the third launch");
      }
      await forceForeground(hwnd, 8000);
      let st: Record<string, number> = {};
      const deadline = Date.now() + 90000;
      while (Date.now() < deadline) {
        st = await libState(client);
        if ((st.visible ?? 0) > 0) {
          break;
        }
        await sleep(500);
      }
      await sleep(8000);
      st = await libState(client);
      captureWindowDCToPng(hwnd, join(OUT, "08-third.png"));
      report.third = { ...(await snapshot()), model: st, service: await serviceStatus() };
      say(`THIRD index=${JSON.stringify(report.third.index)} store=${JSON.stringify(report.third.store)}`);
      say(`THIRD target pile=${report.third.targetPile} desk=${report.third.targetDesk} model visible=${st.visible}`);
      say(`THIRD serviceBooks=${JSON.stringify(report.third.serviceBooks)}`);
    },
    ["-window-pos", posArg, "-log-to-file", join(OUT, "sumlog-third.txt")],
    {},
  );
}

writeFileSync(join(OUT, "report.json"), JSON.stringify(report, null, 2), "utf8");
say(`wrote ${join(OUT, "report.json")}`);
