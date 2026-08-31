import { copyFileSync, existsSync, mkdirSync } from "node:fs";
import { join } from "node:path";

const SRC = join("ext", "build", "ocr-install", "share", "tessdata", "eng.traineddata");

const targets = [
  join("out", "dbg64"),
  join("out", "rel64"),
  join("out", "rel32"),
];

let ok = true;
for (const out of targets) {
  if (!existsSync(out)) {
    continue;
  }
  const dst = join(out, "tessdata", "eng.traineddata");
  try {
    mkdirSync(join(out, "tessdata"), { recursive: true });
    copyFileSync(SRC, dst);
    console.log(`tessdata -> ${dst}`);
  } catch (e) {
    console.error(`tessdata copy failed for ${out}:`, e);
    ok = false;
  }
}

if (!ok) {
  process.exit(1);
}
