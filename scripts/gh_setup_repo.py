#!/usr/bin/env python
"""在 GitHub 上建仓库（已存在则跳过），打印 ssh/https 地址。token 从环境变量 GH_TOKEN 读，不入盘。"""

from __future__ import annotations

import json
import os
import sys
import urllib.error
import urllib.request

for _s in (sys.stdout, sys.stderr):
    try:
        _s.reconfigure(encoding="utf-8", errors="replace")  # type: ignore[attr-defined]
    except Exception:  # noqa: BLE001
        pass

API = "https://api.github.com"
TOKEN = os.environ.get("GH_TOKEN", "").strip()


def call(method: str, path: str, payload: dict | None = None) -> tuple[int, dict]:
    data = json.dumps(payload).encode() if payload is not None else None
    req = urllib.request.Request(
        API + path,
        data=data,
        method=method,
        headers={
            "Authorization": f"Bearer {TOKEN}",
            "Accept": "application/vnd.github+json",
            "User-Agent": "lumenbook-setup",
            "Content-Type": "application/json",
        },
    )
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return r.status, json.loads(r.read().decode() or "{}")
    except urllib.error.HTTPError as e:
        body = e.read().decode("utf-8", "replace")
        try:
            return e.code, json.loads(body)
        except json.JSONDecodeError:
            return e.code, {"message": body[:200]}


def main() -> int:
    if not TOKEN:
        print("GH_TOKEN 为空", file=sys.stderr)
        return 2

    status, me = call("GET", "/user")
    if status != 200:
        print(f"取用户失败 {status}: {me.get('message')}", file=sys.stderr)
        return 3
    login = me["login"]
    print(f"账号：{login}")

    name = sys.argv[1] if len(sys.argv) > 1 else "lumenbook"
    desc = sys.argv[2] if len(sys.argv) > 2 else "鎏书 LumenBook — 安卓电子书阅读器"

    status, repo = call("GET", f"/repos/{login}/{name}")
    if status == 200:
        print(f"仓库已存在：{repo['html_url']}（private={repo['private']}）")
    else:
        status, repo = call("POST", "/user/repos", {
            "name": name,
            "description": desc,
            "private": False,
            "has_issues": True,
            "has_wiki": False,
            "auto_init": False,
        })
        if status not in (200, 201):
            print(f"建仓库失败 {status}: {repo.get('message')}", file=sys.stderr)
            return 4
        print(f"已建仓库：{repo['html_url']}")

    print(f"CLONE_URL={repo['clone_url']}")
    print(f"PUSH_URL=https://github.com/{login}/{name}.git")
    return 0


if __name__ == "__main__":
    sys.exit(main())
