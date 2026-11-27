import fs from "node:fs";
import path from "node:path";
import { describe, expect, it } from "vitest";

const SRC_ROOT = path.resolve(__dirname, "..");

describe("Lindo PC UX & visual policy static guards", () => {
  it("leaves mouse motion, cursor updates and wheel events to AppKit and WebKit", () => {
    const bridge = fs.readFileSync(path.join(SRC_ROOT, "../src-tauri/native/macos_webkit_bridge.m"), "utf8");
    expect(bridge).not.toMatch(/NSEventMask(?:MouseMoved|CursorUpdate|ScrollWheel)|\bNSCursor\b/);
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
