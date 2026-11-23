import fs from "node:fs";
import path from "node:path";
import { describe, expect, it } from "vitest";

const SRC_ROOT = path.resolve(__dirname, "..");

function walkFiles(dir: string): string[] {
  const results: string[] = [];
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    if (entry.name === "__tests__" || entry.name === "node_modules") continue;
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      results.push(...walkFiles(full));
    } else if (/\.(ts|tsx|css)$/.test(entry.name)) {
      results.push(full);
    }
  }
  return results;
}

describe("Lindo PC UX & visual policy static guards", () => {
  it("enforces strict 2-state cursor policy (zero resize/I-beam/zoom/grab cursors in source)", () => {
    const forbidden =
      /\bcursor-(?:ns-resize|ew-resize|col-resize|row-resize|nesw-resize|nwse-resize|zoom-in|zoom-out|grab|grabbing|text)\b|cursor:\s*(?:ns-resize|ew-resize|col-resize|row-resize|zoom-in|zoom-out|grab|grabbing|text)\b/i;
    const offenders: string[] = [];
    for (const file of walkFiles(SRC_ROOT)) {
      const text = fs.readFileSync(file, "utf8");
      const lines = text.split("\n");
      lines.forEach((line, idx) => {
        if (line.trim().startsWith("//") || line.trim().startsWith("/*") || line.trim().startsWith("*")) {
          return;
        }
        if (forbidden.test(line)) {
          offenders.push(`${path.relative(SRC_ROOT, file)}:${idx + 1}: ${line.trim()}`);
        }
      });
    }
    expect(offenders).toEqual([]);
  });

  it("ensures removed UI regressions never reappear", () => {
    const stripComments = (src: string) =>
      src
        .split("\n")
        .filter((l) => !l.trim().startsWith("//") && !l.trim().startsWith("*"))
        .join("\n");
    const readerText = stripComments(
      fs.readFileSync(path.join(SRC_ROOT, "components/TopicReaderPane.tsx"), "utf8")
    );
    const cookedText = stripComments(
      fs.readFileSync(path.join(SRC_ROOT, "lib/cooked.ts"), "utf8")
    );
    const appText = stripComments(
      fs.readFileSync(path.join(SRC_ROOT, "App.tsx"), "utf8")
    );

    expect(readerText).not.toContain("抢沙发");
    expect(cookedText).not.toContain("查看原帖");
    expect(appText).not.toContain("canDockInRightColumn");
    expect(appText).not.toContain("isDrawerComposer");
  });
});
