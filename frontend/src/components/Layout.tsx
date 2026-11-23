import { useEffect, useState } from 'react';
import { Link, NavLink, Outlet, useLocation } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';
import { useCart } from '../cart/CartContext';
import { ROLE_LABEL } from '../lib/format';

interface NavItem {
  to: string;
  label: string;
  end?: boolean;
  badge?: number;
}

export function Layout() {
  const { user, hasRole, logout } = useAuth();
  const cart = useCart();
  const location = useLocation();
  const [open, setOpen] = useState(false);

  // Sayfa değişince mobil menüyü kapat.
  useEffect(() => setOpen(false), [location.pathname]);

  const items: NavItem[] = [{ to: '/', label: 'Mağaza', end: true }];
  if (hasRole('CUSTOMER')) {
    items.push(
      { to: '/sepet', label: 'Sepet', badge: cart.count },
      { to: '/siparisler', label: 'Siparişlerim' },
      { to: '/kutuphane', label: 'Kütüphanem' },
      { to: '/istek-listesi', label: 'İstek Listem' },
      { to: '/yorumlarim', label: 'Yorumlarım' },
    );
  }
  if (user) items.push({ to: '/profil', label: 'Profil' });
  if (hasRole('ADMIN')) {
    items.push({ to: '/admin/katalog', label: 'Admin Katalog' }, { to: '/admin/raporlar', label: 'Admin Raporlar' });
  }

  return (
    <div className="app">
      <a href="#main" className="skip-link">
        İçeriğe geç
      </a>
      <header className="topbar">
        <div className="topbar-inner">
          <Link to="/" className="brand" aria-label="OyunPort ana sayfa">
            <span className="brand-mark" aria-hidden="true">
              OP
            </span>
            <span className="brand-name">OyunPort</span>
          </Link>
          <button
            type="button"
            className="nav-toggle"
            aria-expanded={open}
            aria-controls="main-nav"
            onClick={() => setOpen((o) => !o)}
          >
            <span aria-hidden="true">☰</span> Menü
            {cart.count > 0 && <span className="nav-badge">{cart.count}</span>}
          </button>
          <nav id="main-nav" className={`nav ${open ? 'nav-open' : ''}`} aria-label="Ana menü">
            <ul className="nav-list">
              {items.map((it) => (
                <li key={it.to}>
                  <NavLink to={it.to} end={it.end} className={({ isActive }) => `nav-link ${isActive ? 'active' : ''}`}>
                    {it.label}
                    {it.badge !== undefined && it.badge > 0 && (
                      <span className="nav-badge" aria-label={`${it.badge} ürün`}>
                        {it.badge}
                      </span>
                    )}
                  </NavLink>
                </li>
              ))}
            </ul>
            <div className="nav-user">
              {user ? (
                <>
                  <span className="nav-user-name" title={user.email}>
                    {user.displayName}
                    <span className="nav-user-roles">{user.roles.map((r) => ROLE_LABEL[r] ?? r).join(' · ')}</span>
                  </span>
                  <button type="button" className="btn btn-ghost btn-small" onClick={logout}>
                    Çıkış
                  </button>
                </>
              ) : (
                <>
                  <NavLink to="/giris" className="btn btn-ghost btn-small">
                    Giriş
                  </NavLink>
                  <NavLink to="/kayit" className="btn btn-small">
                    Kayıt ol
                  </NavLink>
                </>
              )}
            </div>
          </nav>
        </div>
      </header>
      <main id="main" className="main" tabIndex={-1}>
        <Outlet />
      </main>
      <footer className="footer">
        <p>
          OyunPort · TOBB ETÜ BİL372 dönem projesi · Ödemeler yalnızca simülasyondur; gerçek para veya kart bilgisi
          kullanılmaz.
        </p>
      </footer>
    </div>
  );
}
