import { launchSumatra, waitForFrame, sendCommand } from "./win-automation";
import { sleep, postMessage } from "./winapi";
import { cmdId } from "./util";

const WM_CLOSE = 0x0010;
const PDF = "C:\\Users\\Nokel\\Documents\\ebooks\\manga_novels\\The Silo Saga Omnibus_ Wool, Shift, Dust, and Silo Stories by Hugh Howey.pdf";
const API = "http://127.0.0.1:7862";

async function state(): Promise<any> {
  try {
    const r = await fetch(API + "/state", { signal: AbortSignal.timeout(3000) });
    return await r.json();
  } catch {
    return null;
  }
}

async function post(path: string, body: any = {}): Promise<any> {
  const r = await fetch(API + path, {
    method: "POST",
    body: JSON.stringify(body),
    signal: AbortSignal.timeout(30000),
  });
  return await r.json().catch(() => null);
}

const proc = launchSumatra(["-for-testing", PDF]);
const frame = await waitForFrame(proc.pid!);
await sleep(3000);

sendCommand(frame, cmdId("CmdReadAloud"));

let st = null;
for (let i = 0; i < 120; i++) {
  await sleep(1000);
  st = await state();
  if (st && st.playing) break;
}
if (!st || !st.playing) {
  console.log("FAIL: never started playing:", JSON.stringify(st));
  postMessage(frame, WM_CLOSE, 0, 0);
  process.exit(1);
}
console.log("playing at index", st.index, "speaker", st.speaker);

const idx0 = st.index;
console.log("-- skipping 3 pages forward in quick succession (unloaded region)");
await post("/page", { dir: 1 });
await sleep(400);
await post("/page", { dir: 1 });
await sleep(400);
const r = await post("/page", { dir: 1 });
const target = r?.index ?? r?.unit;
console.log("after skips, engine reports:", JSON.stringify(r).slice(0, 120));

const seen: number[] = [];
let regress = false;
for (let i = 0; i < 90; i++) {
  await sleep(1000);
  st = await state();
  if (!st) continue;
  seen.push(st.index);
  if (seen.length > 2) {
    const last = seen[seen.length - 1];
    if (last <= idx0 + 1) regress = true;
  }
  const uniq = new Set(seen.slice(-30));
  if (uniq.size >= 4 && seen[seen.length - 1] > seen[0]) break;
}
console.log("indices after skip:", seen.join(","));
console.log("still playing:", st?.playing, "paused:", st?.paused);

console.log("-- skip back one page mid-speech");
await post("/page", { dir: -1 });
const seen2: number[] = [];
for (let i = 0; i < 60; i++) {
  await sleep(1000);
  const s2 = await state();
  if (!s2) continue;
  seen2.push(s2.index);
  const uniq = new Set(seen2);
  if (uniq.size >= 3) break;
}
console.log("indices after back-skip:", seen2.join(","));

sendCommand(frame, cmdId("CmdStopReadAloud"));
await sleep(2000);
st = await state();
console.log("after stop, state:", st ? JSON.stringify({ playing: st.playing }) : "engine gone");

const advanced = seen.length > 2 && seen[seen.length - 1] > seen[0];
const advanced2 = seen2.length > 1 && new Set(seen2).size >= 2;
console.log("VERDICT:", advanced && advanced2 && !regress
  ? "PASS (seeks land, playback advances, no regression to old position)"
  : `FAIL advanced=${advanced} advanced2=${advanced2} regress=${regress}`);

postMessage(frame, WM_CLOSE, 0, 0);
await sleep(2500);
console.log("closed");
