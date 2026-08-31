import { existsSync, readFileSync, writeFileSync, appendFileSync, mkdirSync, rmSync } from "node:fs";
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
  getWindowPid,
  getClassName,
  realMouseMove,
  realMouseClick,
  realKeyPress,
  getCursorPos,
  getPopupMenuHandle,
  readMenuItemsFlat,
  getMenuItemRect,
  getMenuItemState,
  captureWindowToPng,
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

// A popup menu that is on screen right now. EnumWindows also hands back the
// hidden #32768 windows the system keeps around after a menu closes, so a
// visibility test is what separates "the menu opened" from "a menu once opened".
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
const OUT = process.env.CHUNK19RB_OUT ?? join(ROOT, "scratchpad", "chunk19rb");
const PORT = 7863;

mkdirSync(OUT, { recursive: true });
const traceFile = join(OUT, "trace.txt");
writeFileSync(traceFile, "");

function say(line: string): void {
  console.log(line);
  appendFileSync(traceFile, line + "\n", "utf8");
}

const report: any = { harness: {}, menu: {}, before: {}, command: {}, after: {}, allBooks: {}, restart: {} };

const PYTHONW = join(CHATTERBOX, ".venv-amd", "Scripts", "pythonw.exe");

// Bring up the library service with the same command line LibraryEnsureService
// uses, and only when nothing already answers. A second instance cannot bind the
// port, so this never races the one the app starts for itself.
async function ensureService(waitMs = 25000): Promise<boolean> {
  if ((await serviceStatus()).ok) {
    return true;
  }
  say("no library service is answering; starting one the way the app does");
  Bun.spawn([PYTHONW, "-m", "audiobook.library", "--port", String(PORT)], {
    cwd: CHATTERBOX,
    stdout: "ignore",
    stderr: "ignore",
  });
  const deadline = Date.now() + waitMs;
  while (Date.now() < deadline) {
    await sleep(250);
    if ((await serviceStatus()).ok) {
      return true;
    }
  }
  return false;
}

async function serviceStatus(): Promise<any> {
  try {
    return await getJson("/status");
  } catch (e) {
    return { down: String(e) };
  }
}

async function serviceLibrary(): Promise<any> {
  try {
    return await getJson("/library?limit=5000");
  } catch (e) {
    return { down: String(e), total: -1, books: [], series: [] };
  }
}

async function getJson(path: string): Promise<any> {
  const rsp = await fetch(`http://127.0.0.1:${PORT}${path}`, { signal: AbortSignal.timeout(4000) });
  if (!rsp.ok) {
    throw new Error(`${path}: HTTP ${rsp.status}`);
  }
  return await rsp.json();
}

function indexCounts(): { books: number; documents: number; ignored: number } {
  try {
    const idx = JSON.parse(readFileSync(join(PYCACHE, "library.json"), "utf8"));
    return {
      books: (idx.books ?? []).length,
      documents: (idx.documents ?? []).length,
      ignored: (idx.ignored ?? []).length,
    };
  } catch (e) {
    return { books: -1, documents: -1, ignored: -1 };
  }
}

function indexBookPaths(): string[] {
  try {
    const idx = JSON.parse(readFileSync(join(PYCACHE, "library.json"), "utf8"));
    return (idx.books ?? []).map((b: any) => String(b.path ?? ""));
  } catch (e) {
    return [];
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
      series: (text.match(/^		Name = /gm) ?? []).length,
      total: num(/^Total = (\d+)/m),
      documents: num(/^Documents = (\d+)/m),
      entries: (text.match(/^\t\t\tId = /gm) ?? []).length + (text.match(/^\t\tId = /gm) ?? []).length,
    };
  } catch (e) {
    return { total: -1, documents: -1, entries: -1, version: -1, series: -1 };
  }
}

const APPLOG = join(OUT, "sumlog.txt");

// The cover cache entry that keeps the library service alive. Its book is the one
// file PyMuPDF takes an access violation on, so while this file is present the
// cover sweep steps over it. CHUNK19RA_DROP_COVER makes the harness delete it in
// the instant before the menu command runs, which puts the service crash exactly
// where a user meets it: inside the sweep that POST /kind itself starts.
const POISON_COVER = join(PYCACHE, "covers", "3545489b64f00efc.jpg");
const DROP_COVER = false;
// The service is down for most of a real session, so the command has to be
// exercised in that state too, not only while the service happens to answer.
const REQUIRE_SERVICE = process.env.CHUNK19RB_SERVICE === "up";
// Which context-menu command to run. "remove" is Remove from library; "series"
// is Take out of <series>, a different command that reloads the catalogue the
// same way, so the same reload-failure path is reached without Remove.
const MENU = process.env.CHUNK19RB_MENU ?? "remove";
const MENU_RE = MENU === "series" ? /take out of /i : /remove from library/i;
const MENU_NAME = MENU === "series" ? "Take out of <series>" : "Remove from library";

function appLogLines(): string[] {
  if (!existsSync(APPLOG)) {
    return [];
  }
  return readFileSync(APPLOG, "utf8").split(String.fromCharCode(10));
}

// Every "/kind" POST the app made, out of its own ServicePost log line, which
// carries the request body verbatim.
function kindPosts(): string[] {
  const want = MENU === "series" ? "path=/series/pull" : "path=/kind";
  return appLogLines().filter((l) => l.includes("ServicePost:") && l.includes(want));
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

if (!existsSync(EXE)) {
  say(`missing ${EXE}`);
  process.exit(1);
}

setProcessDpiAware();

const status0 = await serviceStatus();
say(`service on ${PORT} before launch: ${JSON.stringify(status0)}`);

const prim = primaryMonitor()!;
say(`primary monitor: ${JSON.stringify(prim.rect)} work=${JSON.stringify(prim.work)}`);

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
    report.harness.hwndDec = hwnd;
    say(`frame hwnd=0x${hwnd.toString(16)} pid=${pid} class=${getClassName(hwnd)} windowPid=${getWindowPid(hwnd)}`);
    if (getWindowPid(hwnd) !== pid) {
      throw new Error("frame window belongs to another process");
    }

    if (isIconic(hwnd)) {
      showWindow(hwnd, SW_RESTORE);
    }
    let mon = monitorOfWindow(hwnd)!;
    if (!mon.primary) {
      say("window is not on the primary monitor; moving it");
      moveWindow(hwnd, winX, winY, winW, winH, true);
      await sleep(400);
      mon = monitorOfWindow(hwnd)!;
    }
    showWindow(hwnd, SW_RESTORE);
    const rect = getWindowRect(hwnd);
    report.harness.windowRect = rect;
    report.harness.onPrimary = mon.primary;
    say(`window rect=${JSON.stringify(rect)} onPrimaryMonitor=${mon.primary}`);
    if (!mon.primary) {
      throw new Error("could not place the window on the primary monitor");
    }

    const fg = await forceForeground(hwnd, 8000);
    report.harness.foreground = fg;
    report.harness.foregroundHwnd = `0x${getForegroundWindow().toString(16)}`;
    say(`foreground=${fg} GetForegroundWindow=0x${getForegroundWindow().toString(16)}`);
    if (!fg) {
      throw new Error("SumatraPDF window did not become foreground");
    }

    say("waiting for the library to draw");
    let st: Record<string, number> = {};
    const deadline = Date.now() + 60000;
    while (Date.now() < deadline) {
      st = await libState(client);
      if ((st.visible ?? 0) > 0 && (st.scanning ?? 0) === 0) {
        break;
      }
      await sleep(500);
    }
    // The first LoadModelThread must have finished: while it is still running the
    // page is drawing the on-disk store, and a load that starts in that state
    // falls back to the store instead of emptying the model.
    const loadWait = Date.now() + 120000;
    let firstLoads = await loadCompleteCount(client);
    while (firstLoads < 1 && Date.now() < loadWait) {
      await sleep(1000);
      firstLoads = await loadCompleteCount(client);
    }
    say(`model state: ${JSON.stringify(st)}; completed loads=${firstLoads}`);
    report.before.loadsCompleted = firstLoads;
    if ((st.visible ?? 0) <= 0) {
      throw new Error("library never showed a book");
    }
    await sleep(1500);

    const canvas = findCanvas(hwnd);
    const cr = getClientRect(canvas);
    say(`canvas hwnd=0x${canvas.toString(16)} client=${JSON.stringify(cr)}`);
    captureWindowDCToPng(hwnd, join(OUT, "01-before.png"));

    report.before.serviceUp = REQUIRE_SERVICE ? await ensureService() : (((await serviceStatus()).ok ?? false) as boolean);
    say(`service up before the command: ${report.before.serviceUp}`);
    report.before = {
      ...report.before,
      index: indexCounts(),
      store: storeCounts(),
      model: st,
      service: await serviceStatus(),
    };
    const lib0 = await serviceLibrary();
    report.before.serviceBooks = lib0.total ?? (lib0.books ?? []).length;
    report.before.serviceSeries = (lib0.series ?? []).length;
    say(`BEFORE index=${JSON.stringify(report.before.index)} store=${JSON.stringify(report.before.store)}`);
    say(`BEFORE service books=${report.before.serviceBooks} series=${report.before.serviceSeries} model visible=${st.visible}`);
    writeFileSync(join(OUT, "before-book-paths.json"), JSON.stringify(indexBookPaths(), null, 1), "utf8");

    report.before.serviceUpBeforeProbe = REQUIRE_SERVICE ? await ensureService() : (((await serviceStatus()).ok ?? false) as boolean);
    say(`service up right before probing: ${report.before.serviceUpBeforeProbe}`);

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
        const isCard =
          texts.some((t) => MENU_RE.test(t)) && texts.some((t) => /play as audio/i.test(t));
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
    report.menu.probes = probes.length;
    if (!hit) {
      throw new Error("no library card produced a book context menu");
    }
    say(`card hit at canvas (${hit.x},${hit.y}) screen (${hit.sx},${hit.sy})`);
    say(`popup hwnd=0x${popup.toString(16)} class=${getClassName(popup)} hmenu=0x${hmenu.toString(16)}`);
    report.menu.clickPoint = hit;
    report.menu.popup = `0x${popup.toString(16)}`;
    report.menu.popupClass = getClassName(popup);
    report.menu.items = items.map((i) => ({ pos: i.pos, text: i.text, id: i.id }));
    for (const i of items) {
      say(`  menu[${i.pos}] id=${i.id} ${JSON.stringify(i.text)}${i.sub ? " (submenu)" : ""}`);
    }
    captureWindowDCToPng(popup, join(OUT, "02-popup.png"));
    captureWindowDCRegionToPng(canvas, Math.max(0, hit.x - 200), Math.max(0, hit.y - 200), 400, 400, join(OUT, "03-card.png"), 1);

    // The service dies seconds after it starts (see the report), so the menu is
    // opened again from the recorded card point only once /status answers, and
    // the item is clicked only while it still answers. Otherwise the POST never
    // reaches the service and the command is a no-op.
    realKeyPress(VK_ESCAPE);
    await sleep(400);

    let clickedWithService = false;
    let attempt = 0;
    while (attempt < 6 && !clickedWithService) {
      attempt++;
      const up = REQUIRE_SERVICE ? await ensureService() : ((await serviceStatus()).ok ?? false) as boolean;
      say(`attempt ${attempt}: service up = ${up}`);
      if (REQUIRE_SERVICE && !up) {
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
      const ix2 = Math.round((ir2.left + ir2.right) / 2);
      const iy2 = Math.round((ir2.top + ir2.bottom) / 2);
      realMouseMove(ix2, iy2);
      await sleep(300);
      const st2 = getMenuItemState(hmenu, t2.pos);
      report.menu.targetPos = t2.pos;
      report.menu.targetId = t2.id;
      report.menu.targetText = t2.text;
      report.menu.itemRect = ir2;
      report.menu.hilited = (st2 & MFS_HILITE) !== 0;
      report.menu.items = items.map((i) => ({ pos: i.pos, text: i.text, id: i.id }));
      report.menu.popup = `0x${popup.toString(16)}`;
      report.menu.popupClass = getClassName(popup);
      say(`attempt ${attempt}: popup=0x${popup.toString(16)} class=${getClassName(popup)} item pos=${t2.pos} id=${t2.id} rect=${JSON.stringify(ir2)} fState=0x${st2.toString(16)} hilite=${report.menu.hilited}`);
      for (const i of items) {
        say(`  menu[${i.pos}] id=${i.id} ${JSON.stringify(i.text)}${i.sub ? " (submenu)" : ""}`);
      }
      captureWindowDCToPng(popup, join(OUT, "04-popup-hilite.png"));
      if (!report.menu.hilited) {
        realKeyPress(VK_ESCAPE);
        await sleep(300);
        continue;
      }
      if (DROP_COVER && existsSync(POISON_COVER)) {
        rmSync(POISON_COVER);
        report.command.droppedCover = POISON_COVER;
        say(`removed ${POISON_COVER} so the sweep that /kind starts meets the crashing book`);
      }
      const stillUp = ((await serviceStatus()).ok ?? false) as boolean;
      report.menu.serviceUpAtClick = stillUp;
      say(`attempt ${attempt}: service answering at the moment of the click: ${stillUp}`);
      if (REQUIRE_SERVICE && !stillUp) {
        realKeyPress(VK_ESCAPE);
        await sleep(300);
        continue;
      }
      clickedWithService = true;
    }
    if (!clickedWithService) {
      throw new Error("could not open the menu while the library service was answering");
    }

    const kindBefore = kindPosts().length;
    const loadsBefore = await loadCompleteCount(client);
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
    report.menu.popupClosed = gone;
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
      for (const l of appLogLines()) {
        if (/ServicePost: result=/.test(l) || /library service/i.test(l)) {
          say(`app log: ${l.trim()}`);
        }
      }
    } else {
      say("the app never logged a /kind POST");
    }

    const timeline: any[] = [];
    let settle = 0;
    const loadDeadline = Date.now() + 120000;
    let loaded = false;
    while (Date.now() < loadDeadline) {
      const t = {
        ms: Date.now(),
        model: (await libState(client)).visible,
        index: indexCounts(),
        store: storeCounts().total,
        loads: await loadCompleteCount(client),
      };
      timeline.push(t);
      if (t.model === 0 && !report.command.emptySeen) {
        report.command.emptySeen = true;
        report.command.emptyAt = timeline.length;
        captureWindowDCToPng(hwnd, join(OUT, "05a-empty.png"));
        say("the model went empty; captured 05a-empty.png");
      }
      say(`t+${Math.round((t.ms - loadDeadline + 120000) / 1000)}s model=${t.model} index=${t.index.books}/${t.index.documents} store=${t.store} loads=${t.loads}`);
      if (t.loads > loadsBefore) {
        loaded = true;
        if (settle++ > 20) {
          break;
        }
      }
      await sleep(1000);
    }
    report.command.timeline = timeline;
    report.command.modelReloaded = loaded;
    await sleep(4000);

    const stAfter = await libState(client);
    captureWindowDCToPng(hwnd, join(OUT, "05-after.png"));
    report.after = {
      index: indexCounts(),
      store: storeCounts(),
      model: stAfter,
      service: await serviceStatus(),
    };
    const lib1 = await serviceLibrary();
    report.after.serviceBooks = lib1.total ?? (lib1.books ?? []).length;
    report.after.serviceSeries = (lib1.series ?? []).length;
    writeFileSync(join(OUT, "after-book-paths.json"), JSON.stringify(indexBookPaths(), null, 1), "utf8");
    say(`AFTER index=${JSON.stringify(report.after.index)} store=${JSON.stringify(report.after.store)}`);
    say(`AFTER service books=${report.after.serviceBooks} series=${report.after.serviceSeries} model visible=${stAfter.visible}`);

    await client.request(ControlCommand.TestLibAllBooks, []);
    await sleep(3000);
    const stAll = await libState(client);
    captureWindowDCToPng(hwnd, join(OUT, "06-all-books.png"));
    report.allBooks = { model: stAll };
    say(`ALL BOOKS model visible=${stAll.visible} ${JSON.stringify(stAll)}`);

  },
  ["-window-pos", posArg, "-log-to-file", APPLOG],
  {},
);

say("app closed; reopening");
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
    const deadline = Date.now() + 60000;
    while (Date.now() < deadline) {
      st = await libState(client);
      if ((st.visible ?? 0) > 0 && (st.scanning ?? 0) === 0) {
        break;
      }
      await sleep(500);
    }
    await sleep(3000);
    st = await libState(client);
    captureWindowDCToPng(hwnd, join(OUT, "07-restart.png"));
    report.restart = {
      index: indexCounts(),
      store: storeCounts(),
      model: st,
      service: await serviceStatus(),
    };
    say(`RESTART index=${JSON.stringify(report.restart.index)} store=${JSON.stringify(report.restart.store)}`);
    say(`RESTART model visible=${st.visible}`);
  },
  ["-window-pos", posArg, "-log-to-file", join(OUT, "sumlog-restart.txt")],
  {},
);

writeFileSync(join(OUT, "report.json"), JSON.stringify(report, null, 2), "utf8");
say(`wrote ${join(OUT, "report.json")}`);
