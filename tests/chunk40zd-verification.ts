import { existsSync } from "node:fs";
import { copyFile, mkdir, readdir, rm, writeFile } from "node:fs/promises";
import { dirname, join, relative, resolve } from "node:path";
import { tmpdir } from "node:os";
import { spawn, type Subprocess } from "bun";
import { Database } from "bun:sqlite";
import { ControlCommand, type ControlClient, withControlledSumatra } from "./control";
import { captureWindowToPng, waitForFrame } from "./win-automation";

const ROOT = process.cwd();
const EXE = join(ROOT, "out", "dbg64", "SumatraPDF.exe");
const BENCH = join(ROOT, "out", "dbg64", "bench_library.exe");
const CHATTERBOX = resolve(ROOT, "..", "Chatterbox-TTS-Extended-main");
const PYTHON = join(CHATTERBOX, ".venv-amd", "Scripts", "python.exe");
const BASE = join(tmpdir(), "SumatraPDF-tests", "chunk40zd");
const SOURCE_LIBRARY = resolve(ROOT, "out", "dbg64", "real-library-copy", "manga_novels");
const LIBRARY = join(BASE, "library");

type Status = Record<string, number>;

type CaseResult = {
  ratio: number;
  progressive: boolean;
  before: Status;
  persisted: number;
  checkpointFiles: number;
  checkpointManifest: number;
  restored: number;
  reopened: Status;
  final: Status;
  catalogue: unknown;
  checkpointCleared: boolean;
  screenshots: string[];
};

function statusOf(raw: string): Status {
  const values: Status = {};
  for (const match of raw.matchAll(/([A-Za-z]+)=(\d+)/g)) values[match[1]] = Number(match[2]);
  return values;
}

async function status(client: ControlClient): Promise<Status> {
  const response = await client.request(ControlCommand.TestLibScanStatus, []);
  return statusOf(String(response[1] ?? ""));
}

async function getJson(port: number, path: string): Promise<any> {
  const response = await fetch(`http://127.0.0.1:${port}${path}`);
  if (!response.ok) throw new Error(`${path}: HTTP ${response.status}`);
  return await response.json();
}

async function catalogue(port: number): Promise<any> {
  const library = await getJson(port, "/library?limit=5000");
  const books = [];
  for (const row of library.books ?? []) books.push(await getJson(port, `/book?id=${encodeURIComponent(row.id)}&meta=0`));
  books.sort((a, b) => String(a.id).localeCompare(String(b.id)));
  const series = (await getJson(port, "/series")).series ?? [];
  series.sort((a: any, b: any) => String(a.key).localeCompare(String(b.key)));
  return { total: library.total, books, series };
}

function checkpointCounts(cache: string): { files: number; manifest: number } {
  const db = new Database(join(cache, "library", "scan_checkpoint.sqlite3"), { readonly: true });
  try {
    return { files: Number((db.query("SELECT count(*) AS n FROM completed").get() as any).n), manifest: Number((db.query("SELECT count(*) AS n FROM manifest").get() as any).n) };
  } finally {
    db.close();
  }
}

async function startService(port: number, cache: string): Promise<Subprocess> {
  const proc = spawn({
    cmd: [PYTHON, "-m", "audiobook.library", "--port", String(port), "--root", LIBRARY],
    cwd: CHATTERBOX,
    env: { ...process.env, SUMATRA_LIBRARY_CACHE_ROOT: cache, SUMATRA_BENCH_LIBRARY: BENCH, SUMATRA_TEST_NO_SWEEP: "1" },
    stdout: "pipe",
    stderr: "pipe",
  });
  for (let i = 0; i < 160; i++) {
    try {
      if ((await fetch(`http://127.0.0.1:${port}/status`)).ok) return proc;
    } catch {}
    await Bun.sleep(125);
  }
  proc.kill();
  throw new Error(`service ${port} did not start`);
}

async function stopService(port: number, proc: Subprocess): Promise<void> {
  try {
    await fetch(`http://127.0.0.1:${port}/quit`, { method: "POST", headers: { "Content-Type": "application/json" }, body: "{}" });
  } catch {}
  await Promise.race([proc.exited, Bun.sleep(8000)]);
  try { proc.kill(); } catch {}
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
        const target = join(LIBRARY, relative(SOURCE_LIBRARY, path));
        await mkdir(dirname(target), { recursive: true });
        await copyFile(path, target);
        if (existsSync(path + ".sumatra")) await copyFile(path + ".sumatra", target + ".sumatra");
        copied++;
      }
    }
  }
  await walk(SOURCE_LIBRARY);
  return copied;
}

async function setupCase(name: string, progressive: boolean, port: number): Promise<{ cache: string; appdata: string; shots: string }> {
  const root = join(BASE, name);
  const cache = join(root, "cache");
  const appdata = join(root, "appdata");
  const shots = join(root, "screenshots");
  await mkdir(join(cache, "library"), { recursive: true });
  await mkdir(appdata, { recursive: true });
  await mkdir(shots, { recursive: true });
  await writeFile(join(cache, "library", "scan_scope.txt"), "2", "utf8");
  await writeFile(join(appdata, "SumatraPDF-settings.txt"), `Audiobook [\n\tPythonExe = ${PYTHON}\n\tChatterboxDir = ${CHATTERBOX}\n\tLibraryPort = ${port}\n\tLibraryRoots = ${LIBRARY}\n\tLibraryHome = true\n\tProgressiveLibraryScan = ${progressive ? "true" : "false"}\n]\nHomePage [\n\tHomePageViewMode = list\n]\n`, "utf8");
  return { cache, appdata, shots };
}

async function uninterrupted(): Promise<any> {
  const paths = await setupCase("uninterrupted", true, 7890);
  const service = await startService(7890, paths.cache);
  try {
    await withControlledSumatra(EXE, async (client, proc) => {
      await client.request(ControlCommand.TestLibRescan, []);
      const deadline = Date.now() + 300000;
      let active = false;
      while (Date.now() < deadline) {
        const row = await status(client);
        if (row.scanning || row.native) active = true;
        if (active && !row.scanning && !row.native) {
          await client.quit();
          await proc.exited;
          return;
        }
        await Bun.sleep(10);
      }
      throw new Error("uninterrupted scan did not finish");
    }, ["-appdata", paths.appdata], { env: { ...process.env, SUMATRA_LIBRARY_CACHE_ROOT: paths.cache, SUMATRA_BENCH_LIBRARY: BENCH } });
    return await catalogue(7890);
  } finally {
    await stopService(7890, service);
  }
}

async function interrupted(ratio: number, progressive: boolean, port: number): Promise<CaseResult> {
  const tag = `${Math.round(ratio * 100)}-${progressive ? "on" : "off"}`;
  const paths = await setupCase(tag, progressive, port);
  let service = await startService(port, paths.cache);
  let before: Status = {};
  let persisted = 0;
  let checkpointFiles = 0;
  let checkpointManifest = 0;
  const screenshots: string[] = [];
  try {
    await withControlledSumatra(EXE, async (client, proc) => {
      const frame = await waitForFrame(proc.pid!);
      await client.request(ControlCommand.TestLibRescan, []);
      const deadline = Date.now() + 300000;
      let pollCount = 0;
      while (Date.now() < deadline) {
        const row = await status(client);
        if (pollCount % 50 === 0) {
          console.error(`${tag} poll #${pollCount}: total=${row.total} done=${row.done} native=${row.native} scanning=${row.scanning}`);
        }
        pollCount++;
        if (row.total > 0 && row.done >= Math.max(1, Math.ceil(row.total * ratio)) && row.done < row.total) {
          console.error(`${tag} HIT at poll #${pollCount}: total=${row.total} done=${row.done}`);
          before = row;
          proc.kill();
          await proc.exited;
          try {
            const shot = join(paths.shots, "before-close.png");
            if (captureWindowToPng(frame, shot)) screenshots.push(shot);
          } catch {}
          persisted = Number((await getJson(port, "/library?limit=1")).total || 0);
          const checkpoint = checkpointCounts(paths.cache);
          checkpointFiles = checkpoint.files;
          checkpointManifest = checkpoint.manifest;
          return;
        }
        await Bun.sleep(10);
      }
      throw new Error(`${tag}: interruption point was not reached`);
    }, ["-appdata", paths.appdata], { env: { ...process.env, SUMATRA_LIBRARY_CACHE_ROOT: paths.cache, SUMATRA_BENCH_LIBRARY: BENCH } });
  } finally {
    await stopService(port, service);
  }
  service = await startService(port, paths.cache);
  try {
    const restored = Number((await getJson(port, "/library?limit=1")).total || 0);
    let reopened: Status = {};
    let final: Status = {};
    await withControlledSumatra(EXE, async (client, proc) => {
      const frame = await waitForFrame(proc.pid!);
      const deadline = Date.now() + 300000;
      while (Date.now() < deadline) {
        const row = await status(client);
        if (!reopened.total && row.total > 0) reopened = row;
        if (reopened.total && !row.scanning && !row.native) {
          const visible = Number((await getJson(port, "/library?limit=1")).total || 0);
          if (row.visible !== visible) {
            await Bun.sleep(10);
            continue;
          }
          final = row;
          await Bun.sleep(300);
          const shot = join(paths.shots, "after-resume.png");
          if (!captureWindowToPng(frame, shot)) throw new Error(`${tag}: screenshot after resume failed`);
          screenshots.push(shot);
          return;
        }
        await Bun.sleep(10);
      }
      throw new Error(`${tag}: resumed scan did not finish`);
    }, ["-appdata", paths.appdata], { env: { ...process.env, SUMATRA_LIBRARY_CACHE_ROOT: paths.cache, SUMATRA_BENCH_LIBRARY: BENCH } });
    const finalCatalogue = await catalogue(port);
    const checkpointCleared = !existsSync(join(paths.cache, "library", "scan_checkpoint.json"));
    return { ratio, progressive, before, persisted, checkpointFiles, checkpointManifest, restored, reopened, final, catalogue: finalCatalogue, checkpointCleared, screenshots };
  } finally {
    await stopService(port, service);
  }
}

function comparable(value: any): string {
  const books = (value.books ?? []).map((book: any) => ({
    id: book.id,
    title: book.title,
    author: book.author,
    series: book.series,
    series_key: book.series_key,
    editions: (book.editions ?? []).map((edition: any) => ({ path: edition.path, ext: edition.ext, pages: edition.pages })).sort((a: any, b: any) => String(a.path).localeCompare(String(b.path))),
  }));
  return JSON.stringify({ total: value.total, books, series: value.series });
}

if (!existsSync(EXE) || !existsSync(BENCH) || !existsSync(PYTHON) || !existsSync(SOURCE_LIBRARY)) throw new Error("required runtime or real Library copy is missing");
await rm(BASE, { recursive: true, force: true });
await mkdir(LIBRARY, { recursive: true });
const copied = await prepareLibrary();
if (copied !== 18) throw new Error(`expected 18 real files, copied ${copied}`);
const baseline = await uninterrupted();
const cases = [await interrupted(0.1, true, 7891), await interrupted(0.5, false, 7892), await interrupted(0.9, true, 7893)];
const expected = comparable(baseline);
const result = { baseline, cases };
await writeFile(join(BASE, "results.json"), JSON.stringify(result, null, 2), "utf8");
for (const item of cases) {
  console.error(`${item.ratio}: before.done=${item.before.done} checkpointFiles=${item.checkpointFiles} persisted=${item.persisted}`);
  if (item.before.done <= 0 || item.checkpointFiles < item.before.done || item.persisted <= 0) throw new Error(`${item.ratio}: progress was not durable before close`);
  if (item.checkpointManifest !== item.before.total) throw new Error(`${item.ratio}: scan manifest is incomplete`);
  if (item.restored !== item.persisted) throw new Error(`${item.ratio}: restored ${item.restored}, expected ${item.persisted}`);
  if (item.reopened.done < item.checkpointFiles) throw new Error(`${item.ratio}: resumed from ${item.reopened.done}, expected at least ${item.checkpointFiles}`);
  if (item.final.traversals !== 1 || item.final.processed !== item.final.discovered) throw new Error(`${item.ratio}: resume was not one traversal`);
  if (item.final.full !== 0 || item.final.shape !== 0 || item.final.ocr !== 0) throw new Error(`${item.ratio}: identity work repeated`);
  if (!item.checkpointCleared) throw new Error(`${item.ratio}: checkpoint remains`);
  if (comparable(item.catalogue) !== expected) throw new Error(`${item.ratio}: final catalogue differs from uninterrupted scan`);
}
console.log(JSON.stringify({ cases: cases.map((item) => ({ ratio: item.ratio, progressive: item.progressive, before: item.before, persisted: item.persisted, checkpointFiles: item.checkpointFiles, checkpointManifest: item.checkpointManifest, restored: item.restored, reopened: item.reopened, final: item.final, checkpointCleared: item.checkpointCleared })), result: join(BASE, "results.json"), screenshots: cases.flatMap((item) => item.screenshots) }, null, 2));
