#!/usr/bin/env python3
"""Surefire XML raporlarından (gerçek test koşusu) Markdown test tablosu üretir.
Açıklamalar test kaynak kodundaki @DisplayName değerlerinden alınır. Sayı elle girilmez.

Kullanım: python3 scripts/test_report.py [surefire_dizini] > docs/kanit/backend_testleri.md
"""
from __future__ import annotations

import glob
import os
import re
import sys
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "backend/src/test/java/tr/edu/etu/oyunport")
DISPLAY = re.compile(r'@DisplayName\("((?:[^"\\]|\\.)*)"\)\s*(?:@\w+(?:\([^)]*\))?\s*)*void\s+(\w+)\s*\(', re.S)


def display_names(cls: str) -> dict[str, str]:
    path = os.path.join(SRC, cls + ".java")
    if not os.path.exists(path):
        return {}
    return {m.group(2): m.group(1).replace('\\"', '"') for m in DISPLAY.finditer(open(path, encoding="utf-8").read())}


def main() -> int:
    d = sys.argv[1] if len(sys.argv) > 1 else os.path.join(ROOT, "backend/target/surefire-reports")
    files = sorted(glob.glob(os.path.join(d, "TEST-*.xml")))
    if not files:
        print("Surefire XML bulunamadı: " + d, file=sys.stderr)
        return 1
    total = fails = errors = skipped = 0
    out = []
    for f in files:
        suite = ET.parse(f).getroot()
        cls = suite.get("name").rsplit(".", 1)[-1]
        names = display_names(cls)
        t, fl, er, sk = (int(suite.get(k, 0)) for k in ("tests", "failures", "errors", "skipped"))
        total, fails, errors, skipped = total + t, fails + fl, errors + er, skipped + sk
        out.append(f"\n### {cls} — {t} test, {fl} başarısız, {er} hata, {sk} atlandı ({float(suite.get('time', 0)):.2f} s)\n")
        out.append("| Test | Açıklama | Sonuç | Süre (s) |\n|---|---|---|---|")
        for tc in suite.findall("testcase"):
            name = tc.get("name")
            status = "BAŞARILI"
            if tc.find("failure") is not None:
                status = "BAŞARISIZ"
            elif tc.find("error") is not None:
                status = "HATA"
            elif tc.find("skipped") is not None:
                status = "ATLANDI"
            out.append(f"| `{name}` | {names.get(name, '')} | {status} | {float(tc.get('time', 0)):.3f} |")
    head = [
        "# Backend entegrasyon testleri — ayrıntılı sonuç",
        "",
        "Otomatik üretildi: `python3 scripts/test_report.py docs/kanit/surefire` (Surefire XML raporlarından).",
        f"Kaynak XML: `docs/kanit/surefire/`. Toplam: **{total} test, {fails} başarısız, {errors} hata, {skipped} atlandı**.",
    ]
    print("\n".join(head + out))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
