import { Link } from 'react-router-dom';

export function NotFoundPage() {
  return (
    <section className="page narrow" aria-labelledby="nf-title">
      <div className="card center">
        <h1 id="nf-title">Sayfa bulunamadı</h1>
        <p className="muted">Aradığınız sayfa mevcut değil veya taşınmış olabilir.</p>
        <Link to="/" className="btn btn-primary">
          Mağazaya dön
        </Link>
      </div>
    </section>
  );
}
