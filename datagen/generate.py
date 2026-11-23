#!/usr/bin/env python3
"""
OyunPort sentetik veri üreticisi (rapor §8.1).

* Yalnızca Python standart kütüphanesi; sabit seed ile deterministik.
* PostgreSQL COPY (CSV) için dosyalar üretir: datagen/out/*.csv
* İş kuralları üretim sırasında sağlanır (rapor §8.1 son paragraf):
    - her PRODUCT tam olarak BASE_GAME veya DLC; en az bir genre ve developer (BR01, §4.2)
    - siparişler PENDING oluşturulur; tamamlananlar tek SUCCESS ödemeyle COMPLETED olur,
      başarısız denemeler önce gelir (BR09); ödeme tutarı = kalem toplamı (BR04)
    - siparişte aynı ürün bir kez (BR03); kullanıcı-ürün sahipliği benzersiz (BR02)
    - DLC yalnızca ana oyuna önceden sahip olunduysa veya aynı siparişteyse (BR05)
    - kütüphane yalnızca tamamlanmış kalemlerden; acquired_at = completed_at (BR04)
    - oturumlar yalnızca sahip olunan ana oyunlarda, edinimden sonra, ended_at >= started_at (BR06)
    - yorum yalnızca sahip olunan ana oyuna, kullanıcı-oyun başına tek (BR06)
    - unit_price_paid tarihsel fiyattır, list_price'tan farklı olabilir (BR07)

Kullanım:
    python3 datagen/generate.py --scale full   # rapor hacimleri (~3,1 M satır)
    python3 datagen/generate.py --scale small  # hızlı demo verisi
"""
from __future__ import annotations

import argparse
import bisect
import csv
import itertools
import json
import os
import random
import sys
import time
import uuid
from datetime import date, datetime, timedelta, timezone

UTC = timezone.utc

# Seed admin (V6 migration) user_id = 1. Sentetik kullanıcılar 2'den başlar.
FIRST_USER_ID = 2
# Tüm sentetik kullanıcıların parolası: Oyun123!  (bcrypt hash bir kez hesaplandı)
DEMO_PASSWORD_HASH = "$2b$10$miZdSoTDXbEyR/a/9A2.oONTFqoy8fB5T/7cqHHR8UIVgHyqTwmNK"

SCALES = {
    "full": dict(users=40_000, base_games=4_000, dlcs=1_000, publishers=250, developers=600,
                 orders=300_000, sessions=1_000_000, reviews=80_000, wishlist=150_000),
    "small": dict(users=400, base_games=120, dlcs=40, publishers=15, developers=30,
                  orders=3_000, sessions=10_000, reviews=800, wishlist=1_500),
}

ORDER_START = datetime(2024, 1, 1, tzinfo=UTC)
ORDER_END = datetime(2026, 10, 31, 23, 0, tzinfo=UTC)
REF_END = datetime(2026, 11, 15, tzinfo=UTC)          # oturum/yorum/istek listesi üst sınırı
USER_START = datetime(2023, 1, 1, tzinfo=UTC)
USER_END = datetime(2026, 9, 30, tzinfo=UTC)

GENRES = ["Aksiyon", "Macera", "Rol Yapma (RPG)", "Strateji", "Simülasyon", "Spor", "Yarış", "Bulmaca",
          "Korku", "Platform", "Nişancı", "Dövüş", "Hayatta Kalma", "Açık Dünya", "Roguelike", "Kart Oyunu",
          "Ritim", "Bağımsız"]
ADJ = ["Kayıp", "Sonsuz", "Karanlık", "Kızıl", "Gizli", "Unutulmuş", "Demir", "Gölge", "Yıldız", "Buzul",
       "Ateş", "Sessiz", "Kadim", "Son", "Vahşi", "Altın", "Gümüş", "Kırık", "Derin", "Neon", "Fırtına",
       "Sisli", "Mavi", "Yasak", "Parlak", "Rüzgâr", "Kristal", "Paslı", "Uyanan", "Yitik", "Çelik", "Yeşil"]
NOUN = ["Krallık", "Vadi", "Ada", "Şehir", "Orman", "Yol", "Kule", "Kale", "Galaksi", "Okyanus", "Çöl",
        "Labirent", "Sınır", "Liman", "Ufuk", "Dağ", "Mağara", "Gezegen", "Arena", "Hanedan", "Muhafız",
        "Avcı", "Gezgin", "Lejyon", "Simyacı", "Kâşif", "Pilot", "Şövalye", "Bahçe", "Kervan", "Rota", "Yankı"]
TITLE_SUFFIX = ["Kronikleri", "Efsanesi", "Günlükleri", "Sırrı", "Yolculuğu", "Savaşları", "Taktikleri", "Uyanışı"]
DLC_WORDS = ["Kuzey Rüzgârı", "Kış Seferi", "Antik Kalıntılar", "Kayıp Topraklar", "Derin Sular", "Gece Pazarı",
             "Demir Yollar", "Yıldız Haritası", "Sis Adaları", "Son Kale", "Kızıl Çöl", "Gölge Loncası"]
DLC_KIND = ["Genişlemesi", "Paketi", "Bölümü", "Hikâye Paketi", "Sezon Kartı"]
COMPANY_WORD = ["Anka", "Boğaz", "Kapadokya", "Lale", "Pusula", "Mavi Kuş", "Yıldırım", "Çınar", "Martı",
                "Kartal", "Okyanus", "Atlas", "Zirve", "Meltem", "Toros", "Kervan", "Dicle", "Fırat", "Ege", "Pera"]
COMPANY_KIND = ["Oyun", "Yayıncılık", "Interactive", "Dijital", "Eğlence", "Games", "Medya"]
DEV_KIND = ["Stüdyo", "Atölye", "Lab", "Yazılım", "Oyun Evi", "Collective"]
COUNTRIES = ["TR", "TR", "TR", "DE", "PL", "FR", "JP", "US", "KR", "SE", "FI", "GB", "CA", "BR", "ES"]
FIRST_NAMES = ["Ayşe", "Mehmet", "Zeynep", "Ahmet", "Elif", "Mustafa", "Fatma", "Emre", "Merve", "Can",
               "Deniz", "Burak", "Ece", "Kerem", "Selin", "Mert", "Defne", "Arda", "İrem", "Yusuf", "Eren",
               "Ceren", "Onur", "Gizem", "Kaan", "Buse", "Berk", "Damla", "Ozan", "Pelin"]
LAST_INITIALS = "ABCÇDEFGHIİKLMNOÖPRSŞTUÜVYZ"
REQ = ["4 GB RAM, GTX 750", "8 GB RAM, GTX 1050 Ti", "8 GB RAM, GTX 1060", "16 GB RAM, RTX 2060",
       "16 GB RAM, RTX 3060", "32 GB RAM, RTX 4070", "4 GB RAM, entegre grafik"]
REVIEW_TEXT = {
    1: ["Beklediğim gibi değildi.", "Performans sorunları çok fazla.", "Hikâye zayıf kalmış."],
    2: ["Fikir güzel ama uygulaması eksik.", "Kısa sürede sıkıldım."],
    3: ["Fena değil, indirimde alınabilir.", "Ortalama bir deneyim.", "Bazı bölümleri keyifli."],
    4: ["Çok keyifli, tavsiye ederim.", "Görseller ve müzikler başarılı.", "Arkadaşlarla oynaması güzel."],
    5: ["Yılın en iyi oyunlarından!", "Defalarca bitirdim, muhteşem.", "Her saniyesine değdi."],
}
BASE_PRICES = [49.99, 99.99, 149.99, 199.99, 249.99, 349.99, 499.99, 749.99, 999.99, 1299.99]
DLC_PRICES = [19.99, 39.99, 59.99, 99.99, 149.99, 249.99]
DISCOUNTS = [(0.0, 0.70), (0.10, 0.10), (0.25, 0.10), (0.50, 0.07), (0.75, 0.03)]


def ts(dt: datetime) -> str:
    return dt.isoformat(timespec="seconds")


def rand_dt(rng: random.Random, start: datetime, end: datetime) -> datetime:
    span = int((end - start).total_seconds())
    return start + timedelta(seconds=rng.randint(0, max(span, 0)))


def rand_uuid(rng: random.Random) -> str:
    return str(uuid.UUID(int=rng.getrandbits(128), version=4))


class WeightedSampler:
    """Kümülatif ağırlıklarla hızlı örnekleme (bisect)."""

    def __init__(self, items, weights):
        self.items = list(items)
        self.cum = list(itertools.accumulate(weights))
        self.total = self.cum[-1]

    def sample(self, rng: random.Random):
        return self.items[bisect.bisect_right(self.cum, rng.random() * self.total)]


def zipf_weights(n: int, s: float):
    return [1.0 / ((k + 1) ** s) for k in range(n)]


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--scale", choices=sorted(SCALES), default="full")
    ap.add_argument("--seed", type=int, default=372)
    ap.add_argument("--out", default=os.path.join(os.path.dirname(os.path.abspath(__file__)), "out"))
    args = ap.parse_args()

    cfg = SCALES[args.scale]
    rng = random.Random(args.seed)
    os.makedirs(args.out, exist_ok=True)
    t0 = time.time()
    counts: dict[str, int] = {}

    def writer(name: str):
        f = open(os.path.join(args.out, f"{name}.csv"), "w", newline="", encoding="utf-8")
        return f, csv.writer(f, lineterminator="\n")

    # ------------------------------------------------------------------ katalog
    f, w = writer("genre")
    for gid, g in enumerate(GENRES, start=1):
        w.writerow([gid, g])
    f.close()
    counts["genre"] = len(GENRES)

    f, w = writer("publisher")
    for pid in range(1, cfg["publishers"] + 1):
        name = f"{rng.choice(COMPANY_WORD)} {rng.choice(COMPANY_KIND)} {pid}"
        w.writerow([pid, name, rng.choice(COUNTRIES)])
    f.close()
    counts["publisher"] = cfg["publishers"]

    f, w = writer("developer")
    for did in range(1, cfg["developers"] + 1):
        name = f"{rng.choice(DEV_KIND)} {rng.choice(COMPANY_WORD)} {did}"
        w.writerow([did, name, rng.choice(COUNTRIES)])
    f.close()
    counts["developer"] = cfg["developers"]

    n_base, n_dlc = cfg["base_games"], cfg["dlcs"]
    base_ids = list(range(1, n_base + 1))
    dlc_ids = list(range(n_base + 1, n_base + n_dlc + 1))
    # Popülerlik: ana oyunlara karıştırılmış Zipf (s=0.6) ağırlıkları
    pop = zipf_weights(n_base, 0.6)
    rng.shuffle(pop)
    base_pop = dict(zip(base_ids, pop))
    base_sampler = WeightedSampler(base_ids, [base_pop[b] for b in base_ids])

    release: dict[int, date] = {}
    price: dict[int, float] = {}
    title: dict[int, str] = {}
    publisher_of: dict[int, int] = {}
    dlc_base: dict[int, int] = {}
    dlcs_of_base: dict[int, list[int]] = {}
    active: dict[int, bool] = {}

    for b in base_ids:
        # Çoğu ürün 2024 öncesi; bir kısmı sipariş döneminde çıkar
        if rng.random() < 0.8:
            release[b] = date(2012, 1, 1) + timedelta(days=rng.randint(0, (date(2023, 12, 31) - date(2012, 1, 1)).days))
        else:
            release[b] = date(2024, 1, 1) + timedelta(days=rng.randint(0, (date(2026, 9, 30) - date(2024, 1, 1)).days))
        r = rng.random()
        if r < 0.35:
            t = f"{rng.choice(ADJ)} {rng.choice(NOUN)}"
        elif r < 0.65:
            t = f"{rng.choice(NOUN)} {rng.choice(TITLE_SUFFIX)}"
        elif r < 0.85:
            t = f"{rng.choice(ADJ)} {rng.choice(NOUN)}: {rng.choice(ADJ)} {rng.choice(NOUN)}"
        else:
            t = f"{rng.choice(ADJ)} {rng.choice(NOUN)} {rng.choice(['II', 'III', 'IV', 'Zero', '2077'])}"
        title[b] = f"{t} #{b}"
        price[b] = rng.choice(BASE_PRICES)
        publisher_of[b] = rng.randint(1, cfg["publishers"])
        active[b] = rng.random() > 0.02

    for d in dlc_ids:
        b = base_sampler.sample(rng)
        dlc_base[d] = b
        dlcs_of_base.setdefault(b, []).append(d)
        rd = release[b] + timedelta(days=rng.randint(30, 720))
        release[d] = min(rd, date(2026, 10, 15))
        title[d] = f"{title[b].split(' #')[0]}: {rng.choice(DLC_WORDS)} {rng.choice(DLC_KIND)} #{d}"
        price[d] = rng.choice(DLC_PRICES)
        publisher_of[d] = publisher_of[b]
        active[d] = rng.random() > 0.02

    f, w = writer("product")
    fb, wb = writer("base_game")
    fd, wd = writer("dlc")
    fg, wg = writer("product_genre")
    fv, wv = writer("product_developer")
    n_pg = n_pd = 0
    for pid in base_ids + dlc_ids:
        is_base = pid in base_pop
        desc = (f"{title[pid].split(' #')[0]} için sentetik açıklama. "
                + ("Ana oyun." if is_base else f"'{title[dlc_base[pid]].split(' #')[0]}' için ek içerik."))
        w.writerow([pid, publisher_of[pid], title[pid], desc, f"{price[pid]:.2f}", release[pid].isoformat(),
                    "t" if active[pid] else "f"])
        if is_base:
            wb.writerow([pid, rng.choice(REQ)])
        else:
            wd.writerow([pid, dlc_base[pid], f"{rng.choice(DLC_WORDS)} bölgesi, yeni görevler ve eşyalar."])
        for gid in rng.sample(range(1, len(GENRES) + 1), rng.choices([1, 2, 3], [0.45, 0.4, 0.15])[0]):
            wg.writerow([pid, gid])
            n_pg += 1
        for did in rng.sample(range(1, cfg["developers"] + 1), rng.choices([1, 2], [0.8, 0.2])[0]):
            wv.writerow([pid, did])
            n_pd += 1
    for fh in (f, fb, fd, fg, fv):
        fh.close()
    counts.update(product=n_base + n_dlc, base_game=n_base, dlc=n_dlc, product_genre=n_pg, product_developer=n_pd)

    # ------------------------------------------------------------------ kullanıcılar
    n_users = cfg["users"]
    user_ids = list(range(FIRST_USER_ID, FIRST_USER_ID + n_users))
    user_created: dict[int, datetime] = {}
    f, w = writer("app_user")
    for i, uid in enumerate(user_ids):
        created = rand_dt(rng, USER_START, USER_END)
        user_created[uid] = created
        if i == 0:
            email, name = "demo@oyunport.test", "Demo Oyuncu"
        else:
            email = f"oyuncu{uid}@oyunport.test"
            name = f"{rng.choice(FIRST_NAMES)} {rng.choice(LAST_INITIALS)}."
        is_active = "f" if (i > 0 and rng.random() < 0.015) else "t"
        w.writerow([uid, email, name, DEMO_PASSWORD_HASH, ts(created), is_active])
    f.close()
    counts["app_user"] = n_users

    # Kullanıcı etkinliği: lognormal ağırlık -> sipariş dağılımı
    activity = WeightedSampler(user_ids, [rng.lognormvariate(0, 0.8) for _ in user_ids])

    # ------------------------------------------------------------------ siparişler
    n_orders = cfg["orders"]
    orders_by_user: dict[int, int] = {}
    for _ in range(n_orders):
        u = activity.sample(rng)
        orders_by_user[u] = orders_by_user.get(u, 0) + 1

    fo, wo = writer("store_order")             # PENDING olarak yüklenir
    ff, wf = writer("order_final_status")      # sonraki adımda status/completed_at güncellemesi
    fi, wi = writer("order_item")
    fp, wp = writer("payment_attempt")
    fl, wl = writer("library_entry")

    order_id = 0
    payment_id = 0
    library_id = 0
    n_items = n_completed = n_pending = n_cancelled = n_pay = n_fail = 0
    base_entries: list[tuple[int, int, int, datetime]] = []   # (library_id, user, product, acquired)
    owned_by_user: dict[int, set[int]] = {}

    def pick_discount() -> float:
        r = rng.random()
        acc = 0.0
        for disc, p in DISCOUNTS:
            acc += p
            if r < acc:
                return disc
        return 0.0

    for uid in user_ids:
        k = orders_by_user.get(uid, 0)
        if k == 0:
            continue
        start = max(user_created[uid] + timedelta(hours=1), ORDER_START)
        if start >= ORDER_END:
            start = ORDER_END - timedelta(days=1)
        times = sorted(rand_dt(rng, start, ORDER_END) for _ in range(k))
        used: set[int] = set()          # kullanıcının herhangi bir siparişinde geçen ürünler
        owned: set[int] = set()         # tamamlanmış siparişlerden sahip olunanlar
        last_t = start
        for created in times:
            # Sonraki sipariş, öncekinin son ödeme denemesinden sonra oluşturulur (T1 anında sahiplik doğru olsun).
            if created <= last_t:
                created = last_t + timedelta(minutes=rng.randint(1, 120))
            r = rng.random()
            status = "COMPLETED" if r < 0.90 else ("PENDING" if r < 0.95 else "CANCELLED")
            n_slots = rng.choices([1, 2, 3, 4], [0.40, 0.30, 0.20, 0.10])[0]
            chosen: list[int] = []
            chosen_set: set[int] = set()
            cday = created.date()
            for _ in range(n_slots):
                pid = None
                if rng.random() < 0.30:
                    bases = [b for b in itertools.chain(owned, chosen_set) if b in dlcs_of_base]
                    if bases:
                        cands = [d for d in dlcs_of_base[rng.choice(bases)]
                                 if d not in used and d not in chosen_set and active[d] and release[d] <= cday]
                        if cands:
                            pid = rng.choice(cands)
                if pid is None:
                    for _try in range(25):
                        b = base_sampler.sample(rng)
                        if b not in used and b not in chosen_set and active[b] and release[b] <= cday:
                            pid = b
                            break
                if pid is not None:
                    chosen.append(pid)
                    chosen_set.add(pid)
            if not chosen:
                continue
            order_id += 1
            used |= chosen_set
            wo.writerow([order_id, uid, ts(created), rand_uuid(rng)])
            total = 0.0
            paid: list[tuple[int, int]] = []
            for line_no, pid in enumerate(chosen, start=1):
                unit = round(price[pid] * (1 - pick_discount()), 2)
                total += unit
                wi.writerow([order_id, line_no, pid, f"{unit:.2f}"])
                paid.append((line_no, pid))
            n_items += len(chosen)
            amount = f"{round(total, 2):.2f}"

            if status == "COMPLETED":
                fails = rng.choices([0, 1, 2], [0.82, 0.14, 0.04])[0]
            elif status == "PENDING":
                fails = rng.choices([0, 1, 2], [0.50, 0.35, 0.15])[0]
            else:
                fails = rng.choices([0, 1, 2], [0.40, 0.40, 0.20])[0]
            t = created
            for _ in range(fails):
                t = t + timedelta(minutes=rng.randint(1, 30))
                payment_id += 1
                wp.writerow([payment_id, order_id, amount, "FAILED", ts(t), rand_uuid(rng)])
                n_fail += 1
            if status == "COMPLETED":
                t = t + timedelta(minutes=rng.randint(1, 30))
                payment_id += 1
                wp.writerow([payment_id, order_id, amount, "SUCCESS", ts(t), rand_uuid(rng)])
                wf.writerow([order_id, "COMPLETED", ts(t)])
                for line_no, pid in paid:
                    library_id += 1
                    wl.writerow([library_id, uid, pid, order_id, line_no, ts(t)])
                    owned.add(pid)
                    if pid in base_pop:
                        base_entries.append((library_id, uid, pid, t))
                n_completed += 1
            elif status == "CANCELLED":
                wf.writerow([order_id, "CANCELLED", ""])
                n_cancelled += 1
            else:
                n_pending += 1
            n_pay += fails + (1 if status == "COMPLETED" else 0)
            last_t = t
        owned_by_user[uid] = owned
    for fh in (fo, ff, fi, fp, fl):
        fh.close()
    counts.update(store_order=order_id, order_item=n_items, payment_attempt=n_pay, library_entry=library_id,
                  orders_completed=n_completed, orders_pending=n_pending, orders_cancelled=n_cancelled,
                  payments_failed=n_fail)

    # ------------------------------------------------------------------ oynama oturumları
    # Her ana oyun kaydına bir ilgi ağırlığı; düşük ağırlıklılar hiç/az oynanır (Q4 için gerçekçi dağılım).
    engagement = [(0.0 if rng.random() < 0.18 else rng.lognormvariate(0, 1.2)) for _ in base_entries]
    sess_sampler = WeightedSampler(range(len(base_entries)), engagement)
    f, w = writer("play_session")
    for sid in range(1, cfg["sessions"] + 1):
        lib, _uid, _pid, acquired = base_entries[sess_sampler.sample(rng)]
        start_lo = acquired + timedelta(minutes=5)
        if start_lo >= REF_END:
            start_lo = acquired
        started = rand_dt(rng, start_lo, REF_END)
        minutes = min(int(rng.lognormvariate(3.8, 0.8)) + 1, 8 * 60)
        w.writerow([sid, lib, ts(started), ts(started + timedelta(minutes=minutes))])
    f.close()
    counts["play_session"] = cfg["sessions"]

    # ------------------------------------------------------------------ yorumlar
    f, w = writer("review")
    seen: set[tuple[int, int]] = set()
    n_rev = 0
    while n_rev < cfg["reviews"] and len(seen) < len(base_entries):
        _lib, uid, pid, acquired = base_entries[rng.randrange(len(base_entries))]
        if (uid, pid) in seen:
            continue
        seen.add((uid, pid))
        rating = rng.choices([1, 2, 3, 4, 5], [0.05, 0.08, 0.20, 0.35, 0.32])[0]
        lo = acquired + timedelta(days=1)
        created = rand_dt(rng, lo, REF_END) if lo < REF_END else acquired
        updated = rand_dt(rng, created, REF_END) if (rng.random() < 0.2 and created < REF_END) else created
        comment = rng.choice(REVIEW_TEXT[rating]) if rng.random() < 0.85 else ""
        w.writerow([uid, pid, rating, comment, ts(created), ts(updated)])
        n_rev += 1
    f.close()
    counts["review"] = n_rev

    # ------------------------------------------------------------------ istek listesi
    all_products = base_ids + dlc_ids
    f, w = writer("wishlist")
    wseen: set[tuple[int, int]] = set()
    n_wish = 0
    attempts = 0
    while n_wish < cfg["wishlist"] and attempts < cfg["wishlist"] * 20:
        attempts += 1
        uid = activity.sample(rng)
        pid = base_sampler.sample(rng) if rng.random() < 0.8 else rng.choice(all_products)
        if (uid, pid) in wseen or pid in owned_by_user.get(uid, ()):
            continue
        wseen.add((uid, pid))
        w.writerow([uid, pid, ts(rand_dt(rng, user_created[uid], REF_END))])
        n_wish += 1
    f.close()
    counts["wishlist"] = n_wish

    counts["generation_seconds"] = round(time.time() - t0, 1)
    meta = {"scale": args.scale, "seed": args.seed, "generated_at": datetime.now(UTC).isoformat(timespec="seconds"),
            "counts": counts}
    with open(os.path.join(args.out, "manifest.json"), "w", encoding="utf-8") as fh:
        json.dump(meta, fh, ensure_ascii=False, indent=2)
    print(json.dumps(meta, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
