#!/usr/bin/env python3
"""perf/results/*_{without,with}.txt dosyalarındaki gerçek EXPLAIN (ANALYZE, BUFFERS) çıktılarından
özet tablo üretir (perf/results/OZET.md ve ozet.json). Hiçbir değer elle girilmez."""
from __future__ import annotations

import glob
import json
import os
import re
import statistics

HERE = os.path.dirname(os.path.abspath(__file__))
RES = os.path.join(HERE, "results")

EXP = re.compile(r"Execution Time: ([0-9.]+) ms")
PLAN = re.compile(r"Planning Time: ([0-9.]+) ms")
BUF = re.compile(r"Buffers: shared(?: hit=(\d+))?(?: read=(\d+))?")
TOPROWS = re.compile(r"cost=[0-9.]+\.\.([0-9.]+) rows=(\d+) width=\d+\) \(actual time=[0-9.]+\.\.[0-9.]+ rows=(\d+)")
SCAN = re.compile(r"((?:Parallel )?(?:Seq Scan|Index Only Scan|Index Scan|Bitmap Heap Scan|Bitmap Index Scan)(?: Backward)? (?:using \S+ )?on \w+)")

TITLES = {
    "ix1_user_order_history": "IX1 store_order(user_id, created_at DESC) — kullanıcı sipariş geçmişi",
    "ix2_period_completed_sales": "IX2 store_order(completed_at, order_id) WHERE COMPLETED — 1 haftalık satış",
    "ix2r_period_sales_rpc11": "IX2 (ek deney, random_page_cost=1.1) — 1 haftalık satış",
    "ix3_product_sales": "IX3 order_item(product_id, order_id) — tek ürün satışları",
    "ix4_user_playtime": "IX4 play_session(library_id, started_at) — kullanıcı oynama süreleri",
    "ix4b_q4_low_playtime": "IX4 (ikinci iş yükü) — Q4 tüm sahiplikler",
    "ix5_publisher_catalog": "IX5 product(publisher_id, release_date) — publisher kataloğu",
    "ix5b_q1_monthly_publishers": "IX5 (ikinci iş yükü) — Q1 aylık publisher geliri",
}


def parse(path: str) -> dict:
    text = open(path, encoding="utf-8").read()
    runs = text.split("## koşu ")[1:]
    exec_ms, plan_ms, hits, reads = [], [], [], []
    first_plan = runs[0] if runs else ""
    for r in runs:
        m = EXP.search(r)
        if m:
            exec_ms.append(float(m.group(1)))
        m = PLAN.search(r)
        if m:
            plan_ms.append(float(m.group(1)))
        m = BUF.search(r)
        if m:
            hits.append(int(m.group(1) or 0))
            reads.append(int(m.group(2) or 0))
    top = TOPROWS.search(first_plan)
    scans = sorted(set(SCAN.findall(first_plan)))
    return {
        "runs": len(exec_ms),
        "exec_ms": exec_ms,
        "exec_median": statistics.median(exec_ms) if exec_ms else None,
        "exec_min": min(exec_ms) if exec_ms else None,
        "exec_max": max(exec_ms) if exec_ms else None,
        "plan_median": statistics.median(plan_ms) if plan_ms else None,
        "top_cost": float(top.group(1)) if top else None,
        "top_est_rows": int(top.group(2)) if top else None,
        "top_actual_rows": int(top.group(3)) if top else None,
        "shared_hit": hits[0] if hits else None,
        "shared_read": reads[0] if reads else None,
        "scans": scans,
    }


def fmt(v, nd=3):
    return "—" if v is None else (f"{v:.{nd}f}" if isinstance(v, float) else str(v))


def main() -> int:
    names = sorted({os.path.basename(p).rsplit("_", 1)[0] for p in glob.glob(os.path.join(RES, "*_without.txt"))})
    if not names:
        print("Sonuç dosyası yok; önce perf/run_index_experiments.sh çalıştırın.")
        return 1
    summary = {}
    lines = ["# İndeks deneyleri — özet (otomatik üretildi: perf/summarize.py)", "",
             "Değerler `perf/results/*_{without,with}.txt` içindeki gerçek `EXPLAIN (ANALYZE, BUFFERS)` çıktılarından "
             "ayrıştırılmıştır. Süreler ısınma koşusundan sonraki koşuların ortanca/min/max değeridir (sıcak önbellek).", ""]
    params = os.path.join(RES, "params.txt")
    if os.path.exists(params):
        lines += ["Parametreler:", "```", open(params, encoding="utf-8").read().strip(), "```", ""]
    lines += ["| Deney | İndekssiz ortanca (min–max) ms | İndeksli ortanca (min–max) ms | Oran | Plan maliyeti (önce → sonra) | "
              "Tahmini/gerçek satır (kök) | shared hit/read (önce → sonra) | Tarama türleri (önce → sonra) |",
              "|---|---|---|---|---|---|---|---|"]
    for n in names:
        wo = parse(os.path.join(RES, f"{n}_without.txt"))
        wi = parse(os.path.join(RES, f"{n}_with.txt"))
        ratio = (wo["exec_median"] / wi["exec_median"]) if wo["exec_median"] and wi["exec_median"] else None
        summary[n] = {"without": wo, "with": wi, "speedup": ratio}
        lines.append(
            f"| {TITLES.get(n, n)} | {fmt(wo['exec_median'])} ({fmt(wo['exec_min'])}–{fmt(wo['exec_max'])}) "
            f"| {fmt(wi['exec_median'])} ({fmt(wi['exec_min'])}–{fmt(wi['exec_max'])}) | {fmt(ratio, 1)}× "
            f"| {fmt(wo['top_cost'], 1)} → {fmt(wi['top_cost'], 1)} "
            f"| {fmt(wo['top_est_rows'])}/{fmt(wo['top_actual_rows'])} → {fmt(wi['top_est_rows'])}/{fmt(wi['top_actual_rows'])} "
            f"| {fmt(wo['shared_hit'])}/{fmt(wo['shared_read'])} → {fmt(wi['shared_hit'])}/{fmt(wi['shared_read'])} "
            f"| {'; '.join(wo['scans']) or '—'} → {'; '.join(wi['scans']) or '—'} |")
    lines += ["", "Oran = indekssiz ortanca / indeksli ortanca (1'den büyükse indeks hızlandırmıştır; "
              "≈1 ise planlayıcı indeksi kullanmamış veya fark ölçülemeyecek kadar küçüktür)."]
    with open(os.path.join(RES, "OZET.md"), "w", encoding="utf-8") as fh:
        fh.write("\n".join(lines) + "\n")
    with open(os.path.join(RES, "ozet.json"), "w", encoding="utf-8") as fh:
        json.dump(summary, fh, ensure_ascii=False, indent=2)
    print("\n".join(lines))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
