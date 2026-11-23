import { useState } from 'react';
import { Link } from 'react-router-dom';
import { wishlistApi } from '../api/endpoints';
import { ADD_RESULT_MESSAGE, useCart } from '../cart/CartContext';
import { Badge, TypeBadge } from '../components/Badges';
import { EmptyState, ErrorBox, Loading, Notice } from '../components/Feedback';
import { formatDate, formatMoney } from '../lib/format';
import { useAsync } from '../lib/useAsync';

export function WishlistPage() {
  const wishlist = useAsync(() => wishlistApi.list(), []);
  const cart = useCart();
  const [busyId, setBusyId] = useState<number | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [flash, setFlash] = useState<{ kind: 'success' | 'warning' | 'info'; text: string } | null>(null);

  async function remove(productId: number, title: string) {
    setBusyId(productId);
    setError(null);
    setFlash(null);
    try {
      await wishlistApi.remove(productId);
      wishlist.setData((prev) => prev?.filter((w) => w.productId !== productId));
      setFlash({ kind: 'info', text: `"${title}" istek listenizden çıkarıldı.` });
    } catch (err) {
      setError(err);
    } finally {
      setBusyId(null);
    }
  }

  function addToCart(productId: number) {
    const r = cart.add(productId);
    setFlash({ kind: r === 'added' ? 'success' : 'warning', text: ADD_RESULT_MESSAGE[r] });
  }

  return (
    <section className="page" aria-labelledby="wishlist-title">
      <div className="page-head">
        <h1 id="wishlist-title">İstek Listem</h1>
        {wishlist.data && <p className="muted">{wishlist.data.length} ürün</p>}
      </div>
      {flash && (
        <Notice kind={flash.kind} onClose={() => setFlash(null)}>
          {flash.text} {flash.kind === 'success' && <Link to="/sepet">Sepete git →</Link>}
        </Notice>
      )}
      <ErrorBox error={error} />
      <ErrorBox error={wishlist.error} onRetry={wishlist.reload} />
      {wishlist.loading && !wishlist.data && <Loading label="İstek listesi yükleniyor…" />}
      {wishlist.data &&
        (wishlist.data.length === 0 ? (
          <EmptyState>
            İstek listeniz boş. Ürün sayfalarından ekleyebilirsiniz. <Link to="/">Mağazaya göz atın →</Link>
          </EmptyState>
        ) : (
          <ul className="row-list">
            {wishlist.data.map((w) => {
              const inCart = cart.has(w.productId);
              return (
                <li key={w.productId} className="row-item card">
                  <div className="row-main">
                    <div className="badge-row">
                      <TypeBadge type={w.productType} />
                      {w.owned && <Badge tone="success">Sahipsiniz</Badge>}
                      {!w.active && <Badge tone="danger">Satışta değil</Badge>}
                      {inCart && <Badge tone="info">Sepette</Badge>}
                    </div>
                    <h3 className="row-title">
                      <Link to={`/urun/${w.productId}`}>{w.title}</Link>
                    </h3>
                    <p className="muted small">Eklenme: {formatDate(w.addedAt)}</p>
                  </div>
                  <div className="row-side">
                    <span className="price">{formatMoney(w.listPrice)}</span>
                    <div className="button-row">
                      <button
                        type="button"
                        className="btn btn-primary btn-small"
                        onClick={() => addToCart(w.productId)}
                        disabled={w.owned || !w.active || inCart}
                      >
                        {inCart ? 'Sepette' : 'Sepete ekle'}
                      </button>
                      <button
                        type="button"
                        className="btn btn-ghost btn-small"
                        onClick={() => remove(w.productId, w.title)}
                        disabled={busyId === w.productId}
                        aria-label={`${w.title} ürününü istek listesinden kaldır`}
                      >
                        Kaldır
                      </button>
                    </div>
                  </div>
                </li>
              );
            })}
          </ul>
        ))}
    </section>
  );
}
