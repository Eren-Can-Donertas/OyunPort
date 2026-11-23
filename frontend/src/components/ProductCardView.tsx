import { Link } from 'react-router-dom';
import type { ProductCard } from '../api/types';
import { formatDate, formatMoney } from '../lib/format';
import { Badge, Stars, TypeBadge } from './Badges';

export function ProductCardView({ p, inCart }: { p: ProductCard; inCart: boolean }) {
  return (
    <article className="product-card">
      <div className="product-card-head">
        <TypeBadge type={p.productType} />
        {p.owned && <Badge tone="success">Sahipsiniz</Badge>}
        {p.wishlisted && <Badge tone="warning">İstek listesinde</Badge>}
        {inCart && <Badge tone="info">Sepette</Badge>}
      </div>
      <h3 className="product-card-title">
        <Link to={`/urun/${p.productId}`}>{p.title}</Link>
      </h3>
      {p.productType === 'DLC' && p.baseGameTitle && (
        <p className="product-card-sub">
          Ana oyun:{' '}
          {p.baseGameId ? <Link to={`/urun/${p.baseGameId}`}>{p.baseGameTitle}</Link> : p.baseGameTitle}
        </p>
      )}
      <p className="product-card-sub muted">
        {p.publisherName}
        {p.releaseDate && <> · {formatDate(p.releaseDate)}</>}
      </p>
      {p.genres.length > 0 && (
        <ul className="chips" aria-label="Türler">
          {p.genres.map((g) => (
            <li key={g} className="chip">
              {g}
            </li>
          ))}
        </ul>
      )}
      <div className="product-card-foot">
        {p.productType === 'BASE_GAME' ? <Stars value={p.avgRating} count={p.reviewCount} /> : <span />}
        <span className="price">{formatMoney(p.listPrice)}</span>
      </div>
    </article>
  );
}
