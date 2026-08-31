// chunk 31R: end-to-end regression test for the catalogue-only
// reload path. Drives the REAL production handlers the UI uses
// (PostPartition, PostBookEdit) via the control pipe, and checks:
//   - the in-memory model agrees with the service catalogue after
//     every operation
//   - catalogue-only reloads do zero embedded-file reads
//   - the stale-field bug is gone (fresh catalogue keys are never
//     overwritten by old keys / seriesKey)
//   - the metadata-edit path does not grow the load count
//
// The test uses the existing chunk31-appdata corpus (231 books).
// Picks books whose initial service state is unambiguous so the
// comparisons are simple:
//   - "The Visitor" (id=3bef779edbebc0bb) is in series:animorphs
//     with full keys "|parent:animorphs|series:animorphs|"
//   - "Andalite Chronicles" (id in the chronicles series) is used
//     to test stale-snapshot regression.

import { copyFileSync, existsSync, mkdirSync, rmSync } from "node:fs";
import { join } from "node:path";
import { ROOT } from "./util";
import { ControlCommand, withControlledSumatra } from "./control";
import { FIXTURE_APPDATA, FIXTURE_PORT, fixtureEnv, setupLibraryFixture, startLibraryFixtureService, stopLibraryFixtureService } from "./library-fixture";

const CORPUS_DIR = join(ROOT, "out", "dbg64");
const APPDATA_DIR = FIXTURE_APPDATA;
const SERVICE_PORT = FIXTURE_PORT;

function setupAppDir(): string {
  if (existsSync(APPDATA_DIR)) {
    rmSync(APPDATA_DIR, { recursive: true, force: true });
  }
  mkdirSync(APPDATA_DIR, { recursive: true });
  for (const f of [
    "SumatraLibrary.txt",
    "SumatraCovers.txt",
    "SumatraLibraryThumbs.txt",
    "SumatraLibraryFingerprints.txt",
    "SumatraLibraryFingerprints.dat",
    "SumatraPDF-settings.txt",
  ]) {
    const src = join(CORPUS_DIR, f);
    if (existsSync(src)) {
      copyFileSync(src, join(APPDATA_DIR, f));
    }
  }
  return APPDATA_DIR;
}

interface LoadPerf {
  adoptMs: number;
  syncMs: number;
  reads: number;
  writes: number;
  pdfOpen: number;
  books: number;
  skippedEmbedded: number;
}

function parsePerf(reply: string): LoadPerf {
  const out: LoadPerf = { adoptMs: 0, syncMs: 0, reads: 0, writes: 0, pdfOpen: 0, books: 0, skippedEmbedded: 0 };
  const m = /^OK\s+(.*)$/.exec(reply.trim());
  if (!m) return out;
  for (const part of m[1]!.split(/\s+/)) {
    const eq = part.indexOf("=");
    if (eq <= 0) continue;
    const k = part.substring(0, eq);
    const v = parseInt(part.substring(eq + 1), 10);
    if (k === "adoptMs") out.adoptMs = v;
    else if (k === "syncMs") out.syncMs = v;
    else if (k === "reads") out.reads = v;
    else if (k === "writes") out.writes = v;
    else if (k === "pdfOpen") out.pdfOpen = v;
    else if (k === "books") out.books = v;
    else if (k === "skippedEmbedded") out.skippedEmbedded = v;
  }
  return out;
}

interface BookState {
  id: string;
  title: string;
  series: string;
  seriesSource: string;
  seriesKey: string;
  seriesParent: string;
  keys: string;
  genre: string;
  subgenre: string;
  tags: string;
  path: string;
}

function parseBookState(reply: string): BookState | null {
  const m = /^OK\s+(.*)$/.exec(reply.trim());
  if (!m) return null;
  const out: Partial<BookState> = {};
  for (const part of m[1]!.split(/\s+/)) {
    const eq = part.indexOf("=");
    if (eq <= 0) continue;
    out[part.substring(0, eq) as keyof BookState] = part.substring(eq + 1);
  }
  return out as BookState;
}

function parseLoadCount(reply: string): number {
  const m = /count=(\d+)/.exec(reply);
  return m ? parseInt(m[1], 10) : -1;
}

interface ServiceBook {
  id: string;
  title?: string;
  series?: string | null;
  series_key?: string | null;
  series_keys?: string;
  series_source?: string;
  series_parent?: string;
  partitions?: string[];
  genre?: string;
  subgenre?: string;
  tags?: string[];
}

interface ServiceLibrary {
  total: number;
  books: ServiceBook[];
  series: any[];
}

async function fetchServiceLibrary(): Promise<ServiceLibrary> {
  const r = await fetch(`http://127.0.0.1:${SERVICE_PORT}/library?limit=4096&sort=alpha`);
  if (!r.ok) throw new Error(`service /library failed: ${r.status}`);
  return (await r.json()) as ServiceLibrary;
}

function bookById(lib: ServiceLibrary, id: string): ServiceBook | undefined {
  return lib.books.find((b) => b.id === id);
}

// Normalize the service's `series_keys` (a joined string like
// "|parent:animorphs|series:animorphs|") and the in-memory model's
// `keys` (same format) for a direct compare.
function stateMatchesService(state: BookState, svc: ServiceBook | undefined): boolean {
  if (!svc) return false;
  const svcKeys = svc.series_keys || "";
  if (state.keys !== svcKeys) {
    console.log(`  keys mismatch: native='${state.keys}' service='${svcKeys}'`);
    return false;
  }
  if ((svc.series_key || "") !== state.seriesKey) {
    console.log(`  seriesKey mismatch: native='${state.seriesKey}' service='${svc.series_key || ""}'`);
    return false;
  }
  return true;
}

async function getLoadCount(
  client: { request: (cmd: ControlCommand, args: unknown[]) => Promise<unknown[]> },
): Promise<number> {
  const r = (await client.request(ControlCommand.TestLoadCount, [])) as unknown[];
  return parseLoadCount(String(r[1] ?? ""));
}

async function getCompleteCount(
  client: { request: (cmd: ControlCommand, args: unknown[]) => Promise<unknown[]> },
): Promise<number> {
  const r = (await client.request(ControlCommand.TestLoadCompleteCount, [])) as unknown[];
  return parseLoadCount(String(r[1] ?? ""));
}

async function getPerf(
  client: { request: (cmd: ControlCommand, args: unknown[]) => Promise<unknown[]> },
): Promise<LoadPerf> {
  const r = (await client.request(ControlCommand.TestLastLoadPerf, [])) as unknown[];
  return parsePerf(String(r[1] ?? ""));
}

async function getBookState(
  client: { request: (cmd: ControlCommand, args: unknown[]) => Promise<unknown[]> },
  bookId: string,
): Promise<BookState | null> {
  const r = (await client.request(ControlCommand.TestBookState, [bookId])) as unknown[];
  return parseBookState(String(r[1] ?? ""));
}

// Poll the load counter until it changes AND the complete counter
// catches up. Without the complete counter, a slow adopt loop from
// a previous load (e.g. the initial Full load on a 231-book library)
// can finish AFTER the catalogue-only load the test just triggered,
// overwriting the snapshot the test is about to read. The complete
// counter is incremented at the very end of LoadModelThread, after
// the snapshot is written.
async function waitForLoadSettle(
  client: { request: (cmd: ControlCommand, args: unknown[]) => Promise<unknown[]> },
  before: number,
  extraMs = 500,
): Promise<void> {
  const deadline = Date.now() + 60_000;
  while (Date.now() < deadline) {
    const c = await getLoadCount(client);
    if (c > before) {
      const target = c;
      const dline = Date.now() + 60_000;
      while (Date.now() < dline) {
        const done = await getCompleteCount(client);
        if (done >= target) {
          await new Promise((r) => setTimeout(r, extraMs));
          return;
        }
        await new Promise((r) => setTimeout(r, 50));
      }
      throw new Error("timed out waiting for LoadModelThread to complete");
    }
    await new Promise((r) => setTimeout(r, 50));
  }
  throw new Error("timed out waiting for new LoadModelThread start");
}

async function runPartition(
  client: { request: (cmd: ControlCommand, args: unknown[]) => Promise<unknown[]> },
  url: string,
  body: string,
): Promise<{ before: number; after: number; wallMs: number; perf: LoadPerf }> {
  const before = await getLoadCount(client);
  const start = Date.now();
  await client.request(ControlCommand.TestTriggerPartition, [url, body]);
  await waitForLoadSettle(client, before);
  const after = await getLoadCount(client);
  const perf = await getPerf(client);
  return { before, after, wallMs: Date.now() - start, perf };
}

async function runBookEdit(
  client: { request: (cmd: ControlCommand, args: unknown[]) => Promise<unknown[]> },
  body: string,
  bookId: string,
): Promise<{ before: number; after: number; wallMs: number }> {
  const before = await getLoadCount(client);
  const start = Date.now();
  await client.request(ControlCommand.TestTriggerBookEdit, [body, bookId]);
  // MetadataEditThread persists one book, not a full reload. Give
  // the targeted sidecar write a moment to complete.
  await new Promise((r) => setTimeout(r, 1500));
  const after = await getLoadCount(client);
  return { before, after, wallMs: Date.now() - start };
}

async function runUserFieldEdit(
  client: { request: (cmd: ControlCommand, args: unknown[]) => Promise<unknown[]> },
  bookId: string,
  field: string,
  value: string,
  exactKey = "",
): Promise<{ before: number; after: number; wallMs: number }> {
  const before = await getLoadCount(client);
  const start = Date.now();
  await client.request(ControlCommand.TestTriggerUserFieldEdit, [bookId, field, value, exactKey]);
  await new Promise((r) => setTimeout(r, 1500));
  return { before, after: await getLoadCount(client), wallMs: Date.now() - start };
}

// Cleanup any test partition the test may have created earlier.
async function cleanupTestPartition(name: string): Promise<void> {
  // the service's /partition/new returns the key; we don't have it
  // across runs, so brute-force: list partitions, delete any whose
  // name matches
  try {
    const r = await fetch(`http://127.0.0.1:${SERVICE_PORT}/partitions`);
    if (!r.ok) return;
    const j: any = await r.json();
    const list: any[] = (j?.partitions || j?.items || []) as any[];
    for (const p of list) {
      if (p?.name === name && p?.key) {
        await fetch(`http://127.0.0.1:${SERVICE_PORT}/partition/delete`, {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ key: p.key }),
        });
      }
    }
  } catch {
    // service may not be up; nothing to do
  }
}

export async function testit(): Promise<void> {
  setupLibraryFixture();
  const appDir = APPDATA_DIR;
  console.log(`chunk31R-user-actions: appdata=${appDir}`);

  const failures: string[] = [];
  const warnings: string[] = [];
  const TEST_PARTITION_NAME = "Chunk31R Shelf";

  const service = await startLibraryFixtureService();
  try {
    await withControlledSumatra(
    join(ROOT, "out", "dbg64", "SumatraPDF.exe"),
    async (client) => {
      // give the initial LoadModelThread time to settle
      await new Promise((r) => setTimeout(r, 6_000));
      const initialCount = await getLoadCount(client);
      console.log(`initial LoadModelThread count: ${initialCount}`);

      const svcLib = await fetchServiceLibrary();
      const direct = new Map<string, ServiceBook[]>();
      for (const book of svcLib.books) {
        if (!book.series_key) continue;
        direct.set(book.series_key, [...(direct.get(book.series_key) || []), book]);
      }
      const actionRows = svcLib.series.filter((row: any) => row.kind === "series" && (direct.get(row.key) || []).length >= 2);
      if (actionRows.length < 2) throw new Error("fixture must provide two populated Series rows");
      const targetSeries = actionRows[0];
      const childSeries = actionRows[1];
      const targetSeriesKey = String(targetSeries.key);
      const childSeriesKey = String(childSeries.key);
      // Pick a book that IS directly in series:animorphs (so Test
      // B's Series->partition actually changes its keys) and that
      // has not been touched by previous test runs. "The Visitor" is
      // contaminated by /book/edit history; we look for a clean book
      // in animorphs.
      const animorphsBook = direct.get(targetSeriesKey)![0];
      const partitionBookId = animorphsBook.id;
      console.log(
        `target book: id=${partitionBookId} path='${animorphsBook?.path}' series_key='${animorphsBook?.series_key}'` +
          ` series_keys='${animorphsBook?.series_keys}'`,
      );

      // The chronicles book (in series:chronicles) for the
      // Series->Series Test A state check.
      const chroniclesBook = direct.get(childSeriesKey)![0];
      const targetBookId = chroniclesBook.id;
      if (chroniclesBook) {
        console.log(
          `chronicles book: id=${chroniclesBook.id} series_keys='${chroniclesBook.series_keys}'`,
        );
      }

      await cleanupTestPartition(TEST_PARTITION_NAME);

      // ───────────────────────────────────────────────────────────────
      // Test A: Series -> Series (Chronicles under Animorphs)
      // The chunk 31R check is the COUNTERS: catalogue-only mode
      // should give reads=0, skippedEmbedded=1, and exactly 1 new
      // LoadModelThread invocation. The in-memory state of the
      // chronicles book should be the fresh catalogue value
      // (parent:animorphs added), not a stale snapshot.
      // ───────────────────────────────────────────────────────────────
      console.log("\n=== Test A: Series -> Series (Chronicles under Animorphs) ===");
      const aBeforeSvc = await fetchServiceLibrary();
      const aChroniclesBefore = bookById(aBeforeSvc, chroniclesBook?.id || "");
      console.log(`chronicles before: series_keys='${aChroniclesBefore?.series_keys}'`);
      const aResult = await runPartition(
        client,
        "/series/parent",
        JSON.stringify({ row: childSeriesKey, parent: targetSeriesKey }),
      );
      console.log(
        `[Series->Series] wall=${aResult.wallMs}ms loads ${aResult.before}->${aResult.after}` +
          ` reads=${aResult.perf.reads} writes=${aResult.perf.writes}` +
          ` adoptMs=${aResult.perf.adoptMs} syncMs=${aResult.perf.syncMs}` +
          ` pdfOpen=${aResult.perf.pdfOpen} skippedEmbedded=${aResult.perf.skippedEmbedded}`,
      );
      if (aResult.after - aResult.before !== 1) {
        failures.push(`Test A: expected 1 new LoadModelThread, got ${aResult.after - aResult.before}`);
      }
      if (aResult.perf.reads !== 0) {
        failures.push(`Test A: expected 0 embedded reads, got ${aResult.perf.reads}`);
      }
      if (aResult.perf.writes !== 0 || aResult.perf.pdfOpen !== 0) {
        failures.push(`Test A: expected writes=0 pdfOpen=0, got writes=${aResult.perf.writes} pdfOpen=${aResult.perf.pdfOpen}`);
      }
      if (aResult.perf.skippedEmbedded !== 1) {
        failures.push(`Test A: expected skippedEmbedded=1, got ${aResult.perf.skippedEmbedded}`);
      }

      const aSvc = await fetchServiceLibrary();
      const aSvcChronicles = bookById(aSvc, chroniclesBook?.id || "");
      console.log(`chronicles service after: series_key='${aSvcChronicles?.series_key}' series_keys='${aSvcChronicles?.series_keys}'`);
      if (aSvcChronicles && chroniclesBook) {
        // After the move, the chronicles book should be under
        // animorphs. Its series_keys should now include
        // parent:animorphs.
        if (!aSvcChronicles.series_keys?.includes(targetSeriesKey)) {
          failures.push(
            `Test A: child book service keys did not include '${targetSeriesKey}' after move: '${aSvcChronicles.series_keys}'`,
          );
        }
        if (aSvcChronicles.series_keys === chroniclesBook.series_keys) {
          warnings.push(
            `Test A: chronicles book service keys did not change after move (still '${aSvcChronicles.series_keys}')`,
          );
        }
      }
      if (chroniclesBook) {
        const aInMem = await getBookState(client, chroniclesBook.id);
        console.log(`chronicles in-mem after: seriesKey='${aInMem?.seriesKey}' keys='${aInMem?.keys}'`);
        if (aInMem && aSvcChronicles && !stateMatchesService(aInMem, aSvcChronicles)) {
          failures.push("Test A: in-memory model does not match service after Series->Series");
        }
      }

      // Restore chronicles to top-level
      await runPartition(client, "/series/parent", JSON.stringify({ row: childSeriesKey, parent: "" }));

      // ───────────────────────────────────────────────────────────────
      // Test B: Series -> partition (Animorphs into a test partition)
      // Use the megamorphs book to verify the partition key is added
      // to its keys.
      // ───────────────────────────────────────────────────────────────
      console.log("\n=== Test B: Series -> partition (Animorphs into test partition) ===");
      const createResp = await fetch(`http://127.0.0.1:${SERVICE_PORT}/partition/new`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ name: TEST_PARTITION_NAME }),
      });
      const createJson: any = await createResp.json();
      const partitionKey = createJson?.key || "";
      if (!partitionKey) {
        throw new Error(`Test B fixture creation failed: ${JSON.stringify(createJson)}`);
      } else {
        console.log(`created partition: ${partitionKey}`);
      }

      const bBeforeSvc = await fetchServiceLibrary();
      const bTargetBefore = bookById(bBeforeSvc, partitionBookId);
      console.log(`animorphs before: series_keys='${bTargetBefore?.series_keys}'`);
      const bResult = await runPartition(
        client,
        "/partition/assign",
        JSON.stringify({ key: partitionKey, row: targetSeriesKey }),
      );
      console.log(
        `[Series->partition] wall=${bResult.wallMs}ms loads ${bResult.before}->${bResult.after}` +
          ` reads=${bResult.perf.reads} writes=${bResult.perf.writes}` +
          ` adoptMs=${bResult.perf.adoptMs} syncMs=${bResult.perf.syncMs}` +
          ` pdfOpen=${bResult.perf.pdfOpen} skippedEmbedded=${bResult.perf.skippedEmbedded}`,
      );
      if (bResult.after - bResult.before !== 1) {
        failures.push(`Test B: expected 1 new LoadModelThread, got ${bResult.after - bResult.before}`);
      }
      if (bResult.perf.reads !== 0) {
        failures.push(`Test B: expected 0 embedded reads, got ${bResult.perf.reads}`);
      }
      if (bResult.perf.writes !== 0 || bResult.perf.pdfOpen !== 0) {
        failures.push(`Test B: expected writes=0 pdfOpen=0, got writes=${bResult.perf.writes} pdfOpen=${bResult.perf.pdfOpen}`);
      }
      if (bResult.perf.skippedEmbedded !== 1) {
        failures.push(`Test B: expected skippedEmbedded=1, got ${bResult.perf.skippedEmbedded}`);
      }

      const bSvc = await fetchServiceLibrary();
      const bTargetSvc = bookById(bSvc, partitionBookId);
      console.log(`animorphs service after: series_keys='${bTargetSvc?.series_keys}'`);
      if (partitionBookId) {
        const bInMem = await getBookState(client, partitionBookId);
        console.log(`animorphs in-mem after: seriesKey='${bInMem?.seriesKey}' keys='${bInMem?.keys}'`);
        if (bSvc && bTargetSvc && !bTargetSvc.series_keys?.includes(partitionKey)) {
          failures.push(`Test B: service /library did not include '${partitionKey}' for animorphs book`);
        }
        if (bInMem && !bInMem.keys.includes(partitionKey)) {
          failures.push(`Test B: in-memory model did not include '${partitionKey}' for animorphs book`);
        }
        if (bInMem && bTargetSvc && !stateMatchesService(bInMem, bTargetSvc)) {
          failures.push("Test B: in-memory model does not match service after Series->partition");
        }
      }

      // Move the row out of the partition
      await runPartition(
        client,
        "/partition/assign",
        JSON.stringify({ key: "", row: targetSeriesKey, out_of: partitionKey }),
      );
      // Delete the test partition to clean up
      await fetch(`http://127.0.0.1:${SERVICE_PORT}/partition/delete`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ key: partitionKey }),
      }).catch(() => {});

      // ───────────────────────────────────────────────────────────────
      // Test C: Book -> Series (real /book/edit path)
      // Uses the target megamorphs book to set its series to
      // "Animorphs" via the production /book/edit path. Verifies
      // the operation does NOT trigger a full LoadModelThread.
      // ───────────────────────────────────────────────────────────────
      console.log("\n=== Test C: Book -> Series (real /book/edit) ===");
      if (!targetBookId) {
        throw new Error("Test C fixture has no target book");
      } else {
        const cSvc = await fetchServiceLibrary();
        const cBook = bookById(cSvc, targetBookId);
        console.log(
          `book before: series='${cBook?.series || ""}' series_source='${cBook?.series_source || ""}'` +
            ` series_key='${cBook?.series_key || ""}' series_keys='${cBook?.series_keys || ""}'`,
        );
        const cRes = await runUserFieldEdit(client, targetBookId, "series", targetSeries.name, targetSeriesKey);
        console.log(`[Book->Series] wall=${cRes.wallMs}ms loads ${cRes.before}->${cRes.after}`);
        if (cRes.after !== cRes.before) {
          failures.push(`Test C: book->Series should not trigger LoadModelThread; delta=${cRes.after - cRes.before}`);
        }
        const cAfterSvc = await fetchServiceLibrary();
        const cAfterBook = bookById(cAfterSvc, targetBookId);
        console.log(
          `book after:  series='${cAfterBook?.series || ""}' series_source='${cAfterBook?.series_source || ""}'` +
            ` series_key='${cAfterBook?.series_key || ""}' series_keys='${cAfterBook?.series_keys || ""}'`,
        );
        if (cAfterBook?.series_source !== "user") {
          failures.push(`Test C: expected series_source='user', got '${cAfterBook?.series_source}'`);
        }
        if (cAfterBook?.series_key !== targetSeriesKey) {
          failures.push(`Test C: service lost exact Series key '${targetSeriesKey}', got '${cAfterBook?.series_key}'`);
        }
        const cNative = await getBookState(client, targetBookId);
        if (!cNative || cNative.seriesKey !== targetSeriesKey || !stateMatchesService(cNative, cAfterBook)) {
          failures.push("Test C: native and service state do not preserve the selected Series key");
        }
      }

      // ───────────────────────────────────────────────────────────────
      // Test D: Metadata edit (the targeted sidecar-write path)
      // ───────────────────────────────────────────────────────────────
      console.log("\n=== Test D: Metadata edit (tag change) ===");
      if (!targetBookId) {
        throw new Error("Test D fixture has no target book");
      } else {
        const dRes = await runUserFieldEdit(client, targetBookId, "title", "Chunk31RTitle");
        console.log(`[metadata edit] wall=${dRes.wallMs}ms loads ${dRes.before}->${dRes.after}`);
        if (dRes.after !== dRes.before) {
          failures.push(`Test D: metadata edit should not trigger LoadModelThread; delta=${dRes.after - dRes.before}`);
        }
        const dSvc = bookById(await fetchServiceLibrary(), targetBookId);
        const dNative = await getBookState(client, targetBookId);
        if (dSvc?.title !== "Chunk31RTitle" || dNative?.title !== "Chunk31RTitle") {
          failures.push("Test D: edited title was not immediately visible in service and native state");
        }
      }

      // ───────────────────────────────────────────────────────────────
      // Idle check: no background work after the test sequence
      // ───────────────────────────────────────────────────────────────
      const idleA = await getLoadCount(client);
      await new Promise((r) => setTimeout(r, 1500));
      const idleB = await getLoadCount(client);
      console.log(`\nidle check: loads ${idleA} -> ${idleB} over 1.5s`);
      if (idleB !== idleA) {
        warnings.push(`idle check: ${idleB - idleA} extra load(s) after the test sequence`);
      }

      await new Promise((r) => setTimeout(r, 45_000));

      await client.request(ControlCommand.Quit);
    },
    ["-appdata", appDir],
      { connectTimeoutMs: 60_000, window: { x: 100, y: 100, dx: 800, dy: 600 }, env: fixtureEnv() },
    );
  } finally {
    await stopLibraryFixtureService(service);
  }

  // Make sure no test partition hangs around
  await cleanupTestPartition(TEST_PARTITION_NAME);

  if (warnings.length > 0) {
    console.log("\n=== warnings ===");
    for (const w of warnings) console.log(`  - ${w}`);
  }
  if (failures.length > 0) {
    console.log("\n=== failures ===");
    for (const f of failures) console.log(`  - ${f}`);
    throw new Error(`chunk31R-user-actions: ${failures.length} failure(s)`);
  }
  console.log("\nchunk31R-user-actions: ALL CHECKS PASSED");
}

if (import.meta.main) {
  await testit().catch((e) => {
    console.error(e);
    process.exit(1);
  });
}
