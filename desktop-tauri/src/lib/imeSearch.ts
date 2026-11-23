import type React from "react";
import { useCallback, useRef, useState } from "react";

export interface ImeSearchState {
  getInputValue(): string;
  getCommittedValue(): string;
  isComposing(): boolean;
  onCompositionStart(): void;
  onChange(nextValue: string): void;
  onCompositionEnd(finalValue: string): void;
  setDirect(nextValue: string): void;
}

/**
 * 纯状态机：把「输入框显示值 inputValue」与「真正用于过滤/搜索的已上屏值 committedValue」彻底解耦。
 *
 * - 拼音拼写未按空格/选词期间（compositionstart ~ compositionend）：
 *   `inputValue` 实时同步保证光标和候选窗 0 延迟，`committedValue` 保持冻结，
 *   不触发无意义的拼音字母云端请求，也不会把本地列表瞬间闪空成「未找到」。
 * - 同时兼容 Chrome/WebView2（onChange 先于 compositionend）和 macOS WKWebView（compositionend 先于 onChange）。
 */
export function createImeSearchState(initialValue = ""): ImeSearchState {
  let inputValue = initialValue;
  let committedValue = initialValue;
  let composing = false;

  return {
    getInputValue: () => inputValue,
    getCommittedValue: () => committedValue,
    isComposing: () => composing,
    onCompositionStart() {
      composing = true;
    },
    onChange(nextValue: string) {
      inputValue = nextValue;
      if (!composing || nextValue === "") {
        committedValue = nextValue;
      }
    },
    onCompositionEnd(finalValue: string) {
      composing = false;
      inputValue = finalValue;
      committedValue = finalValue;
    },
    setDirect(nextValue: string) {
      composing = false;
      inputValue = nextValue;
      committedValue = nextValue;
    },
  };
}

/**
 * 轻量内存 LRU + TTL 搜索结果缓存：
 * 退格回到上一关键词或切换排序/历史词时 0ms 瞬出结果，避免重复拉网和白屏抖动。
 */
export class SearchResultLruCache<T> {
  private readonly map = new Map<string, { value: T; expiresAt: number }>();

  constructor(
    private readonly maxEntries = 32,
    private readonly ttlMs = 60_000
  ) {}

  get(key: string, now: number = Date.now()): T | undefined {
    const entry = this.map.get(key);
    if (!entry) return undefined;
    if (now > entry.expiresAt) {
      this.map.delete(key);
      return undefined;
    }
    // refresh LRU order
    this.map.delete(key);
    this.map.set(key, entry);
    return entry.value;
  }

  set(key: string, value: T, now: number = Date.now()): void {
    if (this.map.has(key)) {
      this.map.delete(key);
    } else if (this.map.size >= this.maxEntries) {
      const oldestKey = this.map.keys().next().value;
      if (oldestKey !== undefined) {
        this.map.delete(oldestKey);
      }
    }
    this.map.set(key, { value, expiresAt: now + this.ttlMs });
  }

  clear(): void {
    this.map.clear();
  }
}

export interface UseImeSearchInputResult {
  inputValue: string;
  committedValue: string;
  isComposing: boolean;
  setDirect: (next: string) => void;
  inputProps: {
    value: string;
    onChange: (e: React.ChangeEvent<HTMLInputElement>) => void;
    onCompositionStart: () => void;
    onCompositionEnd: (e: React.CompositionEvent<HTMLInputElement>) => void;
  };
}

export function useImeSearchInput(initialValue = ""): UseImeSearchInputResult {
  const [inputValue, setInputValue] = useState(initialValue);
  const [committedValue, setCommittedValue] = useState(initialValue);
  const [isComposing, setIsComposing] = useState(false);
  const composingRef = useRef(false);

  const onCompositionStart = useCallback(() => {
    composingRef.current = true;
    setIsComposing(true);
  }, []);

  const onChange = useCallback((e: React.ChangeEvent<HTMLInputElement>) => {
    const next = e.target.value;
    setInputValue(next);
    if (!composingRef.current || next === "") {
      setCommittedValue(next);
    }
  }, []);

  const onCompositionEnd = useCallback((e: React.CompositionEvent<HTMLInputElement>) => {
    const finalValue = e.currentTarget.value;
    composingRef.current = false;
    setIsComposing(false);
    setInputValue(finalValue);
    setCommittedValue(finalValue);
  }, []);

  const setDirect = useCallback((next: string) => {
    composingRef.current = false;
    setIsComposing(false);
    setInputValue(next);
    setCommittedValue(next);
  }, []);

  return {
    inputValue,
    committedValue,
    isComposing,
    setDirect,
    inputProps: {
      value: inputValue,
      onChange,
      onCompositionStart,
      onCompositionEnd,
    },
  };
}
