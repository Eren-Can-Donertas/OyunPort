import type { AuthResult, Role, UserView } from '../api/types';

/** localStorage'da tutulan oturum: JWT + son kullanma zamanı + kullanıcı özeti. */
export interface Session {
  token: string;
  expiresAt: string;
  user: UserView;
}

const SESSION_KEY = 'oyunport.session';

function storage(): Storage | null {
  try {
    return typeof window !== 'undefined' ? window.localStorage : null;
  } catch {
    return null;
  }
}

export function isExpired(session: Session, now: number = Date.now()): boolean {
  const exp = Date.parse(session.expiresAt);
  return Number.isNaN(exp) || exp <= now;
}

function isSession(value: unknown): value is Session {
  if (!value || typeof value !== 'object') return false;
  const v = value as Partial<Session>;
  return (
    typeof v.token === 'string' &&
    typeof v.expiresAt === 'string' &&
    !!v.user &&
    typeof v.user.userId === 'number' &&
    Array.isArray(v.user.roles)
  );
}

/** Geçerli (süresi dolmamış) oturumu döndürür; bozuk veya süresi dolmuş kayıt silinir. */
export function loadSession(): Session | null {
  const s = storage();
  if (!s) return null;
  try {
    const raw = s.getItem(SESSION_KEY);
    if (!raw) return null;
    const parsed: unknown = JSON.parse(raw);
    if (!isSession(parsed) || isExpired(parsed)) {
      s.removeItem(SESSION_KEY);
      return null;
    }
    return parsed;
  } catch {
    s.removeItem(SESSION_KEY);
    return null;
  }
}

export function saveSession(result: AuthResult): Session {
  const session: Session = { token: result.token, expiresAt: result.expiresAt, user: result.user };
  storage()?.setItem(SESSION_KEY, JSON.stringify(session));
  return session;
}

export function updateSessionUser(patch: Partial<UserView>): Session | null {
  const current = loadSession();
  if (!current) return null;
  const next: Session = { ...current, user: { ...current.user, ...patch } };
  storage()?.setItem(SESSION_KEY, JSON.stringify(next));
  return next;
}

export function clearSession(): void {
  storage()?.removeItem(SESSION_KEY);
}

/** API istemcisi için: süresi dolmamış token veya null. */
export function getToken(): string | null {
  return loadSession()?.token ?? null;
}

export function hasRole(user: UserView | null | undefined, role: Role): boolean {
  return !!user && user.roles.includes(role);
}
