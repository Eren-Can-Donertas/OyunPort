# Normalizasyon analizi (rapor §5.1–5.2'nin final hâli)

Her tablo için aday anahtarlar (CK), önemsiz olmayan fonksiyonel bağımlılıklar (FD) ve normal form.
Tanım: bir şema, önemsiz olmayan her X → Y bağımlılığında X bir süper anahtarsa BCNF'dir.
Bütün tablolar 1NF'dir (çok değerli developer/genre bilgisi ayrı bağlantı tablolarındadır; dizi/liste sütunu yoktur).

| Tablo | Aday anahtarlar | Önemsiz olmayan FD'ler | NF | Not |
|---|---|---|---|---|
| APP_USER | {user_id}, {email} | user_id → email, display_name, password_hash, created_at, is_active; email → user_id, … | BCNF | Her iki belirleyici aday anahtar. |
| APP_ROLE | {role_id}, {role_name} | role_id → role_name; role_name → role_id | BCNF | |
| USER_ROLE | {user_id, role_id} | — (tüm-anahtar) | BCNF | |
| PUBLISHER | {publisher_id} | publisher_id → name, country | BCNF | name aday anahtar değildir (raporda UQ yok, Ç12). |
| DEVELOPER | {developer_id} | developer_id → name, country | BCNF | |
| GENRE | {genre_id}, {name} | genre_id → name; name → genre_id | BCNF | |
| PRODUCT | {product_id} | product_id → publisher_id, title, description, list_price, release_date, is_active | BCNF | 3NF gereği publisher adı kopyalanmaz; alt tür sütunu (product_type) yoktur, üyelikten türetilir. |
| BASE_GAME | {product_id} | product_id → minimum_requirements | BCNF | Subclass; PK aynı zamanda PRODUCT FK'si. |
| DLC | {product_id} | product_id → base_game_id, content_summary | BCNF | |
| PRODUCT_GENRE | {product_id, genre_id} | — | BCNF | M:N CLASSIFIED_AS. |
| PRODUCT_DEVELOPER | {product_id, developer_id} | — | BCNF | M:N DEVELOPS. |
| WISHLIST | {user_id, product_id} | user_id, product_id → added_at | BCNF | |
| STORE_ORDER | {order_id}, {checkout_key} | order_id → user_id, status, created_at, completed_at, checkout_key; checkout_key → order_id, … | BCNF | Sipariş toplamı saklanmaz (`v_order_summary`). status → completed_at bir FD değildir (durum değeri tarih değerini belirlemez; yalnızca NULL olup olmadığını CHECK sınırlar). |
| ORDER_ITEM | {order_id, line_no}, {order_id, product_id} | (order_id, line_no) → product_id, unit_price_paid; (order_id, product_id) → line_no, unit_price_paid | BCNF | 2NF: product_id'ye kısmi bağımlı title/list_price tutulmaz. product_id → unit_price_paid bir FD **değildir** (BR07: tarihsel fiyat). |
| PAYMENT_ATTEMPT | {payment_id}, {request_key} | payment_id → order_id, amount, status, attempted_at, request_key; request_key → payment_id, … | BCNF | amount türetilmiş bir değer gibi görünse de bir denemenin tutarıdır; tutarlılığı trigger denetler (Ç4). |
| LIBRARY_ENTRY | {library_id}, {user_id, product_id}, {order_id, line_no} | library_id → …; (user_id, product_id) → …; (order_id, line_no) → user_id, product_id, acquired_at, library_id | BCNF | Bkz. aşağıdaki not. |
| PLAY_SESSION | {session_id} | session_id → library_id, started_at, ended_at | BCNF | Toplam süre saklanmaz (`v_library_playtime`). |
| REVIEW | {user_id, base_game_id} | (user_id, base_game_id) → rating, comment, created_at, updated_at | BCNF | Kullanıcı-oyun başına tek yorum. |

## LIBRARY_ENTRY üzerine not (ilişkiler arası fazlalık)

LIBRARY_ENTRY içindeki `user_id` ve `product_id`, kaynak kalem (order_id, line_no) üzerinden ORDER_ITEM ve
STORE_ORDER'dan da türetilebilir. Bu, tablo içinde bir normal form ihlali değildir — (order_id, line_no)
LIBRARY_ENTRY'nin aday anahtarıdır, dolayısıyla tablo BCNF'dir — ancak ilişkiler arası bir fazlalıktır.
Rapor bu tasarımı bilinçli olarak seçer: BR02'nin "kullanıcı bir ürüne bir kez sahip olur" kuralı yalnızca
(user_id, product_id) aynı tabloda bulunduğunda declaratif `UNIQUE` ile korunabilir. Fazlalığın tutarsızlık
üretmemesi `library_entry_source` deferred constraint trigger'ı ile garanti edilir (kullanıcı = sipariş sahibi,
ürün = kalem ürünü) ve sahiplik satırı değiştirilemez (`library_entry_immutable`). Bu durum
`SchemaConstraintsIT.libraryEntrySourceConsistency` ve `datagen/sql/30_validate.sql` ile sınanmıştır.

## Türetilmiş değerler

Sipariş toplamı (`v_order_summary`), aylık satış (`v_game_sales_monthly`) ve toplam oynama süresi
(`v_library_playtime`) saklanmaz; view'larla hesaplanır. Böylece güncelleme anomalisi oluşmaz.
