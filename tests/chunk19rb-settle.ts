import { join } from "node:path";
import { mkdirSync } from "node:fs";
import { ControlCommand, withControlledSumatra } from "./control.ts";
import { setProcessDpiAware, forceForeground, captureWindowDCToPng, sleep } from "./winapi.ts";
import { waitForFrame } from "./win-automation.ts";

const ROOT = process.cwd();
const EXE = join(ROOT, "out", "dbg64", "SumatraPDF.exe");
const OUT = process.env.SETTLE_OUT ?? join(ROOT, "scratchpad", "chunk19rb-settle");
const SHOT = process.env.SETTLE_SHOT ?? "settled.png";
const MAX_S = Number(process.env.SETTLE_MAX_S ?? "900");

setProcessDpiAware();
mkdirSync(OUT, { recursive: true });

function fields(raw: string): Record<string, number> {
  const out: Record<string, number> = {};
  for (const m of String(raw).matchAll(/(\w+)=(-?\d+)/g)) {
    out[m[1]] = Number(m[2]);
  }
  return out;
}

await withControlledSumatra(
  EXE,
  async (client, proc) => {
    const hwnd = await waitForFrame(proc.pid!, 20000);
    await forceForeground(hwnd, 8000);
    let st: Record<string, number> = {};
    let quiet = 0;
    for (let i = 0; i < MAX_S; i++) {
      await sleep(1000);
      const r = await client.request(ControlCommand.TestLibScanStatus, []);
      st = fields(String(r[1] ?? ""));
      if (i % 15 === 0) {
        console.log(`t=${i}s visible=${st.visible} scanning=${st.scanning} native=${st.native} done=${st.done}/${st.total}`);
      }
      if ((st.visible ?? 0) > 0 && (st.scanning ?? 1) === 0 && (st.native ?? 1) === 0) {
        quiet++;
        if (quiet >= 10) {
          break;
        }
      } else {
        quiet = 0;
      }
    }
    await sleep(2000);
    await forceForeground(hwnd, 4000);
    await sleep(1500);
    captureWindowDCToPng(hwnd, join(OUT, SHOT));
    const r = await client.request(ControlCommand.TestLibScanStatus, []);
    console.log("SETTLED " + String(r[1] ?? "").trim());
  },
  ["-window-pos", "2000x1350@40x30", "-log-to-file", join(OUT, "sumlog-settle.txt")],
  {},
);
