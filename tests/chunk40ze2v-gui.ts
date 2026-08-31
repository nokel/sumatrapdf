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
  captureWindowDCRegionToPng,
  getPopupMenuHandle,
  readMenuItemsFlat,
  getMenuItemRect,
  getWindowRect,
  getClientRect,
  getWindowText,
  getClassName,
  enumWindows,
  enumChildWindows,
  getWindowPid,
  getCursorPos,
  isWindowVisible,
  sendText,
  VK_RETURN,
  topLevelWindowFromPoint,
  clientToScreen,
  realMouseMove,
  realMouseClick,
  realKeyPress,
  sendMessage,
  postMessage,
  treeGetRoot,
  treeGetNextItem,
  SW_RESTORE,
  VK_ESCAPE,
  WM_CLOSE,
  sleep,
} from "./winapi.ts";
import { waitForFrame, waitForContextMenu, findCanvas } from "./win-automation.ts";

const ROOT = process.cwd();
const EXE = join(ROOT, "out", "dbg64", "SumatraPDF.exe");
const SETTINGS = join(ROOT, "out", "dbg64", "SumatraPDF-settings.txt");
const OUT = process.env.ET_OUT ?? join(ROOT, "scratchpad", "chunk40ze2v");
const MODE = process.env.ET_MODE ?? "open";
const TOGGLE = process.env.ET_TOGGLE ?? "";
const HOVER = process.env.ET_HOVER ?? "";
const DOC = process.env.ET_DOC ?? "";
const RESCAN = process.env.ET_RESCAN ?? "";
const SCAN_S = Number(process.env.ET_SCAN_S ?? "0");
const SAVE = process.env.ET_SAVE === "1";
const ADD = process.env.ET_ADD ?? "";
const PORT = 7863;
const PYCACHE = resolve(ROOT, "..", "Chatterbox-TTS-Extended-main", "audiobook", "cache", "library");

const VK_MENU = 0x12;
const TV_FIRST = 0x1100;
const TVM_GETITEMHEIGHT = TV_FIRST + 28;
const TVGN_NEXTVISIBLE = 0x6;

mkdirSync(OUT, { recursive: true });
const traceFile = join(OUT, `trace-${MODE}.txt`);
writeFileSync(traceFile, "");

function say(line: string): void {
  console.log(line);
  appendFileSync(traceFile, line + "\n", "utf8");
}

function settingsRootsBlock(): string[] {
  if (!existsSync(SETTINGS)) {
    return ["<no settings file>"];
  }
  const lines = readFileSync(SETTINGS, "utf8").split(/\r?\n/);
  const out: string[] = [];
  let depth = 0;
  let inBlock = false;
  for (const l of lines) {
    if (!inBlock && /^\s*LibraryRoots\s*\[/.test(l)) {
      inBlock = true;
      depth = 0;
    }
    if (inBlock) {
      out.push(l);
      depth += (l.match(/\[/g) ?? []).length;
      depth -= (l.match(/\]/g) ?? []).length;
      if (depth <= 0) {
        break;
      }
    }
  }
  return out.length ? out : ["<no LibraryRoots block>"];
}

function captureScreenRect(r: any, outPath: string): boolean {
  const w = r.right - r.left;
  const h = r.bottom - r.top;
  if (w <= 0 || h <= 0) {
    return false;
  }
  return captureWindowDCRegionToPng(0, r.left, r.top, w, h, outPath);
}

async function scanPerf(): Promise<any> {
  try {
    const rsp = await fetch(`http://127.0.0.1:${PORT}/status`, { signal: AbortSignal.timeout(8000) });
    const j: any = await rsp.json();
    return { books: j.books, documents: j.documents, scan_perf: j.scan_perf, roots: j.roots };
  } catch (e) {
    return { down: String(e) };
  }
}

function indexSummary(): any {
  try {
    const idx = JSON.parse(readFileSync(join(PYCACHE, "library.json"), "utf8"));
    const paths: string[] = [];
    for (const name of ["books", "documents", "ignored"]) {
      for (const b of idx[name] ?? []) {
        if (b.path) {
          paths.push(String(b.path));
        }
        for (const e of b.editions ?? []) {
          if (e.path) {
            paths.push(String(e.path));
          }
        }
      }
    }
    const uniq = Array.from(new Set(paths.map((p) => p.toLowerCase())));
    const byRoot: Record<string, number> = {};
    for (const r of idx.roots ?? []) {
      const rl = String(r).toLowerCase();
      byRoot[String(r)] = uniq.filter((p) => p.startsWith(rl)).length;
    }
    const sep = String.fromCharCode(92);
    const ebooksRoot = ["c:", "Users", "Nokel", "Documents", "ebooks"].join(sep).toLowerCase();
    const ebooks = uniq.filter((p) => p.startsWith(ebooksRoot)).length;
    return {
      generation: idx.generation,
      books: (idx.books ?? []).length,
      documents: (idx.documents ?? []).length,
      ignored: (idx.ignored ?? []).length,
      paths: uniq.length,
      underEbooks: ebooks,
      roots: idx.roots ?? [],
      byRoot,
    };
  } catch (e) {
    return { error: String(e) };
  }
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

function findSecondPopup(first: number): number {
  let found = 0;
  enumWindows((h) => {
    if (h !== first && getClassName(h) === "#32768") {
      const r = getWindowRect(h);
      if (r.right - r.left > 8) {
        found = h;
        return false;
      }
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

function findTooltipWindow(pid: number): { hwnd: number; rect: any } | null {
  let res: { hwnd: number; rect: any } | null = null;
  enumWindows((h) => {
    if (getClassName(h) !== "tooltips_class32" || getWindowPid(h) !== pid) {
      return true;
    }
    const r = getWindowRect(h);
    if (r.right - r.left > 8 && r.bottom - r.top > 4) {
      res = { hwnd: h, rect: r };
      return false;
    }
    return true;
  });
  return res;
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

async function addFolder(wnd: number, pid: number, path: string, out: string): Promise<string> {
  const wr = getWindowRect(wnd);
  const bx = wr.right - 122;
  const by = wr.bottom - 33;
  realMouseMove(bx, by);
  await sleep(300);
  realMouseClick("left");
  const dlg = await waitForPicker(pid, 12000);
  if (!dlg) {
    return "no folder picker appeared";
  }
  await sleep(1200);
  try {
    captureWindowDCToPng(dlg, out);
  } catch (e) {
    // the picker screenshot is a nicety, not evidence we depend on
  }
  const edit = findChildOfClass(dlg, "Edit");
  if (!edit) {
    postMessage(dlg, WM_CLOSE, 0, 0);
    return "the folder picker has no edit control";
  }
  sendText(edit, path);
  await sleep(400);
  realKeyPress(VK_RETURN);
  for (let i = 0; i < 24; i++) {
    await sleep(300);
    if (!isWindowVisible(dlg)) {
      return "picked";
    }
  }
  realKeyPress(VK_RETURN);
  await sleep(1200);
  if (!isWindowVisible(dlg)) {
    return "picked after a second Enter";
  }
  postMessage(dlg, WM_CLOSE, 0, 0);
  return "the folder picker stayed open";
}

async function clickMenuItem(frame: number, hmenu: bigint, pos: number, what: string): Promise<boolean> {
  const r = getMenuItemRect(frame, hmenu, pos);
  if (!r) {
    say(`  could not measure the menu item ${what}`);
    return false;
  }
  const x = Math.floor((r.left + r.right) / 2);
  const y = Math.floor((r.top + r.bottom) / 2);
  say(`  clicking ${what} at ${x},${y}`);
  realMouseMove(x, y);
  await sleep(220);
  realMouseClick("left");
  await sleep(450);
  return true;
}

const report: any = { mode: MODE };

setProcessDpiAware();
const prim = primaryMonitor()!;
const winW = Math.min(1700, prim.work.right - prim.work.left - 160);
const winH = Math.min(1150, prim.work.bottom - prim.work.top - 160);
const winX = prim.work.left + 60;
const winY = prim.work.top + 40;
const posArg = `${winW}x${winH}@${winX}x${winY}`;

report.indexBefore = indexSummary();
say(`index before: ${JSON.stringify(report.indexBefore)}`);
report.settingsBefore = settingsRootsBlock();
say(`settings before: ${JSON.stringify(report.settingsBefore)}`);

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
    await sleep(1500);

    const rootsBefore = String((await client.request(ControlCommand.TestLibraryRoots, []))[1] ?? "");
    report.scanRootsBefore = rootsBefore.trim().split(/\r?\n/);
    say(`scan roots before: ${JSON.stringify(report.scanRootsBefore)}`);

    if (MODE !== "scan") {
    say("pressing Alt to open the hamburger menu");
    let popup = 0;
    for (let attempt = 0; attempt < 5 && !popup; attempt++) {
      popup = await waitForContextMenu(250);
      if (popup) {
        say(`  a menu was already open on attempt ${attempt}`);
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
    say(`hamburger menu: ${JSON.stringify(report.hamburgerItems)}`);

    const settingsIdx = top.findIndex((i) => i.text.replace(/&/g, "") === "Settings");
    if (settingsIdx < 0) {
      throw new Error("no Settings item in the hamburger menu");
    }
    await clickMenuItem(hwnd, hmenu, settingsIdx, "Settings");

    const subMenu = top[settingsIdx].sub;
    const subItems = readMenuItemsFlat(subMenu);
    report.settingsItems = subItems.map((i) => `${i.text} (id ${i.id})`);
    say(`Settings menu: ${JSON.stringify(report.settingsItems)}`);

    const libIdx = subItems.findIndex((i) => i.text.replace(/&/g, "").startsWith("Library Indexing"));
    report.hasLibraryIndexing = libIdx >= 0;
    say(`Settings menu holds Library Indexing: ${report.hasLibraryIndexing}`);

    try {
      const popupRect = getWindowRect(popup);
      const subPopup = findSecondPopup(popup);
      const subRect = subPopup ? getWindowRect(subPopup) : popupRect;
      captureScreenRect(
        {
          left: Math.min(popupRect.left, subRect.left) - 4,
          top: Math.min(popupRect.top, subRect.top) - 4,
          right: Math.max(popupRect.right, subRect.right) + 4,
          bottom: Math.max(popupRect.bottom, subRect.bottom) + 4,
        },
        join(OUT, `menu-${MODE}.png`),
      );
      say(`screenshot of the open Settings menu: ${join(OUT, `menu-${MODE}.png`)}`);
    } catch (e) {
      say(`menu screenshot failed: ${e}`);
    }

    if (MODE === "menu" || libIdx < 0) {
      realKeyPress(VK_ESCAPE);
      await sleep(250);
      realKeyPress(VK_ESCAPE);
      await sleep(400);
    } else {
      await clickMenuItem(hwnd, subMenu, libIdx, "Library Indexing...");
    }

    if (MODE !== "menu" && libIdx >= 0) {
      const wnd = await waitForWindowTitled("Library Indexing", 20000);
      report.windowOpened = wnd !== 0;
      say(`Library Indexing window opened: ${report.windowOpened}`);
      if (!wnd) {
        throw new Error("the Library Indexing window did not open");
      }
      const wr = getWindowRect(wnd);
      report.windowRect = wr;
      report.windowClass = getClassName(wnd);
      say(`window class ${report.windowClass} rect ${JSON.stringify(wr)}`);

      const tree = findChildOfClass(wnd, "SysTreeView32");
      report.treeFound = tree !== 0;
      say(`tree control found: ${report.treeFound}`);

      let rows = 0;
      if (tree) {
        let it = treeGetRoot(tree);
        while (it !== 0n && rows < 1000) {
          rows++;
          it = treeGetNextItem(tree, TVGN_NEXTVISIBLE, it);
        }
      }
      report.treeRowCount = rows;
      say(`rows drawn in the tree control: ${rows}`);

      const dump1 = String((await client.request(ControlCommand.TestLibraryIndexing, []))[1] ?? "");
      report.dumpOpen = dump1.trim().split(/\r?\n/);
      for (const l of report.dumpOpen) {
        say(`  ${l}`);
      }

      try {
        captureWindowDCToPng(wnd, join(OUT, `window-${MODE}.png`));
        say(`screenshot of the window: ${join(OUT, `window-${MODE}.png`)}`);
      } catch (e) {
        say(`window screenshot failed: ${e}`);
      }

      const itemH = Number(sendMessage(tree, TVM_GETITEMHEIGHT, 0, 0n));
      report.itemHeight = itemH;
      say(`tree item height: ${itemH}`);
      const treeClient = getClientRect(tree);
      report.treeClient = treeClient;

      if (HOVER !== "") {
        const idx = Number(HOVER);
        const p = clientToScreen(tree, 40, Math.floor(idx * itemH + itemH / 2));
        say(`hovering row ${idx} at ${p.x},${p.y}`);
        say(`  tree window rect ${JSON.stringify(getWindowRect(tree))}`);
        realMouseMove(p.x - 60, p.y);
        await sleep(400);
        let tip = null as any;
        for (let jig = 0; jig < 14 && !tip; jig++) {
          realMouseMove(p.x - (jig % 2), p.y);
          await sleep(450);
          tip = findTooltipWindow(proc.pid!);
        }
        say(`  cursor at ${JSON.stringify(getCursorPos())}, top-level window under it ${topLevelWindowFromPoint(p.x, p.y)}, expected ${wnd}`);
        try {
          captureWindowDCToPng(wnd, join(OUT, `hoverwin-${MODE}.png`));
        } catch (e) {
          say(`hover window screenshot failed: ${e}`);
        }
        report.tooltipShown = tip !== null;
        if (tip) {
          report.tooltipRect = tip.rect;
          say(`tooltip window at ${JSON.stringify(tip.rect)}`);
          const pad = 6;
          try {
            captureScreenRect(
              {
                left: tip.rect.left - pad,
                top: tip.rect.top - pad,
                right: tip.rect.right + pad,
                bottom: tip.rect.bottom + pad,
              },
              join(OUT, `tooltip-${MODE}.png`),
            );
            say(`screenshot of the tooltip: ${join(OUT, `tooltip-${MODE}.png`)}`);
          } catch (e) {
            say(`tooltip screenshot failed: ${e}`);
          }
          try {
            captureScreenRect(
              {
                left: wr.left,
                top: wr.top,
                right: Math.max(wr.right, tip.rect.right + pad),
                bottom: Math.max(wr.bottom, tip.rect.bottom + pad),
              },
              join(OUT, `hover-${MODE}.png`),
            );
            say(`screenshot of the window with the tooltip: ${join(OUT, `hover-${MODE}.png`)}`);
          } catch (e) {
            say(`hover screenshot failed: ${e}`);
          }
        } else {
          say("no tooltip window was found");
        }
        const dumpTip = String((await client.request(ControlCommand.TestLibraryIndexing, []))[1] ?? "");
        report.dumpAfterHover = dumpTip.trim().split(/\r?\n/);
        say(`  ${report.dumpAfterHover[0]}`);
      }

      if (ADD !== "") {
        report.addResults = [];
        let n = 0;
        for (const pathToAdd of ADD.split("|")) {
          say(`clicking Add folder... to add ${pathToAdd}`);
          const how = await addFolder(wnd, proc.pid!, pathToAdd, join(OUT, `picker-${n}.png`));
          say(`  ${how}`);
          await sleep(900);
          const dump = String((await client.request(ControlCommand.TestLibraryIndexing, []))[1] ?? "");
          const lines = dump.trim().split(/\r?\n/);
          for (const l of lines) {
            say(`  ${l}`);
          }
          report.addResults.push({ path: pathToAdd, how, rows: lines });
          n++;
        }
        try {
          captureWindowDCToPng(wnd, join(OUT, `added-${MODE}.png`));
          say(`screenshot after the additions: ${join(OUT, `added-${MODE}.png`)}`);
        } catch (e) {
          say(`screenshot failed: ${e}`);
        }
      }

      if (TOGGLE !== "") {
        for (const part of TOGGLE.split(",")) {
          const idx = Number(part);
          const p = clientToScreen(tree, 12, Math.floor(idx * itemH + itemH / 2));
          say(`clicking the checkbox of row ${idx} at ${p.x},${p.y}`);
          realMouseMove(p.x, p.y);
          await sleep(250);
          realMouseClick("left");
          await sleep(500);
        }
        const dump2 = String((await client.request(ControlCommand.TestLibraryIndexing, []))[1] ?? "");
        report.dumpAfterToggle = dump2.trim().split(/\r?\n/);
        for (const l of report.dumpAfterToggle) {
          say(`  ${l}`);
        }
        try {
          captureWindowDCToPng(wnd, join(OUT, `toggled-${MODE}.png`));
          say(`screenshot after the toggle: ${join(OUT, `toggled-${MODE}.png`)}`);
        } catch (e) {
          say(`toggle screenshot failed: ${e}`);
        }
      }

      say("closing the Library Indexing window");
      postMessage(wnd, WM_CLOSE, 0, 0);
      await sleep(1200);
      report.windowClosed = findWindowByTitle("Library Indexing") === 0;
      say(`window closed: ${report.windowClosed}`);
    }

    }

    const rootsAfter = String((await client.request(ControlCommand.TestLibraryRoots, []))[1] ?? "");
    report.scanRootsAfter = rootsAfter.trim().split(/\r?\n/);
    say(`scan roots after: ${JSON.stringify(report.scanRootsAfter)}`);

    if (MODE === "remove") {
      await client.request(ControlCommand.TestLibAllBooks, []);
      await sleep(2500);
      const canvas = findCanvas(hwnd);
      report.canvas = canvas;
      const cr = getClientRect(canvas);
      say(`canvas ${canvas} client ${JSON.stringify(cr)}`);
      let done = false;
      for (let gy = 160; gy < cr.bottom - 40 && !done; gy += 200) {
        for (let gx = 90; gx < cr.right - 40 && !done; gx += 150) {
          const p = clientToScreen(canvas, gx, gy);
          realMouseMove(p.x, p.y);
          await sleep(200);
          realMouseClick("right");
          const popup = await waitForContextMenu(1500);
          if (!popup) {
            continue;
          }
          const hmenu = getPopupMenuHandle(popup);
          const items = readMenuItemsFlat(hmenu);
          const idx = items.findIndex((i) => i.text.replace(/&/g, "") === "Remove from library");
          if (idx < 0) {
            realKeyPress(VK_ESCAPE);
            await sleep(300);
            continue;
          }
          say(`book context menu at ${gx},${gy}: ${JSON.stringify(items.map((i) => i.text))}`);
          report.bookMenu = items.map((i) => i.text);
          try {
            captureWindowDCToPng(popup, join(OUT, "bookmenu.png"));
          } catch (e) {
            say(`menu screenshot failed: ${e}`);
          }
          const r = getMenuItemRect(hwnd, hmenu, idx);
          if (!r) {
            realKeyPress(VK_ESCAPE);
            break;
          }
          const mx = Math.floor((r.left + r.right) / 2);
          const my = Math.floor((r.top + r.bottom) / 2);
          say(`clicking Remove from library at ${mx},${my}`);
          realMouseMove(mx, my);
          await sleep(250);
          realMouseClick("left");
          done = true;
          await sleep(4000);
        }
      }
      report.removeClicked = done;
      say(`Remove from library used: ${done}`);
      await sleep(3000);
    }

    if (RESCAN !== "") {
      say("asking for a library scan");
      await client.request(ControlCommand.TestLibRescan, []);
      const deadline = Date.now() + SCAN_S * 1000;
      let last = "";
      while (Date.now() < deadline) {
        const s = String((await client.request(ControlCommand.TestLibScanStatus, []))[1] ?? "").trim();
        if (s !== last) {
          say(`  ${s}`);
          last = s;
        }
        if (/scanning=0 /.test(s) && /native=0/.test(s) && report.scanSeen) {
          break;
        }
        if (/native=1/.test(s)) {
          report.scanSeen = true;
        }
        await sleep(2000);
      }
      report.scanFinal = last;
      report.scanPerf = await scanPerf();
      say(`scan perf: ${JSON.stringify(report.scanPerf.scan_perf)}`);
      say(`service now: books=${report.scanPerf.books} documents=${report.scanPerf.documents}`);
    }
  },
  DOC
    ? ["-window-pos", posArg, "-log-to-file", join(OUT, `sumlog-${MODE}.txt`), DOC]
    : ["-window-pos", posArg, "-log-to-file", join(OUT, `sumlog-${MODE}.txt`)],
  { saveSettings: SAVE },
);

report.indexAfter = indexSummary();
say(`index after: ${JSON.stringify(report.indexAfter)}`);
report.settingsAfter = settingsRootsBlock();
say(`settings after: ${JSON.stringify(report.settingsAfter)}`);

writeFileSync(join(OUT, `report-${MODE}.json`), JSON.stringify(report, null, 2));
say(`wrote ${join(OUT, `report-${MODE}.json`)}`);
