import { join } from "node:path";
import { ControlCommand, withControlledSumatra } from "./control.ts";
import { setProcessDpiAware, forceForeground, captureWindowDCToPng, sleep } from "./winapi.ts";
import { mkdirSync } from "node:fs";
import { waitForFrame } from "./win-automation.ts";

const ROOT = process.cwd();
const EXE = join(ROOT, "out", "dbg64", "SumatraPDF.exe");
const OUT = process.env.SHOT_OUT ?? join(ROOT, "scratchpad", "chunk19ra");

setProcessDpiAware();
mkdirSync(OUT, { recursive: true });

await withControlledSumatra(
  EXE,
  async (client, proc) => {
    const hwnd = await waitForFrame(proc.pid!, 20000);
    await forceForeground(hwnd, 8000);
    for (let i = 0; i < 60; i++) {
      await sleep(1000);
      const r = await client.request(ControlCommand.TestLibScanStatus, []);
      const s = String(r[1] ?? "");
      const v = Number((s.match(/visible=(\d+)/) || [])[1] ?? 0);
      if (v > 0 && i > 10) {
        console.log(`settled after ${i}s with visible=${v}`);
        break;
      }
    }
    await sleep(4000);
    await forceForeground(hwnd, 4000);
    await sleep(1500);
    captureWindowDCToPng(hwnd, join(OUT, process.env.SHOT_NAME ?? "08-restart-settled.png"));
    const r = await client.request(ControlCommand.TestLibScanStatus, []);
    console.log(String(r[1] ?? "").trim());
  },
  ["-window-pos", "2000x1350@40x30", "-log-to-file", join(OUT, "sumlog-restart2.txt")],
  {},
);
