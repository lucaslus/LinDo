import React, { act } from "react";
import { createRoot, type Root } from "react-dom/client";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { DesktopUpdateModal } from "../components/SecondaryScreens";
import { useUpdatePrompt } from "../lib/useUpdatePrompt";
import { checkAppUpdateInRust } from "../lib/api";
import type { DesktopUpdateInfo } from "../lib/types";

vi.mock("../lib/api", async importOriginal => ({
  ...await importOriginal<typeof import("../lib/api")>(),
  checkAppUpdateInRust: vi.fn(),
}));

const check = vi.mocked(checkAppUpdateInRust);
const download = vi.fn();
const notify = vi.fn();
const release: DesktopUpdateInfo = {
  currentVersion: "1.0.0", latestVersion: "1.1.0", hasUpdate: true, arch: "arm64",
  notes: ["一条更新记录"], zipUrl: "", dmgUrl: "https://example.com/a.dmg", sha256: "", updatedAt: "",
};
let root: Root;
let host: HTMLDivElement;

function Screen({ canPrompt }: { canPrompt: boolean }) {
  const { info, prompt, checkManually, close } = useUpdatePrompt(true, canPrompt, notify);
  return React.createElement(React.Fragment, null,
    React.createElement("button", { onClick: checkManually }, "检查更新"),
    info && prompt && React.createElement(DesktopUpdateModal, {
      info, compact: prompt === "automatic", installing: false, progress: null,
      error: null, onStartInstall: download, onClose: close,
    }),
  );
}

async function render(canPrompt: boolean) {
  await act(() => root.render(React.createElement(Screen, { canPrompt })));
}
async function tick(ms: number) {
  await act(() => vi.advanceTimersByTimeAsync(ms));
}
async function click(label: string) {
  const button = [...host.querySelectorAll("button")].find(b => b.textContent === label);
  expect(button).toBeDefined();
  await act(() => button!.click());
}

beforeEach(() => {
  (globalThis as any).IS_REACT_ACT_ENVIRONMENT = true;
  vi.useFakeTimers();
  vi.spyOn(document, "hasFocus").mockReturnValue(true);
  localStorage.clear();
  vi.clearAllMocks();
  check.mockResolvedValue(release);
  host = document.createElement("div");
  document.body.append(host);
  root = createRoot(host);
});
afterEach(async () => {
  await act(() => root.unmount());
  host.remove();
  vi.restoreAllMocks();
  vi.useRealTimers();
});

it("waits for interrupted work, then offers download or later; settings can reopen the same release", async () => {
  await render(false);
  await tick(3500);
  expect(check).toHaveBeenCalledTimes(1);
  expect(host.querySelector('[role="dialog"]')).toBeNull();
  await render(true);
  expect(host.textContent).toContain("发现新版本 v1.1.0");
  expect(host.textContent).not.toContain(release.notes[0]);
  expect(download).not.toHaveBeenCalled();
  await click("稍后");
  await act(() => root.unmount());
  root = createRoot(host);
  await render(true);
  await tick(3500);
  expect(host.querySelector('[role="dialog"]')).toBeNull();
  await click("检查更新");
  expect(host.textContent).toContain(release.notes[0]);
  await click("下载更新");
  expect(download).toHaveBeenCalledOnce();
  await click("稍后");
  check.mockResolvedValue({ ...release, latestVersion: "1.2.0" });
  await tick(30 * 60_000);
  expect(host.textContent).toContain("发现新版本 v1.2.0");
});

it("keeps startup failures silent and only reports up-to-date for a manual check", async () => {
  check.mockRejectedValueOnce(new Error("offline")).mockResolvedValue({ ...release, hasUpdate: false });
  await render(true);
  await tick(3500);
  expect(host.querySelector('[role="dialog"]')).toBeNull();
  expect(notify).not.toHaveBeenCalled();
  await click("检查更新");
  expect(notify).toHaveBeenCalledWith("已是最新版本");
  expect(host.querySelector('[role="dialog"]')).toBeNull();
});
