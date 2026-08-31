import { join } from "node:path";
import { tmpdir } from "node:os";
import { mkdirSync } from "node:fs";
import { ControlCommand } from "./control.ts";
import { enumChildWindows, getClassName, getFocusedHwnd, getParent, postMessage, sendText, WM_CHAR } from "./winapi.ts";
import { captureWindowToPng, clickAt, launchControlled } from "./win-automation.ts";

const files = {
  pdf: `C:\\Users\\Nokel\\Documents\\ebooks\\manga_novels\\01 The Hitchhiker's Guide to the Galaxy - Douglas Adams.pdf`,
  mobi: `C:\\Users\\Nokel\\Documents\\ebooks\\manga_novels\\01 The Hitchhiker's Guide to the Galaxy - Douglas Adams.mobi`,
};
const queries = {
  opening: "unfashionable end of the Western Spiral arm",
  ending: "take in a quick bite at the Restaurant at the End of the Universe",
  afterword: "Douglas Adams once famously described the process",
  amendment: "now that filming on The Hitchhiker's Guide to the Galaxy is complete",
  author: "Douglas Adams was born in 1952 and created all the various",
};
const out = join(tmpdir(), "sumatrapdf-chunk38", "ui-compare");
mkdirSync(out, { recursive: true });

for (const [format, path] of Object.entries(files).filter(([name]) => !process.argv[2] || name === process.argv[2])) {
  for (const [section, query] of Object.entries(queries).filter(([name]) => !process.argv[3] || name === process.argv[3])) {
    const app = await launchControlled(["-window-pos", "1200x900@40x40", path]);
    try {
      await app.client.setNotificationsEnabled(false);
      let toolbar = 0;
      enumChildWindows(app.frame, (hwnd) => {
        if (getClassName(hwnd) === "SUMATRA_VIRT_TOOLBAR") toolbar = hwnd;
        return true;
      });
      if (!toolbar) throw new Error("toolbar not found");
      await clickAt(toolbar, 575, 20, 300);
      const search = getFocusedHwnd(app.frame);
      if (!search || getClassName(search) !== "Edit") throw new Error("Find field did not receive focus");
      sendText(search, "");
      for (const char of query) postMessage(search, WM_CHAR, char.charCodeAt(0), 0);
      await new Promise((resolve) => setTimeout(resolve, 1800));
      const findBar = getParent(search);
      await clickAt(findBar, 316, 12, 1800);
      await app.client.waitForRenderIdle();
      const page = await app.client.request(ControlCommand.TestFavoriteNav, ["page"]);
      const target = join(out, `${format}-${section}.png`);
      captureWindowToPng(app.frame, target);
      captureWindowToPng(findBar, join(out, `${format}-${section}-find.png`));
      console.log(`${format} ${section} ${String(page[1]).trim()} ${target}`);
    } finally {
      await app.client.quit();
      app.client.close();
      await app.proc.exited;
    }
  }
}
