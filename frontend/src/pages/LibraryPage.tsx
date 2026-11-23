import { useMemo, useState, type FormEvent } from 'react';
import { Link } from 'react-router-dom';
import { libraryApi } from '../api/endpoints';
import type { LibraryItem, SessionView } from '../api/types';
import { Badge, Stars, TypeBadge } from '../components/Badges';
import { EmptyState, ErrorBox, Loading, Notice } from '../components/Feedback';
import { formatDate, formatDateTime, formatDuration } from '../lib/format';
import { useAsync } from '../lib/useAsync';

function SessionHistory({ libraryId, version }: { libraryId: number; version: number }) {
  const sessions = useAsync(() => libraryApi.sessions(libraryId), [libraryId, version], { keepPrevious: true });
  if (sessions.loading && !sessions.data) return <Loading label="Oturumlar yükleniyor…" />;
  if (sessions.error) return <ErrorBox error={sessions.error} onRetry={sessions.reload} />;
  const list: SessionView[] = sessions.data ?? [];
  if (list.length === 0) return <p className="muted small">Henüz oynama oturumu yok.</p>;
  return (
    <div className="table-wrap">
      <table className="table table-compact">
        <caption className="sr-only">Oynama oturumları (en yeni 100)</caption>
        <thead>
          <tr>
            <th scope="col">Oturum</th>
            <th scope="col">Başlangıç</th>
            <th scope="col">Bitiş</th>
            <th scope="col" className="num">
              Süre
            </th>
          </tr>
        </thead>
        <tbody>
          {list.map((s) => (
            <tr key={s.sessionId}>
              <td>#{s.sessionId}</td>
              <td>{formatDateTime(s.startedAt)}</td>
              <td>{s.endedAt ? formatDateTime(s.endedAt) : <Badge tone="warning">Devam ediyor</Badge>}</td>
              <td className="num">{s.durationSeconds === null ? '—' : formatDuration(s.durationSeconds)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

interface RowProps {
  item: LibraryItem;
  onChanged: (message: string) => void;
}

function LibraryRow({ item, onChanged }: RowProps) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const [minutes, setMinutes] = useState('30');
  const [open, setOpen] = useState(false);
  const [version, setVersion] = useState(0);
  const isBase = item.productType === 'BASE_GAME';

  async function run(action: () => Promise<unknown>, message: string) {
    setBusy(true);
    setError(null);
    try {
      await action();
      setVersion((v) => v + 1);
      onChanged(message);
    } catch (err) {
      setError(err);
    } finally {
      setBusy(false);
    }
  }

  function onRecord(e: FormEvent) {
    e.preventDefault();
    const m = Number(minutes);
    if (!Number.isInteger(m) || m < 1 || m > 1440) {
      setError(new Error('Süre 1 ile 1440 dakika arasında bir tamsayı olmalıdır.'));
      return;
    }
    void run(() => libraryApi.record(item.libraryId, m), `"${item.title}" için ${m} dakikalık oturum kaydedildi.`);
  }

  const minutesId = `rec-${item.libraryId}`;

  return (
    <li className="library-item card">
      <div className="library-head">
        <div>
          <div className="badge-row">
            <TypeBadge type={item.productType} />
            {item.openSessionId !== null && <Badge tone="warning">Oturum açık</Badge>}
          </div>
          <h3 className="library-title">
            <Link to={`/urun/${item.productId}`}>{item.title}</Link>
          </h3>
          {!isBase && item.baseGameId && (
            <p className="muted small">
              <Link to={`/urun/${item.baseGameId}`}>Ana oyuna git →</Link>
            </p>
          )}
        </div>
        {isBase && (
          <div className="library-rating">
            {item.myRating !== null ? (
              <>
                <Stars value={item.myRating} />
                <Link to={`/urun/${item.productId}#yorum`} className="small">
                  Yorumu düzenle
                </Link>
              </>
            ) : (
              <Link to={`/urun/${item.productId}#yorum`} className="btn btn-small">
                Yorum yap
              </Link>
            )}
          </div>
        )}
      </div>

      <dl className="facts facts-compact">
        <div>
          <dt>Edinilme</dt>
          <dd>{formatDate(item.acquiredAt)}</dd>
        </div>
        <div>
          <dt>Sipariş</dt>
          <dd>
            <Link to={`/siparisler/${item.orderId}`}>#{item.orderId}</Link>
          </dd>
        </div>
        {isBase && (
          <>
            <div>
              <dt>Toplam oynama</dt>
              <dd>{formatDuration(item.totalSeconds)}</dd>
            </div>
            <div>
              <dt>Tamamlanan oturum</dt>
              <dd>{item.completedSessions}</dd>
            </div>
            <div>
              <dt>Son oynama</dt>
              <dd>{formatDateTime(item.lastPlayedAt)}</dd>
            </div>
          </>
        )}
      </dl>

      <ErrorBox error={error} />

      {isBase ? (
        <div className="library-actions">
          {item.openSessionId === null ? (
            <button
              type="button"
              className="btn btn-primary btn-small"
              disabled={busy}
              onClick={() => run(() => libraryApi.start(item.libraryId), `"${item.title}" için oturum başlatıldı.`)}
            >
              Oturumu başlat
            </button>
          ) : (
            <button
              type="button"
              className="btn btn-danger btn-small"
              disabled={busy}
              onClick={() =>
                run(
                  () => libraryApi.end(item.libraryId, item.openSessionId!),
                  `"${item.title}" oturumu bitirildi.`,
                )
              }
            >
              Oturumu bitir
            </button>
          )}
          <form className="inline-form" onSubmit={onRecord}>
            <label htmlFor={minutesId}>Dakika</label>
            <input
              id={minutesId}
              type="number"
              min={1}
              max={1440}
              step={1}
              inputMode="numeric"
              value={minutes}
              onChange={(e) => setMinutes(e.target.value)}
            />
            <button type="submit" className="btn btn-small" disabled={busy}>
              {minutes && Number(minutes) > 0 ? `${minutes} dakika kaydet` : 'Dakika kaydet'}
            </button>
          </form>
          <button
            type="button"
            className="btn btn-ghost btn-small"
            aria-expanded={open}
            onClick={() => setOpen((o) => !o)}
          >
            {open ? 'Oturum geçmişini gizle' : 'Oturum geçmişi'}
          </button>
        </div>
      ) : (
        <p className="muted small">[BR06] Oynama oturumu yalnızca ana oyunlar için tutulur; DLC için oturum açılmaz.</p>
      )}
      {isBase && open && (
        <div className="library-sessions">
          <SessionHistory libraryId={item.libraryId} version={version} />
        </div>
      )}
    </li>
  );
}

export function LibraryPage() {
  const library = useAsync(() => libraryApi.list(), []);
  const [filter, setFilter] = useState('');
  const [typeFilter, setTypeFilter] = useState<'' | 'BASE_GAME' | 'DLC'>('');
  const [flash, setFlash] = useState<string | null>(null);

  const items = useMemo(() => {
    const q = filter.trim().toLocaleLowerCase('tr-TR');
    return (library.data ?? []).filter(
      (i) => (!q || i.title.toLocaleLowerCase('tr-TR').includes(q)) && (!typeFilter || i.productType === typeFilter),
    );
  }, [library.data, filter, typeFilter]);

  const totals = useMemo(() => {
    const all = library.data ?? [];
    return {
      count: all.length,
      seconds: all.reduce((s, i) => s + i.totalSeconds, 0),
    };
  }, [library.data]);

  return (
    <section className="page" aria-labelledby="library-title">
      <div className="page-head">
        <h1 id="library-title">Kütüphanem</h1>
        {library.data && (
          <p className="muted">
            {totals.count} ürün · toplam oynama {formatDuration(totals.seconds)}
          </p>
        )}
      </div>
      <p className="muted small">
        Oynama süreleri simülasyondur: oturumu başlatıp bitirerek veya dakika girerek kaydedilir.
      </p>

      {flash && (
        <Notice kind="success" onClose={() => setFlash(null)}>
          {flash}
        </Notice>
      )}
      <ErrorBox error={library.error} onRetry={library.reload} />
      {library.loading && !library.data && <Loading label="Kütüphane yükleniyor…" />}

      {library.data &&
        (library.data.length === 0 ? (
          <EmptyState>
            Kütüphaneniz boş. Satın aldığınız ürünler ödeme tamamlanınca burada görünür.{' '}
            <Link to="/">Mağazaya göz atın →</Link>
          </EmptyState>
        ) : (
          <>
            <div className="filters filters-inline card">
              <div className="field field-grow">
                <label htmlFor="lib-filter">Kütüphanede ara</label>
                <input id="lib-filter" type="search" value={filter} onChange={(e) => setFilter(e.target.value)} />
              </div>
              <div className="field">
                <label htmlFor="lib-type">Ürün tipi</label>
                <select
                  id="lib-type"
                  value={typeFilter}
                  onChange={(e) => setTypeFilter(e.target.value as '' | 'BASE_GAME' | 'DLC')}
                >
                  <option value="">Tümü</option>
                  <option value="BASE_GAME">Ana oyun</option>
                  <option value="DLC">DLC</option>
                </select>
              </div>
            </div>
            {items.length === 0 ? (
              <EmptyState>Filtreye uyan ürün yok.</EmptyState>
            ) : (
              <ul className={`library-list ${library.loading ? 'is-refreshing' : ''}`}>
                {items.map((item) => (
                  <LibraryRow
                    key={item.libraryId}
                    item={item}
                    onChanged={(msg) => {
                      setFlash(msg);
                      library.reload();
                    }}
                  />
                ))}
              </ul>
            )}
          </>
        ))}
    </section>
  );
}
