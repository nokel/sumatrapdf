import { join } from "node:path";
import { copyFileSync } from "node:fs";
import { ROOT } from "./util";
import { ControlCommand, withControlledSumatra } from "./control";
import { WM_COMMAND, postMessage, waitForTopWindow, isWindow } from "./winapi";
import { FIXTURE_APPDATA, fixtureEnv, FIXTURE_PORT, setupLibraryFixture, startLibraryFixtureService, stopLibraryFixtureService } from "./library-fixture";

const PORT = FIXTURE_PORT;
const CmdLibraryRescan = 461;
const APPDIR = FIXTURE_APPDATA;
const EXE = join(ROOT, "out", "dbg64", "SumatraPDF.exe");
const LOG = join(ROOT, "out", "dbg64", "sumlog.txt");

function parseCount(reply: string): number {
  const m = /count=(\d+)/.exec(reply);
  return m ? parseInt(m[1], 10) : -1;
}

async function loads(client: any): Promise<number> {
  return parseCount(String((await client.request(ControlCommand.TestLoadCount, []))[1] ?? ""));
}

async function settle(client: any): Promise<number> {
  await new Promise((r) => setTimeout(r, 8_000));
  let c = await loads(client);
  for (;;) {
    await new Promise((r) => setTimeout(r, 5_000));
    const n = await loads(client);
    if (n === c) return c;
    c = n;
  }
}

async function scanning(): Promise<boolean> {
  try {
    const st: any = await (await fetch(`http://127.0.0.1:${PORT}/status`)).json();
    return !!st.scanning;
  } catch {
    return false;
  }
}

export async function testit(): Promise<void> {
  const failures: string[] = [];
  const runs = parseInt(process.env.CHUNK33_RUNS || "2", 10);
  const manifest = setupLibraryFixture();
  const service = await startLibraryFixtureService();
  try {
    await withControlledSumatra(
    EXE,
    async (client: any, proc: any) => {
      const start = await settle(client);
      console.log(`settled at load count ${start}`);
      const hwnd = await waitForTopWindow(proc.pid, "SUMATRA_PDF_FRAME", 30_000);
      if (!hwnd) throw new Error("no SUMATRA_PDF_FRAME window");
      console.log(`frame hwnd 0x${hwnd.toString(16)}`);
      let before = start;
      for (let run = 1; run <= runs; run++) {
        if (run === 1) {
          copyFileSync(manifest.new_text, join(manifest.root, "New Arrival.pdf"));
        }
        const logSize = Bun.file(LOG).size;
        console.log(`\n=== rescan run ${run} ===`);
        if (!postMessage(hwnd, WM_COMMAND, CmdLibraryRescan, 0)) {
          throw new Error("PostMessage(WM_COMMAND, CmdLibraryRescan) failed");
        }
        const t0 = Date.now();
        let lastLoads = before;
        let quietFor = 0;
        let died = false;
        let reloaded = before;
        for (;;) {
          await new Promise((r) => setTimeout(r, 5_000));
          const secs = Math.round((Date.now() - t0) / 1000);
          if (proc.exitCode !== null) {
            failures.push(`run ${run}: the app exited ${secs}s into the rescan, code ${proc.exitCode}`);
            console.log(`  DIED at t+${secs}s, exit code ${proc.exitCode}`);
            died = true;
            break;
          }
          if (!isWindow(hwnd)) {
            failures.push(`run ${run}: the frame window is gone ${secs}s into the rescan`);
            console.log(`  WINDOW GONE at t+${secs}s`);
            died = true;
            break;
          }
          reloaded = await loads(client);
          const busy = await scanning();
          quietFor = reloaded === lastLoads ? quietFor + 5 : 0;
          lastLoads = reloaded;
          console.log(`  t+${secs}s alive, load count ${reloaded}, service scanning=${busy}, stable ${quietFor}s`);
          if (!busy && reloaded > before && quietFor >= 30) break;
          if (secs > 420) {
            failures.push(`run ${run}: the rescan did not finish within 420s`);
            break;
          }
        }
        if (died) {
          const text = await Bun.file(LOG).text();
          console.log(`--- sumlog tail after the death (from byte ${logSize}) ---`);
          console.log(text.substring(Math.max(logSize - 400, 0)).split("\n").slice(-40).join("\n"));
          return;
        }
        if (reloaded <= before) {
          failures.push(`run ${run}: the model did not reload (load count stayed ${reloaded})`);
        } else {
          console.log(`  run ${run} completed: load count ${before} -> ${reloaded}`);
        }
        before = reloaded;
        const runLog = (await Bun.file(LOG).text()).substring(logSize);
        const fpWork = [...runLog.matchAll(/fingerprint full=(\d+) shape=(\d+)/g)];
        const full = fpWork.reduce((sum, match) => sum + Number(match[1]), 0);
        const shape = fpWork.reduce((sum, match) => sum + Number(match[2]), 0);
        console.log(`  fingerprint work: full=${full} shape=${shape}`);
        if (full !== (run === 1 ? 1 : 0) || shape !== 0) {
          failures.push(`run ${run}: expected full=${run === 1 ? 1 : 0} shape=0, got full=${full} shape=${shape}`);
        }
      }
      const perf = String((await client.request(ControlCommand.TestLastLoadPerf, []))[1] ?? "");
      console.log(`\nlast load perf: ${perf}`);
      await client.request(ControlCommand.Quit);
    },
    ["-appdata", APPDIR],
      { connectTimeoutMs: 60_000, env: fixtureEnv() },
    ).catch((e) => {
      failures.push(`the controlled run ended early: ${e}`);
    });
  } finally {
    await stopLibraryFixtureService(service);
  }

  if (failures.length) {
    console.log("\n=== failures ===");
    for (const f of failures) console.log(`  - ${f}`);
    throw new Error(`chunk33-rescan: ${failures.length} failure(s)`);
  }
  console.log("\nchunk33-rescan: ALL RESCAN RUNS COMPLETED");
}

if (import.meta.main) {
  await testit().catch((e) => {
    console.error(e);
    process.exit(1);
  });
}
