import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { tmpdir } from "node:os";
import { ControlCommand } from "./control.ts";
import { captureWindowToPng, clickAt, launchControlled } from "./win-automation.ts";
import { enumChildWindows, getClassName, getWindowRect, isWindowVisible } from "./winapi.ts";

const port = 7863;
const out = join(tmpdir(), "sumatrapdf-chunk38", "real-ui");
mkdirSync(out, { recursive: true });
const label = process.argv[2] || "home";
const log = join(out, `${label}.log`);

async function get(path: string): Promise<any> {
  const response = await fetch(`http://127.0.0.1:${port}${path}`);
  if (!response.ok) throw new Error(`${path} returned ${response.status}`);
  return await response.json();
}

const app = await launchControlled(["-window-pos", "1200x900@40x40", "-log", "-log-to-file", log], { saveSettings: true });
try {
  let status: any = null;
  for (let attempt = 0; attempt < 600; attempt++) {
    try {
      status = await get("/status");
      if (!status.scanning && status.books > 0) break;
    } catch {}
    await Bun.sleep(500);
  }
  if (!status || status.scanning || !status.books) throw new Error(`library did not settle: ${JSON.stringify(status)}`);
  await Bun.sleep(2500);
  if (["series-scroll", "hitch-series", "hitch-series-six"].includes(label)) {
    let canvas = 0;
    enumChildWindows(app.frame, (hwnd) => {
      if (getClassName(hwnd) === "SUMATRA_PDF_CANVAS") canvas = hwnd;
      return true;
    });
    if (!canvas) throw new Error("Library canvas not found");
    for (let i = 0; i < 8; i++) await clickAt(canvas, 204, 650, 150);
    if (label === "hitch-series") await clickAt(canvas, 100, 712, 1200);
    if (label === "hitch-series-six") await clickAt(canvas, 100, 740, 1200);
    await Bun.sleep(1000);
  }
  if (label === "rescan") {
    let canvas = 0;
    enumChildWindows(app.frame, (hwnd) => {
      if (getClassName(hwnd) === "SUMATRA_PDF_CANVAS") canvas = hwnd;
      return true;
    });
    if (!canvas) throw new Error("Library canvas not found");
    const previous = status.scanned;
    const initialWork = [...readFileSync(log, "utf8").matchAll(/fingerprint full=(\d+) shape=(\d+)/g)].length;
    await clickAt(canvas, 45, 780, 500);
    for (let attempt = 0; attempt < 1800; attempt++) {
      status = await get("/status");
      const work = [...readFileSync(log, "utf8").matchAll(/fingerprint full=(\d+) shape=(\d+)/g)].length;
      if (!status.scanning && status.scanned !== previous && work >= initialWork + 3) break;
      await Bun.sleep(500);
    }
    const completedWork = [...readFileSync(log, "utf8").matchAll(/fingerprint full=(\d+) shape=(\d+)/g)].length;
    if (status.scanning || status.scanned === previous || completedWork < initialWork + 3) throw new Error(`visible Rescan library action did not complete: ${JSON.stringify(status)}`);
    await Bun.sleep(2500);
  }
  const library = await get("/library?limit=1000");
  const series = library.series.find((row: any) => String(row.name ?? row.title ?? "").toLowerCase().includes("hitchhiker"));
  const books = library.books.filter((row: any) => String(row.series ?? "").toLowerCase().includes("hitchhiker") || String(row.title ?? "").toLowerCase().includes("hitchhiker"));
  const details = [];
  for (const book of books) details.push(await get(`/book?id=${encodeURIComponent(book.id)}`));
  const perf = String((await app.client.request(ControlCommand.TestLastLoadPerf, []))[1] ?? "");
  const logText = readFileSync(log, "utf8");
  const fingerprintWork = [...logText.matchAll(/fingerprint full=(\d+) shape=(\d+)/g)].map((match) => ({ full: Number(match[1]), shape: Number(match[2]) }));
  const result = { status, series, books, details, perf, fingerprintWork };
  if (process.argv[2] === "probe") {
    enumChildWindows(app.frame, (hwnd) => {
      if (isWindowVisible(hwnd)) console.log(`child=${getClassName(hwnd)} ${JSON.stringify(getWindowRect(hwnd))}`);
      return true;
    });
  }
  const json = join(out, "state.json");
  const png = join(out, `${label}.png`);
  writeFileSync(json, JSON.stringify(result, null, 2));
  captureWindowToPng(app.frame, png);
  console.log(`status=${JSON.stringify(status)}`);
  console.log(`series=${JSON.stringify(series)}`);
  console.log(`books=${books.length}`);
  for (const book of books) console.log(`book=${JSON.stringify(book)}`);
  for (const detail of details) console.log(`detail=${JSON.stringify(detail)}`);
  console.log(`perf=${perf}`);
  console.log(`fingerprintWork=${JSON.stringify(fingerprintWork)}`);
  console.log(`png=${png}`);
  console.log(`json=${json}`);
} finally {
  await app.client.quit();
  app.client.close();
  await app.proc.exited;
}
