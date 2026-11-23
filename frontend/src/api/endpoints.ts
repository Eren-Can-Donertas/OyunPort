import { http } from './client';
import type {
  AdminProduct,
  AuthResult,
  CartValidation,
  CatalogQuery,
  CreateOrderResult,
  LibraryItem,
  MyReviewView,
  NamedRef,
  OrderDetail,
  OrderSummary,
  Page,
  PaymentResult,
  PlanResult,
  ProductCard,
  ProductDetail,
  ProductInput,
  Profile,
  PublicReview,
  Ref,
  RefInput,
  RefKind,
  ReportDef,
  ReportResult,
  SessionView,
  SimulatedOutcome,
  WishlistItem,
} from './types';

export const authApi = {
  login: (email: string, password: string) =>
    http.post<AuthResult>('/auth/login', { email, password }, { auth: false }),
  register: (email: string, displayName: string, password: string) =>
    http.post<AuthResult>('/auth/register', { email, displayName, password }, { auth: false }),
};

export const catalogApi = {
  search: (q: CatalogQuery) => http.get<Page<ProductCard>>('/catalog/products', { query: { ...q } }),
  product: (id: number) => http.get<ProductDetail>(`/catalog/products/${id}`),
  reviews: (id: number, page = 0, size = 10) =>
    http.get<Page<PublicReview>>(`/catalog/products/${id}/reviews`, { query: { page, size } }),
  genres: () => http.get<NamedRef[]>('/catalog/genres'),
  publishers: () => http.get<NamedRef[]>('/catalog/publishers'),
  developers: () => http.get<NamedRef[]>('/catalog/developers'),
};

export const cartApi = {
  validate: (productIds: number[]) => http.post<CartValidation>('/cart/validate', { productIds }),
};

export const orderApi = {
  /** T1: sepetten PENDING sipariş (checkoutKey idempotency anahtarıdır). */
  create: (checkoutKey: string, productIds: number[]) =>
    http.post<CreateOrderResult>('/orders', { checkoutKey, productIds }),
  /** T2: ödeme denemesi (requestKey idempotency anahtarıdır). */
  pay: (orderId: number, requestKey: string, simulateOutcome: SimulatedOutcome) =>
    http.post<PaymentResult>(`/orders/${orderId}/payments`, { requestKey, simulateOutcome }),
  cancel: (orderId: number) => http.post<OrderDetail>(`/orders/${orderId}/cancel`),
  list: (page = 0, size = 20) => http.get<Page<OrderSummary>>('/orders', { query: { page, size } }),
  get: (orderId: number) => http.get<OrderDetail>(`/orders/${orderId}`),
};

export const libraryApi = {
  list: () => http.get<LibraryItem[]>('/library'),
  sessions: (libraryId: number) => http.get<SessionView[]>(`/library/${libraryId}/sessions`),
  start: (libraryId: number) => http.post<SessionView>(`/library/${libraryId}/sessions`),
  end: (libraryId: number, sessionId: number) =>
    http.post<SessionView>(`/library/${libraryId}/sessions/${sessionId}/end`),
  record: (libraryId: number, minutes: number) =>
    http.post<SessionView>(`/library/${libraryId}/sessions/record`, { minutes }),
};

export const reviewApi = {
  upsert: (baseGameId: number, rating: number, comment: string | null) =>
    http.put<MyReviewView>(`/reviews/${baseGameId}`, { rating, comment }),
  mine: () => http.get<MyReviewView[]>('/reviews/mine'),
};

export const wishlistApi = {
  list: () => http.get<WishlistItem[]>('/wishlist'),
  add: (productId: number) => http.put<void>(`/wishlist/${productId}`),
  remove: (productId: number) => http.del<void>(`/wishlist/${productId}`),
};

export const profileApi = {
  me: () => http.get<Profile>('/me'),
  update: (displayName: string) => http.put<Profile>('/me', { displayName }),
  changePassword: (currentPassword: string, newPassword: string) =>
    http.put<void>('/me/password', { currentPassword, newPassword }),
};

export const adminApi = {
  products: (q: string | undefined, page = 0, size = 20) =>
    http.get<Page<AdminProduct>>('/admin/products', { query: { q, page, size } }),
  product: (id: number) => http.get<AdminProduct>(`/admin/products/${id}`),
  createProduct: (input: ProductInput) => http.post<AdminProduct>('/admin/products', input),
  updateProduct: (id: number, input: ProductInput) => http.put<AdminProduct>(`/admin/products/${id}`, input),
  setActive: (id: number, active: boolean) => http.put<AdminProduct>(`/admin/products/${id}/active`, { active }),
  refs: (kind: RefKind) => http.get<Ref[]>(`/admin/${kind}`),
  createRef: (kind: RefKind, input: RefInput) => http.post<Ref>(`/admin/${kind}`, input),
  updateRef: (kind: RefKind, id: number, input: RefInput) => http.put<Ref>(`/admin/${kind}/${id}`, input),
  deleteRef: (kind: RefKind, id: number) => http.del<void>(`/admin/${kind}/${id}`),
};

export const reportApi = {
  definitions: () => http.get<ReportDef[]>('/admin/reports/definitions'),
  run: (key: string) => http.get<ReportResult>(`/admin/reports/run/${encodeURIComponent(key)}`),
  plan: (key: string) => http.get<PlanResult>(`/admin/reports/plan/${encodeURIComponent(key)}`),
};
