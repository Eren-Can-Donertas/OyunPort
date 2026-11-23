import { formatInt } from '../lib/format';

interface Props {
  page: number; // 0 tabanlı
  size: number;
  total: number;
  onChange: (page: number) => void;
  disabled?: boolean;
}

export function Pagination({ page, size, total, onChange, disabled }: Props) {
  const pageCount = Math.max(1, Math.ceil(total / Math.max(1, size)));
  const current = Math.min(page, pageCount - 1);
  const go = (p: number) => {
    if (p >= 0 && p < pageCount && p !== current) onChange(p);
  };
  return (
    <nav className="pagination" aria-label="Sayfalama">
      <button type="button" className="btn btn-ghost btn-small" onClick={() => go(0)} disabled={disabled || current === 0} aria-label="İlk sayfa">
        «
      </button>
      <button type="button" className="btn btn-ghost btn-small" onClick={() => go(current - 1)} disabled={disabled || current === 0}>
        ‹ Önceki
      </button>
      <span className="pagination-info" aria-live="polite">
        Sayfa {formatInt(current + 1)} / {formatInt(pageCount)}
        <span className="muted"> · {formatInt(total)} kayıt</span>
      </span>
      <button
        type="button"
        className="btn btn-ghost btn-small"
        onClick={() => go(current + 1)}
        disabled={disabled || current >= pageCount - 1}
      >
        Sonraki ›
      </button>
      <button
        type="button"
        className="btn btn-ghost btn-small"
        onClick={() => go(pageCount - 1)}
        disabled={disabled || current >= pageCount - 1}
        aria-label="Son sayfa"
      >
        »
      </button>
    </nav>
  );
}
