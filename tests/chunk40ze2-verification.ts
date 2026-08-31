import { existsSync } from "node:fs";
import { mkdir, rm, writeFile } from "node:fs/promises";
import { join, resolve } from "node:path";
import { tmpdir } from "node:os";
import { spawn, type Subprocess } from "bun";
import { ControlCommand, withControlledSumatra } from "./control";

const root = process.cwd();
const exe = join(root, "out", "dbg64", "SumatraPDF.exe");
const bench = join(root, "out", "dbg64", "bench_library.exe");
const chatterbox = resolve(root, "..", "Chatterbox-TTS-Extended-main");
const python = join(chatterbox, ".venv-amd", "Scripts", "python.exe");
const library = join(root, "out", "dbg64", "real-library-copy", "manga_novels");
const base = join(tmpdir(), "SumatraPDF-tests", "chunk40ze2");
const cache = join(base, "cache");
const appdata = join(base, "appdata");
const port = 7898;

async function get(path: string): Promise<any> {
  const response = await fetch(`http://127.0.0.1:${port}${path}`);
  if (!response.ok) throw new Error(`${path}: ${response.status}`);
  return response.json();
}

async function startService(): Promise<Subprocess> {
  const process = spawn({ cmd: [python, "-m", "audiobook.library", "--port", String(port), "--root", library], cwd: chatterbox, env: { ...Bun.env, SUMATRA_LIBRARY_CACHE_ROOT: cache, SUMATRA_BENCH_LIBRARY: bench, SUMATRA_TEST_NO_SWEEP: "1" }, stdout: "pipe", stderr: "pipe" });
  for (let i = 0; i < 200; i++) {
    try { if ((await fetch(`http://127.0.0.1:${port}/status`)).ok) return process; } catch {}
    await Bun.sleep(50);
  }
  process.kill();
  throw new Error("service did not start");
}

if (![exe, bench, python, library].every(existsSync)) throw new Error("runtime is missing");
await rm(base, { recursive: true, force: true });
await mkdir(join(cache, "library"), { recursive: true });
await mkdir(appdata, { recursive: true });
await writeFile(join(cache, "library", "scan_scope.txt"), "2", "utf8");
await writeFile(join(appdata, "SumatraPDF-settings.txt"), `Audiobook [\n\tPythonExe = ${python}\n\tChatterboxDir = ${chatterbox}\n\tLibraryPort = ${port}\n\tLibraryRoots = ${library}\n\tLibraryHome = true\n\tProgressiveLibraryScan = true\n]\nHomePage [\n\tHomePageViewMode = list\n]\n`, "utf8");
const service = await startService();
const started = performance.now();
try {
  await withControlledSumatra(exe, async (client) => {
    await client.request(ControlCommand.TestLibRescan, []);
    let active = false;
    const deadline = Date.now() + 300000;
    while (Date.now() < deadline) {
      const raw = String((await client.request(ControlCommand.TestLibScanStatus, []))[1] ?? "");
      if (/scanning=1|native=1/.test(raw)) active = true;
      if (active && /scanning=0/.test(raw) && /native=0/.test(raw)) return;
      await Bun.sleep(10);
    }
    throw new Error("real scan did not finish");
  }, ["-appdata", appdata], { env: { ...Bun.env, SUMATRA_LIBRARY_CACHE_ROOT: cache, SUMATRA_BENCH_LIBRARY: bench, SUMATRA_TEST_NO_SWEEP: "1" } });
  const elapsedMs = performance.now() - started;
  const status = await get("/status");
  const libraryStatus = await get("/library?limit=1");
  const result = { physicalItems: status.scan_perf?.book_calls, logicalBooks: libraryStatus.total, elapsedMs, itemsPerSecond: status.scan_perf?.book_calls / (elapsedMs / 1000), perf: status.scan_perf, checkpointCleared: !existsSync(join(cache, "library", "scan_checkpoint.json")), crashed: false };
  console.log(JSON.stringify(result, null, 2));
  if (!result.physicalItems || !result.logicalBooks || !result.checkpointCleared || status.scan_perf?.save_index_calls !== 1 || status.scan_perf?.full_catalogue_rewrites !== 0) process.exit(1);
} finally {
  try { await fetch(`http://127.0.0.1:${port}/quit`, { method: "POST", headers: { "Content-Type": "application/json" }, body: "{}" }); } catch {}
  await Promise.race([service.exited, Bun.sleep(5000)]);
  try { service.kill(); } catch {}
}
