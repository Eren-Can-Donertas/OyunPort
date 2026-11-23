import { useEffect, useState, type FormEvent } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { adminApi } from '../../api/endpoints';
import { Badge, TypeBadge } from '../../components/Badges';
import { EmptyState, ErrorBox, Loading, Notice } from '../../components/Feedback';
import { Pagination } from '../../components/Pagination';
import { formatDate, formatInt, formatMoney } from '../../lib/format';
import { useAsync } from '../../lib/useAsync';
import { AdminProductForm } from './AdminProductForm';

const PAGE_SIZE = 20;

type Mode = { kind: 'list' } | { kind: 'create' } | { kind: 'edit'; id: number };

export function AdminProductsTab() {
  const [sp, setSp] = useSearchParams();
  const q = sp.get('q') ?? '';
  const page = Math.max(0, Number(sp.get('page') ?? 0) || 0);
  const [mode, setMode] = useState<Mode>({ kind: 'list' });
  const [search, setSearch] = useState(q);
  const [flash, setFlash] = useState<string | null>(null);
  const [rowBusy, setRowBusy] = useState<number | null>(null);
  const [rowError, setRowError] = useState<unknown>(null);

  useEffect(() => setSearch(q), [q]);

  const products = useAsync(() => adminApi.products(q || undefined, page, PAGE_SIZE), [q, page], {
    keepPrevious: true,
  });

  function setParams(next: { q?: string; page?: number }) {
    const p = new URLSearchParams(sp);
    if (next.q !== undefined) {
      if (next.q) p.set('q', next.q);
      else p.delete('q');
    }
    if (next.page !== undefined && next.page > 0) p.set('page', String(next.page));
    else p.delete('page');
    setSp(p);
  }

  function onSearch(e: FormEvent) {
    e.preventDefault();
    setParams({ q: search.trim(), page: 0 });
  }

  async function toggleActive(id: number, active: boolean) {
    setRowBusy(id);
    setRowError(null);
    setFlash(null);
    try {
      const updated = await adminApi.setActive(id, active);
      products.setData((prev) =>
        prev ? { ...prev, items: prev.items.map((p) => (p.productId === id ? updated : p)) } : prev,
      );
      setFlash(`#${id} "${updated.title}" ${updated.active ? 'aktifleştirildi' : 'pasife alındı'}.`);
    } catch (err) {
      setRowError(err);
    } finally {
      setRowBusy(null);
    }
  }

  if (mode.kind !== 'list') {
    return (
      <AdminProductForm
        key={mode.kind === 'edit' ? mode.id : 'new'}
        productId={mode.kind === 'edit' ? mode.id : null}
        onCancel={() => setMode({ kind: 'list' })}
        onSaved={(p, created) => {
          setMode({ kind: 'list' });
          setFlash(created ? `Ürün oluşturuldu: #${p.productId} "${p.title}".` : `Ürün güncellendi: #${p.productId}.`);
          products.reload();
        }}
      />
    );
  }

  return (
    <div>
      <div className="toolbar">
        <form className="inline-form" onSubmit={onSearch} role="search">
          <label htmlFor="admin-q">Başlıkta ara</label>
          <input id="admin-q" type="search" value={search} onChange={(e) => setSearch(e.target.value)} />
          <button type="submit" className="btn btn-small">
            Ara
          </button>
        </form>
        <button type="button" className="btn btn-primary" onClick={() => setMode({ kind: 'create' })}>
          + Yeni ürün
        </button>
      </div>

      {flash && (
        <Notice kind="success" onClose={() => setFlash(null)}>
          {flash}
        </Notice>
      )}
      <ErrorBox error={rowError} />
      <ErrorBox error={products.error} onRetry={products.reload} />
      {products.loading && !products.data && <Loading label="Ürünler yükleniyor…" />}

      {products.data &&
        (products.data.items.length === 0 ? (
          <EmptyState>Ürün bulunamadı.</EmptyState>
        ) : (
          <div className={products.loading ? 'is-refreshing' : undefined} aria-busy={products.loading}>
            <div className="table-wrap">
              <table className="table">
                <thead>
                  <tr>
                    <th scope="col">No</th>
                    <th scope="col">Başlık</th>
                    <th scope="col">Tip</th>
                    <th scope="col" className="num">
                      Fiyat
                    </th>
                    <th scope="col">Çıkış</th>
                    <th scope="col">Publisher</th>
                    <th scope="col" className="num">
                      Satış
                    </th>
                    <th scope="col">Durum</th>
                    <th scope="col">İşlem</th>
                  </tr>
                </thead>
                <tbody>
                  {products.data.items.map((p) => (
                    <tr key={p.productId} className={p.active ? undefined : 'row-inactive'}>
                      <td>#{p.productId}</td>
                      <td>
                        <Link to={`/urun/${p.productId}`}>{p.title}</Link>
                        {p.type === 'DLC' && p.baseGameId && (
                          <div className="muted small">Ana oyun #{p.baseGameId}</div>
                        )}
                      </td>
                      <td>
                        <TypeBadge type={p.type} />
                      </td>
                      <td className="num">{formatMoney(p.listPrice)}</td>
                      <td>{formatDate(p.releaseDate)}</td>
                      <td>{p.publisherName}</td>
                      <td className="num">{formatInt(p.unitsSold)}</td>
                      <td>{p.active ? <Badge tone="success">Aktif</Badge> : <Badge tone="danger">Pasif</Badge>}</td>
                      <td>
                        <div className="button-row nowrap">
                          <button
                            type="button"
                            className="btn btn-small"
                            onClick={() => setMode({ kind: 'edit', id: p.productId })}
                          >
                            Düzenle
                          </button>
                          <button
                            type="button"
                            className={`btn btn-small ${p.active ? 'btn-danger' : ''}`}
                            disabled={rowBusy === p.productId}
                            onClick={() => toggleActive(p.productId, !p.active)}
                          >
                            {p.active ? 'Pasife al' : 'Aktifleştir'}
                          </button>
                        </div>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <Pagination
              page={products.data.page}
              size={products.data.size}
              total={products.data.total}
              disabled={products.loading}
              onChange={(pg) => setParams({ page: pg })}
            />
          </div>
        ))}
    </div>
  );
}
