import { describe, expect, it } from "vitest";
import { createImeSearchState, SearchResultLruCache } from "../lib/imeSearch";

describe("IME-aware search query state & LRU cache (PC)", () => {
  it("freezes committedQuery while Pinyin IME is composing and commits cleanly on compositionend (Chrome order)", () => {
    const state = createImeSearchState("");

    // User starts typing Chinese Pinyin "huancun" -> "缓存"
    state.onCompositionStart();
    state.onChange("h");
    expect(state.getInputValue()).toBe("h");
    expect(state.getCommittedValue()).toBe(""); // must NOT search raw Pinyin!

    state.onChange("huan'cun");
    expect(state.getInputValue()).toBe("huan'cun");
    expect(state.getCommittedValue()).toBe(""); // list must NOT flash empty!

    // Chrome order: final onChange("缓存") arrives before compositionend
    state.onChange("缓存");
    expect(state.getCommittedValue()).toBe("");
    state.onCompositionEnd("缓存");
    expect(state.getInputValue()).toBe("缓存");
    expect(state.getCommittedValue()).toBe("缓存");
  });

  it("handles macOS WKWebView order where compositionend fires BEFORE final onChange", () => {
    const state = createImeSearchState("antigravity");

    state.onCompositionStart();
    state.onChange("antigravityhuan'cun");
    // Still frozen at previously committed "antigravity"!
    expect(state.getCommittedValue()).toBe("antigravity");

    // macOS WKWebView fires compositionend first, then final onChange
    state.onCompositionEnd("antigravity缓存");
    expect(state.getCommittedValue()).toBe("antigravity缓存");
    state.onChange("antigravity缓存");
    expect(state.getCommittedValue()).toBe("antigravity缓存");
  });

  it("updates immediately when typing non-IME text or clearing programmatically", () => {
    const state = createImeSearchState("");

    state.onChange("codex");
    expect(state.getInputValue()).toBe("codex");
    expect(state.getCommittedValue()).toBe("codex");

    // Even if clearing happens mid-composition (e.g. clicking X button), both clear immediately
    state.onCompositionStart();
    state.onChange("codexnihao");
    state.setDirect("");
    expect(state.getInputValue()).toBe("");
    expect(state.getCommittedValue()).toBe("");
  });

  it("caches recent search responses with TTL and LRU eviction", () => {
    const cache = new SearchResultLruCache<string>(2, 60_000);
    cache.set("k1", "v1", 1000);
    cache.set("k2", "v2", 1010);
    expect(cache.get("k1", 1020)).toBe("v1"); // touches k1 -> k2 becomes LRU

    cache.set("k3", "v3", 1030); // evicts k2
    expect(cache.get("k2", 1040)).toBeUndefined();
    expect(cache.get("k1", 1040)).toBe("v1");
    expect(cache.get("k3", 1040)).toBe("v3");

    // Expires after TTL
    expect(cache.get("k1", 70_000)).toBeUndefined();
  });
});
