#!/usr/bin/env python3
"""生成发布签名用的 keystore（只需跑一次）。

    python scripts/make_keystore.py

产物（都在 `keystore/`，已被 .gitignore 忽略）：
    lumenbook.jks          签名库
    keystore.properties    Gradle 读的参数（含密码，务必备份，丢了就没法再更新已装的应用）

已存在时不会覆盖；确实要换新签名加 --force（那会导致已装的应用收不到更新）。
"""
from __future__ import annotations

import secrets
import string
import subprocess
import sys
from pathlib import Path

for _s in (sys.stdout, sys.stderr):
    try:
        _s.reconfigure(encoding="utf-8", errors="replace")  # type: ignore[attr-defined]
    except Exception:  # noqa: BLE001
        pass

WS = Path(__file__).resolve().parent.parent
KEYTOOL = WS / ".tooling" / "jdk-21" / "bin" / "keytool.exe"
STORE = WS / "keystore" / "lumenbook.jks"
PROPS = WS / "keystore" / "keystore.properties"
ALIAS = "lumenbook"

DNAME = "CN=LumenBook, OU=LumenBook, O=LumenBook, L=Xi'an, ST=Shaanxi, C=CN"


def password(n: int = 24) -> str:
    alphabet = string.ascii_letters + string.digits
    return "".join(secrets.choice(alphabet) for _ in range(n))


def main() -> int:
    force = "--force" in sys.argv
    if not KEYTOOL.exists():
        print(f"✗ 找不到 keytool：{KEYTOOL}（先跑 python scripts/install_toolchain.py）")
        return 1
    if STORE.exists() and not force:
        print(f"✓ 签名库已存在，不覆盖：{STORE}")
        print(f"  参数文件：{PROPS}")
        return 0

    pw = password()
    STORE.parent.mkdir(parents=True, exist_ok=True)
    cmd = [
        str(KEYTOOL), "-genkeypair", "-noprompt",
        "-keystore", str(STORE),
        "-storetype", "PKCS12",
        "-alias", ALIAS,
        "-keyalg", "RSA",
        "-keysize", "2048",
        "-validity", "10950",
        "-storepass", pw,
        "-keypass", pw,
        "-dname", DNAME,
    ]
    r = subprocess.run(cmd, capture_output=True, text=True)
    if r.returncode != 0:
        print("✗ keytool 失败：")
        print(r.stdout[-2000:])
        print(r.stderr[-2000:])
        return r.returncode

    PROPS.write_text(
        f"storeFile=keystore/lumenbook.jks\n"
        f"storePassword={pw}\n"
        f"keyAlias={ALIAS}\n"
        f"keyPassword={pw}\n",
        encoding="utf-8",
    )
    print(f"✓ 已生成签名库：{STORE}")
    print(f"✓ 已写入参数：{PROPS}")
    print("  把整个 keystore/ 目录备份到别处——换机或丢了它，已装的应用就再也收不到应用内更新。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
