# İzlenebilirlik tablosu

Ara rapordaki 12 işlevsel gereksinim (FR) ve 9 iş kuralı (BR), uygulandıkları veritabanı nesnelerine,
backend koduna, arayüz ekranına ve **otomatik testlere** eşlenmiştir. Test adları
`backend/src/test/java/tr/edu/etu/oyunport/` altındaki sınıf ve metot adlarıdır; sonuçlar
`docs/TEST_SONUCLARI.md` dosyasındadır.

Kısaltmalar: V1…V6 = `backend/src/main/resources/db/migration/V*.sql`; `svc/` = `backend/src/main/java/tr/edu/etu/oyunport/`.

## İşlevsel gereksinimler

| Kod | Gereksinim | Veritabanı | Backend | Arayüz | Otomatik test | Durum |
|---|---|---|---|---|---|---|
| FR01 | Kayıt, giriş, profil, rol bazlı menü | `app_user` (email UQ, CHECK), `app_role`, `user_role` (V1); `api_register_customer`, `api_login_lookup` SECURITY DEFINER, RLS (V5); seed admin (V6) | `svc/auth/*`, `svc/profile/ProfileController`, `svc/config/SecurityConfig` (JWT, BCrypt, rol kuralları) | Giriş, Kayıt, Profil; role göre menü | `AuthIT` (7 test): kayıt/rol/hash, tekrar e-posta, geçersiz giriş, pasif hesap, 401/403, sahte token, profil+parola | Tamamlandı |
| FR02 | Ad, fiyat, publisher, genre ile arama/filtreleme | `product`, `product_genre`, `publisher`; `v_product_rating` (V3); IX5 (V4) | `svc/catalog/CatalogService.search` (bind parametreli dinamik SQL, beyaz liste sıralama) | Mağaza | `CatalogIT.searchAndFilter`, `inactiveProductsHidden`; `DbSecurityIT.sqlInjectionAttemptIsHarmless` | Tamamlandı |
| FR03 | Ana oyun/DLC detayı, fiyat, sistem gereksinimi | `base_game.minimum_requirements`, `dlc.content_summary`, `dlc.base_game_id`; `v_public_review` | `CatalogService.detail` | Ürün detayı | `CatalogIT.productDetail`, `detailForLoggedInUser` | Tamamlandı |
| FR04 | Sepete ekle/çıkar; sahiplik ve DLC koşulu doğrulama | (sepet tablosu yok — Tasarım notları Ç1) `library_entry`, `dlc` | `svc/cart/CartService.validate` | Sepet (istemci tarafı, sunucu doğrulamalı) | `CheckoutIT.cartValidationStatuses`, `dlcRequiresBaseGame`, `doublePurchaseRejectedAtT1` | Tamamlandı |
| FR05 | Sipariş, kalem, ödeme, kütüphane kayıtlarını transaction ile oluşturma | `store_order`, `order_item`, `payment_attempt`, `library_entry` + V2 trigger'ları | `svc/order/CheckoutService` (T1 `createOrder`, T2 `pay`), `svc/db/Db` (açık BEGIN/COMMIT/ROLLBACK) | Sepet → Sipariş detayı / ödeme | `CheckoutIT.successfulPurchaseEndToEnd`, `rollbackLeavesNoPartialRecords`, `concurrentPaymentsSerializedByUserLock`, `uniqueConstraintIsLastLineOfDefence` | Tamamlandı |
| FR06 | Başarılı/başarısız ödeme denemeleri ve sipariş geçmişi | `payment_attempt` (request_key UQ, tek SUCCESS kısmi UQ), `v_order_summary` | `CheckoutService.pay`, `OrderQueries`, `OrderController` | Siparişlerim, Sipariş detayı | `CheckoutIT.failedPaymentThenRetry`, `paymentRequestKeyIdempotency`, `checkoutKeyIdempotency`, `completedAndCancelledOrdersAreFinal` | Tamamlandı |
| FR07 | Kütüphane ve ana oyun için oynama oturumu | `library_entry`, `play_session` (+ tek açık oturum UQ), `v_library_playtime` | `svc/library/LibraryService` | Kütüphanem | `LibraryReviewWishlistIT.playSessions`, `sessionRestrictions`; `SchemaConstraintsIT.playSessionRules` | Tamamlandı |
| FR08 | Sahip olunan ana oyuna puan/yorum ekleme/güncelleme | `review` (PK, CHECK 1–5, FK sahiplik + BASE_GAME) | `svc/review/ReviewService.upsert` | Ürün detayı yorum formu, Yorumlarım | `LibraryReviewWishlistIT.reviewUpsert`, `reviewRestrictions`; `SchemaConstraintsIT.reviewRules` | Tamamlandı |
| FR09 | İstek listesine ekle/çıkar | `wishlist` (PK, CASCADE FK, RLS) | `svc/wishlist/WishlistController` | İstek listem | `LibraryReviewWishlistIT.wishlist` | Tamamlandı |
| FR10 | Admin: katalog, developer, publisher, genre yönetimi | `app_admin` yetkileri (V5); V2 specialization/sınıflandırma trigger'ları | `svc/admin/AdminCatalogService` (app_admin havuzu) | Admin Katalog | `AdminIT` (4 test) | Tamamlandı |
| FR11 | Satış, sahiplik, oynama analitik raporları | `v_game_sales_monthly`, `rpt_overview`, `rpt_user_activity_monthly`, `rpt_q1`…`rpt_q5` (V3) | `svc/admin/ReportService` (+ canlı EXPLAIN) | Admin Raporlar | `ReportsIT` (7 test; Q1–Q5 elle hesaplanmış beklenen değerlerle) | Tamamlandı |
| FR12 | Bulk veri yükleme, indeks karşılaştırması, yetki testleri | `data_loader` (V5); V4 indeksleri | `datagen/generate.py`, `datagen/load.sh`, `perf/run_index_experiments.sh` | — | `DbSecurityIT` (11 test); yükleme doğrulaması `docs/kanit/veri_dogrulama.txt` (17 denetim, 0 ihlal); indeks deneyleri `docs/PERFORMANS.md` | Tamamlandı |

## İş kuralları

| Kod | Kural | Veritabanında nasıl korunuyor | Serviste | Otomatik test |
|---|---|---|---|---|
| BR01 | PRODUCT tam olarak BASE_GAME veya DLC (total + disjoint) | `trg_subtype_disjoint` (BEFORE), `product_subtype_total` deferred constraint trigger (V2); `product_type` sütunu yok | `AdminCatalogService.create/update` (alt tür değiştirilemez) | `SchemaConstraintsIT.productWithoutSubtypeRejected`, `productInBothSubtypesRejected`; `AdminIT.invalidProductsRejected` |
| BR02 | Müşteri aynı ürüne bir kez sahip olur | `uq_library_entry_user_product` UNIQUE; sahiplik silinemez/değişmez (`library_entry_immutable`) | T1 ve T2'de kilit altında sahiplik kontrolü | `CheckoutIT.doublePurchaseRejectedAtT1`, `secondPendingOrderWithSameProductRejected`, `concurrentPaymentsSerializedByUserLock`, `uniqueConstraintIsLastLineOfDefence` |
| BR03 | Siparişte aynı ürün iki kez yok; line_no benzersiz | `uq_order_item_order_product`, `pk_order_item(order_id, line_no)`, `CHECK line_no > 0` | T1 tekrar kontrolü | `SchemaConstraintsIT.orderItemUniqueness`; `CheckoutIT.cartValidationStatuses` |
| BR04 | Tamamlanan sipariş ≥1 kalem; edinim yalnızca başarılı ödemeyle; tutar kalemlerden | `store_order_has_items` (deferred), `library_entry_source` (deferred: kaynak kalem, sahip, COMPLETED, SUCCESS), `payment_attempt_guard` (amount = Σ kalem) | Tutar DB'den hesaplanır | `SchemaConstraintsIT.orderWithoutItemsRejected`, `libraryEntrySourceConsistency`; `CheckoutIT.paymentAmountMustMatchItems`, `failedPaymentThenRetry` |
| BR05 | DLC için ana oyun önceden sahip olunmalı veya aynı sepette; DLC → BASE_GAME | `fk_dlc_base_game` → `base_game`; `library_entry_source` deferred DLC ön koşulu | T1 ve T2'de kilit altında kontrol, sepet doğrulaması | `CheckoutIT.dlcRequiresBaseGame`, `dlcAfterOwningBaseGame`; `SchemaConstraintsIT.dlcOwnershipRequiresBaseGameAtDbLevel`, `dlcMustReferenceBaseGame` |
| BR06 | Puan 1–5; kullanıcı-oyun başına tek yorum; yorum ve oturum için sahiplik | `ck_review_rating`, `pk_review`, `fk_review_ownership`, `fk_review_base_game`; `play_session_guard` (yalnızca BASE_GAME, edinimden sonra) | `ReviewService`, `LibraryService` | `SchemaConstraintsIT.reviewRules`, `playSessionRules`; `LibraryReviewWishlistIT.reviewRestrictions`, `sessionRestrictions` |
| BR07 | Fiyat değişikliği geçmiş satış fiyatını değiştirmez | `order_item.unit_price_paid` ayrı saklanır; `order_item_guard` UPDATE'i reddeder | T1'de fiyat sunucuda `list_price`'tan | `CheckoutIT.priceChangeDoesNotAffectPlacedOrder`; `SchemaConstraintsIT.completedOrderIsImmutable` |
| BR08 | Ürün/kullanıcı silinmez, pasife alınır; tamamlanmış sipariş yeniden açılmaz | ON DELETE RESTRICT; `is_active`; `store_order_state` durum makinesi; DELETE yetkisi yok | Admin pasife alma; giriş pasif hesabı reddeder | `SchemaConstraintsIT.completedOrderIsImmutable`, `historicalRowsCannotBeDeleted`; `AdminIT.updateAndDeactivate`; `AuthIT.login` |
| BR09 | Ödeme başlarken sipariş PENDING; SUCCESS aynı transaction'da COMPLETED; sipariş başına ≤1 SUCCESS; FAILED sonrası yeniden deneme | `uq_one_successful_payment` kısmi UQ; `payment_attempt_guard`; `payment_success_completes_order` (deferred); `store_order_state` | T2 akışı ve idempotency | `SchemaConstraintsIT.orderStateMachine`, `atMostOneSuccessfulPayment`; `CheckoutIT.failedPaymentThenRetry`, `paymentRequestKeyIdempotency` |

## Rapor §8.4 demo doğrulama senaryoları

| Senaryo | Otomatik test |
|---|---|
| Başarılı satın almada sipariş, ödeme ve kütüphane kayıtlarını birlikte gösterme | `CheckoutIT.successfulPurchaseEndToEnd` |
| Kalemlerin birinde kasıtlı hata → ROLLBACK, yarım kayıt yok | `CheckoutIT.rollbackLeavesNoPartialRecords` |
| Aynı kullanıcıyla eşzamanlı iki satın almada tek sahiplik | `CheckoutIT.concurrentPaymentsSerializedByUserLock`, `uniqueConstraintIsLastLineOfDefence` |
| Ana oyun olmadan DLC reddi; ana oyun + DLC sepetinin kabulü | `CheckoutIT.dlcRequiresBaseGame` |
| Customer rolüyle başka kullanıcının siparişini ve yönetim tablosunu okuma girişiminin reddi | `DbSecurityIT.customerRowLevelSecurity`, `customerCannotTouchAdminObjects`; `CheckoutIT.cannotAccessOtherUsersOrder` |
| Beş analitik sorgu sonuçları ve indeks öncesi/sonrası plan farkları | `ReportsIT`; `perf/results/OZET.md`, `perf/results/q*_plan.txt` |
