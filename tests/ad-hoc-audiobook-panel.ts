import { launchSumatra, waitForFrame, sendCommand } from "./win-automation";
import {
  captureWindowToPng, sleep, postMessage, sendMessage, enumChildWindows,
  getClassName, getScrollPos, moveWindow, getClientRect, SB_VERT, WM_COMMAND,
} from "./winapi";
import { cmdId } from "./util";

const WM_MOUSEWHEEL = 0x020a;
const WM_CLOSE = 0x0010;
const CB_GETCURSEL = 0x0147;
const CBN_SELCHANGE = 1;
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

function wheelParam(delta: number): number {
  return ((delta & 0xffff) << 16) >>> 0;
}

const proc = launchSumatra(["-log", "-log-to-file", `${OUT}\\panel-log.txt`, PDF]);
const frame = await waitForFrame(proc.pid!);
await sleep(3000);
moveWindow(frame, 40, 40, 1100, 600);
await sleep(500);

sendCommand(frame, cmdId("CmdAudiobookCharacters"));
await sleep(4000);

const panel = findChild(frame, PANEL_CLASS);
console.log("panel hwnd:", panel);
if (!panel) { postMessage(frame, WM_CLOSE, 0, 0); process.exit(1); }

let combos = comboBoxes(panel);
console.log("comboboxes in panel:", combos.length);
captureWindowToPng(panel, `${OUT}\\panel-top.png`);

const pos0 = getScrollPos(panel, SB_VERT);
for (let i = 0; i < 8; i++) {
  postMessage(panel, WM_MOUSEWHEEL, wheelParam(-120), 0);
}
await sleep(800);
const pos1 = getScrollPos(panel, SB_VERT);
console.log(`panel wheel: scrollY ${pos0} -> ${pos1}`);
captureWindowToPng(panel, `${OUT}\\panel-wheeled.png`);

combos = comboBoxes(panel);
let comboOk = true;
if (combos.length > 0) {
  const cb = combos[0];
  const selBefore = Number(sendMessage(cb, CB_GETCURSEL, 0, 0));
  for (let i = 0; i < 6; i++) {
    postMessage(cb, WM_MOUSEWHEEL, wheelParam(120), 0);
  }
  await sleep(800);
  const alive = getClassName(cb) === "ComboBox";
  const selAfter = alive ? Number(sendMessage(cb, CB_GETCURSEL, 0, 0)) : -99;
  const pos2 = getScrollPos(panel, SB_VERT);
  console.log(`combo wheel: selection ${selBefore} -> ${selAfter}, scrollY ${pos1} -> ${pos2}`);
  comboOk = selBefore === selAfter;
}

const combosBefore = comboBoxes(panel).length;
if (combosBefore > 0) {
  const cb = comboBoxes(panel)[0];
  sendMessage(panel, WM_COMMAND, BigInt((CBN_SELCHANGE << 16) | 2000), BigInt(cb));
  await sleep(1500);
  const rebuiltCombos = comboBoxes(panel).length;
  console.log(`selchange rebuild: combos ${combosBefore} -> ${rebuiltCombos}, panel alive: ${getClassName(panel) === PANEL_CLASS}`);
}

sendMessage(panel, WM_COMMAND, 4000n, 0n);
await sleep(9000);
console.log("train clicked; panel alive:", getClassName(panel) === PANEL_CLASS);

console.log("sumatra exited?", proc.exitCode !== null ? `yes (${proc.exitCode})` : "no (still running)");
captureWindowToPng(panel, `${OUT}\\panel-final.png`);
postMessage(frame, WM_CLOSE, 0, 0);
await sleep(1500);
console.log("done; comboSelectionStable:", comboOk);
