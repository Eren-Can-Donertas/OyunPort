import { describe, expect, it } from 'vitest';
import { columnKind, columnLabel, formatCell } from './reportFormat';

const norm = (s: string) => s.replace(/[  ]/g, ' ');

describe('rapor hücresi biçimlendirme', () => {
  it('sütun adından türü çıkarır', () => {
    expect(columnKind('revenue')).toBe('money');
    expect(columnKind('total_spent')).toBe('money');
    expect(columnKind('conversion_pct')).toBe('percent');
    expect(columnKind('sales_month')).toBe('month');
    expect(columnKind('user_id')).toBe('id');
    expect(columnKind('units_sold')).toBe('text');
  });

  it('para, yüzde, ay ve kimlik değerlerini biçimlendirir', () => {
    expect(norm(formatCell('revenue', 12345.6))).toBe('₺12.345,60');
    expect(norm(formatCell('low_play_pct', 37.5))).toBe('%37,5');
    expect(formatCell('activity_month', '2026-03-01')).toBe('Mart 2026');
    expect(formatCell('product_id', 12345)).toBe('12345');
    expect(norm(formatCell('units_sold', 12345))).toBe('12.345');
    expect(formatCell('title', 'Oyun')).toBe('Oyun');
    expect(formatCell('revenue', null)).toBe('—');
  });

  it('bilinen sütunlara Türkçe başlık verir', () => {
    expect(columnLabel('units_sold')).toBe('Satış adedi');
    expect(columnLabel('bilinmeyen_sutun')).toBe('bilinmeyen_sutun');
  });
});
