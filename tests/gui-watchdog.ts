import { closeSync, existsSync, mkdirSync, openSync, readSync, statSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import {
  captureWindowDCToPng,
  enumChildWindows,
  enumWindows,
  getClassName,
  getWindowPid,
  getWindowRect,
  getWindowText,
  isWindowVisible,
  postMessage,
  realKeyPress,
  realMouseClickAt,
  sleep,
  VK_ESCAPE,
  WM_CLOSE,
} from "./winapi.ts";

export interface GuardCounters {
  unexpectedModalDialogs: number;
  unexpectedMenus: number;
  pathErrorDialogs: number;
  visibleDiagnosticWindows: number;
  missingFixtureSubmissions: number;
  fixtureChecks: number;
  guardedWaits: number;
  protectedTargetRefusals: number;
  libraryContainmentChecks: number;
  libraryContainmentRefusals: number;
}

export const guardCounters: GuardCounters = {
  unexpectedModalDialogs: 0,
  unexpectedMenus: 0,
  pathErrorDialogs: 0,
  visibleDiagnosticWindows: 0,
  missingFixtureSubmissions: 0,
  fixtureChecks: 0,
  guardedWaits: 0,
  protectedTargetRefusals: 0,
  libraryContainmentChecks: 0,
  libraryContainmentRefusals: 0,
};

export const guardEvents: any[] = [];

const DIAGNOSTIC_PROCESSES = ["notepad", "notepad++", "wordpad", "write", "code", "gvim", "vim"];
const nameCache = new Map<number, string>();

function processName(pid: number): string {
  const had = nameCache.get(pid);
  if (had !== undefined) {
    return had;
  }
  const p = Bun.spawnSync(["powershell", "-NoProfile", "-Command",
    `try{(Get-Process -Id ${pid}).ProcessName}catch{''}`], { timeout: 20_000 });
  const name = new TextDecoder().decode(p.stdout).trim();
  nameCache.set(pid, name);
  return name;
}

function parentPid(pid: number): number {
  const p = Bun.spawnSync(["powershell", "-NoProfile", "-Command",
    `try{(Get-CimInstance Win32_Process -Filter "ProcessId=${pid}").ParentProcessId}catch{''}`],
    { timeout: 20_000 });
  return Number(new TextDecoder().decode(p.stdout).trim() || "0");
}

export interface WindowShape {
  hwnd: number;
  className: string;
  title: string;
  pid: number;
  statics: string[];
  buttons: string[];
  edits: number;
  shellParts: number;
}

export function shapeOf(hwnd: number): WindowShape {
  const statics: string[] = [];
  const buttons: string[] = [];
  let edits = 0;
  let shellParts = 0;
  enumChildWindows(hwnd, (c) => {
    const cls = getClassName(c);
    const text = getWindowText(c);
    if (cls === "Static" && text.length > 0) {
      statics.push(text);
    } else if (cls === "Button" && text.length > 0) {
      buttons.push(text);
    } else if (cls === "Edit" || cls === "ComboBox" || cls === "ComboBoxEx32") {
      edits++;
    } else if (cls.includes("SysTreeView") || cls === "SHELLDLL_DefView") {
      shellParts++;
    }
    return true;
  });
  return {
    hwnd,
    className: getClassName(hwnd),
    title: getWindowText(hwnd),
    pid: getWindowPid(hwnd),
    statics,
    buttons,
    edits,
    shellParts,
  };
}

export function topLevelWindows(pid: number): WindowShape[] {
  const rows: WindowShape[] = [];
  enumWindows((h) => {
    if (isWindowVisible(h) && getWindowPid(h) === pid) {
      rows.push(shapeOf(h));
    }
    return true;
  });
  return rows;
}

// a message box is a dialog with nothing to fill in: the file picker and the
// import form both carry entry boxes or shell panes, so neither ever matches
export function looksLikeMessageBox(w: WindowShape): boolean {
  if (w.className !== "#32770") {
    return false;
  }
  return w.edits === 0 && w.shellParts === 0;
}

// the path-error box draws its words in a DirectUIHWND, so GetWindowText on
// the children finds nothing and the words have to come from UI Automation
export function dialogText(hwnd: number): string[] {
  const script = [
    "Add-Type -AssemblyName UIAutomationClient,UIAutomationTypes",
    "$e=[System.Windows.Automation.AutomationElement]::FromHandle([IntPtr]" + hwnd + ")",
    "if($e){",
    "$c=New-Object System.Windows.Automation.PropertyCondition(",
    "[System.Windows.Automation.AutomationElement]::ControlTypeProperty,",
    "[System.Windows.Automation.ControlType]::Text)",
    "foreach($x in $e.FindAll([System.Windows.Automation.TreeScope]::Descendants,$c)){$x.Current.Name}}",
  ].join("\n");
  const p = Bun.spawnSync(["powershell", "-NoProfile", "-Command", script], { timeout: 30_000 });
  return new TextDecoder().decode(p.stdout).split(/\r?\n/).map((s) => s.trim()).filter((s) => s.length > 0);
}

// a popup menu belonging to the application blocks its message loop just as a
// modal dialog does, so a wait that is not expecting one must not sit behind it
export function looksLikePopupMenu(w: WindowShape): boolean {
  return w.className === "#32768";
}

function diagnosticWindowsNow(): WindowShape[] {
  const rows: WindowShape[] = [];
  enumWindows((h) => {
    if (!isWindowVisible(h)) {
      return true;
    }
    const name = processName(getWindowPid(h)).toLowerCase();
    if (DIAGNOSTIC_PROCESSES.some((n) => name === n)) {
      rows.push(shapeOf(h));
    }
    return true;
  });
  return rows;
}

// the requirement is that the run spawns no diagnostic window, so an editor the
// user already had open before the harness started is not one of ours; it is
// recorded once at startup and every later one is still reported
const baselineDiagnosticPids = new Set<number>();

export function noteDiagnosticBaseline(): number[] {
  baselineDiagnosticPids.clear();
  for (const w of diagnosticWindowsNow()) {
    baselineDiagnosticPids.add(w.pid);
  }
  return [...baselineDiagnosticPids];
}

export function diagnosticBaseline(): number[] {
  return [...baselineDiagnosticPids];
}

export function diagnosticWindows(): WindowShape[] {
  return diagnosticWindowsNow().filter((w) => !baselineDiagnosticPids.has(w.pid));
}

noteDiagnosticBaseline();

export class UnexpectedModalError extends Error {
  dialog: any;

  constructor(dialog: any) {
    super("an unexpected dialog blocked " + dialog.step + ": " + JSON.stringify(dialog.text));
    this.dialog = dialog;
  }
}

export class MissingFixtureError extends Error {}

export class ProtectedTargetError extends Error {
  classification: any;

  constructor(message: string, classification: any) {
    super(message);
    this.classification = classification;
  }
}

export class LibraryNotContainedError extends Error {
  check: any;

  constructor(message: string, check: any) {
    super(message);
    this.check = check;
  }
}

const CONTAINMENT = join(import.meta.dir, "library-fixture-containment.py");
const CONTAINMENT_PYTHON = join(import.meta.dir, "..", "..", "Chatterbox-TTS-Extended-main", ".venv-amd", "Scripts",
                                "python.exe");

function runContainment(args: string[]): { code: number; body: any; raw: string } {
  const run = Bun.spawnSync([CONTAINMENT_PYTHON, CONTAINMENT, ...args],
    { cwd: join(import.meta.dir, ".."), timeout: 120_000 });
  const raw = run.stdout.toString() + run.stderr.toString();
  let body: any = null;
  try {
    body = JSON.parse(run.stdout.toString());
  } catch (e) {
    body = null;
  }
  return { code: run.exitCode ?? -1, body, raw };
}

export function provenanceOf(path: string): any {
  const r = runContainment(["guard-target", path]);
  if (!r.body || typeof r.body.kind !== "string") {
    return { ok: false, kind: "unknown", given: path, reason: "the provenance guard gave no answer", raw: r.raw,
             exitCode: r.code };
  }
  return { ...r.body, exitCode: r.code, ok: r.code === 0 && r.body.ok === true };
}

export function assertContainedLibrary(label: string, step: string): any {
  guardCounters.libraryContainmentChecks++;
  const r = runContainment(["check-library"]);
  const contained = r.code === 0 && r.body?.contained === true;
  const event = { label, step, libraryContainment: r.body ?? { raw: r.raw }, exitCode: r.code, contained };
  guardEvents.push(event);
  if (!contained) {
    guardCounters.libraryContainmentRefusals++;
    throw new LibraryNotContainedError(
      "refusing to start the Library for " + step + ": the Library environment reaches files that are not " +
      "authorized writable fixtures: " + JSON.stringify((r.body?.violations ?? [r.raw]).slice(0, 5)), event);
  }
  return r.body;
}

export class GuardTimeoutError extends Error {
  detail: any;

  constructor(detail: any) {
    super("timed out waiting for " + detail.step + " after " + detail.waitedMs + "ms");
    this.detail = detail;
  }
}

export class ProcessGoneError extends Error {}

function alive(pid: number): boolean {
  try {
    process.kill(pid, 0);
    return true;
  } catch (e) {
    return false;
  }
}

function dismiss(w: WindowShape): string {
  let button = 0;
  let label = "";
  enumChildWindows(w.hwnd, (c) => {
    if (getClassName(c) !== "Button") {
      return true;
    }
    const text = getWindowText(c).replace("&", "");
    if (/^(OK|Close|Cancel|No)$/i.test(text)) {
      button = c;
      label = text;
      return false;
    }
    return true;
  });
  if (button) {
    const r = getWindowRect(button);
    realMouseClickAt(Math.round((r.left + r.right) / 2), Math.round((r.top + r.bottom) / 2), "left");
    return "clicked " + label;
  }
  postMessage(w.hwnd, WM_CLOSE, 0, 0);
  return "closed the window";
}

export interface GuardOptions {
  pid: number;
  label: string;
  step: string;
  out: string;
  timeoutMs: number;
  ready: () => number | Promise<number>;
  allow?: number[];
  pollMs?: number;
  allowMenus?: boolean;
  allowProcessExit?: boolean;
  // a ready() probe that never resolves would otherwise stop the condition
  // from ever being sampled again while the wait looks healthy; when one call
  // stays outstanding this long the wait fails as a hang, attributed to the step
  readyDeadlineMs?: number;
}

// one wait that watches the wanted state, unexpected dialogs, the application
// process and the clock at the same time
export async function waitGuarded(o: GuardOptions): Promise<number> {
  guardCounters.guardedWaits++;
  const allow = new Set(o.allow ?? []);
  const started = Date.now();
  const poll = o.pollMs ?? 200;
  const readyDeadline = o.readyDeadlineMs ?? 120_000;
  let pending = false;
  let sampleStartedAt = 0;
  let result: number | undefined;
  let failure: unknown;
  mkdirSync(o.out, { recursive: true });
  for (;;) {
    for (const w of topLevelWindows(o.pid)) {
      if (allow.has(w.hwnd) || !looksLikeMessageBox(w)) {
        continue;
      }
      const words = w.statics.concat(dialogText(w.hwnd));
      const isPathError = words.some((t) => /does not exist|check the (path|file name)/i.test(t));
      guardCounters.unexpectedModalDialogs++;
      if (isPathError) {
        guardCounters.pathErrorDialogs++;
      }
      const shot = join(o.out, "unexpected-modal-" + guardCounters.unexpectedModalDialogs + ".png");
      captureWindowDCToPng(w.hwnd, shot);
      const dialog = {
        seenAt: new Date().toISOString(),
        waitedMs: Date.now() - started,
        label: o.label,
        step: o.step,
        hwnd: w.hwnd,
        className: w.className,
        title: w.title,
        text: words,
        buttons: w.buttons,
        pid: w.pid,
        processName: processName(w.pid),
        parentPid: parentPid(w.pid),
        pathError: isPathError,
        screenshot: shot,
        otherWindows: topLevelWindows(o.pid)
          .filter((x) => x.hwnd !== w.hwnd)
          .map((x) => ({ hwnd: x.hwnd, className: x.className, title: x.title })),
      };
      guardEvents.push(dialog);
      writeFileSync(join(o.out, "unexpected-modal-" + guardCounters.unexpectedModalDialogs + ".json"),
        JSON.stringify(dialog, null, 1));
      const how = dismiss(w);
      (dialog as any).dismissed = how;
      writeFileSync(join(o.out, "unexpected-modal-" + guardCounters.unexpectedModalDialogs + ".json"),
        JSON.stringify(dialog, null, 1));
      throw new UnexpectedModalError(dialog);
    }
    const menus = topLevelWindows(o.pid).filter((w) => !allow.has(w.hwnd) && looksLikePopupMenu(w));
    if (menus.length > 0 && !o.allowMenus) {
      guardCounters.unexpectedMenus++;
      const shot = join(o.out, "unexpected-menu-" + guardCounters.unexpectedMenus + ".png");
      captureWindowDCToPng(menus[0].hwnd, shot);
      const detail = {
        seenAt: new Date().toISOString(),
        waitedMs: Date.now() - started,
        label: o.label,
        step: o.step,
        menus: menus.map((w) => ({
          hwnd: w.hwnd,
          className: w.className,
          title: w.title,
          items: w.statics.concat(dialogText(w.hwnd)),
          pid: w.pid,
          processName: processName(w.pid),
          parentPid: parentPid(w.pid),
        })),
        screenshot: shot,
      };
      guardEvents.push(detail);
      writeFileSync(join(o.out, "unexpected-menu-" + guardCounters.unexpectedMenus + ".json"),
        JSON.stringify(detail, null, 1));
      const dismissDeadline = Date.now() + 3000;
      while (topLevelWindows(o.pid).some(looksLikePopupMenu) && Date.now() < dismissDeadline) {
        realKeyPress(VK_ESCAPE);
        await sleep(100);
      }
      throw new UnexpectedModalError({ ...detail, text: ["an unexpected popup menu was open"] });
    }
    const seenDiagnostic = diagnosticWindows();
    if (seenDiagnostic.length > 0) {
      guardCounters.visibleDiagnosticWindows += seenDiagnostic.length;
      const detail = {
        seenAt: new Date().toISOString(),
        label: o.label,
        step: o.step,
        windows: seenDiagnostic.map((w) => ({
          hwnd: w.hwnd,
          title: w.title,
          pid: w.pid,
          processName: processName(w.pid),
          parentPid: parentPid(w.pid),
        })),
      };
      guardEvents.push(detail);
      writeFileSync(join(o.out, "diagnostic-window.json"), JSON.stringify(detail, null, 1));
      for (const w of seenDiagnostic) {
        captureWindowDCToPng(w.hwnd, join(o.out, "diagnostic-" + w.hwnd + ".png"));
        dismiss(w);
      }
      throw new UnexpectedModalError({ ...detail, step: o.step, text: ["a diagnostic window appeared"] });
    }
    if (failure !== undefined) {
      throw failure;
    }
    if (result) {
      guardEvents.push({ label: o.label, step: o.step, outcome: "ready",
        waitedMs: Date.now() - started, timeoutMs: o.timeoutMs });
      return result;
    }
    if (!alive(o.pid) && !o.allowProcessExit) {
      throw new ProcessGoneError("the application exited while waiting for " + o.step);
    }
    if (pending && Date.now() - sampleStartedAt >= readyDeadline) {
      const windows = topLevelWindows(o.pid);
      const detail = {
        label: o.label,
        step: o.step,
        waitedMs: Date.now() - started,
        reason: "ready() did not complete within " + readyDeadline + "ms (the condition probe hung)",
        sampleOutstandingMs: Date.now() - sampleStartedAt,
        windows,
        pid: o.pid,
        processAlive: alive(o.pid),
      };
      guardEvents.push(detail);
      writeFileSync(join(o.out, "timeout.json"), JSON.stringify(detail, null, 1));
      for (const w of windows) {
        captureWindowDCToPng(w.hwnd, join(o.out, "timeout-" + w.hwnd + ".png"));
      }
      throw new GuardTimeoutError(detail);
    }
    if (Date.now() - started >= o.timeoutMs) {
      const windows = topLevelWindows(o.pid);
      const detail = {
        label: o.label,
        step: o.step,
        waitedMs: Date.now() - started,
        windows,
        pid: o.pid,
        processAlive: alive(o.pid),
        blockedByModal: windows.some((w) => !allow.has(w.hwnd) && looksLikeMessageBox(w)),
      };
      guardEvents.push(detail);
      writeFileSync(join(o.out, "timeout.json"), JSON.stringify(detail, null, 1));
      for (const w of windows) {
        captureWindowDCToPng(w.hwnd, join(o.out, "timeout-" + w.hwnd + ".png"));
      }
      throw new GuardTimeoutError(detail);
    }
    if (!pending) {
      pending = true;
      sampleStartedAt = Date.now();
      Promise.resolve().then(o.ready).then((value) => {
        result = value;
        pending = false;
      }, (error) => {
        failure = error;
        pending = false;
      });
    }
    await sleep(poll);
  }
}

export interface FixtureReport {
  path: string;
  exists: boolean;
  readable: boolean;
  size: number;
  label: string;
  step: string;
  provenance?: any;
}

// run immediately before the path is handed to the application, so a stale or
// deleted fixture is caught by the harness rather than by a product dialog
export function assertFixture(path: string, label: string, step: string): FixtureReport {
  guardCounters.fixtureChecks++;
  const provenance = provenanceOf(path);
  if (provenance.kind !== "writable-fixture") {
    guardCounters.protectedTargetRefusals++;
    guardEvents.push({ path, label, step, provenance, refused: true });
    throw new ProtectedTargetError(
      "refusing to hand " + JSON.stringify(path) + " to " + step + ": it is " + provenance.kind +
      " (" + (provenance.canonical ?? path) + "), not an authorized writable fixture", provenance);
  }
  let exists = false;
  let readable = false;
  let size = -1;
  try {
    const st = statSync(path);
    exists = st.isFile();
    size = st.size;
  } catch (e) {
    exists = existsSync(path);
  }
  if (exists) {
    try {
      const fd = openSync(path, "r");
      const buf = Buffer.alloc(1);
      readSync(fd, buf, 0, 1, 0);
      closeSync(fd);
      readable = true;
    } catch (e) {
      readable = false;
    }
  }
  const report: FixtureReport = { path, exists, readable, size, label, step, provenance };
  if (process.env.GUI_WATCHDOG_SELFTEST === "skip-fixture-check") {
    guardEvents.push({ ...report, selfTest: "the fixture check was skipped on purpose to exercise the modal watchdog" });
    return report;
  }
  if (!exists || !readable) {
    guardCounters.missingFixtureSubmissions++;
    guardEvents.push({ ...report, refused: true });
    throw new MissingFixtureError(
      "refusing to hand " + JSON.stringify(path) + " to " + step +
      ": exists=" + exists + " readable=" + readable);
  }
  guardEvents.push({ ...report, accepted: true });
  return report;
}
