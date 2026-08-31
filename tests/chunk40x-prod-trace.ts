/**
 * Chunk 40X: Reproduce the actual GUI rescan stall on the user's real library.
 *
 * - Uses the actual SumatraPDF.exe (not bench_library).
 * - Uses the actual Python audiobook library service.
 * - Triggers Rescan via the SumatraControl interface.
 * - Polls scan status; detects stalls.
 * - When stalled, dumps thread stacks via TestDumpAllStacks.
 */

import { mkdir, rm, copyFile, readFile, writeFile, readdir, stat } from "node:fs/promises";
import { existsSync } from "node:fs";
import { join, resolve, dirname, basename, relative } from "node:path";
import { tmpdir } from "node:os";
import { spawn, type Subprocess } from "bun";
import { withControlledSumatra, ControlClient, ControlCommand } from "./control";

const ROOT = process.cwd();
const EXE = join(ROOT, "out", "dbg64", "SumatraPDF.exe");
const CHATTERBOX = join(ROOT, "..", "Chatterbox-TTS-Extended-main");
const PYTHON = join(CHATTERBOX, ".venv-amd", "Scripts", "python.exe");
const BENCH = join(ROOT, "out", "dbg64", "bench_library.exe");

const USER_LIBRARY = process.env.USER_LIBRARY ?? "C:\\Users\\Nokel\\Documents\\ebooks";
const APPROVED = resolve(tmpdir(), "SumatraPDF-chunk40x");
const LIBRARY_COPY = join(APPROVED, "library");
const CACHE_DIR = join(APPROVED, "cache");
const APPDATA = join(APPROVED, "appdata");
const PORT = 7874;
const SUMATRA_BENCH = BENCH;

interface ScanSnapshot {
  scanning: number;
  native: number;
  done: number;
  total: number;
  branding: number;
  sweep: number;
  elapsedMs: number;
}

async function scanStatus(client: ControlClient): Promise<ScanSnapshot> {
  const res = await client.request(ControlCommand.TestLibScanStatus);
  const raw = String(res[1] ?? "");
  const m = /scanning=(\d+) native=(\d+) done=(\d+) total=(\d+) branding=(\d+) sweep=(\d+)/.exec(raw);
  if (!m) throw new Error(`could not parse scan status: ${raw}`);
  return {
    scanning: parseInt(m[1], 10),
    native: parseInt(m[2], 10),
    done: parseInt(m[3], 10),
    total: parseInt(m[4], 10),
    branding: parseInt(m[5], 10),
    sweep: parseInt(m[6], 10),
    elapsedMs: Date.now(),
  };
}

async function waitScanComplete(
  client: ControlClient,
  timeoutMs: number,
  pollMs: number,
  onSnapshot: (s: ScanSnapshot, stalledMs: number) => Promise<void>,
): Promise<{ completed: boolean; lastSnapshot: ScanSnapshot | null; stalled: ScanSnapshot | null }> {
  const start = Date.now();
  let last: ScanSnapshot | null = null;
  let lastChange = Date.now();
  let lastProgress = -1;
  let stalledSnapshot: ScanSnapshot | null = null;

  while (Date.now() - start < timeoutMs) {
    let s: ScanSnapshot;
    try {
      s = await scanStatus(client);
    } catch (e) {
      await new Promise((r) => setTimeout(r, pollMs));
      continue;
    }

    if (s.done !== lastProgress) {
      lastChange = Date.now();
      lastProgress = s.done;
    }

    if (s.scanning === 0 && s.native === 0 && s.branding === 0) {
      return { completed: true, lastSnapshot: s, stalled: null };
    }

    const stalledMs = Date.now() - lastChange;
    onSnapshot(s, stalledMs);

    if (stalledMs > 10_000 && stalledSnapshot === null) {
      stalledSnapshot = s;
    }

    last = s;
    await new Promise((r) => setTimeout(r, pollMs));
  }

  return { completed: false, lastSnapshot: last, stalled: stalledSnapshot };
}

async function dumpStacks(client: ControlClient): Promise<string> {
  try {
    const res = await client.request(ControlCommand.TestDumpAllStacks);
    return String(res[1] ?? "");
  } catch (e) {
    return `<dump failed: ${e}>`;
  }
}

async function copyDir(src: string, dst: string): Promise<void> {
  await mkdir(dst, { recursive: true });
  const entries = await readdir(src, { withFileTypes: true });
  for (const e of entries) {
    const s = join(src, e.name);
    const d = join(dst, e.name);
    if (e.isDirectory()) {
      await copyDir(s, d);
    } else if (e.isFile()) {
      await copyFile(s, d);
    }
  }
}

async function setup(): Promise<{ approved: string }> {
  if (existsSync(APPROVED)) {
    await rm(APPROVED, { recursive: true, force: true });
  }
  await mkdir(APPROVED, { recursive: true });
  await mkdir(LIBRARY_COPY, { recursive: true });
  await mkdir(CACHE_DIR, { recursive: true });
  await mkdir(APPDATA, { recursive: true });

  // Copy the user's library verbatim into a controlled destination.
  console.error(`copying ${USER_LIBRARY} -> ${LIBRARY_COPY}`);
  await copyDir(USER_LIBRARY, LIBRARY_COPY);

  const settings = `Audiobook [
\tPythonExe = ${PYTHON}
\tChatterboxDir = ${CHATTERBOX}
\tLibraryPort = ${PORT}
\tLibraryRoots = ${LIBRARY_COPY}
\tLibraryHome = true
]
HomePage [
\tHomePageViewMode = list
]
`;
  await writeFile(join(APPDATA, "SumatraPDF-settings.txt"), settings, "utf8");

  return { approved: APPROVED };
}

async function startService(): Promise<Subprocess> {
  const proc = spawn({
    cmd: [PYTHON, "-m", "audiobook.library", "--port", String(PORT), "--root", LIBRARY_COPY],
    cwd: CHATTERBOX,
    env: {
      ...process.env,
      SUMATRA_LIBRARY_CACHE_ROOT: CACHE_DIR,
      SUMATRA_BENCH_LIBRARY: SUMATRA_BENCH,
    },
    stdout: "pipe",
    stderr: "pipe",
  });
  for (let i = 0; i < 100; i++) {
    try {
      const r = await fetch(`http://127.0.0.1:${PORT}/status`);
      if (r.ok) return proc;
    } catch {}
    await new Promise((r) => setTimeout(r, 250));
  }
  proc.kill();
  throw new Error("library service did not start");
}

async function stopService(proc: Subprocess): Promise<void> {
  try {
    await fetch(`http://127.0.0.1:${PORT}/quit`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: "{}",
    });
  } catch {}
  await Promise.race([proc.exited, new Promise((r) => setTimeout(r, 8000))]);
  try { proc.kill(); } catch {}
}

async function run(): Promise<number> {
  if (!existsSync(EXE)) {
    console.error(`SumatraPDF.exe not found: ${EXE}`);
    return 2;
  }
  if (!existsSync(PYTHON)) {
    console.error(`Python venv not found: ${PYTHON}`);
    return 2;
  }
  if (!existsSync(BENCH)) {
    console.error(`bench_library.exe not found: ${BENCH}`);
    return 2;
  }

  await setup();
  const service = await startService();

  const start = Date.now();
  let snapshotLog: ScanSnapshot[] = [];
  let stalledAt: ScanSnapshot | null = null;
  let stacksAtStall: string | null = null;

  try {
    await withControlledSumatra(
      EXE,
      async (client) => {
        await client.request(ControlCommand.Ping);
        await new Promise((r) => setTimeout(r, 1500));

        // Start the rescan
        await client.request(ControlCommand.TestLibRescan);

        // Poll the scan status until stall, then dump stacks and exit
        let lastDumpAt = 0;
        while (true) {
          let s: ScanSnapshot;
          try {
            s = await scanStatus(client);
          } catch (e) {
            await new Promise((r) => setTimeout(r, 500));
            continue;
          }
          if (snapshotLog.length === 0 || snapshotLog[snapshotLog.length - 1].done !== s.done) {
            snapshotLog.push(s);
          }
          if (s.scanning === 0 && s.native === 0 && s.branding === 0 && snapshotLog.length > 5) {
            console.error("");
            console.error(`scan completed at done=${s.done}/${s.total}`);
            break;
          }
          // Show progress every 5 changes
          if (snapshotLog.length % 3 === 1) {
            const now = Date.now();
            const lastChange = snapshotLog[snapshotLog.length - 1];
            const sinceLast = now - (lastChange.elapsedMs || 0);
            console.error(
              `t=${((Date.now() - start) / 1000).toFixed(1)}s scanning=${s.scanning} ` +
              `native=${s.native} done=${s.done}/${s.total} branding=${s.branding} sweep=${s.sweep}`,
            );
          }
          // If stalled >15s and we have a stalled file, dump stacks
          if (snapshotLog.length > 0) {
            const lastSnap = snapshotLog[snapshotLog.length - 1];
            const stallMs = Date.now() - lastSnap.elapsedMs;
            if (stallMs > 15_000 && lastSnap.done > 0) {
              console.error("");
              console.error(`STALL at done=${lastSnap.done}/${lastSnap.total} for ${(stallMs/1000).toFixed(1)}s`);
              if (Date.now() - lastDumpAt > 2000) {
                lastDumpAt = Date.now();
                const stacks = await dumpStacks(client);
                console.error("=== STACKS ===");
                console.error(stacks);
                console.error("=== END STACKS ===");
              }
            }
          }
          await new Promise((r) => setTimeout(r, 500));
        }

        // Wait for the brand loop to complete (it runs in a background thread)
        let brandWaitStart = Date.now();
        while (Date.now() - brandWaitStart < 60_000) {
          try {
            const s = await scanStatus(client);
            if (s.branding === 0) {
              console.error(`brand loop done at elapsed=${((Date.now() - start) / 1000).toFixed(1)}s`);
              break;
            }
          } catch (e) {}
          await new Promise((r) => setTimeout(r, 500));
        }

        console.error("");
        console.error(`elapsed ${((Date.now() - start) / 1000).toFixed(1)}s`);
        console.error(`snapshots: ${snapshotLog.length}`);
      },
      ["-appdata", APPDATA, "-for-testing"],
      {
        env: {
          ...process.env,
          SUMATRA_LIBRARY_CACHE_ROOT: CACHE_DIR,
          SUMATRA_BENCH_LIBRARY: SUMATRA_BENCH,
        },
      },
    );
  } finally {
    await stopService(service);
  }
  return process.exitCode ?? 0;
}

run().then((code) => process.exit(code)).catch((e) => {
  console.error(e);
  process.exit(1);
});
