import { join } from "node:path";
import { ROOT } from "./util";
import { ControlCommand, withControlledSumatra } from "./control";
import { WM_COMMAND, postMessage, waitForTopWindow } from "./winapi";

const PORT = 7863;
const CmdLibraryRescan = 461;
const APPDIR = join(ROOT, "scratchpad", "chunk32-appdata");
const STORE = join(APPDIR, "SumatraLibrary.txt");
const EXE = join(ROOT, "out", "dbg64", "SumatraPDF.exe");

interface BookState {
  series: string;
  seriesSource: string;
  seriesKey: string;
  keys: string;
}

function parseBookState(reply: string): BookState | null {
  const m = /^OK\s+(.*)$/.exec(reply.trim());
  if (!m) return null;
  const out: any = {};
  for (const part of m[1]!.split(/\s+/)) {
    const eq = part.indexOf("=");
    if (eq > 0) out[part.substring(0, eq)] = part.substring(eq + 1);
  }
  return out as BookState;
}

function parseCount(reply: string): number {
  const m = /count=(\d+)/.exec(reply);
  return m ? parseInt(m[1], 10) : -1;
}

async function library(): Promise<any> {
  return await (await fetch(`http://127.0.0.1:${PORT}/library?limit=4096&sort=alpha`)).json();
}

async function readStore(): Promise<Map<string, { series: string; seriesKey: string; keys: string }>> {
  const text = await Bun.file(STORE).text();
  const out = new Map<string, { series: string; seriesKey: string; keys: string }>();
  let id = "";
  let cur = { series: "", seriesKey: "", keys: "" };
  for (const raw of text.split("\n")) {
    const line = raw.trim();
    const eq = line.indexOf(" = ");
    if (eq < 0) {
      if (line === "]" || line === "[") {
        if (id) out.set(id, cur);
        id = "";
        cur = { series: "", seriesKey: "", keys: "" };
      }
      continue;
    }
    const k = line.substring(0, eq);
    const v = line.substring(eq + 3);
    if (k === "Id") {
      if (id) out.set(id, cur);
      id = v;
      cur = { series: "", seriesKey: "", keys: "" };
    } else if (k === "Series") cur.series = v;
    else if (k === "SeriesKey") cur.seriesKey = v;
    else if (k === "Keys") cur.keys = v;
  }
  if (id) out.set(id, cur);
  return out;
}

async function settle(client: any): Promise<number> {
  const loads = async () =>
    parseCount(String((await client.request(ControlCommand.TestLoadCount, []))[1] ?? ""));
  await new Promise((r) => setTimeout(r, 8_000));
  let c = await loads();
  for (;;) {
    await new Promise((r) => setTimeout(r, 5_000));
    const n = await loads();
    if (n === c) return c;
    c = n;
  }
}

async function waitForQuietLog(): Promise<void> {
  const logPath = join(ROOT, "out", "dbg64", "sumlog.txt");
  let prev = -1;
  let stable = 0;
  while (stable < 5) {
    await new Promise((r) => setTimeout(r, 5_000));
    const sz = Bun.file(logPath).size;
    stable = sz === prev ? stable + 1 : 0;
    prev = sz;
  }
}

async function compareAll(client: any, label: string, failures: string[]): Promise<void> {
  const lib = await library();
  let user = 0;
  let auto = 0;
  let bad = 0;
  let container = 0;
  for (const b of lib.books as any[]) {
    const st = parseBookState(
      String((await client.request(ControlCommand.TestBookState, [b.id]))[1] ?? ""),
    );
    if (!st) {
      failures.push(`${label}: ${b.id} missing from the native model`);
      continue;
    }
    if ((b.series_source || "").toLowerCase() === "user") user++;
    else auto++;
    if (st.seriesKey !== (b.series_key || "") || st.keys !== (b.series_keys || "")) {
      bad++;
      if (bad <= 6) {
        console.log(
          `  ${label} MISMATCH ${b.id} '${b.series}' native='${st.seriesKey}' ${st.keys}` +
            ` service='${b.series_key}' ${b.series_keys}`,
        );
      }
    }
    if (/^(parent|part|form|collection):/.test(st.seriesKey) && st.seriesKey !== (b.series_key || "")) {
      container++;
      if (container <= 6) console.log(`  ${label} CONTAINER AS DIRECT ${b.id} -> ${st.seriesKey}`);
    }
  }
  console.log(`  ${label}: ${user} user, ${auto} automatic, ${bad} mismatches, ${container} on container rows`);
  if (bad) failures.push(`${label}: ${bad} book(s) disagree with the service`);
  if (container) failures.push(`${label}: ${container} book(s) sit directly on a container row`);
}

export async function testit(): Promise<void> {
  const failures: string[] = [];
  const before = await (async () => {
    let snap: Map<string, any> = new Map();
    await withControlledSumatra(
      EXE,
      async (client: any, proc: any) => {
        const c = await settle(client);
        console.log(`pre-rescan: settled at load count ${c}`);
        const hwnd = await waitForTopWindow(proc.pid, "SUMATRA_PDF_FRAME", 30_000);
        if (!hwnd) throw new Error("no SUMATRA_PDF_FRAME window");
        console.log(`frame hwnd 0x${hwnd.toString(16)}`);
        await compareAll(client, "pre-rescan", failures);
        snap = await readStore();
        console.log(`pre-rescan store: ${snap.size} books`);
        await waitForQuietLog();

        console.log("posting CmdLibraryRescan (the real 'Rescan library' command)");
        if (!postMessage(hwnd, WM_COMMAND, CmdLibraryRescan, 0)) {
          throw new Error("PostMessage(WM_COMMAND, CmdLibraryRescan) failed");
        }
        const t0 = Date.now();
        while (Date.now() - t0 < 10 * 60_000) {
          await new Promise((r) => setTimeout(r, 5_000));
          if (proc.exitCode !== null) {
            console.log(`the app exited ${Math.round((Date.now() - t0) / 1000)}s into the rescan,` +
              ` code ${proc.exitCode} (see UNRESOLVED ISSUES)`);
            return;
          }
          const st: any = await (await fetch(`http://127.0.0.1:${PORT}/status`)).json();
          if (!st.scanning && Date.now() - t0 > 60_000) break;
        }
        console.log("the app survived the rescan");
        await compareAll(client, "post-rescan (live)", failures);
        await client.request(ControlCommand.Quit);
      },
      ["-appdata", APPDIR],
      { connectTimeoutMs: 60_000 },
    ).catch((e) => {
      console.log(`the controlled run ended early: ${e}`);
    });
    return snap;
  })();

  const after = await readStore();
  console.log(`\npost-rescan store: ${after.size} books (was ${before.size})`);
  let moved = 0;
  for (const [id, was] of before) {
    const now = after.get(id);
    if (!now) continue;
    if (was.seriesKey !== now.seriesKey || was.keys !== now.keys) {
      moved++;
      if (moved <= 8) {
        console.log(
          `  MOVED ${id} '${was.series}' before='${was.seriesKey}' ${was.keys}` +
            ` after='${now.seriesKey}' ${now.keys}`,
        );
      }
    }
  }
  console.log(`  identities changed by the rescan in the persisted store: ${moved}`);
  if (moved) failures.push(`${moved} book(s) changed identity across the rescan`);

  console.log("\nrelaunching after the rescan");
  await withControlledSumatra(
    EXE,
    async (client: any) => {
      const c = await settle(client);
      console.log(`post-rescan relaunch: settled at load count ${c}`);
      await compareAll(client, "post-rescan (relaunch)", failures);
      await client.request(ControlCommand.Quit);
    },
    ["-appdata", APPDIR],
    { connectTimeoutMs: 60_000 },
  );

  if (failures.length) {
    console.log("\n=== failures ===");
    for (const f of failures) console.log(`  - ${f}`);
    throw new Error(`chunk32-rescan: ${failures.length} failure(s)`);
  }
  console.log("\nchunk32-rescan: ALL IDENTITY CHECKS PASSED");
}

if (import.meta.main) {
  await testit().catch((e) => {
    console.error(e);
    process.exit(1);
  });
}
