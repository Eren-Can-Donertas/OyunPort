import { describe, expect, it } from 'vitest';
import {
  formatDate,
  formatDuration,
  formatInt,
  formatMoney,
  formatMonth,
  formatPercent,
  formatRating,
  parseDate,
  shortKey,
} from './format';

/** Intl çıktısındaki bölünmez boşlukları normal boşluğa çevirir. */
const norm = (s: string) => s.replace(/[  ]/g, ' ');

describe('formatMoney', () => {
  it('TRY ve tr-TR ayırıcılarıyla biçimlendirir', () => {
    expect(norm(formatMoney(1234.5))).toBe('₺1.234,50');
    expect(norm(formatMoney(0))).toBe('₺0,00');
    expect(norm(formatMoney(199.9))).toBe('₺199,90');
  });

  it('sayısal metni kabul eder, geçersiz değerde tire döner', () => {
    expect(norm(formatMoney('49.99'))).toBe('₺49,99');
    expect(formatMoney(null)).toBe('—');
    expect(formatMoney(undefined)).toBe('—');
    expect(formatMoney('abc')).toBe('—');
  });
});

describe('formatDuration', () => {
  it('saat ve dakika olarak gösterir', () => {
    expect(formatDuration(0)).toBe('0 dk');
    expect(formatDuration(30)).toBe("1 dk'dan az");
    expect(formatDuration(60)).toBe('1 dk');
    expect(formatDuration(45 * 60 + 59)).toBe('45 dk');
    expect(formatDuration(3600)).toBe('1 sa');
    expect(formatDuration(3600 + 5 * 60)).toBe('1 sa 5 dk');
    expect(formatDuration(125 * 3600 + 59 * 60)).toBe('125 sa 59 dk');
  });

  it('binlik ayırıcı kullanır ve geçersiz değeri sıfır sayar', () => {
    expect(norm(formatDuration(1234 * 3600))).toBe('1.234 sa');
    expect(formatDuration(-10)).toBe('0 dk');
    expect(formatDuration(null)).toBe('0 dk');
  });
});

describe('tarih biçimleri', () => {
  it('LocalDate metnini saat dilimi kayması olmadan yorumlar', () => {
    const d = parseDate('2026-01-01');
    expect(d?.getFullYear()).toBe(2026);
    expect(d?.getMonth()).toBe(0);
    expect(d?.getDate()).toBe(1);
  });

  it('tr-TR ay adlarını kullanır', () => {
    expect(formatDate('2026-11-23')).toBe('23 Kasım 2026');
    expect(formatMonth('2026-05-01')).toBe('Mayıs 2026');
    expect(formatDate(null)).toBe('—');
    expect(formatDate('geçersiz')).toBe('—');
  });
});

describe('sayı biçimleri', () => {
  it('puan, tamsayı ve yüzde', () => {
    expect(formatRating(4.25)).toBe('4,25');
    expect(formatRating(4)).toBe('4,0');
    expect(formatRating(null)).toBe('—');
    expect(norm(formatInt(1234567))).toBe('1.234.567');
    expect(norm(formatPercent(12.5))).toBe('%12,5');
  });

  it('uzun anahtarı kısaltır', () => {
    expect(shortKey('1f0c3a2b-1111-4222-8333-444455559e1d')).toBe('1f0c3a2b…9e1d');
    expect(shortKey(null)).toBe('—');
  });
});
