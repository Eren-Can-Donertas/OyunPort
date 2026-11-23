import { useEffect, useState, type FormEvent } from 'react';
import { profileApi } from '../api/endpoints';
import { useAuth } from '../auth/AuthContext';
import { Badge } from '../components/Badges';
import { ErrorBox, Loading, Notice } from '../components/Feedback';
import { ROLE_LABEL, formatDateTime, formatInt } from '../lib/format';
import { useAsync } from '../lib/useAsync';

function DisplayNameForm({ current, onSaved }: { current: string; onSaved: (name: string) => void }) {
  const [name, setName] = useState(current);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const [ok, setOk] = useState(false);

  useEffect(() => setName(current), [current]);

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    setOk(false);
    try {
      const profile = await profileApi.update(name.trim());
      onSaved(profile.displayName);
      setOk(true);
    } catch (err) {
      setError(err);
    } finally {
      setBusy(false);
    }
  }

  return (
    <form className="form" onSubmit={onSubmit} aria-labelledby="name-form-title">
      <h2 id="name-form-title">Görünen adı değiştir</h2>
      {ok && <Notice kind="success" onClose={() => setOk(false)}>Görünen ad güncellendi.</Notice>}
      <ErrorBox error={error} />
      <div className="field">
        <label htmlFor="profile-name">Görünen ad</label>
        <input
          id="profile-name"
          type="text"
          required
          minLength={2}
          maxLength={60}
          value={name}
          onChange={(e) => setName(e.target.value)}
        />
        <small className="hint">2–60 karakter.</small>
      </div>
      <button type="submit" className="btn btn-primary" disabled={busy || name.trim() === current}>
        {busy ? 'Kaydediliyor…' : 'Kaydet'}
      </button>
    </form>
  );
}

function PasswordForm() {
  const [current, setCurrent] = useState('');
  const [next, setNext] = useState('');
  const [next2, setNext2] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const [ok, setOk] = useState(false);
  const mismatch = next2.length > 0 && next !== next2;

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    if (next !== next2) {
      setError(new Error('Yeni parolalar eşleşmiyor.'));
      return;
    }
    setBusy(true);
    setError(null);
    setOk(false);
    try {
      await profileApi.changePassword(current, next);
      setOk(true);
      setCurrent('');
      setNext('');
      setNext2('');
    } catch (err) {
      setError(err);
    } finally {
      setBusy(false);
    }
  }

  return (
    <form className="form" onSubmit={onSubmit} aria-labelledby="pw-form-title">
      <h2 id="pw-form-title">Parola değiştir</h2>
      {ok && <Notice kind="success" onClose={() => setOk(false)}>Parolanız değiştirildi.</Notice>}
      <ErrorBox error={error} />
      <div className="field">
        <label htmlFor="pw-current">Mevcut parola</label>
        <input
          id="pw-current"
          type="password"
          autoComplete="current-password"
          required
          value={current}
          onChange={(e) => setCurrent(e.target.value)}
        />
      </div>
      <div className="field">
        <label htmlFor="pw-new">Yeni parola</label>
        <input
          id="pw-new"
          type="password"
          autoComplete="new-password"
          required
          minLength={8}
          maxLength={72}
          value={next}
          onChange={(e) => setNext(e.target.value)}
        />
        <small className="hint">8–72 karakter.</small>
      </div>
      <div className="field">
        <label htmlFor="pw-new2">Yeni parola (tekrar)</label>
        <input
          id="pw-new2"
          type="password"
          autoComplete="new-password"
          required
          minLength={8}
          maxLength={72}
          value={next2}
          aria-invalid={mismatch}
          onChange={(e) => setNext2(e.target.value)}
        />
        {mismatch && <small className="field-error">Yeni parolalar eşleşmiyor.</small>}
      </div>
      <button type="submit" className="btn btn-primary" disabled={busy || mismatch}>
        {busy ? 'Değiştiriliyor…' : 'Parolayı değiştir'}
      </button>
    </form>
  );
}

export function ProfilePage() {
  const profile = useAsync(() => profileApi.me(), []);
  const { updateUser } = useAuth();
  const p = profile.data;

  return (
    <section className="page" aria-labelledby="profile-title">
      <div className="page-head">
        <h1 id="profile-title">Profil</h1>
      </div>
      <ErrorBox error={profile.error} onRetry={profile.reload} />
      {profile.loading && !p && <Loading label="Profil yükleniyor…" />}
      {p && (
        <>
          <div className="stat-grid">
            <div className="stat card">
              <span className="stat-label">Tamamlanan sipariş</span>
              <span className="stat-value">{formatInt(p.completedOrders)}</span>
            </div>
            <div className="stat card">
              <span className="stat-label">Sahip olunan ürün</span>
              <span className="stat-value">{formatInt(p.ownedProducts)}</span>
            </div>
          </div>
          <div className="card">
            <dl className="facts">
              <div>
                <dt>E-posta</dt>
                <dd>{p.email}</dd>
              </div>
              <div>
                <dt>Görünen ad</dt>
                <dd>{p.displayName}</dd>
              </div>
              <div>
                <dt>Roller</dt>
                <dd className="badge-row">
                  {p.roles.map((r) => (
                    <Badge key={r} tone={r === 'ADMIN' ? 'accent' : 'info'}>
                      {ROLE_LABEL[r] ?? r}
                    </Badge>
                  ))}
                </dd>
              </div>
              <div>
                <dt>Hesap durumu</dt>
                <dd>{p.active ? 'Aktif' : 'Pasif'}</dd>
              </div>
              <div>
                <dt>Kayıt tarihi</dt>
                <dd>{formatDateTime(p.createdAt)}</dd>
              </div>
              <div>
                <dt>Kullanıcı no</dt>
                <dd>#{p.userId}</dd>
              </div>
            </dl>
            <p className="muted small">E-posta adresi giriş anahtarıdır ve değiştirilemez.</p>
          </div>
          <div className="two-col">
            <div className="card">
              <DisplayNameForm
                current={p.displayName}
                onSaved={(name) => {
                  profile.setData((prev) => (prev ? { ...prev, displayName: name } : prev));
                  updateUser({ displayName: name });
                }}
              />
            </div>
            <div className="card">
              <PasswordForm />
            </div>
          </div>
        </>
      )}
    </section>
  );
}
