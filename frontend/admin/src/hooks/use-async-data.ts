"use client";

import { useCallback, useEffect, useRef, useState } from "react";

export interface AsyncDataState<T> {
  data: T | null;
  error: unknown;
  loading: boolean;
  refreshing: boolean;
  reload: () => void;
}

interface InternalState<T> {
  settledKey: string | null;
  hasLoaded: boolean;
  data: T | null;
  error: unknown;
}

/**
 * Controlled async loader with cancellation guard.
 * Avoids synchronous setState inside effects (React 19 compiler-safe).
 */
export function useAsyncData<T>(
  fetcher: (signal: AbortSignal) => Promise<T>,
  deps: ReadonlyArray<unknown>,
  enabled = true,
): AsyncDataState<T> {
  const [nonce, setNonce] = useState(0);
  const requestKey = enabled ? `${nonce}:${JSON.stringify(deps)}` : "disabled";

  const [state, setState] = useState<InternalState<T>>({
    settledKey: null,
    hasLoaded: false,
    data: null,
    error: null,
  });

  const fetcherRef = useRef(fetcher);
  useEffect(() => {
    fetcherRef.current = fetcher;
  });

  const reload = useCallback(() => {
    setNonce((n) => n + 1);
  }, []);

  useEffect(() => {
    if (!enabled) return;

    let cancelled = false;
    const controller = new AbortController();

    fetcherRef
      .current(controller.signal)
      .then((result) => {
        if (cancelled) return;
        setState({
          settledKey: requestKey,
          hasLoaded: true,
          data: result,
          error: null,
        });
      })
      .catch((err: unknown) => {
        if (cancelled) return;
        if (err instanceof DOMException && err.name === "AbortError") return;
        setState((prev) => ({
          settledKey: requestKey,
          hasLoaded: prev.hasLoaded,
          data: prev.data,
          error: err,
        }));
      });

    return () => {
      cancelled = true;
      controller.abort();
    };
  }, [enabled, requestKey]);

  const isPending = enabled && state.settledKey !== requestKey;

  return {
    data: enabled ? state.data : null,
    error: enabled ? state.error : null,
    loading: isPending && !state.hasLoaded,
    refreshing: isPending && state.hasLoaded,
    reload,
  };
}
