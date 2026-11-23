# EER ve ilişkisel şema

Ara rapor Şekil 1 ve Ek A (A.1–A.3) EER çizimlerinin finaldeki tam hâli ve uygulanan ilişkisel şema.
Diyagramlar Mermaid ile yazılmıştır (GitHub / VS Code önizlemesinde çizilir). İlişkisel şema
`backend/src/main/resources/db/migration/V1__core_schema.sql` ile birebir aynıdır.

## 1. EER (Chen gösterimi)

Gösterim: dikdörtgen = entity, çift çerçeve = weak entity, eşkenar dörtgen = ilişki,
`((d))` = disjoint specialization (PRODUCT'tan çıkan **kalın** çizgi = total participation).

```mermaid
flowchart LR
    APP_USER[APP_USER]
    APP_ROLE[APP_ROLE]
    PUBLISHER[PUBLISHER]
    DEVELOPER[DEVELOPER]
    GENRE[GENRE]
    PRODUCT[PRODUCT]
    D((d))
    BASE_GAME[BASE_GAME]
    DLC[DLC]
    STORE_ORDER[STORE_ORDER]
    ORDER_ITEM[[ORDER_ITEM]]
    PAYMENT_ATTEMPT[PAYMENT_ATTEMPT]
    LIBRARY_ENTRY[LIBRARY_ENTRY]
    PLAY_SESSION[PLAY_SESSION]

    HAS_ROLE{HAS_ROLE}
    PUBLISHES{PUBLISHES}
    DEVELOPS{DEVELOPS}
    CLASSIFIED{CLASSIFIED_AS}
    REQUIRED_BY{REQUIRED_BY}
    PLACES{PLACES}
    CONTAINS{{CONTAINS}}
    ITEM_OF{ITEM_OF}
    HAS_PAYMENT{HAS_PAYMENT}
    SOURCES{SOURCES}
    OWNS{OWNS}
    WISHES{WISHES}
    REVIEWS{REVIEWS}
    HAS_SESSION{HAS_SESSION}

    APP_USER ---|"0..N"| HAS_ROLE ---|"0..N"| APP_ROLE
    PUBLISHER ---|"1"| PUBLISHES ===|"N (total)"| PRODUCT
    DEVELOPER ---|"0..N"| DEVELOPS ===|"1..N (total)"| PRODUCT
    PRODUCT ===|"1..N (total)"| CLASSIFIED ---|"0..N"| GENRE
    PRODUCT ==> D
    D --> BASE_GAME
    D --> DLC
    BASE_GAME ---|"1"| REQUIRED_BY ===|"N (her DLC tam 1 ana oyun)"| DLC
    APP_USER ---|"1"| PLACES ===|"0..N"| STORE_ORDER
    STORE_ORDER ---|"1"| CONTAINS ===|"1..N (identifying)"| ORDER_ITEM
    PRODUCT ---|"1"| ITEM_OF ===|"0..N"| ORDER_ITEM
    STORE_ORDER ---|"1"| HAS_PAYMENT ---|"0..N"| PAYMENT_ATTEMPT
    ORDER_ITEM ---|"1"| SOURCES ---|"0..1"| LIBRARY_ENTRY
    APP_USER ---|"M"| OWNS ---|"N"| PRODUCT
    OWNS -.-|"ilişki entity'si"| LIBRARY_ENTRY
    APP_USER ---|"M"| WISHES ---|"N (added_at)"| PRODUCT
    APP_USER ---|"M"| REVIEWS ---|"N (rating, comment)"| BASE_GAME
    LIBRARY_ENTRY ---|"1"| HAS_SESSION ===|"0..N (yalnızca ana oyun)"| PLAY_SESSION
```

Önemli öznitelikler ve anahtarlar: PRODUCT(<u>product_id</u>, title, description, list_price, release_date, is_active);
BASE_GAME(minimum_requirements); DLC(content_summary); ORDER_ITEM(<ins>line_no</ins> partial key, unit_price_paid);
STORE_ORDER(<u>order_id</u>, status, created_at, completed_at, checkout_key); PAYMENT_ATTEMPT(<u>payment_id</u>, amount, status,
attempted_at, request_key); LIBRARY_ENTRY(<u>library_id</u>, acquired_at); PLAY_SESSION(<u>session_id</u>, started_at, ended_at);
REVIEWS ilişki öznitelikleri rating, comment, created_at, updated_at; WISHES ilişki özniteliği added_at.

Participation ve kardinalite matrisi rapor §3 tablosu ile aynıdır.

## 2. İlişkisel şema (uygulanan)

```mermaid
erDiagram
    APP_USER ||--o{ USER_ROLE : "has (CASCADE)"
    APP_ROLE ||--o{ USER_ROLE : "granted (RESTRICT)"
    PUBLISHER ||--o{ PRODUCT : publishes
    PRODUCT ||--o| BASE_GAME : "is-a"
    PRODUCT ||--o| DLC : "is-a"
    BASE_GAME ||--o{ DLC : "required_by"
    PRODUCT ||--|{ PRODUCT_GENRE : "classified (CASCADE)"
    GENRE ||--o{ PRODUCT_GENRE : "RESTRICT"
    PRODUCT ||--|{ PRODUCT_DEVELOPER : "developed (CASCADE)"
    DEVELOPER ||--o{ PRODUCT_DEVELOPER : "RESTRICT"
    APP_USER ||--o{ WISHLIST : "wishes (CASCADE)"
    PRODUCT ||--o{ WISHLIST : "CASCADE"
    APP_USER ||--o{ STORE_ORDER : places
    STORE_ORDER ||--|{ ORDER_ITEM : contains
    PRODUCT ||--o{ ORDER_ITEM : "item_of"
    STORE_ORDER ||--o{ PAYMENT_ATTEMPT : "has_payment"
    ORDER_ITEM ||--o| LIBRARY_ENTRY : sources
    APP_USER ||--o{ LIBRARY_ENTRY : owns
    PRODUCT ||--o{ LIBRARY_ENTRY : owned
    LIBRARY_ENTRY ||--o{ PLAY_SESSION : "has_session"
    LIBRARY_ENTRY ||--o| REVIEW : "ownership FK (user_id, base_game_id)"
    BASE_GAME ||--o{ REVIEW : reviewed

    APP_USER {
        bigint user_id PK
        varchar email UK "lower-case CHECK"
        varchar display_name
        varchar password_hash "BCrypt"
        timestamptz created_at
        boolean is_active
    }
    APP_ROLE {
        int role_id PK
        varchar role_name UK "CUSTOMER | ADMIN"
    }
    USER_ROLE {
        bigint user_id PK,FK
        int role_id PK,FK
    }
    PUBLISHER {
        bigint publisher_id PK
        varchar name
        varchar country
    }
    DEVELOPER {
        bigint developer_id PK
        varchar name
        varchar country
    }
    GENRE {
        bigint genre_id PK
        varchar name UK
    }
    PRODUCT {
        bigint product_id PK
        bigint publisher_id FK
        varchar title
        text description
        numeric list_price "CHECK >= 0"
        date release_date
        boolean is_active
    }
    BASE_GAME {
        bigint product_id PK,FK
        text minimum_requirements
    }
    DLC {
        bigint product_id PK,FK
        bigint base_game_id FK "-> BASE_GAME"
        text content_summary
    }
    PRODUCT_GENRE {
        bigint product_id PK,FK
        bigint genre_id PK,FK
    }
    PRODUCT_DEVELOPER {
        bigint product_id PK,FK
        bigint developer_id PK,FK
    }
    WISHLIST {
        bigint user_id PK,FK
        bigint product_id PK,FK
        timestamptz added_at
    }
    STORE_ORDER {
        bigint order_id PK
        bigint user_id FK
        varchar status "PENDING|COMPLETED|CANCELLED"
        timestamptz created_at
        timestamptz completed_at "NOT NULL iff COMPLETED"
        uuid checkout_key UK
    }
    ORDER_ITEM {
        bigint order_id PK,FK
        int line_no PK "CHECK > 0"
        bigint product_id FK "UK(order_id, product_id)"
        numeric unit_price_paid "CHECK >= 0"
    }
    PAYMENT_ATTEMPT {
        bigint payment_id PK
        bigint order_id FK "partial UK WHERE SUCCESS"
        numeric amount "= sum(items), trigger"
        varchar status "SUCCESS|FAILED"
        timestamptz attempted_at
        uuid request_key UK
    }
    LIBRARY_ENTRY {
        bigint library_id PK
        bigint user_id FK "UK(user_id, product_id)"
        bigint product_id FK
        bigint order_id FK "UK(order_id, line_no) -> ORDER_ITEM"
        int line_no FK
        timestamptz acquired_at
    }
    PLAY_SESSION {
        bigint session_id PK
        bigint library_id FK
        timestamptz started_at
        timestamptz ended_at "NULL = açık; >= started_at"
    }
    REVIEW {
        bigint user_id PK,FK
        bigint base_game_id PK,FK
        smallint rating "CHECK 1-5"
        varchar comment
        timestamptz created_at
        timestamptz updated_at
    }
```

## 3. Tablolar arası kurallar (trigger) — özet

| Nesne | Tür | Kural |
|---|---|---|
| `base_game_disjoint`, `dlc_disjoint` | BEFORE | BR01 disjoint |
| `product_subtype_total`, `base_game_subtype_total`, `dlc_subtype_total` | DEFERRABLE INITIALLY DEFERRED | BR01 total |
| `product_classification`, `product_genre_minimum`, `product_developer_minimum` | DEFERRED | En az bir genre ve developer |
| `store_order_has_items`, `order_item_minimum` | DEFERRED | BR04 sipariş ≥ 1 kalem |
| `store_order_state` | BEFORE | BR08/BR09 durum makinesi |
| `order_item_guard` | BEFORE | BR07/BR08 kalem yalnızca PENDING'e eklenir, değiştirilemez |
| `payment_attempt_guard` | BEFORE | BR09 PENDING şartı, BR04 tutar = Σ kalem, geçmiş değişmez |
| `payment_success_completes_order` | DEFERRED | BR09 SUCCESS ⇒ aynı transaction'da COMPLETED |
| `library_entry_source` | DEFERRED | BR04 kaynak tutarlılığı, BR05 DLC ön koşulu |
| `library_entry_immutable` | BEFORE | BR02 sahiplik kalıcı |
| `play_session_guard` | BEFORE | BR06 yalnızca BASE_GAME, edinimden sonra |
| `review_update` | BEFORE | BR06 anahtar değişmez, updated_at |

İndeksler: 18 PK/UQ indeksi, FK destek indeksleri (V1) ve rapor §8.2'nin beş adayı (V4).
View'lar: `v_order_summary`, `v_game_sales_monthly`, `v_library_playtime`, `v_product_rating`, `v_public_review`,
`rpt_overview`, `rpt_user_activity_monthly`, `rpt_q1`…`rpt_q5` (V3).
