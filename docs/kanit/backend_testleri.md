# Backend entegrasyon testleri — ayrıntılı sonuç

Otomatik üretildi: `python3 scripts/test_report.py docs/kanit/surefire` (Surefire XML raporlarından).
Kaynak XML: `docs/kanit/surefire/`. Toplam: **74 test, 0 başarısız, 0 hata, 0 atlandı**.

### AdminIT — 4 test, 0 başarısız, 0 hata, 0 atlandı (0.63 s)

| Test | Açıklama | Sonuç | Süre (s) |
|---|---|---|---|
| `invalidProductsRejected` | FR10: geçersiz ürün girişleri reddedilir; DLC yalnızca ana oyuna bağlanır; alt tür değiştirilemez | BAŞARILI | 0.174 |
| `updateAndDeactivate` | FR10 / BR08: fiyat ve tür güncellenir; ürün silinmez, pasife alınır ve katalogdan kalkar | BAŞARILI | 0.153 |
| `createBaseGameAndDlc` | FR10: ana oyun ve DLC; publisher, developer, genre ile tek transaction'da oluşturulur ve katalogda görünür | BAŞARILI | 0.144 |
| `referenceDataManagement` | FR10: publisher/developer/genre CRUD; kullanılan kayıt silinemez (RESTRICT), tekrarlanan genre adı reddedilir | BAŞARILI | 0.150 |

### AuthIT — 7 test, 0 başarısız, 0 hata, 0 atlandı (1.29 s)

| Test | Açıklama | Sonuç | Süre (s) |
|---|---|---|---|
| `invalidRegistrationRejected` | Kayıt: geçersiz e-posta / kısa parola -> 400 | BAŞARILI | 0.012 |
| `tamperedTokenRejected` | Sahte/bozuk token reddedilir | BAŞARILI | 0.077 |
| `registerAssignsCustomerRoleAndHashesPassword` | Kayıt: CUSTOMER rolü atanır, parola BCrypt ile hash'lenir | BAŞARILI | 0.099 |
| `profileAndPassword` | Profil: görünen ad güncellenir; parola değişikliği mevcut parola doğrulamasıyla yapılır | BAŞARILI | 0.418 |
| `login` | Giriş: doğru parola 200; yanlış parola ve bilinmeyen e-posta 401; pasif hesap 403 (BR08) | BAŞARILI | 0.358 |
| `duplicateEmailRejected` | Kayıt: aynı e-posta (büyük/küçük harf farkı dahil) ikinci kez kaydedilemez -> 409 | BAŞARILI | 0.147 |
| `roleBasedAccess` | Rol bazlı erişim: anonim 401, müşteri admin uç noktalarında 403, admin her iki role sahip | BAŞARILI | 0.173 |

### CatalogIT — 5 test, 0 başarısız, 0 hata, 0 atlandı (0.53 s)

| Test | Açıklama | Sonuç | Süre (s) |
|---|---|---|---|
| `searchAndFilter` | FR02: ad, genre, publisher, fiyat aralığı ve tür (BASE_GAME/DLC) ile filtreleme; fiyat sıralaması | BAŞARILI | 0.152 |
| `detailForLoggedInUser` | FR03: oturum açmış kullanıcıya sahiplik, istek listesi, yorumlar ve ortalama puan gösterilir | BAŞARILI | 0.171 |
| `lookups` | Lookup listeleri: genre, publisher, developer | BAŞARILI | 0.035 |
| `inactiveProductsHidden` | Pasif ürün (BR08) katalog aramasında listelenmez | BAŞARILI | 0.081 |
| `productDetail` | FR03: ana oyun detayında gereksinimler, developer/genre, DLC listesi; DLC detayında bağlı ana oyun | BAŞARILI | 0.083 |

### CheckoutIT — 17 test, 0 başarısız, 0 hata, 0 atlandı (3.45 s)

| Test | Açıklama | Sonuç | Süre (s) |
|---|---|---|---|
| `successfulPurchaseEndToEnd` | UC01: ana oyun + DLC sepeti T1 ve T2 ile COMPLETED olur; ödeme ve kütüphane kayıtları birlikte oluşur | BAŞARILI | 0.207 |
| `concurrentPaymentsSerializedByUserLock` | §8.4 Eşzamanlılık: aynı kullanıcının aynı ürünü içeren iki eşzamanlı ödemesi APP_USER kilidiyle sıralanır; tek sahiplik oluşur | BAŞARILI | 0.202 |
| `uniqueConstraintIsLastLineOfDefence` | BR02 son savunma: servis kilidi atlansa bile UNIQUE(user_id, product_id) ikinci eşzamanlı edinimi reddeder | BAŞARILI | 0.699 |
| `completedAndCancelledOrdersAreFinal` | BR08: tamamlanmış sipariş tekrar ödenemez; PENDING sipariş iptal edilir ve iptal edilen sipariş ödenemez | BAŞARILI | 0.176 |
| `priceChangeDoesNotAffectPlacedOrder` | BR07: T1 sonrası fiyat değişse de sipariş ve ödeme satın alma anındaki fiyatı kullanır | BAŞARILI | 0.148 |
| `secondPendingOrderWithSameProductRejected` | BR02: aynı ürünü içeren iki PENDING siparişten ikincisinin ödemesi reddedilir ve hiçbir kayıt yazılmaz | BAŞARILI | 0.149 |
| `dlcAfterOwningBaseGame` | BR05: ana oyuna önceden sahip olan müşteri DLC'yi tek başına satın alabilir | BAŞARILI | 0.142 |
| `paymentRequestKeyIdempotency` | Idempotency: aynı request_key tekrar gönderildiğinde yeni deneme oluşmaz, önceki sonuç döner (SUCCESS ve FAILED) | BAŞARILI | 0.172 |
| `cannotAccessOtherUsersOrder` | Yetki: müşteri başka kullanıcının siparişini göremez ve ödeyemez (RLS -> 404) | BAŞARILI | 0.215 |
| `cartValidationStatuses` | Sepet doğrulaması: sahip olunan, pasif, tekrarlanan ve bulunmayan ürünler işaretlenir; pasif ürün T1'de reddedilir | BAŞARILI | 0.179 |
| `rollbackLeavesNoPartialRecords` | §8.4 ROLLBACK: T2'de ikinci kalemden önce kasıtlı hata -> ödeme, kütüphane ve durum değişikliği kalmaz | BAŞARILI | 0.228 |
| `anonymousCannotOrder` | Girişsiz kullanıcı sipariş oluşturamaz (401) | BAŞARILI | 0.011 |
| `dlcRequiresBaseGame` | BR05: ana oyun olmadan DLC sepeti ve siparişi reddedilir; ana oyun + DLC sepeti kabul edilir | BAŞARILI | 0.198 |
| `paymentAmountMustMatchItems` | Ödeme tutarı veritabanında kalemlerden doğrulanır: yanlış tutar trigger ile reddedilir | BAŞARILI | 0.170 |
| `failedPaymentThenRetry` | UC02: başarısız ödeme FAILED olarak saklanır, sipariş PENDING kalır, kütüphane oluşmaz; yeni request_key ile yeniden deneme başarılı | BAŞARILI | 0.194 |
| `doublePurchaseRejectedAtT1` | BR02: sahip olunan ürün için yeni sipariş (T1) reddedilir | BAŞARILI | 0.136 |
| `checkoutKeyIdempotency` | Idempotency: aynı checkout_key ile T1 tekrarlanırsa ikinci sipariş oluşmaz | BAŞARILI | 0.202 |

### DbSecurityIT — 11 test, 0 başarısız, 0 hata, 0 atlandı (0.54 s)

| Test | Açıklama | Sonuç | Süre (s) |
|---|---|---|---|
| `roleAttributes` | Roller: hiçbiri süper kullanıcı değil, BYPASSRLS yok; db_admin yalnızca CREATEROLE | BAŞARILI | 0.013 |
| `sqlInjectionAttemptIsHarmless` | SQL injection: arama metni bind parametresi olarak gider; zararlı girdi sorguyu değiştirmez | BAŞARILI | 0.054 |
| `noSessionIdentityNoRows` | RLS: oturum kimliği ayarlanmamış bağlantı hiçbir müşteri satırını göremez | BAŞARILI | 0.069 |
| `customerCannotTouchAdminObjects` | §8.4 app_customer yönetim tablolarına ve rapor view'larına erişemez, katalog DML yapamaz, sahipliği silemez | BAŞARILI | 0.017 |
| `customerRowLevelSecurity` | §8.4 RLS: app_customer başka kullanıcının sipariş, ödeme, kütüphane ve istek listesini göremez/değiştiremez | BAŞARILI | 0.119 |
| `dataLoaderHasNoStandingPrivileges` | data_loader: yükleme dışında kalıcı INSERT yetkisi yok (yükleme betiği verir ve geri alır) | BAŞARILI | 0.009 |
| `sensitiveDataNotExposed` | Hassas veri: parola hash'i API yanıtlarında, e-posta herkese açık yorum view'ında yer almaz | BAŞARILI | 0.098 |
| `adminRolePrivileges` | app_admin: katalog DML ve rapor view'ları var; sipariş/kullanıcı tablolarına doğrudan erişim ve ürün silme yok | BAŞARILI | 0.010 |
| `reportingReadonlyPrivileges` | reporting_readonly: yalnızca analitik view'lar ve katalog SELECT; INSERT/UPDATE/DELETE ve kullanıcı/sipariş tabloları yok | BAŞARILI | 0.019 |
| `publicHasNoPrivileges` | PUBLIC: veritabanı CONNECT, şema USAGE, tablo ve fonksiyon yetkisi yok | BAŞARILI | 0.047 |
| `customerCannotWriteForOthers` | RLS WITH CHECK: müşteri başka kullanıcı adına sipariş veya istek listesi satırı ekleyemez | BAŞARILI | 0.072 |

### LibraryReviewWishlistIT — 5 test, 0 başarısız, 0 hata, 0 atlandı (1.17 s)

| Test | Açıklama | Sonuç | Süre (s) |
|---|---|---|---|
| `sessionRestrictions` | UC03: DLC için oturum açılamaz; başka kullanıcının kütüphane kaydına erişilemez | BAŞARILI | 0.359 |
| `reviewUpsert` | FR08: sahip olunan ana oyuna puan/yorum eklenir ve güncellenir (tek yorum) | BAŞARILI | 0.181 |
| `wishlist` | FR09: istek listesine ekleme (idempotent), listeleme ve çıkarma; pasif ürün eklenemez | BAŞARILI | 0.186 |
| `reviewRestrictions` | UC03 / BR06: sahip olunmayan oyuna ve DLC'ye yorum reddedilir; puan aralığı API ve veritabanında denetlenir | BAŞARILI | 0.214 |
| `playSessions` | FR07 / UC03: ana oyun için oturum başlat/bitir ve dakika ile kayıt; toplam süre v_library_playtime'dan | BAŞARILI | 0.223 |

### ReportsIT — 7 test, 0 başarısız, 0 hata, 0 atlandı (12.68 s)

| Test | Açıklama | Sonuç | Süre (s) |
|---|---|---|---|
| `q5DlcConversion` | Q5: ana oyun sahiplerinde DLC dönüşüm oranı (EXISTS), en az 100 sahip, pasif kullanıcı hariç | BAŞARILI | 4.207 |
| `q1MonthlyTopPublishers` | Q1: ay içinde publisher gelir sıralaması (DENSE_RANK) ve gelir payı doğru hesaplanır | BAŞARILI | 0.280 |
| `plannedViews` | §4.5 view'ları: v_order_summary toplamı kalemlerden; v_library_playtime oturumsuz kayıtları da listeler | BAŞARILI | 0.173 |
| `q4LowPlaytimeGames` | Q4: edinimden >= 30 gün geçmiş sahiplerde toplam < 1 saat oynayanların oranı (oturumsuzlar dahil) | BAŞARILI | 1.112 |
| `q3AboveAverageCustomers` | Q3: harcaması müşteri ortalamasını aşan kullanıcı listelenir; ortalamanın altındaki listelenmez | BAŞARILI | 0.185 |
| `q2GenreTopSellers` | Q2: tür içinde satış sıralaması (RANK), HAVING eşiği (>= 25) ve yalnızca tamamlanmış siparişler | BAŞARILI | 2.260 |
| `adminReportApi` | FR11: admin rapor API'si tüm raporları çalıştırır ve canlı EXPLAIN (ANALYZE, BUFFERS) planı döndürür | BAŞARILI | 0.404 |

### SchemaConstraintsIT — 18 test, 0 başarısız, 0 hata, 0 atlandı (1.67 s)

| Test | Açıklama | Sonuç | Süre (s) |
|---|---|---|---|
| `domainChecks` | Domain CHECK: fiyat/ödeme >= 0, line_no > 0, rating 1–5, ended_at >= started_at, durum değerleri, e-posta biçimi | BAŞARILI | 0.206 |
| `historicalRowsCannotBeDeleted` | BR08: tarihsel kaydı olan kullanıcı ve ürün silinemez (ON DELETE RESTRICT) | BAŞARILI | 0.082 |
| `orderItemUniqueness` | BR03: siparişte aynı ürün iki kez ve aynı line_no iki kez bulunamaz | BAŞARILI | 0.080 |
| `idempotencyKeysUnique` | Idempotency anahtarları veritabanında UNIQUE: checkout_key ve request_key | BAŞARILI | 0.070 |
| `playSessionRules` | BR06: oturum yalnızca BASE_GAME sahipliğine, edinimden sonra; aynı anda tek açık oturum | BAŞARILI | 0.143 |
| `productNeedsGenreAndDeveloper` | §4.2: GENRE veya DEVELOPER bağlantısı olmayan ürün commit edilemez; son genre silinemez | BAŞARILI | 0.067 |
| `productWithoutSubtypeRejected` | BR01 total: alt türü olmayan PRODUCT commit edilemez | BAŞARILI | 0.051 |
| `foreignKeyActionsMatchReport` | §5 FK silme davranışları: tarihsel bağlantılar RESTRICT, bağımlı ilişkiler CASCADE; tüm FK'ler ON UPDATE RESTRICT | BAŞARILI | 0.015 |
| `allReportTablesExist` | Rapordaki 18 tablonun tamamı oluşturulmuştur | BAŞARILI | 0.033 |
| `atMostOneSuccessfulPayment` | BR09: sipariş başına en fazla bir SUCCESS ödeme (kısmi UNIQUE indeks) | BAŞARILI | 0.077 |
| `orderWithoutItemsRejected` | BR04: kalemsiz sipariş commit edilemez | BAŞARILI | 0.036 |
| `libraryEntrySourceConsistency` | BR04: kütüphane kaydı yalnızca kendi, COMPLETED + SUCCESS ödemeli sipariş kaleminden ve aynı üründen oluşur | BAŞARILI | 0.192 |
| `orderStateMachine` | BR09: sipariş PENDING oluşturulur; SUCCESS ödeme olmadan COMPLETED yapılamaz; SUCCESS ödeme aynı transaction'da COMPLETED gerektirir | BAŞARILI | 0.071 |
| `completedOrderIsImmutable` | BR08: tamamlanmış sipariş yeniden açılamaz, kalem eklenemez; kalemler (BR07) ve ödeme geçmişi değiştirilemez | BAŞARILI | 0.130 |
| `productInBothSubtypesRejected` | BR01 disjoint: aynı ürün hem BASE_GAME hem DLC olamaz | BAŞARILI | 0.047 |
| `reviewRules` | BR06: yorum için sahiplik (FK -> LIBRARY_ENTRY) ve BASE_GAME (FK) zorunlu; kullanıcı-oyun başına tek yorum (PK) | BAŞARILI | 0.174 |
| `dlcOwnershipRequiresBaseGameAtDbLevel` | BR05 (veritabanı düzeyi): servis atlansa bile ana oyunu olmayan kullanıcıya DLC sahipliği commit edilemez | BAŞARILI | 0.104 |
| `dlcMustReferenceBaseGame` | BR05: DLC.base_game_id yalnızca BASE_GAME'e bağlanır (DLC'ye bağlanamaz) | BAŞARILI | 0.057 |
