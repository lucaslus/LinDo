import { useCallback, useEffect, useRef, useState } from "react";
import { checkAppUpdateInRust } from "./api";
import { diagLog } from "./diagnostics";
import type { DesktopUpdateInfo } from "./types";

const CHECK_INTERVAL_MS = 30 * 60_000;
const PROMPTED_RELEASE_KEY = "lindo_last_prompted_release";
type Prompt = "automatic" | "manual";

export function useUpdatePrompt(ready: boolean, canPrompt: boolean, notify: (message: string) => void) {
  const [info, setInfo] = useState<DesktopUpdateInfo | null>(null);
  const [prompt, setPrompt] = useState<Prompt | null>(null);
  const [checking, setChecking] = useState(false);
  const [visible, setVisible] = useState(!document.hidden && document.hasFocus());
  const pending = useRef<Promise<DesktopUpdateInfo> | null>(null);
  const lastCheckAt = useRef(0);

  const check = useCallback(() => {
    if (!pending.current) {
      lastCheckAt.current = Date.now();
      setChecking(true);
      pending.current = checkAppUpdateInRust().then(result => {
        setInfo(result);
        return result;
      }).finally(() => {
        pending.current = null;
        setChecking(false);
      });
    }
    return pending.current;
  }, []);

  useEffect(() => {
    const onVisibility = () => setVisible(!document.hidden && document.hasFocus());
    document.addEventListener("visibilitychange", onVisibility);
    window.addEventListener("focus", onVisibility);
    window.addEventListener("blur", onVisibility);
    return () => {
      document.removeEventListener("visibilitychange", onVisibility);
      window.removeEventListener("focus", onVisibility);
      window.removeEventListener("blur", onVisibility);
    };
  }, []);

  useEffect(() => {
    if (!ready || !visible || prompt) return;
    let timer: number;
    const checkIfDue = () => {
      const remaining = CHECK_INTERVAL_MS - (Date.now() - lastCheckAt.current);
      if (remaining <= 0) void check().catch(() => {});
      timer = window.setTimeout(checkIfDue, remaining > 0 ? remaining : CHECK_INTERVAL_MS);
    };
    timer = window.setTimeout(checkIfDue, 3500);
    return () => window.clearTimeout(timer);
  }, [ready, visible, prompt, check]);

  const present = useCallback((release: DesktopUpdateInfo, mode: Prompt) => {
    if (!release.hasUpdate) return;
    if (mode === "automatic" && localStorage.getItem(PROMPTED_RELEASE_KEY) === release.latestVersion) return;
    localStorage.setItem(PROMPTED_RELEASE_KEY, release.latestVersion);
    setPrompt(mode);
  }, []);

  useEffect(() => {
    if (ready && visible && canPrompt && !prompt && info) present(info, "automatic");
  }, [ready, visible, canPrompt, prompt, info, present]);

  const checkManually = async () => {
    try {
      const release = await check();
      if (release.hasUpdate) present(release, "manual");
      else notify("已是最新版本");
    } catch (error) {
      diagLog("WARN", "SYSTEM", "GitHubUpdate", "检查更新失败", String(error));
      if (info?.hasUpdate) present(info, "manual");
      else notify("检查更新失败，请稍后重试");
    }
  };

  const close = useCallback(() => setPrompt(null), []);
  return { info, checking, prompt, checkManually, close };
}
