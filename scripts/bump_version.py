#!/usr/bin/env python3
"""版本号自增（改 app/build.gradle.kts 的 versionCode / versionName）。

用法：
  python scripts/bump_version.py            # 补丁号 +1，versionCode +1
  python scripts/bump_version.py --set 1.2.0
  python scripts/bump_version.py --dry-run
"""
from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

sys.stdout.reconfigure(encoding="utf-8", errors="replace")
sys.stderr.reconfigure(encoding="utf-8", errors="replace")

GRADLE = Path(__file__).resolve().parent.parent / "app" / "build.gradle.kts"


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--set", dest="target", help="指定新版本号，如 1.2.0")
    ap.add_argument("--dry-run", action="store_true")
    a = ap.parse_args()

    text = GRADLE.read_text(encoding="utf-8")
    m_code = re.search(r"versionCode\s*=\s*(\d+)", text)
    m_name = re.search(r'versionName\s*=\s*"([^"]+)"', text)
    if not (m_code and m_name):
        print("✗ build.gradle.kts 里找不到 versionCode / versionName")
        return 1

    code, name = int(m_code.group(1)), m_name.group(1)
    if a.target:
        parts = a.target.split(".")
        if len(parts) != 3 or not all(p.isdigit() for p in parts):
            print(f"✗ 版本号格式应为 x.y.z：{a.target}")
            return 1
        new_name = a.target
    else:
        parts = name.split("-")[0].split(".")
        parts[-1] = str(int(parts[-1]) + 1)
        new_name = ".".join(parts)
    new_code = code + 1

    print(f"versionCode {code} → {new_code}")
    print(f"versionName {name} → {new_name}")
    if a.dry_run:
        return 0

    text = text.replace(m_code.group(0), f"versionCode = {new_code}")
    text = text.replace(m_name.group(0), f'versionName = "{new_name}"')
    GRADLE.write_text(text, encoding="utf-8")
    print("✓ 已写入 app/build.gradle.kts")
    return 0


if __name__ == "__main__":
    sys.exit(main())
