import { useState, type FormEvent } from 'react';
import { Link, Navigate, useLocation, useNavigate } from 'react-router-dom';
import { useAuth, type LoginRedirectState } from '../auth/AuthContext';
import { ErrorBox } from '../components/Feedback';

export function RegisterPage() {
  const { register, user } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const state = (location.state ?? {}) as LoginRedirectState;
  const [email, setEmail] = useState('');
  const [displayName, setDisplayName] = useState('');
  const [password, setPassword] = useState('');
  const [password2, setPassword2] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);

  if (user && !busy) {
    return <Navigate to={state.from ?? '/'} replace />;
  }

  const mismatch = password2.length > 0 && password !== password2;

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    if (password !== password2) {
      setError(new Error('Parolalar eşleşmiyor.'));
      return;
    }
    setBusy(true);
    setError(null);
    try {
      await register(email, displayName, password);
      navigate(state.from ?? '/', { replace: true });
    } catch (err) {
      setError(err);
      setBusy(false);
    }
  }

  return (
    <section className="page narrow" aria-labelledby="register-title">
      <div className="card">
        <h1 id="register-title">Kayıt ol</h1>
        <p className="muted">Yeni hesaplar müşteri (CUSTOMER) rolüyle oluşturulur.</p>
        <ErrorBox error={error} />
        <form onSubmit={onSubmit} className="form">
          <div className="field">
            <label htmlFor="reg-email">E-posta</label>
            <input
              id="reg-email"
              type="email"
              autoComplete="email"
              required
              maxLength={254}
              value={email}
              onChange={(e) => setEmail(e.target.value)}
            />
          </div>
          <div className="field">
            <label htmlFor="reg-name">Görünen ad</label>
            <input
              id="reg-name"
              type="text"
              autoComplete="nickname"
              required
              minLength={2}
              maxLength={60}
              value={displayName}
              onChange={(e) => setDisplayName(e.target.value)}
            />
            <small className="hint">2–60 karakter. Yorumlarınızda bu ad görünür.</small>
          </div>
          <div className="field">
            <label htmlFor="reg-password">Parola</label>
            <input
              id="reg-password"
              type="password"
              autoComplete="new-password"
              required
              minLength={8}
              maxLength={72}
              value={password}
              onChange={(e) => setPassword(e.target.value)}
            />
            <small className="hint">8–72 karakter.</small>
          </div>
          <div className="field">
            <label htmlFor="reg-password2">Parola (tekrar)</label>
            <input
              id="reg-password2"
              type="password"
              autoComplete="new-password"
              required
              minLength={8}
              maxLength={72}
              value={password2}
              aria-invalid={mismatch}
              aria-describedby={mismatch ? 'reg-password2-err' : undefined}
              onChange={(e) => setPassword2(e.target.value)}
            />
            {mismatch && (
              <small id="reg-password2-err" className="field-error">
                Parolalar eşleşmiyor.
              </small>
            )}
          </div>
          <button type="submit" className="btn btn-primary btn-block" disabled={busy || mismatch}>
            {busy ? 'Kaydediliyor…' : 'Kayıt ol'}
          </button>
        </form>
        <p className="muted center">
          Zaten hesabınız var mı?{' '}
          <Link to="/giris" state={state}>
            Giriş yapın
          </Link>
        </p>
      </div>
    </section>
  );
}
