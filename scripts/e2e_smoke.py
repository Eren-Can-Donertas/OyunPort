#!/usr/bin/env python3
"""
Çalışan Docker Compose yığınına karşı uçtan uca duman testi.
İstekler web (nginx) konteynerine gider: tarayıcıdaki React uygulamasıyla aynı köken ve aynı /api yolu.
Zincir: nginx -> Spring Boot (JDBC/HikariCP) -> PostgreSQL. Mock yoktur.

Kullanım:  python3 scripts/e2e_smoke.py [--base http://127.0.0.1:8080]
Ön koşul:  docker compose up -d  ve  datagen/load.sh (demo@oyunport.test kullanıcısı ve katalog)
Çıktı:     her adım için PASS/FAIL; herhangi biri başarısızsa çıkış kodu 1.
"""
from __future__ import annotations

import argparse
import json
import sys
import urllib.error
import urllib.request
import uuid

results: list[tuple[str, bool, str]] = []


def call(base: str, method: str, path: str, token: str | None = None, body=None):
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(base + path, data=data, method=method)
    req.add_header("Accept", "application/json")
    if data is not None:
        req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", f"Bearer {token}")
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            raw = r.read()
            return r.status, (json.loads(raw) if raw else None)
    except urllib.error.HTTPError as e:
        raw = e.read()
        try:
            return e.code, json.loads(raw) if raw else None
        except json.JSONDecodeError:
            return e.code, raw.decode(errors="replace")


def check(name: str, cond: bool, detail: str = "") -> bool:
    results.append((name, cond, detail))
    print(f"{'PASS' if cond else 'FAIL'}  {name}" + (f"  — {detail}" if detail else ""))
    return cond


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="http://127.0.0.1:8080")
    base = ap.parse_args().base

    s, h = call(base, "GET", "/api/health")
    check("Sağlık: nginx -> backend -> PostgreSQL", s == 200 and h["customerPool"]["dbRole"] == "app_customer"
          and h["adminPool"]["dbRole"] == "app_admin", f"{s} {h}")
    with urllib.request.urlopen(base + "/") as r:
        html = r.read().decode()
        asset = [a for a in html.split('"') if a.startswith("/assets/") and a.endswith(".js")]
    check("React SPA nginx üzerinden sunuluyor (index.html + JS paketi)",
          r.status == 200 and '<div id="root">' in html and bool(asset)
          and urllib.request.urlopen(base + asset[0]).status == 200, asset[0] if asset else "js yok")

    s, r = call(base, "POST", "/api/auth/login", body={"email": "demo@oyunport.test", "password": "Oyun123!"})
    if not check("Giriş: sentetik demo müşteri", s == 200, f"{s}"):
        return 1
    tok = r["token"]

    s, r = call(base, "GET", "/api/catalog/products?type=BASE_GAME&sort=price_asc&size=50", tok)
    check("Katalog araması (gerçek veri)", s == 200 and r["total"] > 0, f"total={r.get('total') if isinstance(r, dict) else r}")
    candidates = [p for p in r["items"] if not p["owned"]]
    base_game = candidates[0]
    detail_status, detail = call(base, "GET", f"/api/catalog/products/{base_game['productId']}", tok)
    check("Ürün detayı", detail_status == 200 and detail["productType"] == "BASE_GAME")

    s, v = call(base, "POST", "/api/cart/validate", tok, {"productIds": [base_game["productId"]]})
    check("Sepet doğrulaması", s == 200 and v["valid"], f"total={v.get('total')}")

    s, o = call(base, "POST", "/api/orders", tok, {"checkoutKey": str(uuid.uuid4()), "productIds": [base_game["productId"]]})
    check("T1: PENDING sipariş", s == 201 and o["order"]["status"] == "PENDING", f"{s}")
    oid = o["order"]["orderId"]

    s, p = call(base, "POST", f"/api/orders/{oid}/payments", tok, {"requestKey": str(uuid.uuid4()), "simulateOutcome": "CARD_DECLINED"})
    check("T2: başarısız ödeme FAILED kaydı, sipariş PENDING", s == 200 and p["paymentStatus"] == "FAILED" and p["orderStatus"] == "PENDING")

    key = str(uuid.uuid4())
    s, p = call(base, "POST", f"/api/orders/{oid}/payments", tok, {"requestKey": key, "simulateOutcome": "SUCCESS"})
    check("T2: başarılı ödeme -> COMPLETED + kütüphane", s == 200 and p["orderStatus"] == "COMPLETED" and len(p["libraryIds"]) == 1)
    s, p2 = call(base, "POST", f"/api/orders/{oid}/payments", tok, {"requestKey": key, "simulateOutcome": "SUCCESS"})
    check("Idempotency: aynı request_key önceki sonucu döndürür", s == 200 and p2["replayed"] and p2["paymentId"] == p["paymentId"])

    s, d = call(base, "GET", f"/api/orders/{oid}", tok)
    check("Sipariş geçmişi: FAILED + SUCCESS denemeleri saklı",
          s == 200 and [x["status"] for x in d["payments"]] == ["FAILED", "SUCCESS"])

    s, e = call(base, "POST", "/api/orders", tok, {"checkoutKey": str(uuid.uuid4()), "productIds": [base_game["productId"]]})
    check("BR02: sahip olunan ürün tekrar satın alınamaz", s == 409, f"{s} {e.get('message') if isinstance(e, dict) else e}")

    s, lib = call(base, "GET", "/api/library", tok)
    entry = next(x for x in lib if x["productId"] == base_game["productId"])
    s, sess = call(base, "POST", f"/api/library/{entry['libraryId']}/sessions/record", tok, {"minutes": 1})
    check("Oynama oturumu kaydı (ana oyun)", s == 201)
    s, rv = call(base, "PUT", f"/api/reviews/{base_game['productId']}", tok, {"rating": 5, "comment": "Duman testi"})
    check("Yorum ekleme (sahip olunan ana oyun)", s == 200)

    s, _ = call(base, "GET", "/api/admin/reports/run/q1", tok)
    check("Müşteri admin raporuna erişemez (403)", s == 403)

    s, a = call(base, "POST", "/api/auth/login", body={"email": "admin@oyunport.local", "password": "Admin123!"})
    atok = a["token"]
    for key in ["overview", "q1", "q2", "q3", "q4", "q5"]:
        s, rep = call(base, "GET", f"/api/admin/reports/run/{key}", atok)
        check(f"Admin rapor {key}", s == 200 and len(rep["rows"]) > 0, f"{len(rep['rows']) if isinstance(rep, dict) else rep} satır, {rep.get('elapsedMs') if isinstance(rep, dict) else ''} ms")
    s, plan = call(base, "GET", "/api/admin/reports/plan/q5", atok)
    check("Admin canlı EXPLAIN (ANALYZE, BUFFERS)", s == 200 and plan["executionTimeMs"] is not None,
          f"{plan.get('executionTimeMs')} ms")

    failed = [n for n, ok, _ in results if not ok]
    print(f"\nToplam {len(results)} kontrol: {len(results) - len(failed)} PASS, {len(failed)} FAIL")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
