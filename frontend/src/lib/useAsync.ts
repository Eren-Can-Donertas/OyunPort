import { useCallback, useEffect, useRef, useState, type DependencyList } from 'react';

export interface AsyncState<T> {
  data: T | undefined;
  error: unknown;
  loading: boolean;
  /** Aynı sorguyu yeniden çalıştırır (mevcut veri yükleme bitene kadar ekranda kalır). */
  reload: () => void;
  /** Sunucu yanıtıyla yerel veriyi doğrudan güncellemek için. */
  setData: (updater: (prev: T | undefined) => T | undefined) => void;
}

export interface AsyncOptions {
  /** true: bağımlılıklar değişince (ör. sayfa değişimi) eski veri yeni yanıt gelene kadar gösterilir. */
  keepPrevious?: boolean;
}

/**
 * Bağımlılıklar değiştiğinde asenkron yükleme yapar; eski isteklerin geç gelen yanıtlarını yok sayar.
 */
export function useAsync<T>(fn: () => Promise<T>, deps: DependencyList, options: AsyncOptions = {}): AsyncState<T> {
  const [data, setDataState] = useState<T | undefined>(undefined);
  const [error, setError] = useState<unknown>(null);
  const [loading, setLoading] = useState(true);
  const [tick, setTick] = useState(0);
  const fnRef = useRef(fn);
  fnRef.current = fn;
  const lastTick = useRef(tick);
  const keepPrevious = options.keepPrevious ?? false;

  useEffect(() => {
    let cancelled = false;
    const isReload = lastTick.current !== tick;
    lastTick.current = tick;
    if (!isReload && !keepPrevious) setDataState(undefined);
    setLoading(true);
    setError(null);
    fnRef.current().then(
      (result) => {
        if (cancelled) return;
        setDataState(result);
        setLoading(false);
      },
      (e: unknown) => {
        if (cancelled) return;
        setError(e);
        setLoading(false);
      },
    );
    return () => {
      cancelled = true;
    };
  }, [...deps, tick]);

  const reload = useCallback(() => setTick((t) => t + 1), []);
  const setData = useCallback((updater: (prev: T | undefined) => T | undefined) => {
    setDataState((prev) => updater(prev));
  }, []);

  return { data, error, loading, reload, setData };
}
