import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { setUnauthorizedHandler } from '../api/client';
import { authApi } from '../api/endpoints';
import type { Role, UserView } from '../api/types';
import { clearSession, loadSession, saveSession, updateSessionUser, type Session } from './session';

export interface LoginRedirectState {
  from?: string;
  message?: string;
}

interface AuthContextValue {
  user: UserView | null;
  isAuthenticated: boolean;
  hasRole: (role: Role) => boolean;
  login: (email: string, password: string) => Promise<UserView>;
  register: (email: string, displayName: string, password: string) => Promise<UserView>;
  logout: () => void;
  /** Profil güncellemesinden sonra görünen adı yerel oturuma yansıtır. */
  updateUser: (patch: Partial<UserView>) => void;
  /** Oturum zorla kapatıldıysa (401 / süre doldu) giriş sayfasında gösterilecek mesaj. */
  notice: string | null;
}

const AuthContext = createContext<AuthContextValue | null>(null);

const EXPIRED_MESSAGE = 'Oturumunuzun süresi doldu veya geçersiz. Lütfen tekrar giriş yapın.';

export function AuthProvider({ children }: { children: ReactNode }) {
  const [session, setSession] = useState<Session | null>(() => loadSession());
  const [notice, setNotice] = useState<string | null>(null);
  const navigate = useNavigate();
  const location = useLocation();
  const locationRef = useRef(location);
  locationRef.current = location;

  const forceLogin = useCallback(
    (message: string) => {
      clearSession();
      setNotice(message);
      setSession(null);
      const loc = locationRef.current;
      if (loc.pathname === '/giris') return;
      const state: LoginRedirectState = { from: loc.pathname + loc.search, message };
      navigate('/giris', { replace: true, state });
    },
    [navigate],
  );

  // Kimliği doğrulanmış bir çağrı 401 alırsa: token silinir, giriş sayfasına yönlendirilir.
  useEffect(() => {
    setUnauthorizedHandler(() => forceLogin(EXPIRED_MESSAGE));
    return () => setUnauthorizedHandler(null);
  }, [forceLogin]);

  // Token süresi dolduğunda otomatik çıkış.
  useEffect(() => {
    if (!session) return;
    const ms = Date.parse(session.expiresAt) - Date.now();
    if (!(ms > 0)) {
      forceLogin(EXPIRED_MESSAGE);
      return;
    }
    const timer = window.setTimeout(() => forceLogin(EXPIRED_MESSAGE), Math.min(ms, 2_147_000_000));
    return () => window.clearTimeout(timer);
  }, [session, forceLogin]);

  // Başka sekmede giriş/çıkış yapılırsa senkronize ol.
  useEffect(() => {
    const onStorage = (e: StorageEvent) => {
      if (e.key === null || e.key === 'oyunport.session') setSession(loadSession());
    };
    window.addEventListener('storage', onStorage);
    return () => window.removeEventListener('storage', onStorage);
  }, []);

  const login = useCallback(async (email: string, password: string) => {
    const result = await authApi.login(email.trim(), password);
    setNotice(null);
    setSession(saveSession(result));
    return result.user;
  }, []);

  const register = useCallback(async (email: string, displayName: string, password: string) => {
    const result = await authApi.register(email.trim(), displayName.trim(), password);
    setNotice(null);
    setSession(saveSession(result));
    return result.user;
  }, []);

  const logout = useCallback(() => {
    clearSession();
    setNotice(null);
    setSession(null);
    navigate('/', { replace: true });
  }, [navigate]);

  const updateUser = useCallback((patch: Partial<UserView>) => {
    const next = updateSessionUser(patch);
    if (next) setSession(next);
  }, []);

  const value = useMemo<AuthContextValue>(() => {
    const user = session?.user ?? null;
    return {
      user,
      isAuthenticated: !!user,
      hasRole: (role: Role) => !!user && user.roles.includes(role),
      login,
      register,
      logout,
      updateUser,
      notice,
    };
  }, [session, notice, login, register, logout, updateUser]);

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth, AuthProvider içinde kullanılmalıdır.');
  return ctx;
}
