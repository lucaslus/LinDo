import { diagLog } from "./diagnostics";

export function observeScrollActivity(): () => void {
  let idleTimer: ReturnType<typeof setTimeout> | undefined;
  const onScroll = () => {
    if (idleTimer === undefined) document.documentElement.classList.add("lindo-scrolling");
    else clearTimeout(idleTimer);
    idleTimer = setTimeout(() => {
      document.documentElement.classList.remove("lindo-scrolling");
      idleTimer = undefined;
    }, 240);
  };
  window.addEventListener("scroll", onScroll, { capture: true, passive: true });

  return () => {
    window.removeEventListener("scroll", onScroll, true);
    clearTimeout(idleTimer);
    document.documentElement.classList.remove("lindo-scrolling");
  };
}

export function measureDisplayRefresh(): () => void {
  let frame = 0;
  const timer = setTimeout(() => {
    if (document.hidden) return;
    const samples: number[] = [];
    const probe = (ts: number) => {
      samples.push(ts);
      if (samples.length < 38) {
        frame = requestAnimationFrame(probe);
        return;
      }
      const deltas: number[] = [];
      for (let i = 1; i < samples.length; i++) {
        const dt = samples[i] - samples[i - 1];
        if (dt > 1 && dt < 100) deltas.push(dt);
      }
      if (deltas.length >= 10) {
        deltas.sort((a, b) => a - b);
        const med = deltas[Math.floor(deltas.length / 2)];
        const p90 = deltas[Math.floor(deltas.length * 0.9)];
        const fps = 1000 / med;
        diagLog(
          fps < 75 ? "WARN" : "INFO",
          "SYSTEM",
          "DisplayRefresh",
          `渲染刷新率实测 ~${fps.toFixed(1)}Hz (中位 ${med.toFixed(2)}ms · P90 ${p90.toFixed(2)}ms)`
        );
      }
    };
    frame = requestAnimationFrame(probe);
  }, 1600);
  return () => {
    clearTimeout(timer);
    cancelAnimationFrame(frame);
  };
}
