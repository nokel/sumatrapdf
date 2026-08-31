// chunk 34: both fresh and warm launches must settle with one model load
// and zero known-book fingerprint work. Service failure must not reload-loop.

import { existsSync, mkdirSync, rmSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { ROOT } from "./util";
import { ControlCommand, withControlledSumatra } from "./control";

const EXE = join(ROOT, "out", "dbg64", "SumatraPDF.exe");
const APPDATA = join(ROOT, "scratchpad", "chunk34-regression-appdata");
const NOSERVICE_APPDATA = join(ROOT, "scratchpad", "chunk34-regression-noservice");
const LOG = join(ROOT, "out", "dbg64", "sumlog.txt");
const STEP_S = 10;
const FIRST_BUDGET_S = 420;
const SECOND_BUDGET_S = 150;
const IDLE_PCT = 5;

async function ps(script: string): Promise<string> {
  const p = Bun.spawn(["powershell", "-NoProfile", "-Command", script], { stdout: "pipe", stderr: "pipe" });
  return (await new Response(p.stdout).text()).trim();
}

async function procCpu(pid: number): Promise<number> {
  const s = await ps(`(Get-Process -Id ${pid} -ErrorAction SilentlyContinue).TotalProcessorTime.TotalSeconds`);
  return parseFloat(s) || 0;
}

function parseCount(reply: unknown[]): number {
  const m = /count=(\d+)/.exec(String(reply[1] ?? ""));
  return m ? parseInt(m[1]!, 10) : -1;
}

interface Launch {
  settledAt: number;
  busyS: number;
  peakPct: number;
  loads: number;
  full: number;
  shape: number;
}

async function launch(appDir: string, label: string, budgetS: number): Promise<Launch> {
  const logAt = existsSync(LOG) ? Bun.file(LOG).size : 0;
  return await withControlledSumatra(
    EXE,
    async (client: any, proc: any) => {
      let prev = await procCpu(proc.pid);
      const start = prev;
      let quiet = 0;
      let peakPct = 0;
      let settledAt = -1;
      for (let t = STEP_S; t <= budgetS; t += STEP_S) {
        await new Promise((r) => setTimeout(r, STEP_S * 1000));
        const now = await procCpu(proc.pid);
        const pct = ((now - prev) / STEP_S) * 100;
        prev = now;
        if (pct > peakPct) peakPct = pct;
        quiet = pct < IDLE_PCT ? quiet + 1 : 0;
        if (quiet >= 3) {
          settledAt = t;
          break;
        }
      }
      const loads = parseCount(await client.request(ControlCommand.TestLoadCount, []));
      const log = existsSync(LOG) ? (await Bun.file(LOG).text()).substring(logAt) : "";
      const matches = [...log.matchAll(/fingerprint full=(\d+) shape=(\d+)/g)];
      const last = matches.at(-1);
      const full = parseInt(last?.[1] || "0", 10);
      const shape = parseInt(last?.[2] || "0", 10);
      const busyS = prev - start;
      console.log(
        `[${label}] settled@${settledAt}s cpu=${busyS.toFixed(1)}s peak=${peakPct.toFixed(0)}% loads=${loads} full=${full} shape=${shape}`,
      );
      return { settledAt, busyS, peakPct, loads, full, shape };
    },
    ["-appdata", appDir],
    { connectTimeoutMs: 60_000 },
  );
}

export async function testit(): Promise<void> {
  const failures: string[] = [];

  if (existsSync(APPDATA)) rmSync(APPDATA, { recursive: true, force: true });
  mkdirSync(APPDATA, { recursive: true });

  const first = await launch(APPDATA, "first-launch", FIRST_BUDGET_S);
  if (first.settledAt < 0) {
    failures.push(`first launch: never settled within ${FIRST_BUDGET_S}s`);
  }
  if (first.loads !== 1) {
    failures.push(`first launch: expected 1 load, got ${first.loads}`);
  }
  if (first.full !== 0 || first.shape !== 0) {
    failures.push(`first launch: expected zero fingerprint work, got full=${first.full} shape=${first.shape}`);
  }

  const second = await launch(APPDATA, "second-launch", SECOND_BUDGET_S);
  if (second.settledAt < 0) {
    failures.push(`second launch: never settled within ${SECOND_BUDGET_S}s`);
  }
  if (second.loads !== 1) {
    failures.push(`second launch: expected 1 load, got ${second.loads}`);
  }
  if (second.full !== 0 || second.shape !== 0) {
    failures.push(`second launch: expected zero fingerprint work, got full=${second.full} shape=${second.shape}`);
  }

  // Service unreachable: a fresh appdata pointed at a port nothing
  // listens on, with a python that does not exist so the app cannot
  // start a service of its own. The page reports the error once and
  // stops; it must not keep starting loads.
  if (existsSync(NOSERVICE_APPDATA)) rmSync(NOSERVICE_APPDATA, { recursive: true, force: true });
  mkdirSync(NOSERVICE_APPDATA, { recursive: true });
  writeFileSync(
    join(NOSERVICE_APPDATA, "SumatraPDF-settings.txt"),
    [
      "Audiobook [",
      "\tPythonExe = C:\\no\\such\\python.exe",
      "\tChatterboxDir = C:\\no\\such\\dir",
      "\tLibraryPort = 7999",
      "]",
      "",
    ].join("\n"),
  );
  const noService = await launch(NOSERVICE_APPDATA, "no-service", 90);
  if (noService.settledAt < 0) {
    failures.push("service unavailable: process never went idle");
  }
  if (noService.loads !== 1) {
    failures.push(`service unavailable: reload storm, expected 1 load, got ${noService.loads}`);
  }

  if (failures.length > 0) {
    console.log("\n=== failures ===");
    for (const f of failures) console.log(`  - ${f}`);
    throw new Error(`chunk34-cpu-idle: ${failures.length} failure(s)`);
  }
  console.log("\nchunk34-cpu-idle: ALL CHECKS PASSED");
}

if (import.meta.main) {
  await testit().catch((e) => {
    console.error(e);
    process.exit(1);
  });
}
