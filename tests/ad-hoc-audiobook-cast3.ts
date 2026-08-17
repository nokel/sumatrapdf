import { launchSumatra, waitForFrame, sendCommand } from "./win-automation";
import {
  captureWindowToPng, sleep, postMessage, sendMessage, enumChildWindows,
  getClassName, getWindowText, moveWindow, WM_COMMAND,
} from "./winapi";
import { cmdId } from "./util";

const WM_CLOSE = 0x0010;
const CB_SETCURSEL = 0x014e;
const CB_GETCURSEL = 0x0147;
const CB_GETCOUNT = 0x0146;
const CBN_SELCHANGE = 1;
const PANEL_CLASS = "SUMATRA_AUDIOBOOK_CHARS";
const kIdSort = 1011;
const kIdVoiceFirst = 2000;
const PDF = "C:\\Users\\Nokel\\Documents\\ebooks\\manga_novels\\The Silo Saga Omnibus_ Wool, Shift, Dust, and Silo Stories by Hugh Howey.pdf";
const OUT = "C:\\Users\\Nokel\\AppData\\Local\\Temp\\claude\\C--Users-Nokel-Documents-AI-crap-chatterbox-AI\\2858b9f7-3e1e-4e5f-bbd2-ace9d6992e89\\scratchpad";

let frame = 0;

function panel(): number {
  let found = 0;
  enumChildWindows(frame, (h) => {
    if (getClassName(h) === PANEL_CLASS) { found = h; return false; }
    return true;
  });
  return found;
}

function combosOf(p: number): number[] {
  const out: number[] = [];
  enumChildWindows(p, (h) => {
    if (getClassName(h) === "ComboBox") out.push(h);
    return true;
  });
  return out;
}

function rowNames(p: number, n: number): string[] {
  const out: string[] = [];
  enumChildWindows(p, (h) => {
    if (getClassName(h) === "Static") {
      const t = getWindowText(h);
      if (t && t !== "Characters" && !t.startsWith("Find speakers") &&
          !t.startsWith("Analyse") && !t.startsWith("No characters")) {
        out.push(t.replace(/\s+\(\d+ lines\).*/, "").replace(/\s+- needs a voice/, ""));
      }
    }
    return out.length < n;
  });
  return out;
}

async function state(): Promise<any> {
  const r = await fetch("http://127.0.0.1:7862/state");
  return await r.json();
}

const proc = launchSumatra(["-for-testing", PDF]);
frame = await waitForFrame(proc.pid!);
await sleep(2500);
moveWindow(frame, 40, 40, 1100, 700);
sendCommand(frame, cmdId("CmdAudiobookCharacters"));

for (let i = 0; i < 60; i++) {
  await sleep(1500);
  if (combosOf(panel()).length > 6) break;
}
await sleep(2000);

const st0 = await state();
const nV = st0.voices.length;
console.log("voices:", JSON.stringify(st0.voices));

async function setSort(k: number) {
  const p = panel();
  const sc = combosOf(p)[0];
  const items = Number(sendMessage(sc, CB_GETCOUNT, 0, 0));
  if (items !== 6) { console.log(`ABORT: combos[0] has ${items} items, not the sort combo`); return; }
  sendMessage(sc, CB_SETCURSEL, BigInt(k), 0n);
  sendMessage(p, WM_COMMAND, BigInt((CBN_SELCHANGE << 16) | kIdSort), BigInt(sc));
  await sleep(2500);
}

console.log("-- default:      ", rowNames(panel(), 5).join(" | "));
await setSort(2);
console.log("-- most lines:   ", rowNames(panel(), 5).join(" | "));
await setSort(3);
console.log("-- fewest lines: ", rowNames(panel(), 5).join(" | "));
await setSort(4);
console.log("-- A to Z:       ", rowNames(panel(), 5).join(" | "));
await setSort(5);
console.log("-- Z to A:       ", rowNames(panel(), 5).join(" | "));
await setSort(1);
console.log("-- last appear.: ", rowNames(panel(), 5).join(" | "));
await setSort(0);
console.log("-- first appear.:", rowNames(panel(), 5).join(" | "));
captureWindowToPng(panel(), `${OUT}\\sort-final.png`);

console.log("\n-- row1 cast + UI persistence:");
let p = panel();
let cbs = combosOf(p);
const row1 = rowNames(p, 2)[1];
const selB = Number(sendMessage(cbs[2], CB_GETCURSEL, 0, 0));
const pick = (selB % nV) + 1;
sendMessage(cbs[2], CB_SETCURSEL, BigInt(pick), 0n);
sendMessage(p, WM_COMMAND, BigInt((CBN_SELCHANGE << 16) | (kIdVoiceFirst + 1)), BigInt(cbs[2]));
await sleep(3500);
const st1 = await state();
const engineVoice = st1.characters.find((c: any) => c.name === row1)?.voice;
p = panel();
cbs = combosOf(p);
const selA = Number(sendMessage(cbs[2], CB_GETCURSEL, 0, 0));
console.log(`row1 '${row1}': sel ${selB} -> picked ${pick} ('${st0.voices[pick - 1]}'); engine '${engineVoice}'; UI sel after rebuild ${selA}`);
console.log("VERDICT:", engineVoice === st0.voices[pick - 1] && selA === pick ? "CAST STICKS" : "MISMATCH");

const narSel = Number(sendMessage(cbs[1], CB_GETCURSEL, 0, 0));
console.log(`narrator combo sel: ${narSel} ('${st1.narrator}')`);

postMessage(frame, WM_CLOSE, 0, 0);
await sleep(2500);
console.log("closed");
