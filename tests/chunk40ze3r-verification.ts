import { existsSync } from "node:fs";
import { cp, mkdir, readFile, rm, writeFile } from "node:fs/promises";
import { join, resolve } from "node:path";
import { tmpdir } from "node:os";
import { spawn, type Subprocess } from "bun";
import { Database } from "bun:sqlite";
import { ControlCommand, withControlledSumatra } from "./control";
import { captureWindowToPng, waitForFrame } from "./win-automation";

const root = process.cwd();
const exe = join(root, "out", "dbg64", "SumatraPDF.exe");
const chatterbox = resolve(root, "..", "Chatterbox-TTS-Extended-main");
const python = join(chatterbox, ".venv-amd", "Scripts", "python.exe");
const baseline = join(tmpdir(), "SumatraPDF-tests", "chunk40zd", "uninterrupted", "cache");
const base = join(tmpdir(), "SumatraPDF-tests", "chunk40ze3r");

type Prepared = {
  cache: string;
  appdata: string;
  screenshot: string;
  marker: string;
  rejected: string;
  root: string;
};

async function startService(port: number, cache: string, libraryRoot: string): Promise<Subprocess> {
  const proc = spawn({ cmd: [python, "-m", "audiobook.library", "--port", String(port), "--root", libraryRoot], cwd: chatterbox, env: { ...Bun.env, SUMATRA_LIBRARY_CACHE_ROOT: cache, SUMATRA_TEST_NO_SWEEP: "1" }, stdout: "pipe", stderr: "pipe" });
  for (let i = 0; i < 200; i++) {
    try {
      if ((await fetch(`http://127.0.0.1:${port}/status`)).ok) return proc;
    } catch {}
    await Bun.sleep(50);
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

async function library(port: number): Promise<any> {
  const response = await fetch(`http://127.0.0.1:${port}/library?limit=5000`);
  if (!response.ok) throw new Error(`library HTTP ${response.status}`);
  return response.json();
}

async function prepare(name: string, port: number, sqliteCurrent: boolean): Promise<Prepared> {
  const caseRoot = join(base, name);
  const cache = join(caseRoot, "cache");
  const appdata = join(caseRoot, "appdata");
  const screenshots = join(caseRoot, "screenshots");
  await cp(baseline, cache, { recursive: true });
  await mkdir(appdata, { recursive: true });
  await mkdir(screenshots, { recursive: true });
  const indexPath = join(cache, "library", "library.json");
  const index = JSON.parse(await readFile(indexPath, "utf8"));
  const targetIndex = index.books.length ? 0 : -1;
  if (targetIndex < 0) throw new Error("book is missing");
  const target = structuredClone(index.books[targetIndex]);
  delete target.editions;
  const marker = sqliteCurrent ? "SQLITE CURRENT WINNER" : "JSON CURRENT WINNER";
  const rejected = sqliteCurrent ? "JSON STALE LOSER" : "SQLITE STALE LOSER";
  if (sqliteCurrent) {
    index.books.splice(targetIndex, 1);
    target.title = marker;
  } else {
    index.books[targetIndex].title = marker;
    index.generation = "e3r-json-current";
    target.title = rejected;
  }
  await writeFile(indexPath, JSON.stringify(index), "utf8");
  const libraryRoot = index.roots[0];
  const checkpoint = {
    version: 1,
    in_progress: true,
    roots: index.roots,
    scope: 2,
    generation: sqliteCurrent ? "e3r-sqlite-current" : "e3r-sqlite-stale",
    base_generation: sqliteCurrent ? index.generation : "e3r-json-stale",
    started: 1,
    updated: 1,
    done: 1
  };
  await writeFile(join(cache, "library", "scan_checkpoint.json"), JSON.stringify(checkpoint), "utf8");
  const dbPath = join(cache, "library", "scan_checkpoint.sqlite3");
  await rm(dbPath, { force: true });
  await rm(dbPath + "-wal", { force: true });
  await rm(dbPath + "-shm", { force: true });
  const db = new Database(dbPath, { create: true });
  try {
    db.exec("PRAGMA journal_mode=WAL");
    db.exec("PRAGMA synchronous=FULL");
    db.exec("CREATE TABLE manifest (path TEXT PRIMARY KEY, size INTEGER, mtime REAL, placeholder INTEGER)");
    db.exec("CREATE TABLE completed (path TEXT PRIMARY KEY, size INTEGER, mtime REAL, scan_json TEXT NOT NULL, kind TEXT NOT NULL, entry_json TEXT NOT NULL)");
    db.query("INSERT INTO completed(path,size,mtime,scan_json,kind,entry_json) VALUES(?,?,?,?,?,?)").run(target.path, target.size, target.mtime, JSON.stringify({ path: target.path, size: target.size, mtime: target.mtime }), "books", JSON.stringify(target));
  } finally {
    db.close();
  }
  await writeFile(join(appdata, "SumatraPDF-settings.txt"), `Audiobook [\n\tPythonExe = ${python}\n\tChatterboxDir = ${chatterbox}\n\tLibraryPort = ${port}\n\tLibraryRoots = ${libraryRoot}\n\tLibraryHome = true\n\tProgressiveLibraryScan = true\n]\nHomePage [\n\tHomePageViewMode = list\n]\n`, "utf8");
  return { cache, appdata, screenshot: join(screenshots, "library.png"), marker, rejected, root: libraryRoot };
}

async function runCase(name: string, port: number, sqliteCurrent: boolean): Promise<any> {
  const prepared = await prepare(name, port, sqliteCurrent);
  const service = await startService(port, prepared.cache, prepared.root);
  try {
    const before = await library(port);
    if (!(before.books ?? []).some((book: any) => book.title === prepared.marker)) throw new Error(`${name}: winning state was not loaded`);
    if ((before.books ?? []).some((book: any) => book.title === prepared.rejected)) throw new Error(`${name}: losing state was loaded`);
    let visible = 0;
    await withControlledSumatra(exe, async (client, proc) => {
      const frame = await waitForFrame(proc.pid!);
      const deadline = Date.now() + 10000;
      while (Date.now() < deadline) {
        const response = await client.request(ControlCommand.TestLibScanStatus, []);
        const match = String(response[1] ?? "").match(/visible=(\d+)/);
        visible = Number(match?.[1] ?? 0);
        if (visible > 0) {
          await Bun.sleep(100);
          if (!captureWindowToPng(frame, prepared.screenshot)) throw new Error(`${name}: GUI capture failed`);
          return;
        }
        await Bun.sleep(20);
      }
      throw new Error(`${name}: Library cards did not appear`);
    }, ["-appdata", prepared.appdata], { env: { ...Bun.env, SUMATRA_LIBRARY_CACHE_ROOT: prepared.cache, SUMATRA_TEST_NO_SWEEP: "1" } });
    return { marker: prepared.marker, rejected: prepared.rejected, visible, screenshot: prepared.screenshot };
  } finally {
    await stopService(port, service);
  }
}

if (![exe, python, join(baseline, "library", "library.json")].every(existsSync)) throw new Error("required production baseline is missing");
await rm(base, { recursive: true, force: true });
const caseA = await runCase("sqlite-current", 7901, true);
const caseB = await runCase("json-current", 7902, false);
console.log(JSON.stringify({ caseA, caseB }, null, 2));
