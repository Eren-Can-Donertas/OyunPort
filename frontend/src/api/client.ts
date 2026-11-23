import { getToken } from '../auth/session';
import type { ApiErrorBody } from './types';

/** Backend hata gövdesini ({status, code, message, details}) taşıyan istisna. */
export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  readonly details: string[];

  constructor(status: number, code: string, message: string, details: string[] = []) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.code = code;
    this.details = details;
  }

  /** Hiç HTTP yanıtı alınamadı (bağlantı hatası). */
  get isNetworkError(): boolean {
    return this.status === 0;
  }
}

export const NETWORK_ERROR_MESSAGE = 'Sunucuya ulaşılamadı. Bağlantınızı kontrol edip tekrar deneyin.';

/** Aynı idempotency anahtarıyla güvenle tekrar denenebilecek hatalar: ağ hatası veya ağ geçidi hataları. */
export function isTransientError(e: unknown): boolean {
  return e instanceof ApiError && (e.status === 0 || e.status === 502 || e.status === 503 || e.status === 504);
}

type UnauthorizedHandler = () => void;
let unauthorizedHandler: UnauthorizedHandler | null = null;

/** Kimliği doğrulanmış bir çağrı 401 aldığında çağrılır (AuthProvider kaydeder). */
export function setUnauthorizedHandler(handler: UnauthorizedHandler | null): void {
  unauthorizedHandler = handler;
}

export type QueryValue = string | number | boolean | null | undefined;

export interface RequestOptions {
  query?: Record<string, QueryValue>;
  body?: unknown;
  /** false: Authorization başlığı gönderilmez (giriş/kayıt). */
  auth?: boolean;
  signal?: AbortSignal;
}

export function buildQuery(query?: Record<string, QueryValue>): string {
  if (!query) return '';
  const params = new URLSearchParams();
  for (const [key, value] of Object.entries(query)) {
    if (value === undefined || value === null || value === '') continue;
    params.append(key, String(value));
  }
  const s = params.toString();
  return s ? `?${s}` : '';
}

function isErrorBody(v: unknown): v is ApiErrorBody {
  return !!v && typeof v === 'object' && typeof (v as ApiErrorBody).message === 'string';
}

async function request<T>(method: string, path: string, opts: RequestOptions = {}): Promise<T> {
  const headers: Record<string, string> = { Accept: 'application/json' };
  const token = opts.auth === false ? null : getToken();
  if (token) headers.Authorization = `Bearer ${token}`;
  let body: string | undefined;
  if (opts.body !== undefined) {
    headers['Content-Type'] = 'application/json';
    body = JSON.stringify(opts.body);
  }

  let res: Response;
  try {
    res = await fetch(`/api${path}${buildQuery(opts.query)}`, { method, headers, body, signal: opts.signal });
  } catch (e) {
    if (e instanceof DOMException && e.name === 'AbortError') throw e;
    throw new ApiError(0, 'NETWORK_ERROR', NETWORK_ERROR_MESSAGE);
  }

  const text = await res.text();
  let data: unknown = undefined;
  if (text) {
    try {
      data = JSON.parse(text);
    } catch {
      data = undefined;
    }
  }

  if (!res.ok) {
    if (res.status === 401 && token && unauthorizedHandler) {
      unauthorizedHandler();
    }
    if (isErrorBody(data)) {
      throw new ApiError(res.status, data.code ?? 'ERROR', data.message, Array.isArray(data.details) ? data.details : []);
    }
    const generic =
      res.status >= 502 && res.status <= 504
        ? `Sunucu şu anda yanıt vermiyor (HTTP ${res.status}).`
        : `İstek başarısız oldu (HTTP ${res.status}).`;
    throw new ApiError(res.status, 'HTTP_' + res.status, generic);
  }
  return data as T;
}

export const http = {
  get: <T>(path: string, opts?: RequestOptions) => request<T>('GET', path, opts),
  post: <T>(path: string, body?: unknown, opts?: RequestOptions) => request<T>('POST', path, { ...opts, body }),
  put: <T>(path: string, body?: unknown, opts?: RequestOptions) => request<T>('PUT', path, { ...opts, body }),
  del: <T>(path: string, opts?: RequestOptions) => request<T>('DELETE', path, opts),
};

/** Herhangi bir hatadan kullanıcıya gösterilecek ana mesaj. */
export function errorMessage(e: unknown): string {
  if (e instanceof ApiError) return e.message;
  if (e instanceof Error && e.message) return e.message;
  return 'Beklenmeyen bir hata oluştu.';
}
