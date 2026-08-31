import { join } from "node:path";
import { existsSync } from "node:fs";
import { ROOT } from "./util";
import { ControlCommand, withControlledSumatra } from "./control";
import { FIXTURE_APPDATA, FIXTURE_CACHE, fixtureEnv, FIXTURE_PORT, setupLibraryFixture, startLibraryFixtureService, stopLibraryFixtureService } from "./library-fixture";

const PORT = FIXTURE_PORT;
const RENAMES = join(
  FIXTURE_CACHE, "library", "renames.json");

interface BookState {
  id: string;
  series: string;
  seriesSource: string;
  seriesKey: string;
  keys: string;
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

function parseCount(reply: string): number {
  const m = /count=(\d+)/.exec(reply);
  return m ? parseInt(m[1], 10) : -1;
}

type Client = { request: (cmd: ControlCommand, args?: unknown[]) => Promise<unknown[]> };

async function post(path: string, body: unknown): Promise<any> {
  const r = await fetch(`http://127.0.0.1:${PORT}${path}`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
  return await r.json().catch(() => ({}));
}

async function library(): Promise<any> {
  return await (await fetch(`http://127.0.0.1:${PORT}/library?limit=4096&sort=alpha`)).json();
}

async function loadCount(client: Client): Promise<number> {
  return parseCount(String((await client.request(ControlCommand.TestLoadCount, []))[1] ?? ""));
}

async function bookState(client: Client, id: string): Promise<BookState | null> {
  return parseBookState(String((await client.request(ControlCommand.TestBookState, [id]))[1] ?? ""));
}

async function partition(client: Client, url: string, body: unknown): Promise<number> {
  const before = await loadCount(client);
  await client.request(ControlCommand.TestTriggerPartition, [url, JSON.stringify(body)]);
  const deadline = Date.now() + 30_000;
  while (Date.now() < deadline) {
    if ((await loadCount(client)) > before) break;
    await new Promise((r) => setTimeout(r, 100));
  }
  await new Promise((r) => setTimeout(r, 2_500));
  return await loadCount(client);
}

async function bookEdit(client: Client, body: unknown, id: string): Promise<void> {
  await client.request(ControlCommand.TestTriggerBookEdit, [JSON.stringify(body), id]);
  await new Promise((r) => setTimeout(r, 1_500));
}

async function reload(client: Client, row: string, parent: string): Promise<void> {
  await partition(client, "/series/parent", { row, parent });
}

export async function testit(): Promise<void> {
  const failures: string[] = [];
  const warnings: string[] = [];
  const appDir = FIXTURE_APPDATA;

  setupLibraryFixture();
  const service = await startLibraryFixtureService();
  try {
    await withControlledSumatra(
    join(ROOT, "out", "dbg64", "SumatraPDF.exe"),
    async (client: any) => {
      await new Promise((r) => setTimeout(r, 8_000));
      let c = await loadCount(client);
      for (;;) {
        await new Promise((r) => setTimeout(r, 5_000));
        const n = await loadCount(client);
        if (n === c) break;
        c = n;
      }
      console.log(`settled at load count ${c}`);

      const lib0 = await library();

      const rows = (lib0.series as any[]).filter((r) => r.kind === "series");
      const direct = new Map<string, string[]>();
      for (const b of lib0.books as any[]) {
        if (!b.series_key) continue;
        const got = direct.get(b.series_key);
        if (got) got.push(b.id);
        else direct.set(b.series_key, [b.id]);
      }
      const usable = rows.filter((r) => (direct.get(r.key) || []).length >= 2);
      if (usable.length < 2) {
        throw new Error("need two Series rows with at least two books each");
      }
      const impostor = usable[0];
      const genuine = usable[1];
      const impostorWas: string = impostor.name;
      console.log(`impostor row: ${impostor.key} ('${impostorWas}')`);
      console.log(`genuine  row: ${genuine.key} ('${genuine.name}')`);

      const subjectId: string = direct.get(genuine.key)![0]!;
      const subjectSvc = (lib0.books as any[]).find((b) => b.id === subjectId);
      const store: any = existsSync(RENAMES) ? JSON.parse(await Bun.file(RENAMES).text()) : {};
      const subjectOverrideWas = store?.books?.[subjectId]?.series ?? null;
      const impostorRenameWas: string = store?.series?.[impostor.key] ?? "";
      console.log(
        `subject book: ${subjectId} series='${subjectSvc?.series}' key='${subjectSvc?.series_key}'` +
          ` override=${JSON.stringify(subjectOverrideWas)}`,
      );

      try {
        console.log(`\n=== renaming ${impostor.key} to '${genuine.name}' (production /series/rename) ===`);
        await post("/series/rename", { row: impostor.key, name: genuine.name });
        await reload(client, genuine.key, genuine.parent || "");
        const libA = await library();
        const sameName = (libA.series as any[]).filter(
          (r) => String(r.name).toLowerCase() === String(genuine.name).toLowerCase(),
        );
        console.log(`  rows now showing '${genuine.name}': ${sameName.map((r) => r.key).join(", ")}`);
        if (sameName.length < 2) {
          warnings.push("could not build the two-Series-rows-one-name case; the rest is weaker");
        }

        console.log("\n=== book -> Series chooser (exact key carried) ===");
        await bookEdit(
          client,
          { id: subjectId, fields: { series: { value: genuine.name, source: "user", key: impostor.key } } },
          subjectId,
        );
        await reload(client, genuine.key, genuine.parent || "");
        const chosen = await bookState(client, subjectId);
        const chosenSvc = ((await library()).books as any[]).find((b) => b.id === subjectId);
        console.log(`  native seriesKey='${chosen?.seriesKey}' keys='${chosen?.keys}'`);
        console.log(`  service series_key='${chosenSvc?.series_key}' series_keys='${chosenSvc?.series_keys}'`);
        if (chosen?.seriesKey !== impostor.key) {
          failures.push(
            `chooser: picked '${impostor.key}' but the book landed on '${chosen?.seriesKey}'`,
          );
        }
        if ((chosenSvc?.series_key || "") !== chosen?.seriesKey) {
          failures.push("chooser: native and service disagree after the pick");
        }

        console.log("\n=== typed Series edit (name only, ambiguous) ===");
        const typedRuns: string[] = [];
        for (let i = 0; i < 2; i++) {
          await bookEdit(
            client,
            { id: subjectId, fields: { series: { value: "", source: "" } } },
            subjectId,
          );
          await bookEdit(
            client,
            { id: subjectId, fields: { series: { value: genuine.name, source: "user" } } },
            subjectId,
          );
          await reload(client, genuine.key, genuine.parent || "");
          const st = await bookState(client, subjectId);
          typedRuns.push(st?.seriesKey || "");
          console.log(`  run ${i + 1}: seriesKey='${st?.seriesKey}' keys='${st?.keys}'`);
        }
        if (typedRuns[0] !== typedRuns[1]) {
          failures.push(`typed edit is not deterministic: '${typedRuns[0]}' then '${typedRuns[1]}'`);
        }
        if (!(typedRuns[0] || "").startsWith("series:")) {
          failures.push(`typed edit resolved to a non-Series row '${typedRuns[0]}'`);
        }

        console.log("\n=== Restore automatic Series ===");
        await bookEdit(client, { id: subjectId, fields: { series: { value: "", source: "" } } }, subjectId);
        await reload(client, genuine.key, genuine.parent || "");
        const restored = await bookState(client, subjectId);
        const restoredSvc = ((await library()).books as any[]).find((b) => b.id === subjectId);
        console.log(
          `  native seriesKey='${restored?.seriesKey}' source='${restored?.seriesSource}'` +
            ` service series_key='${restoredSvc?.series_key}'`,
        );
        if ((restoredSvc?.series_key || "") !== restored?.seriesKey) {
          failures.push("Restore automatic Series: native and service disagree");
        }
        if ((restored?.seriesKey || "").startsWith("parent:") || (restored?.seriesKey || "").startsWith("part:")) {
          failures.push(`Restore automatic Series: landed on a container row '${restored?.seriesKey}'`);
        }
      } finally {
        console.log(`\n=== restoring '${impostorWas}' and the subject book ===`);
        await post("/series/rename", { row: impostor.key, name: impostorRenameWas });
        if (subjectOverrideWas && typeof subjectOverrideWas === "object") {
          await post("/book/edit", { id: subjectId, fields: { series: subjectOverrideWas } });
        } else {
          await post("/book/edit", { id: subjectId, fields: { series: { value: "", source: "" } } });
        }
        await reload(client, genuine.key, genuine.parent || "");
      }

      console.log("\n=== Series -> Series ===");
      const childRow = genuine.key;
      const childWasParent: string = genuine.parent || "";
      const hostRow = (await library()).series.find(
        (r: any) => r.kind === "series" && r.key !== childRow && !r.parent,
      );
      if (!hostRow) {
        warnings.push("Series -> Series: no free top-level Series row to host the move");
      } else {
        await partition(client, "/series/parent", { row: childRow, parent: hostRow.key });
        const moved = (await library()).series.find((r: any) => r.key === childRow);
        console.log(`  ${childRow} parent is now '${moved?.parent}'`);
        if (moved?.parent !== hostRow.key) failures.push(`Series -> Series did not take: '${moved?.parent}'`);
        const kid = await bookState(client, subjectId);
        if (kid && !kid.seriesKey.startsWith("series:")) {
          failures.push(`Series -> Series: a book's direct key became '${kid.seriesKey}'`);
        }

        console.log("\n=== Restore automatic parent ===");
        await partition(client, "/series/parent", { row: childRow, parent: childWasParent });
        const back = (await library()).series.find((r: any) => r.key === childRow);
        console.log(`  ${childRow} parent restored to '${back?.parent}'`);
        if ((back?.parent || "") !== childWasParent) {
          failures.push(`Restore automatic parent failed: '${back?.parent}' want '${childWasParent}'`);
        }
      }

      console.log("\n=== Series -> partition ===");
      const made = await post("/partition/new", { name: "Chunk32 Edit Shelf" });
      const partKey: string = made?.key || "";
      if (!partKey) {
        warnings.push("Series -> partition: could not create the shelf");
      } else {
        await partition(client, "/partition/assign", { key: partKey, row: childRow });
        const inShelf = await bookState(client, subjectId);
        console.log(`  subject keys='${inShelf?.keys}' direct='${inShelf?.seriesKey}'`);
        if (inShelf && !inShelf.keys.includes(partKey)) {
          failures.push(`Series -> partition: '${partKey}' missing from the book's keys`);
        }
        if (inShelf && inShelf.seriesKey === partKey) {
          failures.push("Series -> partition: the partition became the book's direct Series");
        }
        await partition(client, "/partition/assign", { key: "", row: childRow, out_of: partKey });
        await post("/partition/delete", { key: partKey });
        await reload(client, childRow, childWasParent);
      }

      console.log("\n=== final whole-catalogue equality ===");
      const finalLib = await library();
      let user = 0;
      let auto = 0;
      let bad = 0;
      for (const b of finalLib.books as any[]) {
        const st = await bookState(client, b.id);
        if (!st) {
          failures.push(`${b.id} missing from the native model`);
          continue;
        }
        if ((b.series_source || "").toLowerCase() === "user") user++;
        else auto++;
        if (st.seriesKey !== (b.series_key || "") || st.keys !== (b.series_keys || "")) {
          bad++;
          if (bad <= 8) {
            console.log(
              `  MISMATCH ${b.id} '${b.series}' native='${st.seriesKey}' ${st.keys}` +
                ` service='${b.series_key}' ${b.series_keys}`,
            );
          }
        }
      }
      console.log(`  user=${user} automatic=${auto} mismatches=${bad}`);
      if (bad) failures.push(`${bad} book(s) disagree with the service after the edit pass`);

      await client.request(ControlCommand.Quit);
    },
    ["-appdata", appDir],
      { connectTimeoutMs: 60_000, env: fixtureEnv() },
    );
  } finally {
    await stopLibraryFixtureService(service);
  }

  if (warnings.length) {
    console.log("\n=== warnings ===");
    for (const w of warnings) console.log(`  - ${w}`);
  }
  if (failures.length) {
    console.log("\n=== failures ===");
    for (const f of failures) console.log(`  - ${f}`);
    throw new Error(`chunk32-series-edits: ${failures.length} failure(s)`);
  }
  console.log("\nchunk32-series-edits: ALL CHECKS PASSED");
}

if (import.meta.main) {
  await testit().catch((e) => {
    console.error(e);
    process.exit(1);
  });
}
