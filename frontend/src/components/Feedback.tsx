import type { ReactNode } from 'react';
import { ApiError, errorMessage } from '../api/client';

/** Yükleniyor göstergesi (ekran okuyucular için role="status"). */
export function Loading({ label = 'Yükleniyor…' }: { label?: string }) {
  return (
    <div className="loading" role="status" aria-live="polite">
      <span className="spinner" aria-hidden="true" />
      <span>{label}</span>
    </div>
  );
}

/** Backend hata mesajını ve varsa ayrıntılarını (details[]) gösterir. */
export function ErrorBox({ error, onRetry, title }: { error: unknown; onRetry?: () => void; title?: string }) {
  if (!error) return null;
  const details = error instanceof ApiError ? error.details : [];
  const code = error instanceof ApiError && error.status > 0 ? `${error.status} · ${error.code}` : null;
  return (
    <div className="alert alert-error" role="alert">
      {title && <strong className="alert-title">{title}</strong>}
      <p className="alert-message">{errorMessage(error)}</p>
      {details.length > 0 && (
        <ul className="alert-details">
          {details.map((d, i) => (
            <li key={i}>{d}</li>
          ))}
        </ul>
      )}
      <div className="alert-footer">
        {code && <span className="alert-code">{code}</span>}
        {onRetry && (
          <button type="button" className="btn btn-small" onClick={onRetry}>
            Tekrar dene
          </button>
        )}
      </div>
    </div>
  );
}

type NoticeKind = 'success' | 'info' | 'warning' | 'error';

export function Notice({ kind = 'info', children, onClose }: { kind?: NoticeKind; children: ReactNode; onClose?: () => void }) {
  return (
    <div className={`alert alert-${kind}`} role={kind === 'error' ? 'alert' : 'status'}>
      <div className="alert-body">{children}</div>
      {onClose && (
        <button type="button" className="alert-close" onClick={onClose} aria-label="Bildirimi kapat">
          ×
        </button>
      )}
    </div>
  );
}

export function EmptyState({ children }: { children: ReactNode }) {
  return <div className="empty">{children}</div>;
}
