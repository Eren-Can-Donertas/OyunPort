import type { ReactNode } from 'react';
import { ORDER_STATUS_LABEL, PAYMENT_STATUS_LABEL, PRODUCT_TYPE_LABEL, formatRating } from '../lib/format';

type Tone = 'neutral' | 'accent' | 'success' | 'warning' | 'danger' | 'info';

export function Badge({ tone = 'neutral', children, title }: { tone?: Tone; children: ReactNode; title?: string }) {
  return (
    <span className={`badge badge-${tone}`} title={title}>
      {children}
    </span>
  );
}

export function TypeBadge({ type }: { type: string | null | undefined }) {
  if (!type) return null;
  return <Badge tone={type === 'DLC' ? 'info' : 'accent'}>{PRODUCT_TYPE_LABEL[type] ?? type}</Badge>;
}

const ORDER_TONE: Record<string, Tone> = { PENDING: 'warning', COMPLETED: 'success', CANCELLED: 'neutral' };

export function OrderStatusBadge({ status }: { status: string }) {
  return <Badge tone={ORDER_TONE[status] ?? 'neutral'}>{ORDER_STATUS_LABEL[status] ?? status}</Badge>;
}

export function PaymentStatusBadge({ status }: { status: string }) {
  return (
    <Badge tone={status === 'SUCCESS' ? 'success' : 'danger'}>{PAYMENT_STATUS_LABEL[status] ?? status}</Badge>
  );
}

/** Yıldızlı puan gösterimi; ekran okuyucu için metin etiketi içerir. */
export function Stars({ value, count }: { value: number | null | undefined; count?: number }) {
  if (value === null || value === undefined) {
    return <span className="stars muted">Henüz puan yok</span>;
  }
  const rounded = Math.round(value);
  const label = `5 üzerinden ${formatRating(value)} puan${count !== undefined ? `, ${count} değerlendirme` : ''}`;
  return (
    <span className="stars" aria-label={label} title={label}>
      <span aria-hidden="true">
        <span className="stars-on">{'★'.repeat(rounded)}</span>
        <span className="stars-off">{'★'.repeat(Math.max(0, 5 - rounded))}</span>
      </span>
      <span className="stars-num" aria-hidden="true">
        {formatRating(value)}
        {count !== undefined && <span className="muted"> ({count})</span>}
      </span>
    </span>
  );
}
