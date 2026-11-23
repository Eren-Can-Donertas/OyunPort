import { Link } from 'react-router-dom';
import { reviewApi } from '../api/endpoints';
import { Stars } from '../components/Badges';
import { EmptyState, ErrorBox, Loading } from '../components/Feedback';
import { formatDateTime } from '../lib/format';
import { useAsync } from '../lib/useAsync';

export function MyReviewsPage() {
  const reviews = useAsync(() => reviewApi.mine(), []);

  return (
    <section className="page" aria-labelledby="myreviews-title">
      <div className="page-head">
        <h1 id="myreviews-title">Yorumlarım</h1>
        {reviews.data && <p className="muted">{reviews.data.length} yorum</p>}
      </div>
      <ErrorBox error={reviews.error} onRetry={reviews.reload} />
      {reviews.loading && !reviews.data && <Loading label="Yorumlar yükleniyor…" />}
      {reviews.data &&
        (reviews.data.length === 0 ? (
          <EmptyState>
            Henüz yorum yapmadınız. <Link to="/kutuphane">Kütüphanenizdeki</Link> ana oyunlara yorum yapabilirsiniz.
          </EmptyState>
        ) : (
          <ul className="row-list">
            {reviews.data.map((r) => (
              <li key={r.baseGameId} className="card review">
                <div className="review-head">
                  <h3 className="row-title">
                    <Link to={`/urun/${r.baseGameId}`}>{r.title}</Link>
                  </h3>
                  <Stars value={r.rating} />
                </div>
                {r.comment ? <p className="review-body">{r.comment}</p> : <p className="muted small">Yorum metni yok.</p>}
                <p className="muted small">
                  Oluşturma: {formatDateTime(r.createdAt)}
                  {r.updatedAt !== r.createdAt && <> · Güncelleme: {formatDateTime(r.updatedAt)}</>}
                  {' · '}
                  <Link to={`/urun/${r.baseGameId}#yorum`}>Düzenle</Link>
                </p>
              </li>
            ))}
          </ul>
        ))}
    </section>
  );
}
