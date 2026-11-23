import type { ReactNode } from 'react';
import { Link, Navigate, useLocation } from 'react-router-dom';
import type { Role } from '../api/types';
import { ROLE_LABEL } from '../lib/format';
import { useAuth, type LoginRedirectState } from './AuthContext';

interface Props {
  /** Belirtilirse kullanıcı bu rollerden en az birine sahip olmalıdır. */
  roles?: Role[];
  children: ReactNode;
}

/** Rota koruması: oturum yoksa giriş sayfasına, rol yetmiyorsa "yetkiniz yok" ekranı. */
export function RequireAuth({ roles, children }: Props) {
  const { user } = useAuth();
  const location = useLocation();

  if (!user) {
    const state: LoginRedirectState = {
      from: location.pathname + location.search,
      message: 'Bu sayfayı görüntülemek için giriş yapmalısınız.',
    };
    return <Navigate to="/giris" replace state={state} />;
  }

  if (roles && !roles.some((r) => user.roles.includes(r))) {
    return (
      <section className="page narrow" aria-labelledby="forbidden-title">
        <div className="card center">
          <h1 id="forbidden-title">Yetkiniz yok</h1>
          <p>
            Bu sayfa yalnızca şu role sahip kullanıcılar içindir:{' '}
            <strong>{roles.map((r) => ROLE_LABEL[r] ?? r).join(', ')}</strong>.
          </p>
          <p className="muted">
            Hesabınızın rolleri: {user.roles.map((r) => ROLE_LABEL[r] ?? r).join(', ') || '—'}
          </p>
          <Link to="/" className="btn">
            Mağazaya dön
          </Link>
        </div>
      </section>
    );
  }

  return <>{children}</>;
}
