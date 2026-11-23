import { useEffect, useState, type FormEvent } from 'react';
import { useSearchParams } from 'react-router-dom';
import { catalogApi } from '../api/endpoints';
import type { CatalogQuery, CatalogSort, NamedRef, ProductType } from '../api/types';
import { useAuth } from '../auth/AuthContext';
import { useCart } from '../cart/CartContext';
import { EmptyState, ErrorBox, Loading } from '../components/Feedback';
import { Pagination } from '../components/Pagination';
import { ProductCardView } from '../components/ProductCardView';
import { useAsync } from '../lib/useAsync';

const PAGE_SIZE = 24;

const SORT_OPTIONS: { value: CatalogSort; label: string }[] = [
  { value: 'title', label: 'Ada göre (A–Z)' },
  { value: 'price_asc', label: 'Fiyat (artan)' },
  { value: 'price_desc', label: 'Fiyat (azalan)' },
  { value: 'release_desc', label: 'En yeni' },
  { value: 'rating_desc', label: 'En yüksek puan' },
];

function numParam(v: string | null): number | undefined {
  if (v === null || v === '') return undefined;
  const n = Number(v);
  return Number.isFinite(n) ? n : undefined;
}

function readQuery(sp: URLSearchParams): CatalogQuery {
  const type = sp.get('type');
  const sort = sp.get('sort');
  return {
    q: sp.get('q') ?? undefined,
    genreId: numParam(sp.get('genreId')),
    publisherId: numParam(sp.get('publisherId')),
    developerId: numParam(sp.get('developerId')),
    minPrice: numParam(sp.get('minPrice')),
    maxPrice: numParam(sp.get('maxPrice')),
    type: type === 'BASE_GAME' || type === 'DLC' ? (type as ProductType) : undefined,
    sort: SORT_OPTIONS.some((o) => o.value === sort) ? (sort as CatalogSort) : 'title',
    page: Math.max(0, numParam(sp.get('page')) ?? 0),
    size: PAGE_SIZE,
  };
}

interface Refs {
  genres: NamedRef[];
  publishers: NamedRef[];
  developers: NamedRef[];
}

export function CatalogPage() {
  const [sp, setSp] = useSearchParams();
  const { user } = useAuth();
  const cart = useCart();
  const query = readQuery(sp);
  const queryKey = sp.toString();

  const refs = useAsync<Refs>(async () => {
    const [genres, publishers, developers] = await Promise.all([
      catalogApi.genres(),
      catalogApi.publishers(),
      catalogApi.developers(),
    ]);
    return { genres, publishers, developers };
  }, []);

  // Oturum değişince sahiplik/istek listesi bayrakları için yeniden yükle.
  const products = useAsync(() => catalogApi.search(query), [queryKey, user?.userId], { keepPrevious: true });

  // Metin/fiyat alanları form gönderilince uygulanır; açılır listeler anında uygulanır.
  const [q, setQ] = useState(query.q ?? '');
  const [minPrice, setMinPrice] = useState(query.minPrice?.toString() ?? '');
  const [maxPrice, setMaxPrice] = useState(query.maxPrice?.toString() ?? '');
  useEffect(() => {
    setQ(query.q ?? '');
    setMinPrice(query.minPrice?.toString() ?? '');
    setMaxPrice(query.maxPrice?.toString() ?? '');
  }, [queryKey]);

  function update(patch: Record<string, string | undefined>, resetPage = true) {
    const next = new URLSearchParams(sp);
    for (const [k, v] of Object.entries(patch)) {
      if (v === undefined || v === '') next.delete(k);
      else next.set(k, v);
    }
    if (resetPage) next.delete('page');
    setSp(next);
  }

  function onSubmit(e: FormEvent) {
    e.preventDefault();
    update({ q: q.trim(), minPrice: minPrice.trim(), maxPrice: maxPrice.trim() });
  }

  const priceError =
    minPrice !== '' && maxPrice !== '' && Number(minPrice) > Number(maxPrice)
      ? 'En düşük fiyat en yüksek fiyattan büyük olamaz.'
      : null;

  const hasFilters = Array.from(sp.keys()).some((k) => k !== 'page' && k !== 'sort');

  return (
    <section className="page" aria-labelledby="catalog-title">
      <div className="page-head">
        <h1 id="catalog-title">Mağaza</h1>
        {products.data && (
          <p className="muted">{products.data.total.toLocaleString('tr-TR')} ürün bulundu</p>
        )}
      </div>

      <form className="filters card" onSubmit={onSubmit} role="search" aria-label="Katalog filtreleri">
        <div className="field field-grow">
          <label htmlFor="f-q">Ara</label>
          <input
            id="f-q"
            type="search"
            placeholder="Oyun veya DLC adı"
            value={q}
            onChange={(e) => setQ(e.target.value)}
          />
        </div>
        <div className="field">
          <label htmlFor="f-genre">Tür</label>
          <select
            id="f-genre"
            value={query.genreId ?? ''}
            onChange={(e) => update({ genreId: e.target.value })}
            disabled={!refs.data}
          >
            <option value="">Tümü</option>
            {refs.data?.genres.map((g) => (
              <option key={g.id} value={g.id}>
                {g.name}
              </option>
            ))}
          </select>
        </div>
        <div className="field">
          <label htmlFor="f-publisher">Publisher</label>
          <select
            id="f-publisher"
            value={query.publisherId ?? ''}
            onChange={(e) => update({ publisherId: e.target.value })}
            disabled={!refs.data}
          >
            <option value="">Tümü</option>
            {refs.data?.publishers.map((p) => (
              <option key={p.id} value={p.id}>
                {p.name}
              </option>
            ))}
          </select>
        </div>
        <div className="field">
          <label htmlFor="f-developer">Geliştirici</label>
          <select
            id="f-developer"
            value={query.developerId ?? ''}
            onChange={(e) => update({ developerId: e.target.value })}
            disabled={!refs.data}
          >
            <option value="">Tümü</option>
            {refs.data?.developers.map((d) => (
              <option key={d.id} value={d.id}>
                {d.name}
              </option>
            ))}
          </select>
        </div>
        <div className="field">
          <label htmlFor="f-type">Ürün tipi</label>
          <select id="f-type" value={query.type ?? ''} onChange={(e) => update({ type: e.target.value })}>
            <option value="">Tümü</option>
            <option value="BASE_GAME">Ana oyun</option>
            <option value="DLC">DLC</option>
          </select>
        </div>
        <div className="field field-small">
          <label htmlFor="f-min">En düşük fiyat (₺)</label>
          <input
            id="f-min"
            type="number"
            min={0}
            step="0.01"
            inputMode="decimal"
            value={minPrice}
            onChange={(e) => setMinPrice(e.target.value)}
          />
        </div>
        <div className="field field-small">
          <label htmlFor="f-max">En yüksek fiyat (₺)</label>
          <input
            id="f-max"
            type="number"
            min={0}
            step="0.01"
            inputMode="decimal"
            value={maxPrice}
            onChange={(e) => setMaxPrice(e.target.value)}
          />
        </div>
        <div className="field">
          <label htmlFor="f-sort">Sıralama</label>
          <select id="f-sort" value={query.sort} onChange={(e) => update({ sort: e.target.value })}>
            {SORT_OPTIONS.map((o) => (
              <option key={o.value} value={o.value}>
                {o.label}
              </option>
            ))}
          </select>
        </div>
        <div className="filters-actions">
          <button type="submit" className="btn btn-primary" disabled={!!priceError}>
            Ara
          </button>
          {hasFilters && (
            <button type="button" className="btn btn-ghost" onClick={() => setSp(new URLSearchParams())}>
              Filtreleri temizle
            </button>
          )}
        </div>
        {priceError && (
          <p className="field-error filters-error" role="alert">
            {priceError}
          </p>
        )}
      </form>

      <ErrorBox error={refs.error} title="Filtre listeleri yüklenemedi" onRetry={refs.reload} />
      <ErrorBox error={products.error} onRetry={products.reload} />

      {products.loading && !products.data && <Loading label="Ürünler yükleniyor…" />}

      {products.data && (
        <div className={products.loading ? 'is-refreshing' : undefined} aria-busy={products.loading}>
          {products.data.items.length === 0 ? (
            <EmptyState>Aramanıza uygun ürün bulunamadı.</EmptyState>
          ) : (
            <div className="product-grid">
              {products.data.items.map((p) => (
                <ProductCardView key={p.productId} p={p} inCart={cart.has(p.productId)} />
              ))}
            </div>
          )}
          <Pagination
            page={products.data.page}
            size={products.data.size}
            total={products.data.total}
            disabled={products.loading}
            onChange={(page) => {
              update({ page: page > 0 ? String(page) : undefined }, false);
              window.scrollTo({ top: 0, behavior: 'smooth' });
            }}
          />
        </div>
      )}
    </section>
  );
}
