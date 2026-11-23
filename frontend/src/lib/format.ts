// Biçimlendirme yardımcıları (tr-TR yerel ayarı, TRY para birimi).

const LOCALE = 'tr-TR';

const moneyFmt = new Intl.NumberFormat(LOCALE, { style: 'currency', currency: 'TRY' });
const intFmt = new Intl.NumberFormat(LOCALE, { maximumFractionDigits: 0 });
const decFmt = new Intl.NumberFormat(LOCALE, { maximumFractionDigits: 2 });
const ratingFmt = new Intl.NumberFormat(LOCALE, { minimumFractionDigits: 1, maximumFractionDigits: 2 });
const pctFmt = new Intl.NumberFormat(LOCALE, { style: 'percent', minimumFractionDigits: 0, maximumFractionDigits: 2 });
const dateFmt = new Intl.DateTimeFormat(LOCALE, { day: 'numeric', month: 'long', year: 'numeric' });
const dateTimeFmt = new Intl.DateTimeFormat(LOCALE, {
  day: 'numeric',
  month: 'short',
  year: 'numeric',
  hour: '2-digit',
  minute: '2-digit',
});
const monthFmt = new Intl.DateTimeFormat(LOCALE, { month: 'long', year: 'numeric' });

export const EMPTY = '—';

/** Sayı veya sayısal metni sayıya çevirir; geçersizse null. */
export function toNumber(value: unknown): number | null {
  if (typeof value === 'number') return Number.isFinite(value) ? value : null;
  if (typeof value === 'string' && value.trim() !== '') {
    const n = Number(value);
    return Number.isFinite(n) ? n : null;
  }
  return null;
}

/** 199.9 -> "₺199,90" */
export function formatMoney(value: number | string | null | undefined): string {
  const n = toNumber(value);
  return n === null ? EMPTY : moneyFmt.format(n);
}

/** 12345 -> "12.345" */
export function formatInt(value: number | null | undefined): string {
  const n = toNumber(value);
  return n === null ? EMPTY : intFmt.format(n);
}

/** 1234.5 -> "1.234,5" (en fazla 2 ondalık) */
export function formatDecimal(value: number | null | undefined): string {
  const n = toNumber(value);
  return n === null ? EMPTY : decFmt.format(n);
}

/** 4.25 -> "4,25"; null -> "—" */
export function formatRating(value: number | null | undefined): string {
  const n = toNumber(value);
  return n === null ? EMPTY : ratingFmt.format(n);
}

/** Yüzde değeri (0-100 ölçeğinde): 12.5 -> "%12,5" */
export function formatPercent(value: number | null | undefined): string {
  const n = toNumber(value);
  return n === null ? EMPTY : pctFmt.format(n / 100);
}

/**
 * Oynama süresi (saniye) -> "2 sa 5 dk", "45 dk", "3 sa", "1 dk'dan az", "0 dk".
 * Negatif veya geçersiz değerler 0 kabul edilir.
 */
export function formatDuration(totalSeconds: number | null | undefined): string {
  const s = Math.max(0, Math.floor(toNumber(totalSeconds) ?? 0));
  if (s > 0 && s < 60) return "1 dk'dan az";
  const totalMinutes = Math.floor(s / 60);
  const hours = Math.floor(totalMinutes / 60);
  const minutes = totalMinutes % 60;
  if (hours === 0) return `${minutes} dk`;
  if (minutes === 0) return `${intFmt.format(hours)} sa`;
  return `${intFmt.format(hours)} sa ${minutes} dk`;
}

const LOCAL_DATE_RE = /^(\d{4})-(\d{2})-(\d{2})$/;

/**
 * "yyyy-mm-dd" (LocalDate) veya ISO zaman damgasını Date'e çevirir.
 * Yalnızca tarih içeren değerler yerel gece yarısı olarak yorumlanır (saat dilimi kayması olmaz).
 */
export function parseDate(value: string | null | undefined): Date | null {
  if (!value) return null;
  const m = LOCAL_DATE_RE.exec(value);
  if (m) return new Date(Number(m[1]), Number(m[2]) - 1, Number(m[3]));
  const d = new Date(value);
  return Number.isNaN(d.getTime()) ? null : d;
}

/** "2026-11-23" -> "23 Kasım 2026" */
export function formatDate(value: string | null | undefined): string {
  const d = parseDate(value);
  return d ? dateFmt.format(d) : EMPTY;
}

/** ISO zaman damgası -> "23 Kas 2026 14:05" */
export function formatDateTime(value: string | null | undefined): string {
  const d = parseDate(value);
  return d ? dateTimeFmt.format(d) : EMPTY;
}

/** "2026-11-01" -> "Kasım 2026" */
export function formatMonth(value: string | null | undefined): string {
  const d = parseDate(value);
  return d ? monthFmt.format(d) : EMPTY;
}

export function isLocalDateString(value: unknown): value is string {
  return typeof value === 'string' && LOCAL_DATE_RE.test(value);
}

/** UUID gibi uzun anahtarları kısaltır: "1f0c3a2b…9e1d" */
export function shortKey(value: string | null | undefined): string {
  if (!value) return EMPTY;
  return value.length > 14 ? `${value.slice(0, 8)}…${value.slice(-4)}` : value;
}

export const PRODUCT_TYPE_LABEL: Record<string, string> = {
  BASE_GAME: 'Ana oyun',
  DLC: 'DLC',
};

export const ORDER_STATUS_LABEL: Record<string, string> = {
  PENDING: 'Ödeme bekliyor',
  COMPLETED: 'Tamamlandı',
  CANCELLED: 'İptal edildi',
};

export const PAYMENT_STATUS_LABEL: Record<string, string> = {
  SUCCESS: 'Başarılı',
  FAILED: 'Başarısız',
};

export const ROLE_LABEL: Record<string, string> = {
  CUSTOMER: 'Müşteri',
  ADMIN: 'Yönetici',
};
