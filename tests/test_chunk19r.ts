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
const BASE = join(tmpdir(), "SumatraPDF-tests", "chunk19r");
const SOURCE_LIBRARY = resolve(ROOT, "out", "dbg64", "real-library-copy", "manga_novels");
const LIBRARY = join(BASE, "library");

const PORT = 17900;

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
  if (!response.ok) throw new Error(`${path}: HTTP ${response.status}`);
  return await response.json();
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

let result: any = {};

try {
  // Phase 1: Initial scan via SumatraPDF
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
      console.error(`scan: total=${t} done=${dn}`);
      if (t > 0 && dn >= t) break;
      await Bun.sleep(500);
    }
    console.error("Scan complete");
  }, ["-appdata", appdata], { env: { ...process.env, SUMATRA_LIBRARY_CACHE_ROOT: cache } });

  // Get library state before removal
  const before = await getJson("/library?limit=100");
  console.error(`\nBefore removal: ${before.total} books`);
  for (const b of before.books ?? []) {
    console.error(`  - ${b.title} path=${b.path}`);
  }
  result.before = { total: before.total, books: (before.books ?? []).map((b: any) => ({ title: b.title, path: b.path })) };

  // Pick the second book to remove
  const targetBook = before.books[1];
  console.error(`\nRemoving: ${targetBook.title} (path=${targetBook.path})`);
  result.removedBook = { title: targetBook.title, path: targetBook.path };

  // Simulate what MoveOneFile does: POST /kind with kind=document and paths=[targetPath]
  const removeResp = await postJson("/kind", {
    kind: "document",
    paths: [targetBook.path],
  });
  console.error(`Remove response:`, removeResp);
  result.removeResponse = removeResp;

  // Get library state after removal
  const after = await getJson("/library?limit=100");
  console.error(`\nAfter removal: ${after.total} books`);
  for (const b of after.books ?? []) {
    console.error(`  - ${b.title} path=${b.path}`);
  }
  result.after = { total: after.total, books: (after.books ?? []).map((b: any) => ({ title: b.title, path: b.path })) };

  // Get desk documents
  const desk = await getJson("/deskpan?show=document");
  console.error(`\nDesk documents: ${desk.total}`);
  for (const b of desk.files ?? []) {
    console.error(`  - ${b.title} path=${b.path}`);
  }
  result.desk = { total: desk.total, files: (desk.files ?? []).map((b: any) => ({ title: b.title, path: b.path })) };

  // Check: expected delta is -1
  const expectedDelta = 1;
  const actualDelta = before.total - after.total;
  console.error(`\nDelta: ${actualDelta} (expected ${expectedDelta})`);
  result.delta = { actual: actualDelta, expected: expectedDelta, pass: actualDelta === expectedDelta };

  if (after.total === 0) {
    console.error("\n!!! BUG REPRODUCED: Library is empty after removing one book !!!");
    result.bug = "library became empty after removing one book";
  } else if (actualDelta !== expectedDelta) {
    console.error(`\n!!! BUG: expected delta ${expectedDelta}, got ${actualDelta} !!!`);
    result.bug = `wrong delta: expected ${expectedDelta}, got ${actualDelta}`;
  } else {
    console.error("\n=== OK: Only the selected book was removed ===");
  }

  // Restart and verify
  console.error("\n=== Restarting to verify persistence ===");
  await withControlledSumatra(EXE, async (client, proc) => {
    await Bun.sleep(2000);
    const afterRestart = await getJson("/library?limit=100");
    console.error(`After restart: ${afterRestart.total} books`);
    for (const b of afterRestart.books ?? []) {
      console.error(`  - ${b.title}`);
    }
    result.afterRestart = { total: afterRestart.total, books: (afterRestart.books ?? []).map((b: any) => ({ title: b.title, path: b.path })) };

    if (afterRestart.total === after.total) {
      console.error("=== OK: Restart preserves the removal ===");
    } else {
      console.error(`!!! BUG: Restart shows ${afterRestart.total} books, expected ${after.total} !!!`);
      result.bug = `restart shows wrong count: ${afterRestart.total} vs ${after.total}`;
    }
  }, ["-appdata", appdata], { env: { ...process.env, SUMATRA_LIBRARY_CACHE_ROOT: cache } });

} finally {
  await stopService(service);
}

console.log(JSON.stringify(result, null, 2));
