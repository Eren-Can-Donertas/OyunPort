import { useState, type FormEvent } from 'react';
import { Link, Navigate, useLocation, useNavigate } from 'react-router-dom';
import { useAuth, type LoginRedirectState } from '../auth/AuthContext';
import { ErrorBox, Notice } from '../components/Feedback';

export function LoginPage() {
  const { login, user, notice } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const state = (location.state ?? {}) as LoginRedirectState;
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);

  if (user && !busy) {
    return <Navigate to={state.from ?? '/'} replace />;
  }

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await login(email, password);
      navigate(state.from ?? '/', { replace: true });
    } catch (err) {
      setError(err);
      setBusy(false);
    }
  }

  return (
    <section className="page narrow" aria-labelledby="login-title">
      <div className="card">
        <h1 id="login-title">Giriş yap</h1>
        {(notice ?? state.message) && <Notice kind="info">{notice ?? state.message}</Notice>}
        <ErrorBox error={error} />
        <form onSubmit={onSubmit} className="form" noValidate={false}>
          <div className="field">
            <label htmlFor="login-email">E-posta</label>
            <input
              id="login-email"
              type="email"
              autoComplete="email"
              required
              value={email}
              onChange={(e) => setEmail(e.target.value)}
            />
          </div>
          <div className="field">
            <label htmlFor="login-password">Parola</label>
            <input
              id="login-password"
              type="password"
              autoComplete="current-password"
              required
              value={password}
              onChange={(e) => setPassword(e.target.value)}
            />
          </div>
          <button type="submit" className="btn btn-primary btn-block" disabled={busy}>
            {busy ? 'Giriş yapılıyor…' : 'Giriş yap'}
          </button>
        </form>
        <p className="muted center">
          Hesabınız yok mu?{' '}
          <Link to="/kayit" state={state}>
            Kayıt olun
          </Link>
        </p>
      </div>
    </section>
  );
}
