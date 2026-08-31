import { existsSync, mkdirSync, readFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, relative, resolve } from "node:path";
import { ROOT } from "./util";
import { ControlCommand, withControlledSumatra } from "./control";
import { fixtureEnv, CHATTERBOX, PYTHON, BENCH } from "./library-fixture";

const EXE = join(ROOT, "out", "dbg64", "SumatraPDF.exe");

const FIXTURE_BASE = join(tmpdir(), "SumatraPDF-tests", "library-chunk39");
const FIXTURE_ROOT = join(FIXTURE_BASE, "library");
const FIXTURE_CACHE = join(FIXTURE_BASE, "cache");
const FIXTURE_APPDATA = join(FIXTURE_BASE, "appdata");
const FIXTURE_PORT = 7880;

function assertChunk39Destination(path: string): void {
  const rel = relative(resolve(FIXTURE_BASE), resolve(path));
  if (rel === "" || (!rel.startsWith("..\\") && rel !== ".." && !rel.startsWith("../"))) return;
  throw new Error(`chunk39 test destination is outside the fixture root: ${path}`);
}

interface ServiceBook {
  id: string;
  title: string;
  series: string;
  series_key: string;
}
interface ServiceSeries {
  key: string;
  name: string;
  books: number;
  kind: string;
}
interface ServiceLibrary {
  total: number;
  books: ServiceBook[];
  series: ServiceSeries[];
}

async function fetchServiceLibrary(): Promise<ServiceLibrary> {
  const r = await fetch(`http://127.0.0.1:${FIXTURE_PORT}/library?limit=4096&sort=alpha`);
  if (!r.ok) throw new Error(`service /library failed: ${r.status}`);
  return (await r.json()) as ServiceLibrary;
}

function parseInt0(s: string | undefined): number {
  if (!s) return -1;
  const m = /-?\d+/.exec(s);
  return m ? parseInt(m[0], 10) : -1;
}

async function getScrollY(client: { request: (cmd: ControlCommand, args: unknown[]) => Promise<unknown[]> }): Promise<number> {
  const r = (await client.request(ControlCommand.TestLibScrollY, [])) as unknown[];
  return parseInt0(String(r[1] ?? ""));
}

async function setScrollY(client: { request: (cmd: ControlCommand, args: unknown[]) => Promise<unknown[]> }, y: number): Promise<void> {
  await client.request(ControlCommand.TestLibForceScrollY, [y]);
}

async function openBook(client: { request: (cmd: ControlCommand, args: unknown[]) => Promise<unknown[]> }, bookId: string): Promise<void> {
  await client.request(ControlCommand.TestLibOpenBook, [bookId]);
}

async function clickBack(client: { request: (cmd: ControlCommand, args: unknown[]) => Promise<unknown[]> }): Promise<void> {
  await client.request(ControlCommand.TestLibBack, []);
}

async function clickAllBooks(client: { request: (cmd: ControlCommand, args: unknown[]) => Promise<unknown[]> }): Promise<void> {
  await client.request(ControlCommand.TestLibAllBooks, []);
}

async function clickSeries(client: { request: (cmd: ControlCommand, args: unknown[]) => Promise<unknown[]> }, seriesKey: string): Promise<void> {
  await client.request(ControlCommand.TestLibSeries, [seriesKey]);
}

interface Chunk39Manifest {
  long_books: string[];
  other_books: string[];
  long_series: string;
  other_series: string;
}

function setupChunk39Fixture(): Chunk39Manifest {
  for (const path of [FIXTURE_ROOT, FIXTURE_CACHE, FIXTURE_APPDATA]) assertChunk39Destination(path);
  const run = Bun.spawnSync([
    PYTHON,
    join(ROOT, "tests", "chunk39-fixture.py"),
    FIXTURE_ROOT,
    CHATTERBOX,
    BENCH,
    FIXTURE_CACHE,
    FIXTURE_APPDATA,
    String(FIXTURE_PORT),
    FIXTURE_BASE,
  ], { cwd: ROOT, env: { ...process.env, SUMATRA_LIBRARY_CACHE_ROOT: FIXTURE_CACHE } });
  if (!run.success) {
    throw new Error(`chunk39 fixture setup failed: ${run.stdout}${run.stderr}`);
  }
  return JSON.parse(readFileSync(join(FIXTURE_ROOT, "manifest.json"), "utf8"));
}

async function startChunk39Service(): Promise<Bun.Subprocess> {
  if (!existsSync(join(FIXTURE_ROOT, "manifest.json"))) setupChunk39Fixture();
  const proc = Bun.spawn([
    PYTHON,
    "-m",
    "audiobook.library",
    "--port",
    String(FIXTURE_PORT),
    "--root",
    FIXTURE_ROOT,
  ], { cwd: CHATTERBOX, env: { ...process.env, SUMATRA_LIBRARY_CACHE_ROOT: FIXTURE_CACHE }, stdout: "pipe", stderr: "pipe" });
  for (let attempt = 0; attempt < 80; attempt++) {
    if (proc.exitCode !== null) {
      throw new Error(`chunk39 service exited ${proc.exitCode}: ${await new Response(proc.stderr).text()}`);
    }
    try {
      const response = await fetch(`http://127.0.0.1:${FIXTURE_PORT}/status`);
      if (response.ok) return proc;
    } catch {}
    await Bun.sleep(250);
  }
  proc.kill();
  throw new Error("chunk39 service did not start");
}

async function stopChunk39Service(proc: Bun.Subprocess): Promise<void> {
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

interface ScrollState {
  scrollY: number;
}

async function scrollOpenBack(
  client: { request: (cmd: ControlCommand, args: unknown[]) => Promise<unknown[]> },
  targetBookId: string,
  setTo: number,
  label: string,
  failures: string[],
): Promise<ScrollState> {
  await setScrollY(client, setTo);
  await new Promise((r) => setTimeout(r, 1500));
  const afterSet = await getScrollY(client);
  if (afterSet < setTo / 2) {
    failures.push(`[${label}] expected scrollY near ${setTo} after set, got ${afterSet} (list not long enough)`);
    return { scrollY: afterSet };
  }
  await openBook(client, targetBookId);
  await new Promise((r) => setTimeout(r, 2000));
  const afterOpen = await getScrollY(client);
  if (afterOpen !== 0) {
    failures.push(`[${label}] expected scrollY=0 in detail view, got ${afterOpen}`);
  }
  await clickBack(client);
  await new Promise((r) => setTimeout(r, 2000));
  const afterBack = await getScrollY(client);
  if (afterBack < afterSet / 2) {
    failures.push(`[${label}] expected scrollY near ${setTo} after Back, got ${afterBack} (pre-fix would be 0)`);
  }
  return { scrollY: afterBack };
}

export async function testit(): Promise<void> {
  const failures: string[] = [];

  if (existsSync(FIXTURE_APPDATA)) {
    rmSync(FIXTURE_APPDATA, { recursive: true, force: true });
  }
  mkdirSync(FIXTURE_APPDATA, { recursive: true });

  const manifest = setupChunk39Fixture();
  console.log("chunk39 fixture: long=" + manifest.long_books.length + " other=" + manifest.other_books.length);
  const service = await startChunk39Service();
  try {
    const svcLib = await fetchServiceLibrary();
    const longSeriesBooks = svcLib.books.filter((b) => b.series === manifest.long_series);
    const otherSeriesBooks = svcLib.books.filter((b) => b.series === manifest.other_series);
    if (longSeriesBooks.length < 10) throw new Error("long series too small: " + longSeriesBooks.length);
    if (otherSeriesBooks.length < 5) throw new Error("other series too small: " + otherSeriesBooks.length);

    const animorphsKey = longSeriesBooks[0].series_key;
    const goosebumpsKey = otherSeriesBooks[0].series_key;
    const animorphsTarget = longSeriesBooks[Math.floor(longSeriesBooks.length / 2)];
    const goosebumpsTarget = otherSeriesBooks[Math.floor(otherSeriesBooks.length / 2)];
    const allBooksTarget = svcLib.books[Math.floor(svcLib.books.length / 2)];

    console.log("series keys: animorphs=" + animorphsKey + " goosebumps=" + goosebumpsKey);
    console.log("targets: animorphs=" + animorphsTarget.id + " goosebumps=" + goosebumpsTarget.id + " allbooks=" + allBooksTarget.id);

    await withControlledSumatra(
      EXE,
      async (client) => {
        await new Promise((r) => setTimeout(r, 15_000));

        const initial = await getScrollY(client);
        console.log("initial scrollY=" + initial);
        if (initial !== 0) {
          failures.push(`expected initial scrollY=0, got ${initial}`);
        }

        console.log("=== Test 3: Animorphs scroll preservation (regression) ===");
        await clickSeries(client, animorphsKey);
        await new Promise((r) => setTimeout(r, 4000));
        const animorphsAfterNav = await getScrollY(client);
        if (animorphsAfterNav !== 0) {
          failures.push(`expected scrollY=0 after switching to Animorphs, got ${animorphsAfterNav}`);
        }
        await scrollOpenBack(client, animorphsTarget.id, 200, "animorphs", failures);

        console.log("=== Test 2: explicit navigation to another Series must not leak stale state ===");
        const animorphsBack = await getScrollY(client);
        console.log("animorphs scrollY after Back=" + animorphsBack);
        if (animorphsBack < 100) {
          failures.push(`expected Animorphs scrollY >= 100 before switching, got ${animorphsBack}`);
        }
        await clickSeries(client, goosebumpsKey);
        await new Promise((r) => setTimeout(r, 1500));
        const goosebumpsAfterNav = await getScrollY(client);
        console.log("goosebumps scrollY after navigation=" + goosebumpsAfterNav);
        if (goosebumpsAfterNav !== 0) {
          failures.push(`[unrelated] expected goosebumps scrollY=0 (no stale Animorphs state), got ${goosebumpsAfterNav}`);
        }
        await openBook(client, goosebumpsTarget.id);
        await new Promise((r) => setTimeout(r, 1500));
        await clickBack(client);
        await new Promise((r) => setTimeout(r, 1500));
        const goosebumpsAfterBack = await getScrollY(client);
        console.log("goosebumps scrollY after Back=" + goosebumpsAfterBack);
        if (goosebumpsAfterBack !== 0) {
          failures.push(`[unrelated] expected goosebumps scrollY=0 after Back (no stale Animorphs state), got ${goosebumpsAfterBack}`);
        }
        console.log("=== Test 2: return to Animorphs through normal sidebar navigation ===");
        await clickSeries(client, animorphsKey);
        await new Promise((r) => setTimeout(r, 1500));
        const animorphsReturned = await getScrollY(client);
        console.log("animorphs scrollY after return-by-sidebar=" + animorphsReturned);
        if (animorphsReturned !== 0) {
          failures.push(`[unrelated] expected animorphs scrollY=0 after normal sidebar return (no stale state), got ${animorphsReturned}`);
        }

        console.log("=== Test 1: All books scroll preservation ===");
        await clickAllBooks(client);
        await new Promise((r) => setTimeout(r, 1500));
        const allBooksAfterNav = await getScrollY(client);
        if (allBooksAfterNav !== 0) {
          failures.push(`expected scrollY=0 after switching to All books, got ${allBooksAfterNav}`);
        }
        await scrollOpenBack(client, allBooksTarget.id, 200, "allbooks", failures);
        const allBooksAfterBack = await getScrollY(client);
        if (allBooksAfterBack < 100) {
          failures.push(`expected All books scrollY >= 100 after Back, got ${allBooksAfterBack}`);
        }
        console.log("All books scrollY after Back=" + allBooksAfterBack);

        await client.request(ControlCommand.Quit);
      },
      ["-appdata", FIXTURE_APPDATA],
      { connectTimeoutMs: 60_000, window: { x: 100, y: 100, dx: 800, dy: 600 }, env: fixtureEnv() },
    );
  } finally {
    await stopChunk39Service(service);
  }

  if (failures.length > 0) {
    console.log("\n=== failures ===");
    for (const f of failures) console.log("  - " + f);
    throw new Error(`chunk39-back-to-library: ${failures.length} failure(s)`);
  }
  console.log("\nchunk39-back-to-library: ALL CHECKS PASSED");
}

if (import.meta.main) {
  await testit().catch((e) => {
    console.error(e);
    process.exit(1);
  });
}
