import { copyFileSync, existsSync, mkdirSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { createHash } from "node:crypto";
import { dirname, join, normalize, resolve } from "node:path";
import { ControlCommand, withControlledSumatra } from "./control";
import { postMessage, waitForTopWindow, WM_COMMAND } from "./winapi";
import { cmdId, ROOT } from "./util";
import {
  BENCH,
  CHATTERBOX,
  FIXTURE_APPDATA,
  FIXTURE_CACHE,
  FIXTURE_ROOT,
  FIXTURE_BASE,
  PYTHON,
  fixtureEnv,
  setupLibraryFixture,
} from "./library-fixture";

type Manifest = ReturnType<typeof setupLibraryFixture>;
type Status = {
  roots: string[];
  books: number;
  documents: number;
  ignored: number;
  scanned: number | null;
  scanning: boolean;
  scope_current: boolean;
  error: string | null;
};

const EXE = join(ROOT, "out", "dbg64", "SumatraPDF.exe");
const WORK = join(FIXTURE_BASE, "chunk37x");
const RESULTS = join(WORK, "results.json");
const failures: string[] = [];

function check(name: string, value: boolean, detail = ""): void {
  console.log(`${value ? "PASS" : "FAIL"} ${name}${detail ? ` ${detail}` : ""}`);
  if (!value) failures.push(name);
}

function key(path: string): string {
  return normalize(resolve(path)).toLowerCase();
}

function samePaths(actual: string[], expected: string[]): boolean {
  const a = actual.map(key).sort();
  const e = expected.map(key).sort();
  return JSON.stringify(a) === JSON.stringify(e);
}

function within(path: string, root: string): boolean {
  const p = key(path);
  const r = key(root).replace(/[\\/]+$/, "") + "\\";
  return p === key(root) || p.startsWith(r);
}

function sha256(path: string): string {
  return createHash("sha256").update(readFileSync(path)).digest("hex");
}

function resetDir(path: string): void {
  rmSync(path, { recursive: true, force: true });
  mkdirSync(path, { recursive: true });
}

function writeSettings(appdata: string, port: number, roots: string, libraryHome = true): void {
  resetDir(appdata);
  const text = [
    "Audiobook [",
    `\tPythonExe = ${PYTHON}`,
    `\tChatterboxDir = ${CHATTERBOX}`,
    `\tLibraryPort = ${port}`,
    `\tLibraryRoots = ${roots}`,
    `\tLibraryHome = ${libraryHome ? "true" : "false"}`,
    "]",
    "",
  ].join("\n");
  writeFileSync(join(appdata, "SumatraPDF-settings.txt"), text, "utf8");
}

async function rootSelectionCase(label: string, configured: string): Promise<{ configured: string; roots: string[]; explicit: boolean }> {
  const base = join(WORK, label);
  const appdata = join(base, "appdata");
  writeSettings(appdata, 7990, configured, false);
  return await withControlledSumatra(EXE, async (client: any) => {
    const response = String((await client.request(ControlCommand.TestLibraryRoots, []))[1] ?? "");
    const lines = response.trim().split(/\r?\n/);
    const header = /explicit=(\d+) roots=(\d+)/.exec(lines[0] ?? "");
    if (!header) throw new Error(`invalid root response: ${response}`);
    const roots = lines.slice(1);
    check(`${label} returned count`, roots.length === Number(header[2]), response);
    return { configured, roots, explicit: header[1] === "1" };
  }, ["-appdata", appdata], { connectTimeoutMs: 60_000 });
}

async function jsonGet<T>(port: number, path: string): Promise<T> {
  const response = await fetch(`http://127.0.0.1:${port}${path}`);
  if (!response.ok) throw new Error(`${path} returned ${response.status}`);
  return await response.json() as T;
}

async function startService(port: number, cache: string, roots: string[]): Promise<Bun.Subprocess> {
  const args = [PYTHON, "-m", "audiobook.library", "--port", String(port)];
  for (const root of roots) args.push("--root", root);
  const proc = Bun.spawn(args, {
    cwd: CHATTERBOX,
    env: { ...process.env, SUMATRA_LIBRARY_CACHE_ROOT: cache },
    stdout: "pipe",
    stderr: "pipe",
    windowsHide: true,
  });
  for (let attempt = 0; attempt < 120; attempt++) {
    if (proc.exitCode !== null) {
      throw new Error(`service ${port} exited with ${proc.exitCode}: ${await new Response(proc.stderr).text()}`);
    }
    try {
      await jsonGet<Status>(port, "/status");
      return proc;
    } catch {}
    await Bun.sleep(250);
  }
  proc.kill();
  throw new Error(`service ${port} did not start`);
}

async function stopService(port: number, proc: Bun.Subprocess): Promise<void> {
  try {
    await fetch(`http://127.0.0.1:${port}/quit`, { method: "POST", body: "{}" });
  } catch {}
  const done = await Promise.race([proc.exited, Bun.sleep(10_000).then(() => -999)]);
  if (done === -999) {
    proc.kill();
    await proc.exited;
  }
}

function parseCount(reply: unknown): number {
  const match = /count=(\d+)/.exec(String(reply ?? ""));
  return match ? Number(match[1]) : -1;
}

async function loadCounts(client: any): Promise<{ starts: number; completes: number }> {
  const starts = parseCount((await client.request(ControlCommand.TestLoadCount, []))[1]);
  const completes = parseCount((await client.request(ControlCommand.TestLoadCompleteCount, []))[1]);
  return { starts, completes };
}

async function waitForPublication(port: number, client: any, previousScanned: number | null, timeoutMs = 240_000): Promise<{ status: Status; loads: { starts: number; completes: number } }> {
  const started = Date.now();
  let last: Status | null = null;
  while (Date.now() - started < timeoutMs) {
    last = await jsonGet<Status>(port, "/status");
    const loads = await loadCounts(client);
    const published = typeof last.scanned === "number" && last.scanned !== previousScanned;
    if (published && !last.scanning && loads.starts >= 2 && loads.completes === loads.starts) {
      await Bun.sleep(1000);
      return { status: last, loads };
    }
    await Bun.sleep(500);
  }
  throw new Error(`catalogue was not published: ${JSON.stringify(last)}`);
}

async function waitForStableLoad(client: any, timeoutMs = 60_000): Promise<{ starts: number; completes: number }> {
  const started = Date.now();
  let stable = 0;
  let prior = "";
  while (Date.now() - started < timeoutMs) {
    const loads = await loadCounts(client);
    const now = `${loads.starts}/${loads.completes}`;
    stable = now === prior && loads.starts > 0 && loads.starts === loads.completes ? stable + 1 : 0;
    prior = now;
    if (stable >= 4) return loads;
    await Bun.sleep(500);
  }
  throw new Error("model load did not settle");
}

function allIndexPaths(index: any): string[] {
  return ["books", "documents", "ignored"].flatMap((bucket) => (index[bucket] ?? []).flatMap((row: any) => row.editions?.length ? row.editions.map((edition: any) => edition.path) : [row.path])).filter(Boolean);
}

function indexAt(cache: string): any {
  return JSON.parse(readFileSync(join(cache, "library", "library.json"), "utf8"));
}

function identity(path: string, cache: string): { present: boolean; full: number; shape: number; fingerprint: string } {
  const run = Bun.spawnSync([BENCH, "readrec", path, cache], { cwd: ROOT, env: { ...process.env, SUMATRA_LIBRARY_CACHE_ROOT: cache } });
  const text = `${run.stdout}\n${run.stderr}`;
  const accepted = /accepted=(\d+)/.exec(text);
  const full = /full=(\d+)/.exec(text);
  const shape = /shape=(\d+)/.exec(text);
  const fingerprint = /fingerprint=([^\s]*)/.exec(text);
  return {
    present: accepted?.[1] === "1" && Boolean(fingerprint?.[1]),
    full: Number(full?.[1] ?? -1),
    shape: Number(shape?.[1] ?? -1),
    fingerprint: fingerprint?.[1] ?? "",
  };
}

async function rootCase(label: string, port: number, configured: string, serviceRoots: string[], expectedRoots: string[] | null, requiredRoots: string[]): Promise<any> {
  const base = join(WORK, label);
  const cache = join(base, "cache");
  const appdata = join(base, "appdata");
  resetDir(cache);
  writeSettings(appdata, port, configured);
  const service = await startService(port, cache, serviceRoots);
  try {
    return await withControlledSumatra(EXE, async (client: any) => {
      const result = await waitForPublication(port, client, null);
      const index = indexAt(cache);
      const paths = allIndexPaths(index);
      if (expectedRoots) check(`${label} roots`, samePaths(result.status.roots, expectedRoots), JSON.stringify(result.status.roots));
      for (const root of requiredRoots) check(`${label} files from ${root}`, paths.some((path) => within(path, root)));
      return { configured, roots: result.status.roots, paths, loads: result.loads, status: result.status };
    }, ["-appdata", appdata, "-log", "-log-to-file", join(base, "sumlog.txt")], { connectTimeoutMs: 60_000, env: { SUMATRA_LIBRARY_CACHE_ROOT: cache } });
  } finally {
    await stopService(port, service);
  }
}

function manifestFiles(manifest: Manifest): string[] {
  return [...manifest.books, ...manifest.no_text, manifest.epub, manifest.mobi, manifest.document, manifest.ignored];
}

function expectedBookPaths(manifest: Manifest): string[] {
  return [...manifest.books, ...manifest.no_text, manifest.epub, manifest.mobi];
}

function classifierRows(index: any, files: string[]): any[] {
  return files.map((path) => {
    for (const bucket of ["books", "documents", "ignored"]) {
      const found = (index[bucket] ?? []).find((row: any) => key(row.path) === key(path) || (row.editions ?? []).some((edition: any) => key(edition.path) === key(path)));
      if (found) return { path, classification: bucket.slice(0, -1), fingerprintPresent: identity(path, FIXTURE_CACHE).present };
    }
    return { path, classification: "missing", fingerprintPresent: identity(path, FIXTURE_CACHE).present };
  });
}

async function freshAndMutation(manifest: Manifest, port: number): Promise<any> {
  const allFiles = manifestFiles(manifest);
  const expectedBooks = expectedBookPaths(manifest);
  const expected = { books: expectedBooks.length, branded: expectedBooks.length - manifest.no_text.length, unbranded: manifest.no_text.length, documents: 1, ignored: 1 };
  check("canonical files present", allFiles.every(existsSync));
  const beforeStates = expectedBooks.map((path) => ({ path, ...identity(path, FIXTURE_CACHE) }));
  check("canonical branded expected", beforeStates.filter((row) => row.present).length === expected.branded);
  check("canonical unbranded expected", beforeStates.filter((row) => !row.present).length === expected.unbranded);

  const startupAppdata = join(WORK, "startup-appdata");
  writeSettings(startupAppdata, port, FIXTURE_ROOT);
  const startupService = await startService(port, FIXTURE_CACHE, [FIXTURE_ROOT]);
  let startup: any;
  try {
    startup = await withControlledSumatra(EXE, async (client: any) => {
      const initial = await jsonGet<Status>(port, "/status");
      const published = initial.scope_current ? null : await waitForPublication(port, client, initial.scanned);
      const loads = published?.loads ?? await waitForStableLoad(client);
      return { initial, final: await jsonGet<Status>(port, "/status"), loads };
    }, ["-appdata", startupAppdata, "-log", "-log-to-file", join(WORK, "startup-sumlog.txt")], { connectTimeoutMs: 60_000, env: fixtureEnv() });
  } finally {
    await stopService(port, startupService);
  }

  writeFileSync(join(FIXTURE_CACHE, "library", "scan_scope.txt"), "2", "utf8");

  const freshAppdata = join(WORK, "fresh-appdata");
  writeSettings(freshAppdata, port, FIXTURE_ROOT);
  const beforeScanHashes = {
    known: sha256(manifest.books[0]),
    noText: sha256(manifest.no_text[0]),
    document: sha256(manifest.document),
    ignored: sha256(manifest.ignored),
  };
  const service = await startService(port, FIXTURE_CACHE, [FIXTURE_ROOT]);
  try {
    return await withControlledSumatra(EXE, async (client: any, proc: any) => {
      const initialStatus = await jsonGet<Status>(port, "/status");
      const initialLoads = await waitForStableLoad(client);
      const initialIndex = indexAt(FIXTURE_CACHE);
      const rows = classifierRows(initialIndex, allFiles);
      const actualBooks = initialIndex.books.length;
      const actualBranded = rows.filter((row) => row.classification === "book" && row.fingerprintPresent).length;
      const actualUnbranded = rows.filter((row) => row.classification === "book" && !row.fingerprintPresent).length;
      const actualDocuments = initialIndex.documents.length;
      const actualIgnored = initialIndex.ignored.length;
      check("fresh books exact", actualBooks === expected.books, `${actualBooks}/${expected.books}`);
      check("fresh branded exact", actualBranded === expected.branded, `${actualBranded}/${expected.branded}`);
      check("fresh unbranded exact", actualUnbranded === expected.unbranded, `${actualUnbranded}/${expected.unbranded}`);
      check("fresh documents exact", actualDocuments === expected.documents, `${actualDocuments}/${expected.documents}`);
      check("fresh ignored exact", actualIgnored === expected.ignored, `${actualIgnored}/${expected.ignored}`);
      for (const path of manifest.no_text) {
        const row = rows.find((candidate) => key(candidate.path) === key(path));
        check(`${path} visible`, row?.classification === "book");
        check(`${path} unbranded`, row?.fingerprintPresent === false);
      }
      const brandedWork = rows.filter((row) => row.classification === "book" && row.fingerprintPresent).map((row) => identity(row.path, FIXTURE_CACHE));
      const full = brandedWork.reduce((sum, row) => sum + row.full, 0);
      const shape = brandedWork.reduce((sum, row) => sum + row.shape, 0);
      check("fresh branded full work zero", full === 0, String(full));
      check("fresh shape work zero", shape === 0, String(shape));

      const newText = join(FIXTURE_ROOT, "Books", "New Arrival.pdf");
      mkdirSync(dirname(newText), { recursive: true });
      copyFileSync(manifest.new_text, newText);
      const newBefore = sha256(newText);
      const firstScanned = initialStatus.scanned;
      const hwnd = await waitForTopWindow(proc.pid, "SUMATRA_PDF_FRAME", 30_000);
      if (!hwnd) throw new Error("SumatraPDF frame was not found");
      if (!postMessage(hwnd, WM_COMMAND, cmdId("CmdLibraryRescan"), 0)) throw new Error("first rescan command failed");
      const first = await waitForPublication(port, client, firstScanned);
      const afterFirstHashes = {
        known: sha256(manifest.books[0]),
        noText: sha256(manifest.no_text[0]),
        document: sha256(manifest.document),
        ignored: sha256(manifest.ignored),
      };
      const newAfterFirst = sha256(newText);
      if (!postMessage(hwnd, WM_COMMAND, cmdId("CmdLibraryRescan"), 0)) throw new Error("second rescan command failed");
      const second = await waitForPublication(port, client, first.status.scanned);
      const newAfterSecond = sha256(newText);
      check("known branded unchanged during scan", beforeScanHashes.known === afterFirstHashes.known);
      check("no-text unchanged during scan", beforeScanHashes.noText === afterFirstHashes.noText);
      check("document unchanged during scan", beforeScanHashes.document === afterFirstHashes.document);
      check("ignored unchanged during scan", beforeScanHashes.ignored === afterFirstHashes.ignored);
      check("new text changed on first scan", newBefore !== newAfterFirst);
      check("new text unchanged on second scan", newAfterFirst === newAfterSecond);
      return {
        expected,
        actual: { books: actualBooks, branded: actualBranded, unbranded: actualUnbranded, documents: actualDocuments, ignored: actualIgnored },
        rows,
        full,
        shape,
        initialStatus,
        initialLoads,
        startup,
        hashes: {
          known: { before: beforeScanHashes.known, after: afterFirstHashes.known, changed: beforeScanHashes.known !== afterFirstHashes.known },
          noText: { before: beforeScanHashes.noText, after: afterFirstHashes.noText, changed: beforeScanHashes.noText !== afterFirstHashes.noText },
          document: { before: beforeScanHashes.document, after: afterFirstHashes.document, changed: beforeScanHashes.document !== afterFirstHashes.document },
          ignored: { before: beforeScanHashes.ignored, after: afterFirstHashes.ignored, changed: beforeScanHashes.ignored !== afterFirstHashes.ignored },
          newText: { before: newBefore, afterFirst: newAfterFirst, changedFirst: newBefore !== newAfterFirst, afterSecond: newAfterSecond, changedSecond: newAfterFirst !== newAfterSecond },
        },
        firstLoads: first.loads,
        secondLoads: second.loads,
      };
    }, ["-appdata", freshAppdata, "-log", "-log-to-file", join(WORK, "fresh-sumlog.txt")], { connectTimeoutMs: 60_000, env: fixtureEnv() });
  } finally {
    await stopService(port, service);
  }
}

async function main(): Promise<void> {
  resetDir(WORK);
  let manifest = setupLibraryFixture();
  rmSync(FIXTURE_CACHE, { recursive: true, force: true });
  mkdirSync(FIXTURE_CACHE, { recursive: true });
  const a = await rootCase("root-a", 7971, FIXTURE_ROOT, [FIXTURE_ROOT], [FIXTURE_ROOT], [FIXTURE_ROOT]);

  const rootB = join(WORK, "root-b-files");
  resetDir(rootB);
  const rootBFile = join(rootB, "Second Root.pdf");
  copyFileSync(manifest.books[0], rootBFile);
  const b = await rootCase("root-b", 7972, `${FIXTURE_ROOT};${rootB}`, [FIXTURE_ROOT, rootB], [FIXTURE_ROOT, rootB], [FIXTURE_ROOT, rootB]);

  const c = await rootSelectionCase("root-c", "");
  check("root-c automatic roots nonempty", c.roots.length > 0, JSON.stringify(c.roots));
  check("root-c is automatic", !c.explicit);
  const invalid = join(WORK, "does-not-exist");
  const d = await rootSelectionCase("root-d", invalid);
  check("root-d fallback roots", samePaths(d.roots, c.roots), JSON.stringify(d.roots));
  check("root-d is automatic", !d.explicit);
  check("root-d invalid explicit absent", !d.roots.some((root: string) => key(root) === key(invalid)));

  check("root-a no automatic roots", samePaths(a.roots, [FIXTURE_ROOT]));
  check("root-a no personal files", a.paths.every((path: string) => within(path, FIXTURE_ROOT)));
  check("root-b no automatic roots", samePaths(b.roots, [FIXTURE_ROOT, rootB]));
  check("root-b no personal files", b.paths.every((path: string) => within(path, FIXTURE_ROOT) || within(path, rootB)));

  manifest = setupLibraryFixture();
  const fresh = await freshAndMutation(manifest, 7975);
  const result = { generatedAt: new Date().toISOString(), roots: { a, b, c, d }, fresh, failures };
  writeFileSync(RESULTS, JSON.stringify(result, null, 2), "utf8");
  console.log(`RESULTS ${RESULTS}`);
  if (failures.length) throw new Error(`${failures.length} verification check(s) failed`);
}

await main().catch((error) => {
  console.error(error);
  process.exit(1);
});
