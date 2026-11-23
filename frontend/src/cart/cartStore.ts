// Sepet istemcide tutulur (Tasarım notları Ç1: şemada CART tablosu yok).
// Sepet = ürün kimlikleri + bu sepet içeriğine ait T1 idempotency anahtarı (checkoutKey).
// Kural: içerik değişince yeni checkoutKey üretilir; içerik aynı kaldıkça aynı anahtar
// yeniden kullanılır (T1 tekrar denemesi sunucuda aynı siparişi döndürür).

/** Backend CheckoutService.MAX_ITEMS_PER_ORDER ile aynı. */
export const MAX_CART_ITEMS = 25;

export interface CartState {
  productIds: number[];
  checkoutKey: string;
}

export type KeyGenerator = () => string;

export type AddResult = 'added' | 'duplicate' | 'full' | 'invalid';

/** Minimal Storage arayüzü (testlerde bellek içi sahte nesne kullanılır). */
export interface KeyValueStorage {
  getItem(key: string): string | null;
  setItem(key: string, value: string): void;
  removeItem(key: string): void;
}

export function isValidProductId(id: unknown): id is number {
  return typeof id === 'number' && Number.isSafeInteger(id) && id > 0;
}

/** Sırayı koruyarak tekrarları ve geçersiz kimlikleri ayıklar. */
export function dedupe(ids: readonly unknown[]): number[] {
  const seen = new Set<number>();
  const out: number[] = [];
  for (const id of ids) {
    if (isValidProductId(id) && !seen.has(id)) {
      seen.add(id);
      out.push(id);
    }
  }
  return out;
}

export function emptyCart(genKey: KeyGenerator): CartState {
  return { productIds: [], checkoutKey: genKey() };
}

export function sameContents(a: readonly number[], b: readonly number[]): boolean {
  if (a.length !== b.length) return false;
  const set = new Set(a);
  return b.every((id) => set.has(id));
}

/** İçerik değiştiyse yeni anahtarla yeni durum; değişmediyse aynı nesneyi döndürür. */
function withContents(state: CartState, productIds: number[], genKey: KeyGenerator): CartState {
  if (sameContents(state.productIds, productIds)) return state;
  return { productIds, checkoutKey: genKey() };
}

export function addItem(
  state: CartState,
  productId: number,
  genKey: KeyGenerator,
  max: number = MAX_CART_ITEMS,
): { state: CartState; result: AddResult } {
  if (!isValidProductId(productId)) return { state, result: 'invalid' };
  if (state.productIds.includes(productId)) return { state, result: 'duplicate' };
  if (state.productIds.length >= max) return { state, result: 'full' };
  return { state: withContents(state, [...state.productIds, productId], genKey), result: 'added' };
}

export function removeItem(state: CartState, productId: number, genKey: KeyGenerator): CartState {
  return removeItems(state, [productId], genKey);
}

/** T1 başarılı olduktan sonra siparişe giren ürünleri sepetten çıkarır. */
export function removeItems(state: CartState, productIds: readonly number[], genKey: KeyGenerator): CartState {
  const drop = new Set(productIds);
  return withContents(
    state,
    state.productIds.filter((id) => !drop.has(id)),
    genKey,
  );
}

export function clearCart(state: CartState, genKey: KeyGenerator): CartState {
  return withContents(state, [], genKey);
}

/**
 * Saklanan ham değeri doğrular. Bozuk/eksik anahtar yeniden üretilir; tekrar eden
 * kimlikler ayıklanır (bu durumda içerik değiştiği için anahtar da yenilenir).
 */
export function normalizeCart(raw: unknown, genKey: KeyGenerator, isKey: (k: unknown) => boolean): CartState {
  if (!raw || typeof raw !== 'object') return emptyCart(genKey);
  const r = raw as { productIds?: unknown; checkoutKey?: unknown };
  const list = Array.isArray(r.productIds) ? r.productIds : [];
  const ids = dedupe(list).slice(0, MAX_CART_ITEMS);
  const unchanged = ids.length === list.length;
  if (unchanged && isKey(r.checkoutKey)) {
    return { productIds: ids, checkoutKey: r.checkoutKey as string };
  }
  return { productIds: ids, checkoutKey: genKey() };
}

export function cartStorageKey(userId: number): string {
  return `oyunport.cart.u${userId}`;
}

export function loadCart(
  storage: KeyValueStorage,
  key: string,
  genKey: KeyGenerator,
  isKey: (k: unknown) => boolean,
): CartState {
  let parsed: unknown = null;
  try {
    const raw = storage.getItem(key);
    parsed = raw ? JSON.parse(raw) : null;
  } catch {
    parsed = null;
  }
  const state = normalizeCart(parsed, genKey, isKey);
  saveCart(storage, key, state);
  return state;
}

export function saveCart(storage: KeyValueStorage, key: string, state: CartState): void {
  try {
    storage.setItem(key, JSON.stringify(state));
  } catch {
    // Depolama dolu/kapalı: sepet yalnızca bellekte kalır.
  }
}
