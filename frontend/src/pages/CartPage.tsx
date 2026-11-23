import { useEffect, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { cartApi, orderApi } from '../api/endpoints';
import type { CartItemStatus, CartValidation } from '../api/types';
import { ADD_RESULT_MESSAGE, useCart } from '../cart/CartContext';
import { MAX_CART_ITEMS } from '../cart/cartStore';
import { Badge, TypeBadge } from '../components/Badges';
import { EmptyState, ErrorBox, Loading, Notice } from '../components/Feedback';
import { formatMoney, shortKey } from '../lib/format';

const STATUS_LABEL: Record<CartItemStatus, string> = {
  OK: 'Uygun',
  NOT_FOUND: 'Bulunamadı',
  INACTIVE: 'Satışta değil',
  OWNED: 'Zaten sahipsiniz',
  DUPLICATE: 'Tekrar',
  DLC_REQUIRES_BASE_GAME: 'Ana oyun gerekli',
};

export interface OrderCreatedState {
  replayed: boolean;
}

export function CartPage() {
  const cart = useCart();
  const navigate = useNavigate();
  const [validation, setValidation] = useState<CartValidation | null>(null);
  const [validating, setValidating] = useState(false);
  const [validateError, setValidateError] = useState<unknown>(null);
  const [orderError, setOrderError] = useState<unknown>(null);
  const [ordering, setOrdering] = useState(false);
  const [flash, setFlash] = useState<string | null>(null);
  const [tick, setTick] = useState(0);
  const idsKey = cart.productIds.join(',');

  // Sayfa açılışında ve her sepet değişikliğinde sunucuda doğrula.
  useEffect(() => {
    if (cart.productIds.length === 0) {
      setValidation(null);
      setValidateError(null);
      setValidating(false);
      return;
    }
    let cancelled = false;
    setValidating(true);
    setValidateError(null);
    cartApi.validate(cart.productIds).then(
      (v) => {
        if (cancelled) return;
        setValidation(v);
        setValidating(false);
      },
      (e: unknown) => {
        if (cancelled) return;
        setValidateError(e);
        setValidating(false);
      },
    );
    return () => {
      cancelled = true;
    };
    // idsKey sepet içeriğini temsil eder.
  }, [idsKey, tick]);

  async function createOrder() {
    setOrdering(true);
    setOrderError(null);
    const productIds = [...cart.productIds];
    try {
      // T1: aynı sepet için aynı checkoutKey; tekrar denemede sunucu aynı siparişi döndürür.
      const result = await orderApi.create(cart.checkoutKey, productIds);
      cart.removeMany(result.order.items.map((i) => i.productId));
      const state: OrderCreatedState = { replayed: result.replayed };
      navigate(`/siparisler/${result.order.orderId}`, { state });
    } catch (err) {
      setOrderError(err);
      setOrdering(false);
      setTick((t) => t + 1); // sunucu durumu değişmiş olabilir; yeniden doğrula
    }
  }

  function addBaseGame(baseGameId: number) {
    const r = cart.add(baseGameId);
    setFlash(ADD_RESULT_MESSAGE[r]);
  }

  // Doğrulama sonucu ile yerel sepet aynı içeriği gösteriyor mu?
  const upToDate = validation !== null && validation.items.map((i) => i.productId).join(',') === idsKey;
  const canOrder = upToDate && validation.valid && !validating && !ordering;

  return (
    <section className="page" aria-labelledby="cart-title">
      <div className="page-head">
        <h1 id="cart-title">Sepet</h1>
        <p className="muted">
          {cart.count} / {MAX_CART_ITEMS} ürün
        </p>
      </div>

      {flash && (
        <Notice kind="info" onClose={() => setFlash(null)}>
          {flash}
        </Notice>
      )}

      {cart.count === 0 ? (
        <EmptyState>
          Sepetiniz boş. <Link to="/">Mağazaya göz atın →</Link>
        </EmptyState>
      ) : (
        <>
          <ErrorBox error={validateError} title="Sepet doğrulanamadı" onRetry={() => setTick((t) => t + 1)} />
          {validating && !validation && <Loading label="Sepet doğrulanıyor…" />}
          {validation && (
            <div className={validating ? 'is-refreshing' : undefined} aria-busy={validating}>
              <ul className="cart-list">
                {validation.items.map((item, idx) => (
                  <li key={`${item.productId}-${idx}`} className={`cart-item ${item.status !== 'OK' ? 'cart-item-bad' : ''}`}>
                    <div className="cart-item-main">
                      <div className="badge-row">
                        <TypeBadge type={item.productType} />
                        <Badge tone={item.status === 'OK' ? 'success' : 'danger'}>{STATUS_LABEL[item.status]}</Badge>
                      </div>
                      <h3 className="cart-item-title">
                        {item.title ? <Link to={`/urun/${item.productId}`}>{item.title}</Link> : `Ürün #${item.productId}`}
                      </h3>
                      {item.productType === 'DLC' && item.baseGameTitle && (
                        <p className="muted small">
                          Ana oyun:{' '}
                          {item.baseGameId ? (
                            <Link to={`/urun/${item.baseGameId}`}>{item.baseGameTitle}</Link>
                          ) : (
                            item.baseGameTitle
                          )}
                        </p>
                      )}
                      {item.message && (
                        <p className="field-error" role="note">
                          {item.message}
                        </p>
                      )}
                      {item.status === 'DLC_REQUIRES_BASE_GAME' && item.baseGameId && !cart.has(item.baseGameId) && (
                        <button type="button" className="btn btn-small" onClick={() => addBaseGame(item.baseGameId!)}>
                          Ana oyunu sepete ekle
                        </button>
                      )}
                    </div>
                    <div className="cart-item-side">
                      <span className="price">{formatMoney(item.listPrice)}</span>
                      <button
                        type="button"
                        className="btn btn-ghost btn-small"
                        onClick={() => cart.remove(item.productId)}
                        aria-label={`${item.title ?? 'Ürün #' + item.productId} ürününü sepetten çıkar`}
                      >
                        Kaldır
                      </button>
                    </div>
                  </li>
                ))}
              </ul>

              <div className="cart-summary card">
                <div className="cart-total">
                  <span>Toplam (uygun ürünler)</span>
                  <strong className="price price-large">{formatMoney(validation.total)}</strong>
                </div>
                <p className="muted small">
                  Fiyatlar sunucuda güncel liste fiyatından hesaplanır; sipariş oluşturulduğunda sabitlenir (BR07).
                </p>
                {!validation.valid && (
                  <Notice kind="warning">
                    Sepette sipariş edilemeyen ürünler var. Yukarıdaki uyarıları giderin veya ilgili ürünleri kaldırın.
                  </Notice>
                )}
                <ErrorBox error={orderError} title="Sipariş oluşturulamadı" />
                <button type="button" className="btn btn-primary btn-block" onClick={createOrder} disabled={!canOrder}>
                  {ordering ? 'Sipariş oluşturuluyor…' : 'Siparişi oluştur'}
                </button>
                <p className="muted small">
                  Sipariş önce "Ödeme bekliyor" durumunda oluşturulur; ödemeyi sipariş sayfasında yaparsınız.
                  <br />
                  Sepet anahtarı (checkoutKey): <code title={cart.checkoutKey}>{shortKey(cart.checkoutKey)}</code>
                </p>
              </div>
            </div>
          )}
        </>
      )}
    </section>
  );
}
