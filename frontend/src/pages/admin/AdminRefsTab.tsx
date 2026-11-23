import { useState, type FormEvent } from 'react';
import { adminApi } from '../../api/endpoints';
import type { Ref, RefKind } from '../../api/types';
import { EmptyState, ErrorBox, Loading, Notice } from '../../components/Feedback';
import { formatInt } from '../../lib/format';
import { useAsync } from '../../lib/useAsync';

const LABELS: Record<RefKind, { singular: string; plural: string }> = {
  publishers: { singular: 'Publisher', plural: "Publisher'lar" },
  developers: { singular: 'Geliştirici', plural: 'Geliştiriciler' },
  genres: { singular: 'Tür', plural: 'Türler' },
};

function EditRow({
  kind,
  item,
  hasCountry,
  onDone,
  onCancel,
}: {
  kind: RefKind;
  item: Ref;
  hasCountry: boolean;
  onDone: (msg: string) => void;
  onCancel: () => void;
}) {
  const [name, setName] = useState(item.name);
  const [country, setCountry] = useState(item.country ?? '');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await adminApi.updateRef(kind, item.id, { name: name.trim(), country: hasCountry ? country.trim() || null : null });
      onDone(`#${item.id} güncellendi.`);
    } catch (err) {
      setError(err);
      setBusy(false);
    }
  }

  return (
    <tr>
      <td colSpan={hasCountry ? 5 : 4}>
        <form className="inline-form wrap" onSubmit={onSubmit}>
          <span>#{item.id}</span>
          <label htmlFor={`edit-name-${item.id}`}>Ad</label>
          <input
            id={`edit-name-${item.id}`}
            type="text"
            required
            maxLength={120}
            value={name}
            onChange={(e) => setName(e.target.value)}
          />
          {hasCountry && (
            <>
              <label htmlFor={`edit-country-${item.id}`}>Ülke</label>
              <input
                id={`edit-country-${item.id}`}
                type="text"
                maxLength={60}
                value={country}
                onChange={(e) => setCountry(e.target.value)}
              />
            </>
          )}
          <button type="submit" className="btn btn-primary btn-small" disabled={busy || !name.trim()}>
            Kaydet
          </button>
          <button type="button" className="btn btn-ghost btn-small" onClick={onCancel} disabled={busy}>
            Vazgeç
          </button>
        </form>
        <ErrorBox error={error} />
      </td>
    </tr>
  );
}

export function AdminRefsTab({ kind }: { kind: RefKind }) {
  const hasCountry = kind !== 'genres';
  const label = LABELS[kind];
  const refs = useAsync(() => adminApi.refs(kind), [kind]);
  const [name, setName] = useState('');
  const [country, setCountry] = useState('');
  const [creating, setCreating] = useState(false);
  const [editingId, setEditingId] = useState<number | null>(null);
  const [busyId, setBusyId] = useState<number | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [flash, setFlash] = useState<string | null>(null);
  const [filter, setFilter] = useState('');

  async function onCreate(e: FormEvent) {
    e.preventDefault();
    setCreating(true);
    setError(null);
    setFlash(null);
    try {
      const created = await adminApi.createRef(kind, {
        name: name.trim(),
        country: hasCountry ? country.trim() || null : null,
      });
      setName('');
      setCountry('');
      setFlash(`${label.singular} eklendi: #${created.id} "${created.name}".`);
      refs.reload();
    } catch (err) {
      setError(err);
    } finally {
      setCreating(false);
    }
  }

  async function onDelete(item: Ref) {
    if (!window.confirm(`"${item.name}" silinsin mi?`)) return;
    setBusyId(item.id);
    setError(null);
    setFlash(null);
    try {
      await adminApi.deleteRef(kind, item.id);
      setFlash(`"${item.name}" silindi.`);
      refs.reload();
    } catch (err) {
      // Ürünlerde kullanılan kayıt FK RESTRICT nedeniyle 409 döner; mesaj gösterilir.
      setError(err);
    } finally {
      setBusyId(null);
    }
  }

  const q = filter.trim().toLocaleLowerCase('tr-TR');
  const list = (refs.data ?? []).filter((r) => !q || r.name.toLocaleLowerCase('tr-TR').includes(q));

  return (
    <div>
      <form className="card form-row" onSubmit={onCreate} aria-label={`Yeni ${label.singular}`}>
        <div className="field field-grow">
          <label htmlFor={`new-${kind}-name`}>Yeni {label.singular.toLocaleLowerCase('tr-TR')} adı</label>
          <input
            id={`new-${kind}-name`}
            type="text"
            required
            maxLength={120}
            value={name}
            onChange={(e) => setName(e.target.value)}
          />
        </div>
        {hasCountry && (
          <div className="field">
            <label htmlFor={`new-${kind}-country`}>Ülke (isteğe bağlı)</label>
            <input
              id={`new-${kind}-country`}
              type="text"
              maxLength={60}
              value={country}
              onChange={(e) => setCountry(e.target.value)}
            />
          </div>
        )}
        <div className="field field-action">
          <button type="submit" className="btn btn-primary" disabled={creating || !name.trim()}>
            {creating ? 'Ekleniyor…' : 'Ekle'}
          </button>
        </div>
      </form>

      {flash && (
        <Notice kind="success" onClose={() => setFlash(null)}>
          {flash}
        </Notice>
      )}
      <ErrorBox error={error} />
      <ErrorBox error={refs.error} onRetry={refs.reload} />
      {refs.loading && !refs.data && <Loading label={`${label.plural} yükleniyor…`} />}

      {refs.data && (
        <>
          <div className="toolbar">
            <div className="inline-form">
              <label htmlFor={`filter-${kind}`}>Listede ara</label>
              <input id={`filter-${kind}`} type="search" value={filter} onChange={(e) => setFilter(e.target.value)} />
            </div>
            <span className="muted small">
              {list.length} / {refs.data.length} kayıt
            </span>
          </div>
          {list.length === 0 ? (
            <EmptyState>Kayıt yok.</EmptyState>
          ) : (
            <div className={`table-wrap ${refs.loading ? 'is-refreshing' : ''}`}>
              <table className="table">
                <thead>
                  <tr>
                    <th scope="col">No</th>
                    <th scope="col">Ad</th>
                    {hasCountry && <th scope="col">Ülke</th>}
                    <th scope="col" className="num">
                      Ürün sayısı
                    </th>
                    <th scope="col">İşlem</th>
                  </tr>
                </thead>
                <tbody>
                  {list.map((r) =>
                    editingId === r.id ? (
                      <EditRow
                        key={r.id}
                        kind={kind}
                        item={r}
                        hasCountry={hasCountry}
                        onCancel={() => setEditingId(null)}
                        onDone={(msg) => {
                          setEditingId(null);
                          setFlash(msg);
                          refs.reload();
                        }}
                      />
                    ) : (
                      <tr key={r.id}>
                        <td>#{r.id}</td>
                        <td>{r.name}</td>
                        {hasCountry && <td>{r.country ?? '—'}</td>}
                        <td className="num">{formatInt(r.productCount)}</td>
                        <td>
                          <div className="button-row nowrap">
                            <button type="button" className="btn btn-small" onClick={() => setEditingId(r.id)}>
                              Yeniden adlandır
                            </button>
                            <button
                              type="button"
                              className="btn btn-danger btn-small"
                              disabled={busyId === r.id}
                              onClick={() => onDelete(r)}
                              title={r.productCount > 0 ? 'Ürünlerde kullanılıyor; silme reddedilecektir (409).' : undefined}
                            >
                              Sil
                            </button>
                          </div>
                        </td>
                      </tr>
                    ),
                  )}
                </tbody>
              </table>
            </div>
          )}
        </>
      )}
    </div>
  );
}
