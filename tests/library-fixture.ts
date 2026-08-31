import { existsSync, readFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, relative, resolve } from "node:path";
import { ROOT } from "./util";

export const FIXTURE_BASE = join(tmpdir(), "SumatraPDF-tests", "library-fixture");
export const FIXTURE_ROOT = join(FIXTURE_BASE, "library");
export const FIXTURE_CACHE = join(FIXTURE_BASE, "cache");
export const FIXTURE_APPDATA = join(FIXTURE_BASE, "appdata");
export const FIXTURE_PORT = 7873;
export const CHATTERBOX = join(ROOT, "..", "Chatterbox-TTS-Extended-main");
export const PYTHON = join(CHATTERBOX, ".venv-amd", "Scripts", "python.exe");
export const BENCH = join(ROOT, "out", "dbg64", "bench_library.exe");

export function assertFixtureDestination(path: string): void {
  const rel = relative(resolve(FIXTURE_BASE), resolve(path));
  if (rel === "" || (!rel.startsWith("..\\") && rel !== ".." && !rel.startsWith("../"))) return;
  throw new Error(`test destination is outside the fixture root: ${path}`);
}

export interface LibraryFixtureManifest {
  root: string;
  cache: string;
  appdata: string;
  port: number;
  books: string[];
  series_a: string;
  series_b: string;
  no_text: string[];
  document: string;
  ignored: string;
  epub: string;
  mobi: string;
  new_text: string;
}

export function fixtureEnv(): Record<string, string> {
  return { SUMATRA_LIBRARY_CACHE_ROOT: FIXTURE_CACHE };
}

export function setupLibraryFixture(): LibraryFixtureManifest {
  for (const path of [FIXTURE_ROOT, FIXTURE_CACHE, FIXTURE_APPDATA]) assertFixtureDestination(path);
  const run = Bun.spawnSync([
    PYTHON,
    join(ROOT, "tests", "chunk37w-fixture.py"),
    FIXTURE_ROOT,
    CHATTERBOX,
    BENCH,
    FIXTURE_CACHE,
    FIXTURE_APPDATA,
    String(FIXTURE_PORT),
    FIXTURE_BASE,
  ], { cwd: ROOT, env: { ...process.env, ...fixtureEnv() } });
  if (!run.success) {
    throw new Error(`fixture setup failed: ${run.stdout}${run.stderr}`);
  }
  return JSON.parse(readFileSync(join(FIXTURE_ROOT, "manifest.json"), "utf8"));
}

export async function startLibraryFixtureService(): Promise<Bun.Subprocess> {
  if (!existsSync(join(FIXTURE_ROOT, "manifest.json"))) setupLibraryFixture();
  const proc = Bun.spawn([
    PYTHON,
    "-m",
    "audiobook.library",
    "--port",
    String(FIXTURE_PORT),
    "--root",
    FIXTURE_ROOT,
  ], { cwd: CHATTERBOX, env: { ...process.env, ...fixtureEnv() }, stdout: "pipe", stderr: "pipe" });
  for (let attempt = 0; attempt < 80; attempt++) {
    if (proc.exitCode !== null) {
      throw new Error(`fixture service exited ${proc.exitCode}: ${await new Response(proc.stderr).text()}`);
    }
    try {
      const response = await fetch(`http://127.0.0.1:${FIXTURE_PORT}/status`);
      if (response.ok) return proc;
    } catch {}
    await Bun.sleep(250);
  }
  proc.kill();
  throw new Error("fixture service did not start");
}

export async function stopLibraryFixtureService(proc: Bun.Subprocess): Promise<void> {
  try {
    await fetch(`http://127.0.0.1:${FIXTURE_PORT}/quit`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: "{}",
    });
  } catch {}
  const result = await Promise.race([proc.exited, Bun.sleep(10_000).then(() => "timeout")]);
  if (result === "timeout") {
    proc.kill();
    await proc.exited;
  }
}
