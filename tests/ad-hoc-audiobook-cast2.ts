import { launchSumatra, waitForFrame, sendCommand } from "./win-automation";
import {
  captureWindowToPng, sleep, postMessage, sendMessage, enumChildWindows,
  getClassName, getWindowText, moveWindow, WM_COMMAND,
} from "./winapi";
import { cmdId } from "./util";

const WM_CLOSE = 0x0010;
const CB_SETCURSEL = 0x014e;
const CB_GETCURSEL = 0x0147;
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

function sortCombo(p: number): number {
  return combosOf(p).find((h) => getWindowText(h).startsWith("By ")) ?? 0;
}

function voiceCombos(p: number): number[] {
  return combosOf(p).filter((h) => {
    const t = getWindowText(h);
    return t === "(none)" || !t.startsWith("By ") && !t.startsWith("A local") &&
           !t.startsWith("BookNLP") && !t.startsWith("the ") && !t.startsWith("(choose");
  });
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
const c0 = st0.characters[0];
console.log("first field:", typeof c0.first === "number" ? `yes (${c0.name}@${c0.first})` : "MISSING");
const expected = [...st0.characters].sort((a: any, b: any) => a.first - b.first || a.name.localeCompare(b.name))
  .slice(0, 4).map((c: any) => c.name);
console.log("expected appearance head:", expected.join(" | "));

async function setSort(k: number) {
  const p = panel();
  const sc = sortCombo(p);
  sendMessage(sc, CB_SETCURSEL, BigInt(k), 0n);
  sendMessage(p, WM_COMMAND, BigInt((CBN_SELCHANGE << 16) | kIdSort), BigInt(sc));
  await sleep(2500);
}

console.log("\nsort combo found:", sortCombo(panel()) !== 0, `text='${getWindowText(sortCombo(panel()))}'`);
console.log("-- default:", rowNames(panel(), 5).join(" | "));
await setSort(2);
console.log("-- most lines:", rowNames(panel(), 5).join(" | "));
await setSort(4);
console.log("-- A to Z:", rowNames(panel(), 5).join(" | "));
await setSort(5);
console.log("-- Z to A:", rowNames(panel(), 5).join(" | "));
await setSort(0);
console.log("-- first appearance:", rowNames(panel(), 5).join(" | "));
captureWindowToPng(panel(), `${OUT}\\sort-appearance.png`);

console.log("\n-- character cast test (row 1):");
let p = panel();
let vcs = voiceCombos(p);
const names = rowNames(p, 2);
const row1Name = names[1];
const cb1 = vcs[1];
const selB = Number(sendMessage(cb1, CB_GETCURSEL, 0, 0));
const nV = st0.voices.length;
const pick = (selB % nV) + 1;
sendMessage(cb1, CB_SETCURSEL, BigInt(pick), 0n);
sendMessage(p, WM_COMMAND, BigInt((CBN_SELCHANGE << 16) | (kIdVoiceFirst + 1)), BigInt(cb1));
await sleep(3000);
const st1 = await state();
const engineVoice = st1.characters.find((c: any) => c.name === row1Name)?.voice;
p = panel();
vcs = voiceCombos(p);
const selA = Number(sendMessage(vcs[1], CB_GETCURSEL, 0, 0));
const uiText = getWindowText(vcs[1]);
console.log(`row1 '${row1Name}': picked idx ${pick} ('${st0.voices[pick - 1]}') -> engine '${engineVoice}', UI '${uiText}' (sel ${selA})`);

console.log("\nsumatra exited?", proc.exitCode !== null ? `yes(${proc.exitCode})` : "no");
postMessage(frame, WM_CLOSE, 0, 0);
await sleep(2500);
