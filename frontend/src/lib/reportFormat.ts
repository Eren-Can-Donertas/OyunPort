// Admin rapor tablolarında sütun adına göre değer biçimlendirme.
import type { ReportCell } from '../api/types';
import {
  EMPTY,
  formatDate,
  formatDateTime,
  formatDecimal,
  formatMoney,
  formatMonth,
  formatPercent,
  isLocalDateString,
  toNumber,
} from './format';

/** Bilinen rapor sütunları için Türkçe başlıklar (bilinmeyenler ham adla gösterilir). */
export const COLUMN_LABELS: Record<string, string> = {
  // overview
  active_users: 'Aktif kullanıcı',
  active_products: 'Aktif ürün',
  base_games: 'Ana oyun',
  dlcs: 'DLC',
  completed_orders: 'Tamamlanan sipariş',
  pending_orders: 'Bekleyen sipariş',
  cancelled_orders: 'İptal edilen sipariş',
  total_revenue: 'Toplam gelir',
  failed_payments: 'Başarısız ödeme',
  library_entries: 'Kütüphane kaydı',
  play_sessions: 'Oynama oturumu',
  reviews: 'Yorum',
  // satış / Q1
  sales_month: 'Ay',
  units_sold: 'Satış adedi',
  revenue: 'Gelir',
  distinct_products: 'Farklı ürün',
  revenue_rank: 'Sıra',
  publisher_id: 'Publisher no',
  publisher_name: 'Publisher',
  month_revenue: 'Aylık toplam gelir',
  revenue_share_pct: 'Gelir payı',
  // Q2
  genre_id: 'Tür no',
  genre_name: 'Tür',
  genre_rank: 'Tür içi sıra',
  product_id: 'Ürün no',
  title: 'Ürün',
  // Q3
  user_id: 'Kullanıcı no',
  display_name: 'Görünen ad',
  email: 'E-posta',
  total_spent: 'Toplam harcama',
  avg_customer_spend: 'Ort. müşteri harcaması',
  spend_ratio: 'Harcama / ortalama',
  // Q4
  eligible_owners: 'Uygun sahip',
  low_play_owners: '< 1 saat oynayan',
  low_play_pct: 'Az oynayan oranı',
  avg_hours_played: 'Ort. oynama (saat)',
  // Q5
  base_game_id: 'Ana oyun no',
  dlc_count: 'DLC sayısı',
  base_owners: 'Ana oyun sahibi',
  dlc_buyers: 'DLC alan',
  conversion_pct: 'Dönüşüm oranı',
  // kullanıcı etkinliği
  activity_month: 'Ay',
  active_players: 'Aktif oyuncu',
  sessions: 'Oturum',
  hours_played: 'Oynama (saat)',
};

export type ColumnKind = 'money' | 'percent' | 'month' | 'id' | 'number' | 'text' | 'date';

const MONEY_COLS = new Set(['revenue', 'total_revenue', 'month_revenue', 'total_spent', 'avg_customer_spend']);

export function columnKind(column: string): ColumnKind {
  const c = column.toLowerCase();
  if (MONEY_COLS.has(c) || c.endsWith('_revenue') || c.endsWith('_spent') || c.endsWith('_amount')) return 'money';
  if (c.endsWith('_pct')) return 'percent';
  if (c.endsWith('_month')) return 'month';
  if (c === 'id' || c.endsWith('_id')) return 'id';
  return 'text';
}

export function columnLabel(column: string): string {
  return COLUMN_LABELS[column] ?? column;
}

/** Sağa hizalanması gereken (sayısal) sütun mu? */
export function isNumericColumn(column: string, sample: ReportCell | undefined): boolean {
  const k = columnKind(column);
  if (k === 'money' || k === 'percent' || k === 'id') return true;
  return typeof sample === 'number';
}

export function formatCell(column: string, value: ReportCell | undefined): string {
  if (value === null || value === undefined) return EMPTY;
  const kind = columnKind(column);
  switch (kind) {
    case 'money':
      return formatMoney(toNumber(value));
    case 'percent':
      return formatPercent(toNumber(value));
    case 'month':
      return typeof value === 'string' ? formatMonth(value) : String(value);
    case 'id':
      return String(value);
    default:
      break;
  }
  if (typeof value === 'boolean') return value ? 'Evet' : 'Hayır';
  if (typeof value === 'number') return formatDecimal(value);
  if (isLocalDateString(value)) return formatDate(value);
  if (/^\d{4}-\d{2}-\d{2}T/.test(value)) return formatDateTime(value);
  return value;
}
