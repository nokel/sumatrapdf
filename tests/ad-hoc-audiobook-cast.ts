import { launchSumatra, waitForFrame, sendCommand } from "./win-automation";
import {
  captureWindowToPng, sleep, postMessage, sendMessage, enumChildWindows,
  getClassName, getWindowText, moveWindow, WM_COMMAND,
} from "./winapi";
import { cmdId } from "./util";

const WM_CLOSE = 0x0010;
const CB_SETCURSEL = 0x014e;
const CB_GETCURSEL = 0x0147;
const CB_GETLBTEXTLEN = 0x0149;
const CBN_SELCHANGE = 1;
const PANEL_CLASS = "SUMATRA_AUDIOBOOK_CHARS";
const kIdSort = 1011;
const kIdVoiceFirst = 2000;
const kIdAnalyzer = 1010;
const PDF = "C:\\Users\\Nokel\\Documents\\ebooks\\manga_novels\\The Silo Saga Omnibus_ Wool, Shift, Dust, and Silo Stories by Hugh Howey.pdf";
const OUT = "C:\\Users\\Nokel\\AppData\\Local\\Temp\\claude\\C--Users-Nokel-Documents-AI-crap-chatterbox-AI\\2858b9f7-3e1e-4e5f-bbd2-ace9d6992e89\\scratchpad";

function children(panel: number, cls: string): number[] {
  const out: number[] = [];
  enumChildWindows(panel, (h) => {
    if (getClassName(h) === cls) out.push(h);
    return true;
  });
  return out;
}

function findChild(parent: number, cls: string): number {
  let found = 0;
  enumChildWindows(parent, (h) => {
    if (getClassName(h) === cls) { found = h; return false; }
    return true;
  });
  return found;
}

function rowLabels(panel: number, n: number): string[] {
  return children(panel, "Static")
    .map(getWindowText)
    .filter((t) => t && t !== "Characters" && !t.startsWith("Find speakers") &&
                   !t.startsWith("Analyse") && !t.startsWith("Order"))
    .slice(0, n);
}

async function state(): Promise<any> {
  const r = await fetch("http://127.0.0.1:7862/state");
  return await r.json();
}

const proc = launchSumatra(["-for-testing", PDF]);
const frame = await waitForFrame(proc.pid!);
await sleep(2500);
moveWindow(frame, 40, 40, 1100, 700);
sendCommand(frame, cmdId("CmdAudiobookCharacters"));

let combos: number[] = [];
for (let i = 0; i < 40; i++) {
  await sleep(1500);
  combos = children(findChild(frame, PANEL_CLASS), "ComboBox");
  if (combos.length > 6) break;
}
const panel = findChild(frame, PANEL_CLASS);
console.log("combos:", combos.length);

const st0 = await state();
console.log("state has 'first' on characters:",
  st0.characters?.length > 0 && typeof st0.characters[0].first === "number");
console.log("cast size:", st0.characters?.length, " narrator:", JSON.stringify(st0.narrator));

console.log("\n-- default sort (first appearance):");
console.log(rowLabels(panel, 6).join(" | "));

const sortCb = combos[0];
async function setSort(k: number) {
  sendMessage(sortCb, CB_SETCURSEL, BigInt(k), 0n);
  sendMessage(panel, WM_COMMAND, BigInt((CBN_SELCHANGE << 16) | kIdSort), BigInt(sortCb));
  await sleep(2500);
}
await setSort(4);
console.log("-- A to Z:");
console.log(rowLabels(panel, 6).join(" | "));
await setSort(2);
console.log("-- most lines:");
console.log(rowLabels(panel, 6).join(" | "));
await setSort(0);
await sleep(1000);
console.log("-- back to first appearance:");
console.log(rowLabels(panel, 6).join(" | "));

console.log("\n-- narrator cast test:");
let cbs = children(panel, "ComboBox");
const narCb = cbs[1];
const before = Number(sendMessage(narCb, CB_GETCURSEL, 0, 0));
const nVoices = st0.voices.length;
const pick = ((before % nVoices) + 1);
sendMessage(narCb, CB_SETCURSEL, BigInt(pick), 0n);
sendMessage(panel, WM_COMMAND, BigInt((CBN_SELCHANGE << 16) | kIdVoiceFirst), BigInt(narCb));
await sleep(3000);
const st1 = await state();
cbs = children(panel, "ComboBox");
const after = Number(sendMessage(cbs[1], CB_GETCURSEL, 0, 0));
console.log(`narrator: sel ${before} -> picked ${pick}; engine narrator now '${st1.narrator}' (want '${st0.voices[pick - 1]}'); UI shows sel ${after}`);

console.log("\n-- character row cast test:");
const st1chars = st1.characters;
const charCb = cbs[2];
const cBefore = Number(sendMessage(charCb, CB_GETCURSEL, 0, 0));
const cPick = ((cBefore % nVoices) + 1);
sendMessage(charCb, CB_SETCURSEL, BigInt(cPick), 0n);
sendMessage(panel, WM_COMMAND, BigInt((CBN_SELCHANGE << 16) | (kIdVoiceFirst + 1)), BigInt(charCb));
await sleep(3000);
const st2 = await state();
cbs = children(panel, "ComboBox");
const cAfter = Number(sendMessage(cbs[2], CB_GETCURSEL, 0, 0));
const rowName = rowLabels(panel, 1)[0];
const castNow = st2.characters.find((c: any) => rowName.startsWith(c.name))?.voice;
console.log(`row1 '${rowName}': sel ${cBefore} -> picked ${cPick}; engine voice '${castNow}' (want '${st0.voices[cPick - 1]}'); UI shows sel ${cAfter}`);

console.log("\n-- booknlp mode narrator check:");
const analyzerCb = cbs.find((h) => {
  const n = Number(sendMessage(h, 0x0146 /*CB_GETCOUNT*/, 0, 0));
  return n === 2;
})!;
sendMessage(analyzerCb, CB_SETCURSEL, 1n, 0n);
sendMessage(panel, WM_COMMAND, BigInt((CBN_SELCHANGE << 16) | kIdAnalyzer), BigInt(analyzerCb));
await sleep(3000);
const labs = rowLabels(findChild(frame, PANEL_CLASS), 3);
const cbs2 = children(findChild(frame, PANEL_CLASS), "ComboBox");
console.log("booknlp mode: rows:", labs.join(" | "), " combos:", cbs2.length);
const narSel = Number(sendMessage(cbs2[1], CB_GETCURSEL, 0, 0));
console.log("narrator combo present with sel:", narSel);

captureWindowToPng(findChild(frame, PANEL_CLASS), `${OUT}\\cast-final.png`);
console.log("\nsumatra exited?", proc.exitCode !== null ? `yes(${proc.exitCode})` : "no");
postMessage(frame, WM_CLOSE, 0, 0);
await sleep(2000);
