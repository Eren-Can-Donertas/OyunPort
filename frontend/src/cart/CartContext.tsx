import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { useAuth } from '../auth/AuthContext';
import { isUuid, newUuid } from '../lib/uuid';
import {
  addItem,
  cartStorageKey,
  clearCart,
  emptyCart,
  loadCart,
  removeItem,
  removeItems,
  saveCart,
  type AddResult,
  type CartState,
} from './cartStore';

interface CartContextValue {
  /** Sepet yalnızca CUSTOMER rolündeki oturum için etkindir. */
  enabled: boolean;
  productIds: number[];
  checkoutKey: string;
  count: number;
  has: (productId: number) => boolean;
  add: (productId: number) => AddResult;
  remove: (productId: number) => void;
  removeMany: (productIds: readonly number[]) => void;
  clear: () => void;
}

const CartContext = createContext<CartContextValue | null>(null);

function browserStorage(): Storage | null {
  try {
    return window.localStorage;
  } catch {
    return null;
  }
}

export function CartProvider({ children }: { children: ReactNode }) {
  const { user } = useAuth();
  const enabled = !!user && user.roles.includes('CUSTOMER');
  const storageKey = enabled && user ? cartStorageKey(user.userId) : null;

  const read = useCallback((): CartState => {
    const storage = browserStorage();
    if (!storageKey || !storage) return emptyCart(newUuid);
    return loadCart(storage, storageKey, newUuid, isUuid);
  }, [storageKey]);

  const [cart, setCart] = useState<CartState>(read);
  const cartRef = useRef(cart);
  cartRef.current = cart;

  // Kullanıcı değişince (giriş/çıkış) o kullanıcının sepetini yükle.
  useEffect(() => {
    setCart(read());
  }, [read]);

  // Başka sekmedeki değişiklikleri al.
  useEffect(() => {
    if (!storageKey) return;
    const onStorage = (e: StorageEvent) => {
      if (e.key === storageKey) setCart(read());
    };
    window.addEventListener('storage', onStorage);
    return () => window.removeEventListener('storage', onStorage);
  }, [storageKey, read]);

  const commit = useCallback(
    (next: CartState) => {
      if (next === cartRef.current) return;
      cartRef.current = next;
      const storage = browserStorage();
      if (storageKey && storage) saveCart(storage, storageKey, next);
      setCart(next);
    },
    [storageKey],
  );

  const add = useCallback(
    (productId: number): AddResult => {
      if (!enabled) return 'invalid';
      const { state, result } = addItem(cartRef.current, productId, newUuid);
      commit(state);
      return result;
    },
    [enabled, commit],
  );

  const remove = useCallback((productId: number) => commit(removeItem(cartRef.current, productId, newUuid)), [commit]);
  const removeMany = useCallback(
    (ids: readonly number[]) => commit(removeItems(cartRef.current, ids, newUuid)),
    [commit],
  );
  const clear = useCallback(() => commit(clearCart(cartRef.current, newUuid)), [commit]);

  const value = useMemo<CartContextValue>(
    () => ({
      enabled,
      productIds: enabled ? cart.productIds : [],
      checkoutKey: cart.checkoutKey,
      count: enabled ? cart.productIds.length : 0,
      has: (id: number) => enabled && cart.productIds.includes(id),
      add,
      remove,
      removeMany,
      clear,
    }),
    [enabled, cart, add, remove, removeMany, clear],
  );

  return <CartContext.Provider value={value}>{children}</CartContext.Provider>;
}

export function useCart(): CartContextValue {
  const ctx = useContext(CartContext);
  if (!ctx) throw new Error('useCart, CartProvider içinde kullanılmalıdır.');
  return ctx;
}

export const ADD_RESULT_MESSAGE: Record<AddResult, string> = {
  added: 'Ürün sepete eklendi.',
  duplicate: 'Bu ürün zaten sepetinizde.',
  full: 'Sepet en fazla 25 ürün içerebilir (tek siparişteki üst sınır).',
  invalid: 'Ürün sepete eklenemedi.',
};
