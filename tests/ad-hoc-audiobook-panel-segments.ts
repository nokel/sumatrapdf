import { launchSumatra, waitForFrame, sendCommand } from "./win-automation";
import {
  sleep,
  enumChildWindows,
  getClassName,
  getParent,
  isWindow,
  getWindowText,
  getWindowRect,
  getScrollPos,
  getScrollInfo,
  postMessage,
  sendMessage,
  packCoords,
  captureWindowToPng,
  WM_COMMAND,
  SB_VERT,
} from "./winapi";
import { cmdId, tmpPath } from "./util";

const WM_CLOSE = 0x0010;
const WM_MOUSEWHEEL = 0x020a;
const PBM_GETPOS = 0x0408;
const kIdCloseChars = 1012;
const CHARS_CLASS = "SUMATRA_AUDIOBOOK_CHARS";
const PDF =
  "C:\\Users\\Nokel\\Documents\\ebooks\\manga_novels\\The Silo Saga Omnibus_ Wool, Shift, Dust, and Silo Stories by Hugh Howey.pdf";

let fails = 0;
function check(ok: boolean, what: string) {
  console.log((ok ? "ok  " : "FAIL") + " " + what);
  if (!ok) fails++;
}

let fake: ReturnType<typeof Bun.serve> | null = null;
let fakeAnalyzing = false;
let fakeLinesRead = 0;
const fakeLinesTotal = 200;

function fakeState(): string {
  const chars = [];
  for (let i = 1; i <= 30; i++) {
    chars.push({ name: `Char${String(i).padStart(2, "0")}`, lines: i * 3, first: i * 10, voice: i % 3 === 0 ? "" : "VoiceA" });
  }
  return JSON.stringify({
    pdf: PDF,
    narrator: "VoiceA",
    voices: ["VoiceA", "VoiceB", "VoiceC"],
    lm_models: ["model-alpha", "model-beta"],
    lm_model: "",
    characters: chars,
    analyzed: true,
    analyzing: fakeAnalyzing,
    analyze_status: fakeAnalyzing ? `chunk ${fakeLinesRead} of ${fakeLinesTotal}` : "done",
    analyze_error: "",
    analyze_complete: true,
    lines_read: fakeAnalyzing ? fakeLinesRead : fakeLinesTotal,
    lines_total: fakeLinesTotal,
    endpoints: [{ url: "http://127.0.0.1:11434", model: "model-alpha", ok: true, local: true }],
    scan: { scanning: false, done: 0, total: 0, message: "", found: [] },
  });
}

function tryStartFakeServer(): boolean {
  try {
    fake = Bun.serve({
      port: 7862,
      hostname: "127.0.0.1",
      fetch(req) {
        const path = new URL(req.url).pathname;
        if (path === "/state") return new Response(fakeState(), { headers: { "Content-Type": "application/json" } });
        return new Response("{}", { headers: { "Content-Type": "application/json" } });
      },
    });
    return true;
  } catch {
    return false;
  }
}

function findPanelWindows(frame: number) {
  const charsWnds: number[] = [];
  enumChildWindows(frame, (h) => {
    if (getClassName(h) === CHARS_CLASS) charsWnds.push(h);
    return true;
  });
  if (charsWnds.length === 0) return null;
  const container = getParent(charsWnds[0]);
  const kids: number[] = [];
  enumChildWindows(container, (h) => {
    if (getParent(h) === container) kids.push(h);
    return true;
  });
  const statics = kids.filter((h) => getClassName(h) === "Static");
  const label = kids.find((h) => getClassName(h) !== "Static" && getClassName(h) !== CHARS_CLASS) ?? 0;
  const ra = getWindowRect(charsWnds[0]);
  const rb = getWindowRect(charsWnds.length > 1 ? charsWnds[1] : charsWnds[0]);
  let scroll = charsWnds[0];
  let footer = charsWnds.length > 1 ? charsWnds[1] : 0;
  if (charsWnds.length > 1 && rb.bottom - rb.top > ra.bottom - ra.top) {
    scroll = charsWnds[1];
    footer = charsWnds[0];
  }
  return { container, label, subtitle: statics[0] ?? 0, scroll, footer };
}

function childTexts(parent: number): { cls: string; text: string; hwnd: number }[] {
  const out: { cls: string; text: string; hwnd: number }[] = [];
  enumChildWindows(parent, (h) => {
    out.push({ cls: getClassName(h), text: getWindowText(h), hwnd: h });
    return true;
  });
  return out;
}

const fakeMode = tryStartFakeServer();
console.log(fakeMode ? "fake control server on 7862 (full test incl. progress bar)" : "real engine on 7862 (structure test only, read-only)");

const proc = launchSumatra(["-for-testing", PDF]);
const frame = await waitForFrame(proc.pid!);
await sleep(3000);

sendCommand(frame, cmdId("CmdAudiobookCharacters"));
await sleep(3000);

let p = findPanelWindows(frame);
check(!!p, "panel windows exist after CmdAudiobookCharacters");
if (!p) {
  postMessage(frame, WM_CLOSE, 0, 0);
  fake?.stop(true);
  process.exit(1);
}
check(getClassName(p.container) === "Static", `container is a Static (got ${getClassName(p.container)})`);
check(p.label !== 0, `header label exists (class ${getClassName(p.label)})`);
check(p.footer !== 0, "separate footer window exists");
check(p.subtitle !== 0 && getWindowText(p.subtitle).length > 0, `subtitle has text: "${getWindowText(p.subtitle).slice(0, 60)}"`);

const rl = getWindowRect(p.label);
const rs = getWindowRect(p.scroll);
const rf = getWindowRect(p.footer);
check(rl.top < rs.top, "header segment sits above the scroll area");
check(rf.top >= rs.bottom - 2, "footer segment sits below the scroll area");

const footerKids = childTexts(p.footer);
const hasFind = footerKids.some((k) => k.text.startsWith("Find speakers with"));
const analyseBtn = footerKids.find((k) => k.cls === "Button" && /analys/i.test(k.text));
check(hasFind, "footer has 'Find speakers with'");
check(!!analyseBtn, `footer has Analyse button ("${analyseBtn?.text}")`);
const scrollKids = childTexts(p.scroll);
check(scrollKids.some((k) => k.cls === "ComboBox"), `scroll area has voice comboboxes (${scrollKids.filter((k) => k.cls === "ComboBox").length})`);
check(!scrollKids.some((k) => k.text.startsWith("Find speakers with")), "scroll area no longer holds the analyse controls");

const si = getScrollInfo(p.scroll, SB_VERT);
console.log(`scroll info: max=${si.max} page=${si.page} pos=${si.pos}`);
const pos0 = getScrollPos(p.scroll, SB_VERT);
for (let i = 0; i < 6; i++) {
  postMessage(p.scroll, WM_MOUSEWHEEL, (-120 << 16) >>> 0, packCoords(50, 50));
}
await sleep(700);
const pos1 = getScrollPos(p.scroll, SB_VERT);
if (si.max > si.page) {
  check(pos1 > pos0, `wheel scrolls the list (${pos0} -> ${pos1})`);
} else {
  console.log(`note: content fits the view (max=${si.max} page=${si.page}); wheel-scroll check skipped`);
}
for (let i = 0; i < 6; i++) {
  postMessage(p.scroll, WM_MOUSEWHEEL, (120 << 16) >>> 0, packCoords(50, 50));
}
await sleep(500);

captureWindowToPng(frame, tmpPath("panel-segments-idle.png"));

if (fakeMode) {
  fakeAnalyzing = true;
  fakeLinesRead = 40;
  await sleep(2600);
  const footerKids2 = childTexts(p.footer);
  const stopBtn = footerKids2.find((k) => k.cls === "Button" && /stop/i.test(k.text));
  const prog = footerKids2.find((k) => k.cls === "msctls_progress32");
  check(!!stopBtn, `analysing: footer shows Stop button ("${stopBtn?.text}")`);
  check(!!prog, "analysing: progress bar exists in footer");
  if (prog && stopBtn) {
    const rProg = getWindowRect(prog.hwnd);
    const rStop = getWindowRect(stopBtn.hwnd);
    check(rProg.top >= rStop.bottom, "progress bar sits under the Stop/Analyse button");
    const v1 = Number(sendMessage(prog.hwnd, PBM_GETPOS, 0, 0));
    fakeLinesRead = 120;
    await sleep(2400);
    const v2 = Number(sendMessage(prog.hwnd, PBM_GETPOS, 0, 0));
    check(v2 > v1, `progress bar advances with lines_read (${v1} -> ${v2})`);
  }
  captureWindowToPng(frame, tmpPath("panel-segments-analysing.png"));
  fakeAnalyzing = false;
  await sleep(2600);
  const footerKids3 = childTexts(p.footer);
  check(!footerKids3.some((k) => k.cls === "msctls_progress32"), "analysis done: progress bar removed");
  check(footerKids3.some((k) => k.cls === "Button" && /analys/i.test(k.text) && !/stop/i.test(k.text)), "analysis done: Analyse button back");
}

postMessage(p.container, WM_COMMAND, kIdCloseChars, 0);
await sleep(1200);
check(!isWindow(p.scroll) && !isWindow(p.footer), "close button (WM_COMMAND from header X) destroys the panel");
check(findPanelWindows(frame) === null, "no panel windows remain after close");

sendCommand(frame, cmdId("CmdAudiobookCharacters"));
await sleep(2500);
p = findPanelWindows(frame);
check(!!p, "panel reopens from the menu after being closed with the X");

postMessage(frame, WM_CLOSE, 0, 0);
await sleep(2500);
fake?.stop(true);

console.log("VERDICT:", fails === 0 ? "PASS" : `FAIL (${fails} checks failed)`);
process.exit(fails === 0 ? 0 : 1);
