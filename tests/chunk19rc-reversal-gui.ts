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
  realMouseMove,
  realMouseClick,
  realKeyPress,
  getPopupMenuHandle,
  readMenuItemsFlat,
  getMenuItemRect,
  getMenuItemState,
  captureWindowDCToPng,
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
const OUT = process.env.RC_OUT ?? join(ROOT, "scratchpad", "chunk19rc-reversal");
const PORT = 7863;
const TARGET = (process.env.RC_TARGET ?? "adventuresinraspberrypi.pdf").toLowerCase();

mkdirSync(OUT, { recursive: true });
const traceFile = join(OUT, "trace.txt");
writeFileSync(traceFile, "");

function say(line: string): void {
  console.log(line);
  appendFileSync(traceFile, line + "\n", "utf8");
}

const report: any = { before: {}, view: {}, menu: {}, command: {}, after: {}, restart: {} };
const APPLOG = join(OUT, "sumlog.txt");

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

function indexPileOf(pathLower: string): string {
  const idx = readIndex();
  if (!idx) {
    return "no-index";
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

function deskKindOf(pathLower: string): string {
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

function storeCounts(): { total: number; entries: number } {
  try {
    const text = readFileSync(join(DATA, "SumatraLibrary.txt"), "utf8");
    const m = text.match(/^Total = (\d+)/m);
    return {
      total: m ? Number(m[1]) : -1,
      entries: (text.match(/^\t\t\tId = /gm) ?? []).length + (text.match(/^\t\tId = /gm) ?? []).length,
    };
  } catch (e) {
    return { total: -1, entries: -1 };
  }
}

function kindPosts(): string[] {
  if (!existsSync(APPLOG)) {
    return [];
  }
  return readFileSync(APPLOG, "utf8")
    .split(String.fromCharCode(10))
    .filter((l) => l.includes("ServicePost:") && l.includes("path=/kind"));
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

async function snapshot(): Promise<any> {
  return {
    index: indexCounts(),
    store: storeCounts(),
    targetPile: indexPileOf(TARGET),
    targetDesk: deskKindOf(TARGET),
    service: await serviceStatus(),
  };
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
      mon = monitorOfWindow(hwnd)!;
    }
    showWindow(hwnd, SW_RESTORE);
    if (!(await forceForeground(hwnd, 8000))) {
      throw new Error("SumatraPDF window did not become foreground");
    }
    let st: Record<string, number> = {};
    const deadline = Date.now() + 90000;
    while (Date.now() < deadline) {
      st = await libState(client);
      if ((st.visible ?? 0) > 0) {
        break;
      }
      await sleep(500);
    }
    await sleep(2500);
    report.before = { ...(await snapshot()), model: st };
    say(`BEFORE index=${JSON.stringify(report.before.index)} store=${JSON.stringify(report.before.store)}`);
    say(`BEFORE target pile=${report.before.targetPile} desk=${report.before.targetDesk} model visible=${st.visible}`);

    const canvas = findCanvas(hwnd);
    const cr = getClientRect(canvas);
    captureWindowDCToPng(hwnd, join(OUT, "01-library.png"));

    // Right-click a point and report which page the popup belongs to.
    async function popupAt(x: number, y: number): Promise<string[] | null> {
      const s = clientToScreen(canvas, x, y);
      realMouseMove(s.x, s.y);
      await sleep(120);
      if (topLevelWindowFromPoint(s.x, s.y) !== hwnd) {
        return null;
      }
      realMouseClick("right");
      const menu = await waitForVisiblePopup(1500);
      if (!menu) {
        return null;
      }
      const hm = getPopupMenuHandle(menu);
      const texts = hm ? readMenuItemsFlat(hm).map((i) => i.text) : [];
      realKeyPress(VK_ESCAPE);
      await sleep(250);
      return texts;
    }

    async function clickAt(x: number, y: number): Promise<void> {
      const s = clientToScreen(canvas, x, y);
      realMouseMove(s.x, s.y);
      await sleep(120);
      realMouseClick("left");
      await sleep(900);
    }

    // The Deskpan row of the rail sits directly under "All books".
    let deskOpen = false;
    const railTried: any[] = [];
    for (let y = 74; y <= 104 && !deskOpen; y += 4) {
      await clickAt(60, y);
      const texts = await popupAt(Math.round(cr.right * 0.2), 200);
      const isDesk = (texts ?? []).some((t) => /^move to library$/i.test(t));
      railTried.push({ y, items: texts, isDesk });
      say(`rail click y=${y} popup=${JSON.stringify(texts)}`);
      if (isDesk) {
        deskOpen = true;
        report.view.railY = y;
      }
    }
    writeFileSync(join(OUT, "rail-probes.json"), JSON.stringify(railTried, null, 1), "utf8");
    captureWindowDCToPng(hwnd, join(OUT, "02-deskpan.png"));
    if (!deskOpen) {
      throw new Error("could not open the Deskpan page");
    }
    say(`Deskpan opened from the rail at canvas y=${report.view.railY}`);

    // Switch the Deskpan to the ignored pile. The pile is known from the
    // context menu: the ignored pile offers "Remove from library", the
    // documents pile offers "Ignore file".
    let ignoredView = false;
    const pillTried: any[] = [];
    outer: for (let y = 66; y <= 96; y += 5) {
      for (let x = 280; x <= 460; x += 12) {
        await clickAt(x, y);
        const texts = await popupAt(Math.round(cr.right * 0.2), 200);
        const isIgnored =
          (texts ?? []).some((t) => /^move to library$/i.test(t)) &&
          (texts ?? []).some((t) => /^remove from library$/i.test(t));
        pillTried.push({ x, y, items: texts, isIgnored });
        if (isIgnored) {
          ignoredView = true;
          report.view.pill = { x, y };
          break outer;
        }
      }
    }
    writeFileSync(join(OUT, "pill-probes.json"), JSON.stringify(pillTried, null, 1), "utf8");
    captureWindowDCToPng(hwnd, join(OUT, "03-ignored.png"));
    if (!ignoredView) {
      throw new Error("could not switch the Deskpan to the ignored pile");
    }
    say(`ignored pile shown after a click at canvas ${JSON.stringify(report.view.pill)}`);

    // The desk list is fetched again when the pile changes, and the tiles keep
    // answering from the old list until it arrives. The ignored pile holds one
    // file and the documents pile holds many, so the list has changed over only
    // when a tile answers in the first column and nothing answers in the second.
    let settled = false;
    for (let i = 0; i < 30 && !settled; i++) {
      const first = await popupAt(290, 200);
      const second = await popupAt(480, 200);
      const firstIsTile = (first ?? []).some((t) => /^move to library$/i.test(t));
      const secondIsTile = (second ?? []).some((t) => /^move to library$/i.test(t));
      say(`desk list check ${i}: first tile=${firstIsTile} second tile=${secondIsTile}`);
      settled = firstIsTile && !secondIsTile;
      if (!settled) {
        await sleep(1000);
      }
    }
    if (!settled) {
      throw new Error("the desk never settled on a single-tile pile");
    }
    captureWindowDCToPng(hwnd, join(OUT, "03b-single-tile.png"));

    // The ignored pile holds exactly one file, so the first tile is it.
    let popup = 0;
    let hmenu = 0n;
    let pos = -1;
    let hit: { sx: number; sy: number } | null = null;
    const tileTried: any[] = [];
    outer2: for (let y = 150; y <= 330; y += 30) {
      for (let x = 240; x <= 340; x += 25) {
        const s = clientToScreen(canvas, x, y);
        realMouseMove(s.x, s.y);
        await sleep(120);
        realMouseClick("right");
        const menu = await waitForVisiblePopup(1500);
        if (!menu) {
          tileTried.push({ x, y, popup: false });
          continue;
        }
        const hm = getPopupMenuHandle(menu);
        const items = hm ? readMenuItemsFlat(hm) : [];
        const move = items.find((i) => /^move to library$/i.test(i.text));
        tileTried.push({ x, y, items: items.map((i) => i.text) });
        if (move) {
          popup = menu;
          hmenu = hm;
          pos = move.pos;
          hit = { sx: s.x, sy: s.y };
          report.menu.items = items.map((i) => ({ pos: i.pos, text: i.text, id: i.id }));
          break outer2;
        }
        realKeyPress(VK_ESCAPE);
        await sleep(250);
      }
    }
    writeFileSync(join(OUT, "tile-probes.json"), JSON.stringify(tileTried, null, 1), "utf8");
    if (!hit) {
      throw new Error("no ignored tile produced a desk context menu");
    }
    for (const i of report.menu.items) {
      say(`  menu[${i.pos}] id=${i.id} ${JSON.stringify(i.text)}`);
    }
    const ir = getMenuItemRect(popup, hmenu, pos);
    if (!ir) {
      throw new Error("no rect for Move to library");
    }
    realMouseMove(Math.round((ir.left + ir.right) / 2), Math.round((ir.top + ir.bottom) / 2));
    await sleep(300);
    const state = getMenuItemState(hmenu, pos);
    report.menu.targetPos = pos;
    report.menu.hilited = (state & MFS_HILITE) !== 0;
    report.menu.itemRect = ir;
    say(`Move to library at pos=${pos} rect=${JSON.stringify(ir)} fState=0x${state.toString(16)} hilite=${report.menu.hilited}`);
    captureWindowDCToPng(popup, join(OUT, "04-desk-popup.png"));
    if (!report.menu.hilited) {
      throw new Error("Move to library did not highlight");
    }

    const before = kindPosts().length;
    realMouseClick("left");
    say("clicked Move to library");
    for (let i = 0; i < 60 && visiblePopup() !== 0; i++) {
      await sleep(100);
    }
    let seen: string | null = null;
    const dl = Date.now() + 60000;
    while (Date.now() < dl) {
      const posts = kindPosts();
      if (posts.length > before) {
        seen = posts[posts.length - 1];
        break;
      }
      await sleep(250);
    }
    report.command.kindLog = seen;
    if (!seen) {
      throw new Error("the app never logged a /kind POST");
    }
    say(`app log: ${seen.trim()}`);
    const m = seen.match(/body=(\{.*\})\s*$/);
    if (m) {
      report.command.body = m[1];
      try {
        report.command.path = JSON.parse(m[1]).paths[0];
      } catch (e) {}
    }
    if (!String(report.command.path ?? "").toLowerCase().includes(TARGET)) {
      throw new Error(`Move to library acted on ${report.command.path}, not on ${TARGET}`);
    }

    const want = report.before.index.books + 1;
    const moveDeadline = Date.now() + 90000;
    while (Date.now() < moveDeadline) {
      if (indexCounts().books === want) {
        break;
      }
      await sleep(500);
    }
    await sleep(8000);
    const stAfter = await libState(client);
    captureWindowDCToPng(hwnd, join(OUT, "05-after.png"));
    report.after = { ...(await snapshot()), model: stAfter };
    say(`AFTER index=${JSON.stringify(report.after.index)} store=${JSON.stringify(report.after.store)}`);
    say(`AFTER target pile=${report.after.targetPile} desk=${report.after.targetDesk} model visible=${stAfter.visible}`);
  },
  ["-window-pos", posArg, "-log-to-file", APPLOG],
  {},
);

say("app closed; waiting for the service to follow");
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
    await sleep(8000);
    st = await libState(client);
    captureWindowDCToPng(hwnd, join(OUT, "06-restart.png"));
    report.restart = { ...(await snapshot()), model: st };
    say(`RESTART index=${JSON.stringify(report.restart.index)} store=${JSON.stringify(report.restart.store)}`);
    say(`RESTART target pile=${report.restart.targetPile} desk=${report.restart.targetDesk} model visible=${st.visible}`);
  },
  ["-window-pos", posArg, "-log-to-file", join(OUT, "sumlog-restart.txt")],
  {},
);

writeFileSync(join(OUT, "report.json"), JSON.stringify(report, null, 2), "utf8");
say(`wrote ${join(OUT, "report.json")}`);
