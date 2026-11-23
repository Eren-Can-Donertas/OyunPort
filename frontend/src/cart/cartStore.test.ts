import { describe, expect, it } from 'vitest';
import {
  addItem,
  cartStorageKey,
  clearCart,
  dedupe,
  emptyCart,
  loadCart,
  MAX_CART_ITEMS,
  normalizeCart,
  removeItem,
  removeItems,
  saveCart,
  type CartState,
  type KeyValueStorage,
} from './cartStore';
import { isUuid, newUuid } from '../lib/uuid';

/** Her çağrıda farklı, öngörülebilir anahtar üreten sayaç. */
function keyGen(prefix = 'k') {
  let n = 0;
  const gen = () => `${prefix}${++n}`;
  return Object.assign(gen, { calls: () => n });
}

function memoryStorage(initial: Record<string, string> = {}): KeyValueStorage & { data: Record<string, string> } {
  const data = { ...initial };
  return {
    data,
    getItem: (k) => (k in data ? data[k] : null),
    setItem: (k, v) => {
      data[k] = v;
    },
    removeItem: (k) => {
      delete data[k];
    },
  };
}

describe('dedupe', () => {
  it('sırayı koruyarak tekrarları ve geçersiz kimlikleri atar', () => {
    expect(dedupe([3, 1, 3, 2, 1, 0, -5, 1.5, '7', null, 2])).toEqual([3, 1, 2]);
  });
});

describe('addItem', () => {
  it('yeni ürünü ekler ve checkoutKey yeniler', () => {
    const gen = keyGen();
    const s0 = emptyCart(gen); // k1
    const { state: s1, result } = addItem(s0, 10, gen);
    expect(result).toBe('added');
    expect(s1.productIds).toEqual([10]);
    expect(s1.checkoutKey).toBe('k2');
    expect(s1.checkoutKey).not.toBe(s0.checkoutKey);
  });

  it('aynı ürünü ikinci kez eklemez; içerik değişmediği için anahtar korunur', () => {
    const gen = keyGen();
    const s1 = addItem(emptyCart(gen), 10, gen).state;
    const { state: s2, result } = addItem(s1, 10, gen);
    expect(result).toBe('duplicate');
    expect(s2).toBe(s1);
    expect(s2.checkoutKey).toBe(s1.checkoutKey);
    expect(gen.calls()).toBe(2);
  });

  it('geçersiz kimliği reddeder', () => {
    const gen = keyGen();
    const s0 = emptyCart(gen);
    expect(addItem(s0, 0, gen).result).toBe('invalid');
    expect(addItem(s0, -1, gen).result).toBe('invalid');
    expect(addItem(s0, 1.5, gen).result).toBe('invalid');
  });

  it('üst sınırda "full" döner', () => {
    const gen = keyGen();
    let s: CartState = emptyCart(gen);
    for (let i = 1; i <= MAX_CART_ITEMS; i++) s = addItem(s, i, gen).state;
    const { state, result } = addItem(s, 999, gen);
    expect(result).toBe('full');
    expect(state).toBe(s);
  });
});

describe('removeItem / removeItems / clearCart', () => {
  it('ürünü çıkarır ve anahtarı yeniler', () => {
    const gen = keyGen();
    let s = emptyCart(gen);
    s = addItem(s, 1, gen).state;
    s = addItem(s, 2, gen).state;
    const before = s.checkoutKey;
    const after = removeItem(s, 1, gen);
    expect(after.productIds).toEqual([2]);
    expect(after.checkoutKey).not.toBe(before);
  });

  it('sepette olmayan ürünü çıkarmak durumu (ve anahtarı) değiştirmez', () => {
    const gen = keyGen();
    const s = addItem(emptyCart(gen), 1, gen).state;
    const same = removeItem(s, 42, gen);
    expect(same).toBe(s);
    expect(same.checkoutKey).toBe(s.checkoutKey);
  });

  it('sipariş sonrası yalnızca siparişe giren ürünleri çıkarır', () => {
    const gen = keyGen();
    let s = emptyCart(gen);
    for (const id of [1, 2, 3]) s = addItem(s, id, gen).state;
    const after = removeItems(s, [1, 3], gen);
    expect(after.productIds).toEqual([2]);
    expect(after.checkoutKey).not.toBe(s.checkoutKey);
  });

  it('tümü sipariş edilince sepet boşalır ve yeni anahtar alır', () => {
    const gen = keyGen();
    let s = emptyCart(gen);
    s = addItem(s, 5, gen).state;
    const after = removeItems(s, [5], gen);
    expect(after.productIds).toEqual([]);
    expect(after.checkoutKey).not.toBe(s.checkoutKey);
  });

  it('boş sepeti temizlemek anahtarı değiştirmez', () => {
    const gen = keyGen();
    const s = emptyCart(gen);
    expect(clearCart(s, gen)).toBe(s);
  });

  it('içerik aynı kaldıkça anahtar yeniden kullanılır (tekrar deneme idempotency)', () => {
    const gen = keyGen();
    let s = emptyCart(gen);
    s = addItem(s, 7, gen).state;
    const key = s.checkoutKey;
    s = addItem(s, 7, gen).state; // tekrar
    s = removeItem(s, 999, gen); // yok
    expect(s.checkoutKey).toBe(key);
  });
});

describe('normalizeCart', () => {
  const isKey = (k: unknown) => typeof k === 'string' && k.startsWith('k');

  it('geçerli kaydı aynen korur', () => {
    const gen = keyGen('n');
    const s = normalizeCart({ productIds: [1, 2], checkoutKey: 'k9' }, gen, isKey);
    expect(s).toEqual({ productIds: [1, 2], checkoutKey: 'k9' });
    expect(gen.calls()).toBe(0);
  });

  it('tekrarlı kayıtta tekrarları atar ve anahtarı yeniler', () => {
    const gen = keyGen('n');
    const s = normalizeCart({ productIds: [1, 1, 2], checkoutKey: 'k9' }, gen, isKey);
    expect(s).toEqual({ productIds: [1, 2], checkoutKey: 'n1' });
  });

  it('bozuk anahtar veya veri için yeni anahtar üretir', () => {
    const gen = keyGen('n');
    expect(normalizeCart({ productIds: [1], checkoutKey: 'bad' }, gen, isKey).checkoutKey).toBe('n1');
    expect(normalizeCart('çöp', gen, isKey)).toEqual({ productIds: [], checkoutKey: 'n2' });
    expect(normalizeCart(null, gen, isKey)).toEqual({ productIds: [], checkoutKey: 'n3' });
  });
});

describe('localStorage kalıcılığı', () => {
  it('kaydedilen sepeti aynı anahtarla geri yükler', () => {
    const storage = memoryStorage();
    const key = cartStorageKey(42);
    const gen = () => newUuid();
    let s = emptyCart(gen);
    s = addItem(s, 3, gen).state;
    s = addItem(s, 4, gen).state;
    saveCart(storage, key, s);

    const loaded = loadCart(storage, key, gen, isUuid);
    expect(loaded).toEqual(s);
    expect(isUuid(loaded.checkoutKey)).toBe(true);
  });

  it('kullanıcıya özel anahtar kullanır', () => {
    expect(cartStorageKey(7)).toBe('oyunport.cart.u7');
    expect(cartStorageKey(7)).not.toBe(cartStorageKey(8));
  });

  it('bozuk JSON durumunda boş sepet oluşturur ve kaydeder', () => {
    const storage = memoryStorage({ 'oyunport.cart.u1': '{bozuk' });
    const loaded = loadCart(storage, 'oyunport.cart.u1', newUuid, isUuid);
    expect(loaded.productIds).toEqual([]);
    expect(isUuid(loaded.checkoutKey)).toBe(true);
    expect(JSON.parse(storage.data['oyunport.cart.u1'])).toEqual(loaded);
  });
});

describe('newUuid', () => {
  it('RFC 4122 v4 biçiminde ve her seferinde farklı', () => {
    const a = newUuid();
    const b = newUuid();
    expect(isUuid(a)).toBe(true);
    expect(a).not.toBe(b);
  });
});
