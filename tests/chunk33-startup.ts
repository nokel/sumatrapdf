import { join } from "node:path";
import { existsSync, rmSync } from "node:fs";
import { ROOT } from "./util";
import { ControlCommand, withControlledSumatra } from "./control";
import { FIXTURE_APPDATA, fixtureEnv, setupLibraryFixture, startLibraryFixtureService, stopLibraryFixtureService } from "./library-fixture";

const APPDIR = FIXTURE_APPDATA;
const EXE = join(ROOT, "out", "dbg64", "SumatraPDF.exe");
const LOG = join(ROOT, "out", "dbg64", "sumlog.txt");

function parseCount(reply: string): number {
  const m = /count=(\d+)/.exec(reply);
  return m ? parseInt(m[1], 10) : -1;
}

async function settle(client: any): Promise<number> {
  await new Promise((r) => setTimeout(r, 8_000));
  let c = parseCount(String((await client.request(ControlCommand.TestLoadCount, []))[1] ?? ""));
  for (;;) {
    await new Promise((r) => setTimeout(r, 5_000));
    const n = parseCount(String((await client.request(ControlCommand.TestLoadCount, []))[1] ?? ""));
    if (n === c) return c;
    c = n;
  }
}

async function cpuSeconds(pid: number): Promise<number> {
  const p = Bun.spawn(
    ["powershell", "-NoProfile", "-Command", `(Get-Process -Id ${pid}).TotalProcessorTime.TotalSeconds`],
    { stdout: "pipe", stderr: "ignore" },
  );
  const out = await new Response(p.stdout).text();
  return parseFloat(out.trim());
}

async function logSince(from: number): Promise<string> {
  const text = await Bun.file(LOG).text();
  return text.substring(Math.max(from - 200, 0));
}

async function run(label: string, failures: string[]): Promise<void> {
  const service = await startLibraryFixtureService();
  const from = existsSync(LOG) ? Bun.file(LOG).size : 0;
  try {
    await withControlledSumatra(
      EXE,
      async (client: any, proc: any) => {
      const loads = await settle(client);
      const perf = String((await client.request(ControlCommand.TestLastLoadPerf, []))[1] ?? "");
      console.log(`${label}: settled at load count ${loads}`);
      console.log(`${label}: ${perf}`);
      let pct = 100;
      for (let attempt = 0; attempt < 6; attempt++) {
        const t0 = await cpuSeconds(proc.pid);
        await new Promise((r) => setTimeout(r, 20_000));
        const t1 = await cpuSeconds(proc.pid);
        pct = ((t1 - t0) / 20) * 100;
        console.log(`${label}: idle CPU ${(t1 - t0).toFixed(2)}s over 20s (${pct.toFixed(1)}% of one core)`);
        if (pct <= 5) break;
      }
      if (pct > 5) failures.push(`${label}: idle CPU did not settle (${pct.toFixed(1)}%)`);
      const text = await logSince(from);
      const sync = text.split("\n").filter((l) => l.includes("SyncEmbeddedRecords:"));
      for (const l of sync) console.log(`${label}: ${l.trim()}`);
      const invalid = text.split("\n").filter((l) => l.includes("could not write metadata"));
      console.log(`${label}: invalid-record diagnostics: ${invalid.length}`);
      for (const l of invalid.slice(0, 5)) console.log(`  ${l.trim()}`);
      if (invalid.length) failures.push(`${label}: ${invalid.length} invalid-record diagnostics`);
      await client.request(ControlCommand.Quit);
      },
      ["-appdata", APPDIR],
      { connectTimeoutMs: 60_000, env: fixtureEnv() },
    );
  } finally {
    await stopLibraryFixtureService(service);
  }
}

export async function testit(): Promise<void> {
  const failures: string[] = [];
  setupLibraryFixture();
  await run("cold", failures);
  await run("warm", failures);
  if (failures.length) {
    console.log("\n=== failures ===");
    for (const f of failures) console.log(`  - ${f}`);
    throw new Error(`chunk33-startup: ${failures.length} failure(s)`);
  }
  console.log("\nchunk33-startup: COLD AND WARM STARTUP CLEAN");
}

if (import.meta.main) {
  await testit().catch((e) => {
    console.error(e);
    process.exit(1);
  });
}
