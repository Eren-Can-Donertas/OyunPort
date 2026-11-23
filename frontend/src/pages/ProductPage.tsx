import { useEffect, useState } from 'react';
import { Link, useLocation, useParams } from 'react-router-dom';
import { catalogApi, wishlistApi } from '../api/endpoints';
import type { PublicReview } from '../api/types';
import { useAuth } from '../auth/AuthContext';
import { ADD_RESULT_MESSAGE, useCart } from '../cart/CartContext';
import { Badge, Stars, TypeBadge } from '../components/Badges';
import { EmptyState, ErrorBox, Loading, Notice } from '../components/Feedback';
import { ReviewForm } from '../components/ReviewForm';
import { formatDate, formatDateTime, formatMoney } from '../lib/format';
import { useAsync } from '../lib/useAsync';

const REVIEW_PAGE_SIZE = 10;

function ReviewList({ reviews }: { reviews: PublicReview[] }) {
  return (
    <ul className="review-list">
      {reviews.map((r, i) => (
        <li key={`${r.displayName}-${r.createdAt}-${i}`} className="review">
          <div className="review-head">
            <strong>{r.displayName}</strong>
            <Stars value={r.rating} />
          </div>
          {r.comment ? <p className="review-body">{r.comment}</p> : <p className="muted small">Yorum metni yok.</p>}
          <p className="muted small">
            {formatDateTime(r.createdAt)}
            {r.updatedAt !== r.createdAt && <> · düzenlendi {formatDateTime(r.updatedAt)}</>}
          </p>
        </li>
      ))}
    </ul>
  );
}

export function ProductPage() {
  const { id } = useParams();
  const productId = Number(id);
  const location = useLocation();
  const { user, hasRole } = useAuth();
  const cart = useCart();
  const isCustomer = hasRole('CUSTOMER');

  const detail = useAsync(() => catalogApi.product(productId), [productId, user?.userId]);
  const [flash, setFlash] = useState<{ kind: 'success' | 'info' | 'warning'; text: string } | null>(null);
  const [actionError, setActionError] = useState<unknown>(null);
  const [wishBusy, setWishBusy] = useState(false);

  // Tüm yorumlar (sayfalı) — ilk 10 yorum zaten detayda geliyor.
  const [allReviews, setAllReviews] = useState<PublicReview[] | null>(null);
  const [reviewPage, setReviewPage] = useState(0);
  const [reviewTotal, setReviewTotal] = useState(0);
  const [reviewsBusy, setReviewsBusy] = useState(false);
  const [reviewsError, setReviewsError] = useState<unknown>(null);

  useEffect(() => {
    setFlash(null);
    setActionError(null);
    setAllReviews(null);
    setReviewPage(0);
  }, [productId]);

  // /urun/5#yorum bağlantısıyla gelindiğinde forma kaydır.
  useEffect(() => {
    if (detail.data && location.hash === '#yorum') {
      document.getElementById('yorum')?.scrollIntoView({ behavior: 'smooth', block: 'start' });
    }
  }, [detail.data, location.hash]);

  if (!Number.isSafeInteger(productId) || productId <= 0) {
    return (
      <section className="page">
        <EmptyState>Geçersiz ürün numarası.</EmptyState>
      </section>
    );
  }

  async function loadReviews(page: number) {
    setReviewsBusy(true);
    setReviewsError(null);
    try {
      const res = await catalogApi.reviews(productId, page, REVIEW_PAGE_SIZE);
      setAllReviews((prev) => (page === 0 || !prev ? res.items : [...prev, ...res.items]));
      setReviewPage(page);
      setReviewTotal(res.total);
    } catch (err) {
      setReviewsError(err);
    } finally {
      setReviewsBusy(false);
    }
  }

  async function toggleWishlist(currently: boolean) {
    setWishBusy(true);
    setActionError(null);
    setFlash(null);
    try {
      if (currently) {
        await wishlistApi.remove(productId);
        setFlash({ kind: 'info', text: 'Ürün istek listenizden çıkarıldı.' });
      } else {
        await wishlistApi.add(productId);
        setFlash({ kind: 'success', text: 'Ürün istek listenize eklendi.' });
      }
      detail.setData((prev) => (prev ? { ...prev, wishlisted: !currently } : prev));
    } catch (err) {
      setActionError(err);
    } finally {
      setWishBusy(false);
    }
  }

  function addToCart() {
    setActionError(null);
    const result = cart.add(productId);
    setFlash({ kind: result === 'added' ? 'success' : 'warning', text: ADD_RESULT_MESSAGE[result] });
  }

  const p = detail.data;

  return (
    <section className="page" aria-labelledby="product-title">
      <p className="breadcrumb">
        <Link to="/">← Mağaza</Link>
      </p>
      <ErrorBox error={detail.error} onRetry={detail.reload} />
      {detail.loading && !p && <Loading label="Ürün yükleniyor…" />}

      {p && (
        <>
          <div className="product-layout">
            <div className="product-main">
              <div className="badge-row">
                <TypeBadge type={p.productType} />
                {!p.active && <Badge tone="danger">Satışta değil</Badge>}
                {p.owned && <Badge tone="success">Sahipsiniz</Badge>}
                {p.wishlisted && <Badge tone="warning">İstek listesinde</Badge>}
                {cart.has(p.productId) && <Badge tone="info">Sepette</Badge>}
              </div>
              <h1 id="product-title">{p.title}</h1>
              {p.productType === 'DLC' && p.baseGame && (
                <p className="lead">
                  Bu bir DLC'dir. Ana oyun: <Link to={`/urun/${p.baseGame.id}`}>{p.baseGame.name}</Link>
                </p>
              )}
              {p.productType === 'BASE_GAME' && (
                <p>
                  <Stars value={p.avgRating} count={p.reviewCount} />
                </p>
              )}
              {p.description ? <p className="description">{p.description}</p> : <p className="muted">Açıklama yok.</p>}

              <dl className="facts">
                <div>
                  <dt>Publisher</dt>
                  <dd>
                    {p.publisher.name}
                    {p.publisherCountry && <span className="muted"> ({p.publisherCountry})</span>}
                  </dd>
                </div>
                <div>
                  <dt>Geliştirici</dt>
                  <dd>{p.developers.map((d) => d.name).join(', ') || '—'}</dd>
                </div>
                <div>
                  <dt>Türler</dt>
                  <dd>{p.genres.map((g) => g.name).join(', ') || '—'}</dd>
                </div>
                <div>
                  <dt>Çıkış tarihi</dt>
                  <dd>{formatDate(p.releaseDate)}</dd>
                </div>
                <div>
                  <dt>Ürün no</dt>
                  <dd>#{p.productId}</dd>
                </div>
              </dl>

              {p.productType === 'BASE_GAME' && (
                <div className="block">
                  <h2>Minimum sistem gereksinimleri</h2>
                  <p className="pre">{p.minimumRequirements || '—'}</p>
                </div>
              )}
              {p.productType === 'DLC' && (
                <div className="block">
                  <h2>İçerik özeti</h2>
                  <p className="pre">{p.contentSummary || '—'}</p>
                </div>
              )}
            </div>

            <aside className="product-side card" aria-label="Satın alma">
              <p className="price price-large">{formatMoney(p.listPrice)}</p>
              {flash && (
                <Notice kind={flash.kind} onClose={() => setFlash(null)}>
                  {flash.text}{' '}
                  {flash.kind === 'success' && cart.has(p.productId) && <Link to="/sepet">Sepete git →</Link>}
                </Notice>
              )}
              <ErrorBox error={actionError} />
              {!user && (
                <p>
                  Satın almak veya istek listesine eklemek için{' '}
                  <Link to="/giris" state={{ from: `/urun/${p.productId}` }}>
                    giriş yapın
                  </Link>
                  .
                </p>
              )}
              {user && !isCustomer && <p className="muted">Sepet ve istek listesi yalnızca müşteri hesapları içindir.</p>}
              {isCustomer && (
                <div className="stack">
                  <button
                    type="button"
                    className="btn btn-primary btn-block"
                    onClick={addToCart}
                    disabled={p.owned || !p.active || cart.has(p.productId)}
                  >
                    {p.owned
                      ? 'Sahipsiniz'
                      : !p.active
                        ? 'Satışta değil'
                        : cart.has(p.productId)
                          ? 'Sepette'
                          : 'Sepete ekle'}
                  </button>
                  <button
                    type="button"
                    className="btn btn-block"
                    onClick={() => toggleWishlist(p.wishlisted)}
                    disabled={wishBusy || (!p.wishlisted && !p.active)}
                  >
                    {p.wishlisted ? 'İstek listesinden çıkar' : 'İstek listesine ekle'}
                  </button>
                  {p.productType === 'DLC' && !p.owned && !p.ownsBaseGame && p.baseGame && (
                    <p className="hint">
                      [BR05] Bu DLC için ana oyuna sahip olmalı veya ana oyunu aynı sepette satın almalısınız.
                    </p>
                  )}
                  {p.owned && (
                    <Link to="/kutuphane" className="small">
                      Kütüphanemde göster →
                    </Link>
                  )}
                </div>
              )}
            </aside>
          </div>

          {p.productType === 'BASE_GAME' && (
            <div className="block">
              <h2>DLC'ler ({p.dlcs.length})</h2>
              {p.dlcs.length === 0 ? (
                <p className="muted">Bu oyun için DLC yok.</p>
              ) : (
                <ul className="dlc-list">
                  {p.dlcs.map((d) => (
                    <li key={d.productId} className="dlc-item">
                      <Link to={`/urun/${d.productId}`}>{d.title}</Link>
                      <span className="dlc-meta">
                        {!d.active && <Badge tone="danger">Satışta değil</Badge>}
                        {d.owned && <Badge tone="success">Sahipsiniz</Badge>}
                        {cart.has(d.productId) && <Badge tone="info">Sepette</Badge>}
                        <span className="price">{formatMoney(d.listPrice)}</span>
                      </span>
                    </li>
                  ))}
                </ul>
              )}
            </div>
          )}

          {p.productType === 'BASE_GAME' && (
            <div className="block" aria-labelledby="reviews-title">
              <h2 id="reviews-title">
                Yorumlar <span className="muted">({p.reviewCount})</span>
              </h2>
              <p>
                Ortalama puan: <Stars value={p.avgRating} count={p.reviewCount} />
              </p>

              <div id="yorum">
                {isCustomer && p.owned && (
                  <div className="card">
                    <ReviewForm baseGameId={p.productId} initial={p.myReview} onSaved={() => detail.reload()} />
                  </div>
                )}
                {isCustomer && !p.owned && (
                  <p className="muted small">[BR06] Yalnızca sahip olduğunuz ana oyunlara yorum yapabilirsiniz.</p>
                )}
              </div>

              {p.recentReviews.length === 0 && <p className="muted">Henüz yorum yok.</p>}
              {allReviews === null ? (
                <>
                  <ReviewList reviews={p.recentReviews} />
                  {p.reviewCount > p.recentReviews.length && (
                    <button type="button" className="btn" onClick={() => loadReviews(0)} disabled={reviewsBusy}>
                      {reviewsBusy ? 'Yükleniyor…' : `Tüm yorumları göster (${p.reviewCount})`}
                    </button>
                  )}
                </>
              ) : (
                <>
                  <ReviewList reviews={allReviews} />
                  {allReviews.length < reviewTotal && (
                    <button
                      type="button"
                      className="btn"
                      onClick={() => loadReviews(reviewPage + 1)}
                      disabled={reviewsBusy}
                    >
                      {reviewsBusy ? 'Yükleniyor…' : 'Daha fazla yorum'}
                    </button>
                  )}
                </>
              )}
              <ErrorBox error={reviewsError} />
            </div>
          )}
        </>
      )}
    </section>
  );
}
