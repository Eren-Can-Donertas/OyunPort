// Backend Java record'larının birebir TypeScript karşılıkları.
// Jackson: alan adları camelCase, Instant -> ISO-8601 metin, LocalDate -> "yyyy-mm-dd",
// BigDecimal -> JSON sayı, UUID -> metin.

export type Role = 'CUSTOMER' | 'ADMIN';
export type ProductType = 'BASE_GAME' | 'DLC';

/** common/GlobalExceptionHandler.ErrorBody */
export interface ApiErrorBody {
  timestamp?: string;
  status: number;
  code: string;
  message: string;
  details?: string[];
}

/** common/Page */
export interface Page<T> {
  items: T[];
  page: number;
  size: number;
  total: number;
}

// ------------------------------------------------------------------ auth

/** auth/AuthService.UserView */
export interface UserView {
  userId: number;
  email: string;
  displayName: string;
  roles: Role[];
}

/** auth/AuthService.AuthResult */
export interface AuthResult {
  token: string;
  expiresAt: string;
  user: UserView;
}

// ------------------------------------------------------------------ katalog

/** catalog/CatalogService.NamedRef */
export interface NamedRef {
  id: number;
  name: string;
}

export type CatalogSort = 'title' | 'price_asc' | 'price_desc' | 'release_desc' | 'rating_desc';

export interface CatalogQuery {
  q?: string;
  genreId?: number;
  publisherId?: number;
  developerId?: number;
  minPrice?: number;
  maxPrice?: number;
  type?: ProductType;
  sort?: CatalogSort;
  page?: number;
  size?: number;
}

/** catalog/CatalogService.ProductCard */
export interface ProductCard {
  productId: number;
  title: string;
  productType: ProductType;
  listPrice: number;
  releaseDate: string | null;
  publisherId: number;
  publisherName: string;
  baseGameId: number | null;
  baseGameTitle: string | null;
  avgRating: number | null;
  reviewCount: number;
  genres: string[];
  owned: boolean;
  wishlisted: boolean;
}

/** catalog/CatalogService.DlcRef */
export interface DlcRef {
  productId: number;
  title: string;
  listPrice: number;
  active: boolean;
  owned: boolean;
}

/** catalog/CatalogService.ReviewView */
export interface PublicReview {
  displayName: string;
  rating: number;
  comment: string | null;
  createdAt: string;
  updatedAt: string;
}

/** catalog/CatalogService.MyReview */
export interface MyReview {
  rating: number;
  comment: string | null;
  updatedAt: string;
}

/** catalog/CatalogService.ProductDetail */
export interface ProductDetail {
  productId: number;
  title: string;
  description: string | null;
  productType: ProductType;
  listPrice: number;
  releaseDate: string | null;
  active: boolean;
  publisher: NamedRef;
  publisherCountry: string | null;
  developers: NamedRef[];
  genres: NamedRef[];
  minimumRequirements: string | null;
  contentSummary: string | null;
  baseGame: NamedRef | null;
  dlcs: DlcRef[];
  avgRating: number | null;
  reviewCount: number;
  recentReviews: PublicReview[];
  owned: boolean;
  ownsBaseGame: boolean;
  wishlisted: boolean;
  myReview: MyReview | null;
}

// ------------------------------------------------------------------ sepet

export type CartItemStatus =
  | 'OK'
  | 'NOT_FOUND'
  | 'INACTIVE'
  | 'OWNED'
  | 'DUPLICATE'
  | 'DLC_REQUIRES_BASE_GAME';

/** cart/CartService.CartItem */
export interface CartItem {
  productId: number;
  title: string | null;
  productType: ProductType | null;
  listPrice: number | null;
  baseGameId: number | null;
  baseGameTitle: string | null;
  status: CartItemStatus;
  message: string | null;
}

/** cart/CartService.CartValidation */
export interface CartValidation {
  items: CartItem[];
  total: number;
  valid: boolean;
}

// ------------------------------------------------------------------ sipariş / ödeme

export type OrderStatus = 'PENDING' | 'COMPLETED' | 'CANCELLED';
export type PaymentStatus = 'SUCCESS' | 'FAILED';
export type SimulatedOutcome = 'SUCCESS' | 'CARD_DECLINED' | 'INSUFFICIENT_FUNDS';

/** order/OrderModels.OrderLine */
export interface OrderLine {
  lineNo: number;
  productId: number;
  title: string;
  productType: ProductType;
  unitPricePaid: number;
  currentListPrice: number;
}

/** order/OrderModels.PaymentView */
export interface PaymentView {
  paymentId: number;
  amount: number;
  status: PaymentStatus;
  attemptedAt: string;
  requestKey: string;
}

/** order/OrderModels.OrderDetail */
export interface OrderDetail {
  orderId: number;
  status: OrderStatus;
  checkoutKey: string;
  createdAt: string;
  completedAt: string | null;
  itemCount: number;
  total: number;
  items: OrderLine[];
  payments: PaymentView[];
}

/** order/OrderModels.OrderSummary */
export interface OrderSummary {
  orderId: number;
  status: OrderStatus;
  createdAt: string;
  completedAt: string | null;
  itemCount: number;
  total: number;
}

/** order/OrderModels.CreateOrderResult */
export interface CreateOrderResult {
  replayed: boolean;
  order: OrderDetail;
}

/** order/OrderModels.PaymentResult */
export interface PaymentResult {
  paymentId: number;
  orderId: number;
  paymentStatus: PaymentStatus;
  orderStatus: OrderStatus | 'UNKNOWN';
  amount: number;
  attemptedAt: string;
  requestKey: string;
  replayed: boolean;
  failureReason: string | null;
  libraryIds: number[];
}

// ------------------------------------------------------------------ kütüphane

/** library/LibraryService.LibraryItem */
export interface LibraryItem {
  libraryId: number;
  productId: number;
  title: string;
  productType: ProductType;
  baseGameId: number | null;
  acquiredAt: string;
  orderId: number;
  completedSessions: number;
  totalSeconds: number;
  lastPlayedAt: string | null;
  openSessionId: number | null;
  myRating: number | null;
}

/** library/LibraryService.SessionView */
export interface SessionView {
  sessionId: number;
  libraryId: number;
  startedAt: string;
  endedAt: string | null;
  durationSeconds: number | null;
}

// ------------------------------------------------------------------ yorum / istek listesi / profil

/** review/ReviewService.ReviewView */
export interface MyReviewView {
  baseGameId: number;
  title: string;
  rating: number;
  comment: string | null;
  createdAt: string;
  updatedAt: string;
  created: boolean;
}

/** wishlist/WishlistController.WishlistItem */
export interface WishlistItem {
  productId: number;
  title: string;
  productType: ProductType;
  listPrice: number;
  active: boolean;
  owned: boolean;
  addedAt: string;
}

/** profile/ProfileController.Profile */
export interface Profile {
  userId: number;
  email: string;
  displayName: string;
  createdAt: string;
  active: boolean;
  roles: Role[];
  completedOrders: number;
  ownedProducts: number;
}

// ------------------------------------------------------------------ admin

/** admin/AdminCatalogService.ProductInput */
export interface ProductInput {
  type: ProductType;
  title: string;
  description: string | null;
  listPrice: number;
  releaseDate: string;
  publisherId: number;
  developerIds: number[];
  genreIds: number[];
  minimumRequirements: string | null;
  baseGameId: number | null;
  contentSummary: string | null;
  active: boolean;
}

/** admin/AdminCatalogService.AdminProduct */
export interface AdminProduct {
  productId: number;
  type: ProductType;
  title: string;
  description: string | null;
  listPrice: number;
  releaseDate: string;
  active: boolean;
  publisherId: number;
  publisherName: string;
  developerIds: number[];
  genreIds: number[];
  minimumRequirements: string | null;
  baseGameId: number | null;
  contentSummary: string | null;
  unitsSold: number;
}

export type RefKind = 'publishers' | 'developers' | 'genres';

/** admin/AdminCatalogService.Ref (productCount güncellemede -1 döner) */
export interface Ref {
  id: number;
  name: string;
  country: string | null;
  productCount: number;
}

export interface RefInput {
  name: string;
  country?: string | null;
}

/** admin/ReportService.ReportDef */
export interface ReportDef {
  key: string;
  title: string;
  description: string;
  sql: string;
}

export type ReportCell = string | number | boolean | null;

/** admin/ReportService.ReportResult */
export interface ReportResult {
  key: string;
  title: string;
  description: string;
  columns: string[];
  rows: Record<string, ReportCell>[];
  elapsedMs: number;
}

/** admin/ReportService.PlanResult */
export interface PlanResult {
  key: string;
  sql: string;
  plan: string[];
  executionTimeMs: number | null;
  planningTimeMs: number | null;
}
