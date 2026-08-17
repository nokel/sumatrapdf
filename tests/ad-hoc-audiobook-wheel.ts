import { launchSumatra, waitForFrame, sendCommand } from "./win-automation";
import {
  captureWindowToPng, sleep, postMessage, sendMessage, enumChildWindows,
  getClassName, getScrollPos, moveWindow, SB_VERT,
} from "./winapi";
import { cmdId } from "./util";

const WM_MOUSEWHEEL = 0x020a;
const WM_CLOSE = 0x0010;
const CB_GETCURSEL = 0x0147;
const PANEL_CLASS = "SUMATRA_AUDIOBOOK_CHARS";
const PDF = "C:\\Users\\Nokel\\Documents\\ebooks\\manga_novels\\The Silo Saga Omnibus_ Wool, Shift, Dust, and Silo Stories by Hugh Howey.pdf";
const OUT = "C:\\Users\\Nokel\\AppData\\Local\\Temp\\claude\\C--Users-Nokel-Documents-AI-crap-chatterbox-AI\\2858b9f7-3e1e-4e5f-bbd2-ace9d6992e89\\scratchpad";

function findChild(parent: number, cls: string): number {
  let found = 0;
  enumChildWindows(parent, (h) => {
    if (getClassName(h) === cls) { found = h; return false; }
    return true;
  });
  return found;
}

function comboBoxes(panel: number): number[] {
  const out: number[] = [];
  enumChildWindows(panel, (h) => {
    if (getClassName(h) === "ComboBox") out.push(h);
    return true;
  });
  return out;
}

const wheel = (delta: number) => (((delta & 0xffff) << 16) >>> 0);

const proc = launchSumatra([PDF]);
const frame = await waitForFrame(proc.pid!);
await sleep(2500);
moveWindow(frame, 40, 40, 1100, 600);
sendCommand(frame, cmdId("CmdAudiobookCharacters"));
await sleep(10000);

const panel = findChild(frame, PANEL_CLASS);
const n0 = comboBoxes(panel).length;
const pos0 = getScrollPos(panel, SB_VERT);
captureWindowToPng(panel, `${OUT}\\wheel-before.png`);

for (let i = 0; i < 6; i++) postMessage(panel, WM_MOUSEWHEEL, wheel(-120), 0);
await sleep(600);
const posDown = getScrollPos(panel, SB_VERT);
captureWindowToPng(panel, `${OUT}\\wheel-down.png`);

for (let i = 0; i < 3; i++) postMessage(panel, WM_MOUSEWHEEL, wheel(120), 0);
await sleep(600);
const posUp = getScrollPos(panel, SB_VERT);

const combos = comboBoxes(panel);
const cb = combos[0];
const selBefore = Number(sendMessage(cb, CB_GETCURSEL, 0, 0));
const posB = getScrollPos(panel, SB_VERT);
for (let i = 0; i < 4; i++) postMessage(cb, WM_MOUSEWHEEL, wheel(-120), 0);
await sleep(600);
const cbAlive = getClassName(cb) === "ComboBox";
const selAfter = cbAlive ? Number(sendMessage(cb, CB_GETCURSEL, 0, 0)) : -99;
const posC = getScrollPos(panel, SB_VERT);
captureWindowToPng(panel, `${OUT}\\wheel-combo.png`);

console.log(`rows settled: ${n0} combos`);
console.log(`panel wheel down: ${pos0} -> ${posDown}   up: -> ${posUp}`);
console.log(`combo wheel: hwnd alive ${cbAlive}, selection ${selBefore} -> ${selAfter}, scroll ${posB} -> ${posC}`);
console.log(`panel class still valid: ${getClassName(panel) === PANEL_CLASS}`);
console.log("sumatra exited?", proc.exitCode !== null ? `yes (${proc.exitCode})` : "no");
postMessage(frame, WM_CLOSE, 0, 0);
await sleep(1200);
