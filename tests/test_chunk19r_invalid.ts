import { existsSync } from "node:fs";
import { copyFile, mkdir, readdir, rm, writeFile } from "node:fs/promises";
import { join, resolve } from "node:path";
import { tmpdir } from "node:os";
import { spawn, type Subprocess } from "bun";
import { ControlCommand, type ControlClient, withControlledSumatra } from "./control";

const ROOT = process.cwd();
const EXE = join(ROOT, "out", "dbg64", "SumatraPDF.exe");
const CHATTERBOX = resolve(ROOT, "..", "Chatterbox-TTS-Extended-main");
const PYTHON = join(CHATTERBOX, ".venv-amd", "Scripts", "python.exe");
const BASE = join(tmpdir(), "SumatraPDF-tests", "chunk19r-invalid");
const SOURCE_LIBRARY = resolve(ROOT, "out", "dbg64", "real-library-copy", "manga_novels");
const LIBRARY = join(BASE, "library");

const PORT = 17901;

async function startService(cache: string): Promise<Subprocess> {
  const proc = spawn({
    cmd: [PYTHON, "-m", "audiobook.library", "--port", String(PORT), "--root", LIBRARY],
    cwd: CHATTERBOX,
    env: { ...process.env, SUMATRA_LIBRARY_CACHE_ROOT: cache, SUMATRA_TEST_NO_SWEEP: "1" },
    stdout: "pipe",
    stderr: "pipe",
  });
  for (let i = 0; i < 160; i++) {
    try {
      if ((await fetch(`http://127.0.0.1:${PORT}/status`)).ok) return proc;
    } catch {}
    await Bun.sleep(125);
  }
  proc.kill();
  throw new Error(`service ${PORT} did not start`);
}

async function stopService(proc: Subprocess): Promise<void> {
  try {
    await fetch(`http://127.0.0.1:${PORT}/quit`, { method: "POST", headers: { "Content-Type": "application/json" }, body: "{}" });
  } catch {}
  await Promise.race([proc.exited, Bun.sleep(8000)]);
  try { proc.kill(); } catch {}
}

async function getJson(path: string): Promise<any> {
  const response = await fetch(`http://127.0.0.1:${PORT}${path}`);
  if (!response.ok) throw new Error(`${path}: HTTP ${response.status}`);
  return await response.json();
}

async function postJson(path: string, body: any): Promise<any> {
  const response = await fetch(`http://127.0.0.1:${PORT}${path}`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
  return { status: response.status, body: await response.json().catch(() => null) };
}

async function setupCase(): Promise<{ cache: string; appdata: string }> {
  const cache = join(BASE, "cache");
  const appdata = join(BASE, "appdata");
  await mkdir(join(cache, "library"), { recursive: true });
  await mkdir(appdata, { recursive: true });
  await writeFile(join(cache, "library", "scan_scope.txt"), "2", "utf8");
  await writeFile(join(appdata, "SumatraPDF-settings.txt"), `Audiobook [\n\tPythonExe = ${PYTHON}\n\tChatterboxDir = ${CHATTERBOX}\n\tLibraryPort = ${PORT}\n\tLibraryRoots = ${LIBRARY}\n\tLibraryHome = true\n]\nHomePage [\n\tHomePageViewMode = list\n]\n`, "utf8");
  return { cache, appdata };
}

async function prepareLibrary(): Promise<number> {
  const names = ["Hitchhiker", "Restaurant", "Life, the Universe", "So Long", "Mostly Harmless", "Another Thing"];
  let copied = 0;
  async function walk(dir: string): Promise<void> {
    for (const entry of await readdir(dir, { withFileTypes: true })) {
      const path = join(dir, entry.name);
      if (entry.isDirectory()) {
        await walk(path);
      } else if (entry.isFile() && names.some((name) => entry.name.includes(name)) && !entry.name.endsWith(".sumatra")) {
        const target = join(LIBRARY, entry.name);
        await copyFile(path, target);
        if (existsSync(path + ".sumatra")) await copyFile(path + ".sumatra", target + ".sumatra");
        copied++;
      }
    }
  }
  await walk(SOURCE_LIBRARY);
  return copied;
}

if (!existsSync(EXE) || !existsSync(PYTHON) || !existsSync(SOURCE_LIBRARY)) {
  console.error("Required runtime or source library is missing");
  process.exit(1);
}

await rm(BASE, { recursive: true, force: true });
await mkdir(LIBRARY, { recursive: true });
const copied = await prepareLibrary();
console.error(`Copied ${copied} files`);

const { cache, appdata } = await setupCase();
const service = await startService(cache);

try {
  // Phase 1: Initial scan
  await withControlledSumatra(EXE, async (client, proc) => {
    await client.request(ControlCommand.TestLibRescan, []);
    const deadline = Date.now() + 120000;
    while (Date.now() < deadline) {
      const status = await client.request(ControlCommand.TestLibScanStatus, []);
      const s = String(status[1] ?? "");
      const m = s.match(/total=(\d+)/);
      const t = m ? Number(m[1]) : 0;
      const d = s.match(/done=(\d+)/);
      const dn = d ? Number(d[1]) : 0;
      if (t > 0 && dn >= t) break;
      await Bun.sleep(500);
    }
  }, ["-appdata", appdata], { env: { ...process.env, SUMATRA_LIBRARY_CACHE_ROOT: cache } });

  const before = await getJson("/library?limit=100");
  console.error(`\nBefore invalid removal: ${before.total} books`);

  // Test invalid target: non-existent path
  console.error("\n=== Test 1: Invalid path (non-existent) ===");
  const r1 = await postJson("/kind", {
    kind: "document",
    paths: ["C:\\NonExistent\\path.pdf"],
  });
  console.error(`Response:`, r1);
  const after1 = await getJson("/library?limit=100");
  console.error(`After: ${after1.total} books (expected ${before.total})`);
  if (after1.total !== before.total) {
    console.error("!!! BUG: Invalid target changed library count !!!");
  }

  // Test invalid target: empty path
  console.error("\n=== Test 2: Empty path ===");
  const r2 = await postJson("/kind", {
    kind: "document",
    paths: [""],
  });
  console.error(`Response:`, r2);
  const after2 = await getJson("/library?limit=100");
  console.error(`After: ${after2.total} books (expected ${before.total})`);
  if (after2.total !== before.total) {
    console.error("!!! BUG: Empty path changed library count !!!");
  }

  // Test invalid target: empty paths array
  console.error("\n=== Test 3: Empty paths array ===");
  const r3 = await postJson("/kind", {
    kind: "document",
    paths: [],
  });
  console.error(`Response:`, r3);
  const after3 = await getJson("/library?limit=100");
  console.error(`After: ${after3.total} books (expected ${before.total})`);
  if (after3.total !== before.total) {
    console.error("!!! BUG: Empty paths array changed library count !!!");
  }

  // Test invalid target: missing paths field
  console.error("\n=== Test 4: Missing paths field ===");
  const r4 = await postJson("/kind", {
    kind: "document",
  });
  console.error(`Response:`, r4);
  const after4 = await getJson("/library?limit=100");
  console.error(`After: ${after4.total} books (expected ${before.total})`);
  if (after4.total !== before.total) {
    console.error("!!! BUG: Missing paths field changed library count !!!");
  }

} finally {
  await stopService(service);
}
