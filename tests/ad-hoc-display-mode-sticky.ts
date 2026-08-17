import { cmdId, runStandalone } from "./util.ts";
import { getScrollInfo, sendCopyDataW, sleep } from "./winapi.ts";
import { findCanvas, launchSumatra, sendCommand, waitForFrame } from "./win-automation.ts";
import { copyFileSync, mkdtempSync } from "node:fs";
import { join } from "node:path";
import { tmpdir } from "node:os";

const kCopyDataDdeW = 0x44646557;

function screens(canvas: number): number {
  const si = getScrollInfo(canvas);
  if (si.page <= 0) {
    return 0;
  }
  return (si.max - si.min + 1) / si.page;
}

function report(canvas: number, what: string): number {
  const n = screens(canvas);
  const si = getScrollInfo(canvas);
  console.log(`${what}: screens=${n.toFixed(2)} (max=${si.max} page=${si.page}) -> ${n > 1.5 ? "continuous" : "single page"}`);
  return n;
}

export async function testit(): Promise<void> {
  const dir = mkdtempSync(join(tmpdir(), "sumatra-sticky-"));
  const a = join(dir, "sticky-a.pdf");
  const b = join(dir, "sticky-b.pdf");
  copyFileSync("ext/zlib/zlib.3.pdf", a);
  copyFileSync("ext/zlib/zlib.3.pdf", b);

  const proc = launchSumatra([a]);
  try {
    const frame = await waitForFrame(proc.pid!);
    await sleep(2500);
    let canvas = findCanvas(frame);
    report(canvas, "1. opened a.pdf (settings default)");

    sendCommand(frame, cmdId("CmdToggleContinuousView"));
    await sleep(1200);
    const single = report(canvas, "2. toggled view on a.pdf");

    sendCommand(frame, cmdId("CmdClose"));
    await sleep(1500);

    sendCopyDataW(frame, kCopyDataDdeW, `[Open("${b}")]`);
    await sleep(3000);
    canvas = findCanvas(frame);
    const fresh = report(canvas, "3. opened b.pdf, never seen before");

    sendCommand(frame, cmdId("CmdToggleContinuousView"));
    await sleep(1200);
    const flipped = report(canvas, "4. toggled view on b.pdf");

    sendCommand(frame, cmdId("CmdClose"));
    await sleep(1500);
    sendCopyDataW(frame, kCopyDataDdeW, `[Open("${a}")]`);
    await sleep(3000);
    canvas = findCanvas(frame);
    const reopened = report(canvas, "5. reopened a.pdf, saved as step 2's view");

    const near = (x: number, y: number) => Math.abs(x - y) < 0.2;
    if (!near(fresh, single)) {
      throw new Error("a new document did not open in the view the user last chose");
    }
    if (!near(reopened, flipped)) {
      throw new Error("a previously seen document opened in its own remembered view");
    }
  } finally {
    if (proc.exitCode === null) {
      proc.kill();
    }
  }
}

if (import.meta.main) {
  await runStandalone(testit);
}
