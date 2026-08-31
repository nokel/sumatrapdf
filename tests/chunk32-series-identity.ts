import { join } from "node:path";
import { ROOT } from "./util";
import { ControlCommand, withControlledSumatra } from "./control";
import { FIXTURE_APPDATA, FIXTURE_PORT, fixtureEnv, setupLibraryFixture, startLibraryFixtureService, stopLibraryFixtureService } from "./library-fixture";

const EXE = join(ROOT, "out", "dbg64", "SumatraPDF.exe");
const countOf = (s: string) => Number(/count=(\d+)/.exec(s)?.[1] ?? -1);

function parseState(reply: string): Record<string, string> {
  const out: Record<string, string> = {};
  for (const part of /^OK\s+(.*)$/.exec(reply.trim())?.[1]?.split(/\s+/) || []) {
    const at = part.indexOf("=");
    if (at > 0) out[part.slice(0, at)] = part.slice(at + 1);
  }
  return out;
}

async function library(): Promise<any> {
  const response = await fetch(`http://127.0.0.1:${FIXTURE_PORT}/library?limit=4096&sort=alpha`);
  if (!response.ok) throw new Error(`library request failed: ${response.status}`);
  return response.json();
}

async function post(path: string, body: unknown): Promise<any> {
  const response = await fetch(`http://127.0.0.1:${FIXTURE_PORT}${path}`, {
    method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body),
  });
  const data = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(`${path} failed: ${JSON.stringify(data)}`);
  return data;
}

async function count(client: any): Promise<number> {
  return countOf(String((await client.request(ControlCommand.TestLoadCount, []))[1] || ""));
}

async function settle(client: any): Promise<void> {
  await Bun.sleep(6_000);
  let previous = await count(client);
  for (;;) {
    await Bun.sleep(2_000);
    const current = await count(client);
    if (current === previous) return;
    previous = current;
  }
}

async function state(client: any, id: string): Promise<Record<string, string>> {
  return parseState(String((await client.request(ControlCommand.TestBookState, [id]))[1] || ""));
}

async function trigger(client: any, path: string, body: unknown): Promise<void> {
  const before = await count(client);
  await client.request(ControlCommand.TestTriggerPartition, [path, JSON.stringify(body)]);
  const deadline = Date.now() + 30_000;
  while (Date.now() < deadline) {
    if ((await count(client)) > before) {
      await Bun.sleep(1_000);
      return;
    }
    await Bun.sleep(100);
  }
  throw new Error(`${path} did not trigger a catalogue reload`);
}

async function runApp(body: (client: any) => Promise<void>): Promise<void> {
  const service = await startLibraryFixtureService();
  try {
    await withControlledSumatra(EXE, async (client: any) => {
      await settle(client);
      await body(client);
      await client.request(ControlCommand.Quit);
    }, ["-appdata", FIXTURE_APPDATA], { connectTimeoutMs: 60_000, env: fixtureEnv() });
  } finally {
    await stopLibraryFixtureService(service);
  }
}

export async function testit(): Promise<void> {
  setupLibraryFixture();
  let userId = "";
  let automaticId = "";
  let childKey = "";

  await runApp(async (client) => {
    const initial = await library();
    const direct = new Map<string, any[]>();
    for (const book of initial.books) {
      if (book.series_key) direct.set(book.series_key, [...(direct.get(book.series_key) || []), book]);
    }
    const rows = initial.series.filter((row: any) => row.kind === "series" && (direct.get(row.key) || []).length >= 2);
    if (rows.length < 2) throw new Error("fixture must contain two populated Series rows");
    const child = rows[0];
    const host = rows[1];
    childKey = child.key;
    userId = direct.get(childKey)![0].id;
    automaticId = direct.get(childKey)![1].id;

    await post("/series/rename", { row: host.key, name: child.name });
    await trigger(client, "/series/parent", { row: childKey, parent: host.key });
    const collision = (await library()).series.filter((row: any) => String(row.name).toLowerCase() === String(child.name).toLowerCase());
    if (collision.length < 2) throw new Error("same-name Series/container collision was not created");

    const before = await count(client);
    await client.request(ControlCommand.TestTriggerBookEdit, [JSON.stringify({
      id: userId, fields: { series: { value: child.name, source: "user", key: childKey } },
    }), userId]);
    await Bun.sleep(1_500);
    if ((await count(client)) !== before) throw new Error("Book -> Series unexpectedly started LoadModelThread");

    const catalogue = await library();
    const user = catalogue.books.find((book: any) => book.id === userId);
    const automatic = catalogue.books.find((book: any) => book.id === automaticId);
    if (user.series_key !== childKey || (await state(client, userId)).seriesKey !== childKey) throw new Error("user key was substituted");
    if (automatic.series_key !== childKey || automatic.series_source === "user") throw new Error("automatic key was substituted");
    console.log(`collision='${child.name}' keys=${childKey},${host.key} user=${user.series_key} automatic=${automatic.series_key}`);
  });

  await runApp(async (client) => {
    const catalogue = await library();
    const keys = catalogue.series.map((row: any) => row.key);
    const duplicateKeys = keys.length - new Set(keys).size;
    let mismatches = 0;
    for (const book of catalogue.books) {
      const native = await state(client, book.id);
      if (native.seriesKey !== (book.series_key || "") || native.keys !== (book.series_keys || "")) mismatches++;
    }
    const user = catalogue.books.find((book: any) => book.id === userId);
    const automatic = catalogue.books.find((book: any) => book.id === automaticId);
    if (user?.series_key !== childKey || (await state(client, userId)).seriesKey !== childKey) throw new Error("restart lost user key");
    if (automatic?.series_key !== childKey || (await state(client, automaticId)).seriesKey !== childKey) throw new Error("restart lost automatic key");
    if (duplicateKeys || mismatches) throw new Error(`restart duplicateKeys=${duplicateKeys} mismatches=${mismatches}`);
    console.log(`restart user=${user.series_key} automatic=${automatic.series_key} duplicateKeys=0 mismatches=0`);
  });

  console.log("\nchunk32-series-identity: ALL CHECKS PASSED");
}

if (import.meta.main) {
  await testit().catch((error) => {
    console.error(error);
    process.exit(1);
  });
}
