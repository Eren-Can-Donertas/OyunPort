import { useMemo, useState, type FormEvent } from 'react';
import { adminApi, catalogApi } from '../../api/endpoints';
import type { AdminProduct, NamedRef, ProductInput, ProductType } from '../../api/types';
import { ErrorBox, Loading } from '../../components/Feedback';
import { useAsync } from '../../lib/useAsync';

interface Props {
  /** null: yeni ürün */
  productId: number | null;
  onSaved: (p: AdminProduct, created: boolean) => void;
  onCancel: () => void;
}

interface FormState {
  type: ProductType;
  title: string;
  description: string;
  listPrice: string;
  releaseDate: string;
  publisherId: string;
  developerIds: number[];
  genreIds: number[];
  minimumRequirements: string;
  baseGameId: number | null;
  baseGameTitle: string;
  contentSummary: string;
  active: boolean;
}

function today(): string {
  const d = new Date();
  const p = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
}

const EMPTY: FormState = {
  type: 'BASE_GAME',
  title: '',
  description: '',
  listPrice: '',
  releaseDate: today(),
  publisherId: '',
  developerIds: [],
  genreIds: [],
  minimumRequirements: '',
  baseGameId: null,
  baseGameTitle: '',
  contentSummary: '',
  active: true,
};

function fromProduct(p: AdminProduct, baseGameTitle: string): FormState {
  return {
    type: p.type,
    title: p.title,
    description: p.description ?? '',
    listPrice: String(p.listPrice),
    releaseDate: p.releaseDate,
    publisherId: String(p.publisherId),
    developerIds: p.developerIds,
    genreIds: p.genreIds,
    minimumRequirements: p.minimumRequirements ?? '',
    baseGameId: p.baseGameId,
    baseGameTitle,
    contentSummary: p.contentSummary ?? '',
    active: p.active,
  };
}

function validate(f: FormState, creating: boolean): string[] {
  const errs: string[] = [];
  if (!f.title.trim()) errs.push('Başlık zorunludur.');
  const price = Number(f.listPrice);
  if (f.listPrice.trim() === '' || !Number.isFinite(price) || price < 0) errs.push('Fiyat 0 veya daha büyük olmalıdır.');
  if (!f.releaseDate) errs.push('Çıkış tarihi zorunludur.');
  if (!f.publisherId) errs.push('Publisher seçilmelidir.');
  if (f.developerIds.length === 0) errs.push('En az bir geliştirici seçilmelidir.');
  if (f.genreIds.length === 0) errs.push('En az bir tür seçilmelidir.');
  if (f.type === 'BASE_GAME' && !f.minimumRequirements.trim())
    errs.push('Ana oyun için minimum sistem gereksinimleri zorunludur.');
  if (f.type === 'DLC') {
    if (creating && f.baseGameId === null) errs.push('DLC için bağlı ana oyun seçilmelidir.');
    if (!f.contentSummary.trim()) errs.push('DLC için içerik özeti zorunludur.');
  }
  return errs;
}

/** Çoklu seçim: filtrelenebilir onay kutusu listesi. */
function CheckboxList({
  legend,
  idPrefix,
  options,
  selected,
  onChange,
}: {
  legend: string;
  idPrefix: string;
  options: NamedRef[];
  selected: number[];
  onChange: (ids: number[]) => void;
}) {
  const [filter, setFilter] = useState('');
  const set = useMemo(() => new Set(selected), [selected]);
  const q = filter.trim().toLocaleLowerCase('tr-TR');
  const visible = q ? options.filter((o) => o.name.toLocaleLowerCase('tr-TR').includes(q)) : options;
  const selectedNames = options.filter((o) => set.has(o.id)).map((o) => o.name);

  function toggle(id: number) {
    onChange(set.has(id) ? selected.filter((x) => x !== id) : [...selected, id]);
  }

  return (
    <fieldset className="fieldset">
      <legend>
        {legend} <span className="muted">({selected.length} seçili)</span>
      </legend>
      {selectedNames.length > 0 && <p className="small selected-names">{selectedNames.join(', ')}</p>}
      {options.length > 12 && (
        <div className="field">
          <label htmlFor={`${idPrefix}-filter`} className="sr-only">
            {legend} içinde ara
          </label>
          <input
            id={`${idPrefix}-filter`}
            type="search"
            placeholder={`${legend} içinde ara`}
            value={filter}
            onChange={(e) => setFilter(e.target.value)}
          />
        </div>
      )}
      <div className="checkbox-list">
        {visible.map((o) => (
          <label key={o.id} className="checkbox">
            <input type="checkbox" checked={set.has(o.id)} onChange={() => toggle(o.id)} />
            <span>{o.name}</span>
          </label>
        ))}
        {visible.length === 0 && <p className="muted small">Eşleşen kayıt yok.</p>}
      </div>
    </fieldset>
  );
}

/** DLC için ana oyun seçimi: başlığa göre arama (yalnızca BASE_GAME sonuçları). */
function BaseGamePicker({ onPick }: { onPick: (id: number, title: string) => void }) {
  const [q, setQ] = useState('');
  const [results, setResults] = useState<AdminProduct[] | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);

  async function search() {
    setBusy(true);
    setError(null);
    try {
      const page = await adminApi.products(q.trim() || undefined, 0, 50);
      setResults(page.items.filter((p) => p.type === 'BASE_GAME'));
    } catch (err) {
      setError(err);
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="picker">
      <div className="inline-form">
        <label htmlFor="bg-search">Ana oyun ara</label>
        <input
          id="bg-search"
          type="search"
          value={q}
          onChange={(e) => setQ(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter') {
              e.preventDefault();
              void search();
            }
          }}
        />
        <button type="button" className="btn btn-small" onClick={search} disabled={busy}>
          {busy ? 'Aranıyor…' : 'Ara'}
        </button>
      </div>
      <ErrorBox error={error} />
      {results && (
        <ul className="picker-results">
          {results.length === 0 && <li className="muted small">Eşleşen ana oyun yok.</li>}
          {results.map((p) => (
            <li key={p.productId}>
              <button type="button" className="link-button" onClick={() => onPick(p.productId, p.title)}>
                #{p.productId} — {p.title}
                {!p.active && ' (pasif)'}
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

interface RefData {
  publishers: NamedRef[];
  developers: NamedRef[];
  genres: NamedRef[];
}

export function AdminProductForm({ productId, onSaved, onCancel }: Props) {
  const creating = productId === null;

  const refs = useAsync<RefData>(async () => {
    const [publishers, developers, genres] = await Promise.all([
      catalogApi.publishers(),
      catalogApi.developers(),
      catalogApi.genres(),
    ]);
    return { publishers, developers, genres };
  }, []);

  const [form, setForm] = useState<FormState | null>(creating ? EMPTY : null);
  const existing = useAsync<AdminProduct | null>(async () => {
    if (productId === null) return null;
    const p = await adminApi.product(productId);
    let baseTitle = '';
    if (p.baseGameId !== null) {
      try {
        baseTitle = (await adminApi.product(p.baseGameId)).title;
      } catch {
        baseTitle = '';
      }
    }
    setForm(fromProduct(p, baseTitle));
    return p;
  }, [productId]);

  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const [clientErrors, setClientErrors] = useState<string[]>([]);

  if (refs.error || existing.error) {
    return (
      <div className="card">
        <ErrorBox error={refs.error ?? existing.error} onRetry={refs.error ? refs.reload : existing.reload} />
        <button type="button" className="btn" onClick={onCancel}>
          Listeye dön
        </button>
      </div>
    );
  }
  if (!refs.data || !form) return <Loading label="Form hazırlanıyor…" />;

  const set = <K extends keyof FormState>(key: K, value: FormState[K]) =>
    setForm((f) => (f ? { ...f, [key]: value } : f));

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    if (!form) return;
    const errs = validate(form, creating);
    setClientErrors(errs);
    if (errs.length > 0) return;
    const input: ProductInput = {
      type: form.type,
      title: form.title.trim(),
      description: form.description.trim() || null,
      listPrice: Number(form.listPrice),
      releaseDate: form.releaseDate,
      publisherId: Number(form.publisherId),
      developerIds: form.developerIds,
      genreIds: form.genreIds,
      minimumRequirements: form.type === 'BASE_GAME' ? form.minimumRequirements.trim() : null,
      baseGameId: form.type === 'DLC' ? form.baseGameId : null,
      contentSummary: form.type === 'DLC' ? form.contentSummary.trim() : null,
      active: form.active,
    };
    setBusy(true);
    setError(null);
    try {
      const saved = creating ? await adminApi.createProduct(input) : await adminApi.updateProduct(productId!, input);
      onSaved(saved, creating);
    } catch (err) {
      setError(err);
      setBusy(false);
    }
  }

  return (
    <form className="form card admin-form" onSubmit={onSubmit} aria-labelledby="product-form-title" noValidate>
      <div className="page-head">
        <h2 id="product-form-title">{creating ? 'Yeni ürün' : `Ürünü düzenle #${productId}`}</h2>
        {existing.data && (
          <span className="muted small">Satış adedi: {existing.data.unitsSold.toLocaleString('tr-TR')}</span>
        )}
      </div>

      {clientErrors.length > 0 && (
        <div className="alert alert-error" role="alert">
          <p className="alert-message">Lütfen formu düzeltin:</p>
          <ul className="alert-details">
            {clientErrors.map((e) => (
              <li key={e}>{e}</li>
            ))}
          </ul>
        </div>
      )}
      <ErrorBox error={error} title="Kaydedilemedi" />

      <div className="form-grid">
        <div className="field">
          <label htmlFor="pf-type">Ürün tipi</label>
          <select
            id="pf-type"
            value={form.type}
            onChange={(e) => set('type', e.target.value as ProductType)}
            disabled={!creating}
            aria-describedby="pf-type-hint"
          >
            <option value="BASE_GAME">Ana oyun (BASE_GAME)</option>
            <option value="DLC">DLC</option>
          </select>
          <small id="pf-type-hint" className="hint">
            {creating ? 'Alt tür sonradan değiştirilemez (BR01).' : 'Alt tür değiştirilemez (BR01).'}
          </small>
        </div>
        <div className="field">
          <label htmlFor="pf-title">Başlık</label>
          <input id="pf-title" type="text" required value={form.title} onChange={(e) => set('title', e.target.value)} />
        </div>
        <div className="field">
          <label htmlFor="pf-price">Liste fiyatı (₺)</label>
          <input
            id="pf-price"
            type="number"
            min={0}
            step="0.01"
            inputMode="decimal"
            required
            value={form.listPrice}
            onChange={(e) => set('listPrice', e.target.value)}
          />
        </div>
        <div className="field">
          <label htmlFor="pf-date">Çıkış tarihi</label>
          <input
            id="pf-date"
            type="date"
            required
            value={form.releaseDate}
            onChange={(e) => set('releaseDate', e.target.value)}
          />
        </div>
        <div className="field">
          <label htmlFor="pf-publisher">Publisher</label>
          <select
            id="pf-publisher"
            required
            value={form.publisherId}
            onChange={(e) => set('publisherId', e.target.value)}
          >
            <option value="">Seçiniz…</option>
            {refs.data.publishers.map((p) => (
              <option key={p.id} value={p.id}>
                {p.name}
              </option>
            ))}
          </select>
        </div>
        <div className="field field-check">
          <label className="checkbox">
            <input type="checkbox" checked={form.active} onChange={(e) => set('active', e.target.checked)} />
            <span>Aktif (satışta)</span>
          </label>
          <small className="hint">Ürünler silinmez; pasife alınır (BR08).</small>
        </div>
      </div>

      <div className="field">
        <label htmlFor="pf-desc">Açıklama</label>
        <textarea id="pf-desc" rows={3} value={form.description} onChange={(e) => set('description', e.target.value)} />
      </div>

      {form.type === 'BASE_GAME' ? (
        <div className="field">
          <label htmlFor="pf-req">Minimum sistem gereksinimleri</label>
          <textarea
            id="pf-req"
            rows={3}
            required
            value={form.minimumRequirements}
            onChange={(e) => set('minimumRequirements', e.target.value)}
          />
        </div>
      ) : (
        <>
          <fieldset className="fieldset">
            <legend>Bağlı ana oyun</legend>
            {form.baseGameId !== null ? (
              <p>
                <strong>
                  #{form.baseGameId} {form.baseGameTitle && `— ${form.baseGameTitle}`}
                </strong>{' '}
                {creating && (
                  <button
                    type="button"
                    className="btn btn-ghost btn-small"
                    onClick={() => setForm((f) => (f ? { ...f, baseGameId: null, baseGameTitle: '' } : f))}
                  >
                    Değiştir
                  </button>
                )}
              </p>
            ) : (
              <p className="muted small">Henüz seçilmedi.</p>
            )}
            {creating && form.baseGameId === null && (
              <BaseGamePicker
                onPick={(id, title) => setForm((f) => (f ? { ...f, baseGameId: id, baseGameTitle: title } : f))}
              />
            )}
            {!creating && <small className="hint">DLC'nin bağlı olduğu ana oyun sonradan değiştirilemez.</small>}
          </fieldset>
          <div className="field">
            <label htmlFor="pf-summary">İçerik özeti</label>
            <textarea
              id="pf-summary"
              rows={3}
              required
              value={form.contentSummary}
              onChange={(e) => set('contentSummary', e.target.value)}
            />
          </div>
        </>
      )}

      <div className="two-col">
        <CheckboxList
          legend="Geliştiriciler"
          idPrefix="pf-dev"
          options={refs.data.developers}
          selected={form.developerIds}
          onChange={(ids) => set('developerIds', ids)}
        />
        <CheckboxList
          legend="Türler"
          idPrefix="pf-genre"
          options={refs.data.genres}
          selected={form.genreIds}
          onChange={(ids) => set('genreIds', ids)}
        />
      </div>

      <div className="button-row">
        <button type="submit" className="btn btn-primary" disabled={busy}>
          {busy ? 'Kaydediliyor…' : creating ? 'Ürünü oluştur' : 'Değişiklikleri kaydet'}
        </button>
        <button type="button" className="btn btn-ghost" onClick={onCancel} disabled={busy}>
          Vazgeç
        </button>
      </div>
    </form>
  );
}
