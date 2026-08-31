import { existsSync } from "node:fs";
import { copyFile, mkdir, readFile, readdir, rm, writeFile } from "node:fs/promises";
import { dirname, join, relative, resolve } from "node:path";
import { tmpdir } from "node:os";
import { spawn, type Subprocess } from "bun";
import { ControlCommand, type ControlClient, withControlledSumatra } from "./control";
import { captureWindowToPng, waitForFrame } from "./win-automation";

const ROOT = process.cwd();
const EXE = join(ROOT, "out", "dbg64", "SumatraPDF.exe");
const BENCH = join(ROOT, "out", "dbg64", "bench_library.exe");
const CHATTERBOX = resolve(ROOT, "..", "Chatterbox-TTS-Extended-main");
const PYTHON = join(CHATTERBOX, ".venv-amd", "Scripts", "python.exe");
const BASE = join(tmpdir(), "SumatraPDF-tests", "chunk40z");
const SOURCE_LIBRARY = resolve(ROOT, "out", "dbg64", "real-library-copy", "manga_novels");
const SLOW_EPUB = join(SOURCE_LIBRARY, "rezero", "Tappei Nagatsuki - Re ZERO - Starting Life in Another World - Volume 12 (epub).epub");
const CORRUPT_PDF = resolve(ROOT, "out", "dbg64", "pathological", "corrupt.pdf");
const LIBRARY = join(BASE, "library");
let unbrandedSource = "";

type Status = {
  scanning: number;
  native: number;
  done: number;
  total: number;
  itemDone: number;
  itemTotal: number;
  itemIndeterminate: number;
  itemAction: number;
  traversals: number;
  discovered: number;
  processed: number;
  visible: number;
  progressive: number;
  sweep: number;
};

type ModeResult = {
  name: string;
  statuses: Status[];
  finalStatus: Status;
  catalogue: unknown;
  snapshots: Record<string, Status>;
  screenshots: string[];
  actions: string[];
  activityFrames: string[];
  activityMoved: boolean;
};

function statusOf(raw: string): Status {
  const values: Record<string, number> = {};
  for (const match of raw.matchAll(/([A-Za-z]+)=(\d+)/g)) values[match[1]] = Number(match[2]);
  const required = ["scanning", "native", "done", "total", "itemDone", "itemTotal", "itemIndeterminate", "itemAction", "traversals", "discovered", "processed", "visible", "progressive", "sweep"];
  for (const key of required) if (!(key in values)) throw new Error(`missing ${key} in ${raw}`);
  return values as Status;
}

async function status(client: ControlClient): Promise<Status> {
  const response = await client.request(ControlCommand.TestLibScanStatus, []);
  return statusOf(String(response[1] ?? ""));
}

async function jsonGet(port: number, path: string): Promise<any> {
  const response = await fetch(`http://127.0.0.1:${port}${path}`);
  if (!response.ok) throw new Error(`${path}: HTTP ${response.status}`);
  return await response.json();
}

async function fullCatalogue(port: number): Promise<unknown> {
  const library = await jsonGet(port, "/library?limit=5000");
  const books = [];
  for (const row of library.books ?? []) {
    books.push(await jsonGet(port, `/book?id=${encodeURIComponent(row.id)}&meta=0`));
  }
  const series = await jsonGet(port, "/series");
  books.sort((a, b) => String(a.id).localeCompare(String(b.id)));
  return { total: library.total, books, series: series.series ?? [] };
}

async function startService(port: number, cache: string): Promise<Subprocess> {
  const proc = spawn({
    cmd: [PYTHON, "-m", "audiobook.library", "--port", String(port), "--root", LIBRARY],
    cwd: CHATTERBOX,
    env: { ...process.env, SUMATRA_LIBRARY_CACHE_ROOT: cache, SUMATRA_BENCH_LIBRARY: BENCH },
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

function nearest(statuses: Status[], ratio: number): Status {
  const active = statuses.filter((row) => row.total > 0);
  return active.reduce((best, row) => {
    const target = row.total * ratio;
    return Math.abs(row.done - target) < Math.abs(best.done - best.total * ratio) ? row : best;
  });
}

async function prepareLibrary(source: string, destination: string): Promise<number> {
  const names = ["Hitchhiker", "Restaurant", "Life, the Universe", "So Long", "Mostly Harmless", "Another Thing"];
  let copied = 0;
  async function walk(dir: string): Promise<void> {
    for (const entry of await readdir(dir, { withFileTypes: true })) {
      const path = join(dir, entry.name);
      if (entry.isDirectory()) {
        await walk(path);
      } else if (entry.isFile() && names.some((name) => entry.name.includes(name)) && !entry.name.endsWith(".sumatra")) {
        const target = join(destination, relative(source, path));
        await mkdir(dirname(target), { recursive: true });
        await copyFile(path, target);
        if (existsSync(path + ".sumatra")) await copyFile(path + ".sumatra", target + ".sumatra");
        if (!unbrandedSource && entry.name.toLowerCase().endsWith(".pdf")) unbrandedSource = target;
        copied++;
      }
    }
  }
  await walk(source);
  return copied;
}

async function makeUnbrandedCopy(): Promise<void> {
  const target = join(LIBRARY, "Unbranded page progress.pdf");
  await rm(target, { force: true });
  await rm(target + ".sumatra", { force: true });
  const code = "import fitz,sys; s=fitz.open(sys.argv[1]); d=fitz.open(); d.insert_pdf(s); d.set_metadata(s.metadata); d.save(sys.argv[2],garbage=4,deflate=True,no_new_id=True)";
  const result = Bun.spawnSync([PYTHON, "-c", code, unbrandedSource, target], { cwd: CHATTERBOX });
  if (result.exitCode !== 0) throw new Error(`could not create unbranded paged copy: ${result.stderr.toString()}`);
}

async function runMode(name: string, progressive: boolean, port: number): Promise<ModeResult> {
  const modeRoot = join(BASE, name);
  const cache = join(modeRoot, "cache");
  const appdata = join(modeRoot, "appdata");
  const shots = join(modeRoot, "screenshots");
  await mkdir(cache, { recursive: true });
  await mkdir(join(cache, "library"), { recursive: true });
  await mkdir(appdata, { recursive: true });
  await mkdir(shots, { recursive: true });
  await writeFile(join(cache, "library", "scan_scope.txt"), "2", "utf8");
  await writeFile(join(appdata, "SumatraPDF-settings.txt"), `Audiobook [\n\tPythonExe = ${PYTHON}\n\tChatterboxDir = ${CHATTERBOX}\n\tLibraryPort = ${port}\n\tLibraryRoots = ${LIBRARY}\n\tLibraryHome = true\n\tProgressiveLibraryScan = true\n]\nHomePage [\n\tHomePageViewMode = list\n]\n`, "utf8");
  await makeUnbrandedCopy();
  const service = await startService(port, cache);
  const screenshots: string[] = [];
  const actions: string[] = [];
  try {
    return await withControlledSumatra(EXE, async (client, proc) => {
      const frame = await waitForFrame(proc.pid!);
      if (!progressive) await client.request(ControlCommand.TestLibToggleProgressive, []);
      const before = await status(client);
      if (before.progressive !== (progressive ? 1 : 0)) throw new Error(`${name}: preference did not change`);
      await client.request(ControlCommand.TestLibRescan, []);
      const statuses: Status[] = [];
      const activityFrames: string[] = [];
      let activityDone = -1;
      let activityAt = 0;
      let activityFirst = "";
      let activityMoved = false;
      let sawScan = false;
      let phase = 0;
      const phaseRatios = [0.25, 0.5, 0.75];
      const deadline = Date.now() + 300000;
      while (Date.now() < deadline) {
        let row: Status;
        try {
          row = await status(client);
        } catch (error) {
          console.error(`${name}: process exit=${await proc.exited}`);
          throw error;
        }
        if (row.scanning || row.native) sawScan = true;
        if (statuses.length === 0 || JSON.stringify(statuses[statuses.length - 1]) !== JSON.stringify(row)) statuses.push(row);
        if (!activityMoved && row.total > 0 && row.itemIndeterminate === 1 && row.itemAction === 3) {
          if (row.done !== activityDone) {
            activityDone = row.done;
            activityAt = Date.now();
            activityFirst = join(shots, `activity-${row.done}-a.png`);
            if (!captureWindowToPng(frame, activityFirst)) throw new Error(`${name}: first activity screenshot failed`);
          } else if (Date.now() - activityAt >= 150) {
            const second = join(shots, `activity-${row.done}-b.png`);
            if (!captureWindowToPng(frame, second)) throw new Error(`${name}: second activity screenshot failed`);
            activityFrames.push(activityFirst, second);
            activityMoved = !(await readFile(activityFirst)).equals(await readFile(second));
          }
        }
        if (sawScan && row.total > 0 && phase < phaseRatios.length && row.done >= row.total * phaseRatios[phase]) {
          const tag = String(Math.round(phaseRatios[phase] * 100));
          const png = join(shots, `${tag}.png`);
          if (!captureWindowToPng(frame, png)) throw new Error(`${name}: screenshot ${tag} failed`);
          screenshots.push(png);
          await client.request(ControlCommand.Ping, []);
          actions.push(`${tag}:ping`);
          if (progressive && phase === 0) {
            await client.request(ControlCommand.TestLibForceScrollY, [200]);
            const scroll = await client.request(ControlCommand.TestLibScrollY, []);
            if (Number(scroll[1]) < 0) throw new Error("scroll did not respond");
            actions.push(`${tag}:scroll`);
            const visible = await jsonGet(port, "/library?limit=8");
            const first = visible.books?.[0];
            const firstSeries = visible.series?.find((s: any) => s.key)?.key;
            await client.request(ControlCommand.TestLibAllBooks, []);
            actions.push(`${tag}:all-books`);
            if (firstSeries) {
              await client.request(ControlCommand.TestLibSeries, [firstSeries]);
              await client.request(ControlCommand.TestLibAllBooks, []);
              actions.push(`${tag}:series`);
            }
            if (first?.id) {
              await client.request(ControlCommand.TestLibOpenBook, [first.id]);
              await Bun.sleep(150);
              await client.request(ControlCommand.TestLibBack, []);
              actions.push(`${tag}:open-back`);
            }
          }
          phase++;
        }
        if (sawScan && row.scanning === 0 && row.native === 0) break;
        await Bun.sleep(10);
      }
      if (!sawScan) throw new Error(`${name}: scan never became active`);
      let finalStatus = await status(client);
      if (finalStatus.scanning || finalStatus.native) throw new Error(`${name}: scan did not finish`);
      const catalogue = await fullCatalogue(port) as any;
      const visibleDeadline = Date.now() + 30000;
      while (finalStatus.visible !== catalogue.total && Date.now() < visibleDeadline) {
        await Bun.sleep(25);
        finalStatus = await status(client);
      }
      if (finalStatus.visible !== catalogue.total) throw new Error(`${name}: final catalogue was not presented`);
      const finalPng = join(shots, "complete.png");
      if (!captureWindowToPng(frame, finalPng)) throw new Error(`${name}: completion screenshot failed`);
      screenshots.push(finalPng);
      await Bun.sleep(300);
      const snapshots = {
        "25": nearest(statuses, 0.25),
        "50": nearest(statuses, 0.5),
        "75": nearest(statuses, 0.75),
      };
      return { name, statuses, finalStatus, catalogue, snapshots, screenshots, actions, activityFrames, activityMoved };
    }, ["-appdata", appdata, "-log", "-log-to-file", join(modeRoot, "sumatra.log")], { env: { ...process.env, SUMATRA_LIBRARY_CACHE_ROOT: cache, SUMATRA_BENCH_LIBRARY: BENCH } });
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
  const series = (value.series ?? []).map((row: any) => ({ ...row })).sort((a: any, b: any) => String(a.key).localeCompare(String(b.key)));
  return JSON.stringify({ total: value.total, books, series });
}

if (!existsSync(EXE) || !existsSync(BENCH) || !existsSync(PYTHON) || !existsSync(SOURCE_LIBRARY)) throw new Error("required runtime or real Library copy is missing");
await rm(BASE, { recursive: true, force: true });
await mkdir(BASE, { recursive: true });
const copied = await prepareLibrary(SOURCE_LIBRARY, LIBRARY);
if (copied !== 18) throw new Error(`expected 18 real Hitchhiker files, copied ${copied}`);
await copyFile(SLOW_EPUB, join(LIBRARY, "Slow EPUB.epub"));
await copyFile(CORRUPT_PDF, join(LIBRARY, "Corrupt book.pdf"));
const on = await runMode("progressive-on", true, 7880);
const off = await runMode("progressive-off", false, 7881);
const equal = comparable(on.catalogue) === comparable(off.catalogue);
const result = { on, off, equal };
await writeFile(join(BASE, "results.json"), JSON.stringify(result, null, 2), "utf8");
console.log(JSON.stringify({
  progressiveOn: { snapshots: on.snapshots, final: on.finalStatus, actions: on.actions },
  progressiveOff: { snapshots: off.snapshots, final: off.finalStatus, actions: off.actions },
  catalogueEqual: equal,
  activityMoved: { progressiveOn: on.activityMoved, progressiveOff: off.activityMoved },
  result: join(BASE, "results.json"),
  screenshots: [...on.screenshots, ...off.screenshots],
}, null, 2));
const progressiveDisplay = on.snapshots["25"].visible > 0 && on.snapshots["75"].visible >= on.snapshots["25"].visible && off.snapshots["75"].visible === 0 && off.finalStatus.visible > 0;
if (!equal || !progressiveDisplay || on.finalStatus.traversals !== 1 || off.finalStatus.traversals !== 1 || on.finalStatus.processed !== on.finalStatus.discovered || off.finalStatus.processed !== off.finalStatus.discovered) process.exit(1);
