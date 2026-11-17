#!/usr/bin/env python3
"""构建 LumenBook APK（设好工具链环境变量后调用 Gradle）。

用法：
  python scripts/build_apk.py            # debug APK
  python scripts/build_apk.py --release  # release APK（需要 keystore，见 AGENTS.md）
  python scripts/build_apk.py --clean    # 先 clean

产物：app/build/outputs/apk/<variant>/… 并复制到 dist/。
"""
from __future__ import annotations

import argparse
import os
import re
import shutil
import subprocess
import sys
import time
from pathlib import Path

# Windows 控制台默认 GBK，脚本里有 ✓/✗ 之类的字符，先兜住编码
for _s in (sys.stdout, sys.stderr):
    try:
        _s.reconfigure(encoding="utf-8", errors="replace")  # type: ignore[attr-defined]
    except Exception:  # noqa: BLE001
        pass

WS = Path(__file__).resolve().parent.parent
TOOLING = WS / ".tooling"
JDK_DIR = TOOLING / "jdk-21"
SDK_DIR = TOOLING / "android-sdk"
ANDROID_USER_HOME = TOOLING / "android-home"
GRADLE_USER_HOME = TOOLING / "gradle-home"
DIST = WS / "dist"

# Gradle 版本必须和 AGP 匹配：AGP 9.x → Gradle 9.x；AGP 8.13.x → Gradle 8.14.x
GRADLE_VERSION = "9.9.1"
GRADLE_BIN = TOOLING / "gradle" / f"gradle-{GRADLE_VERSION}" / "bin" / "gradle.bat"


def resolve_gradle() -> Path:
    if GRADLE_BIN.exists():
        return GRADLE_BIN
    found = sorted(TOOLING.glob("gradle/gradle-*/bin/gradle.bat"))
    if found:
        print(f"[build] 用找到的 Gradle：{found[-1]}")
        return found[-1]
    return GRADLE_BIN


def fail(msg: str) -> None:
    print(f"\n[build] ✗ {msg}", flush=True)
    sys.exit(1)


def ensure_env() -> dict:
    missing = []
    if not (JDK_DIR / "bin" / "java.exe").exists():
        missing.append(f"JDK: {JDK_DIR}")
    if not (SDK_DIR / "platforms").exists():
        missing.append(f"Android SDK: {SDK_DIR}")
    if not resolve_gradle().exists():
        missing.append(f"Gradle {GRADLE_VERSION}: {GRADLE_BIN}")
    if missing:
        fail("工具链不全，先跑 `python scripts/install_toolchain.py`：\n  - " + "\n  - ".join(missing))

    local_props = WS / "local.properties"
    sdk_line = f"sdk.dir={SDK_DIR.as_posix()}"
    if not local_props.exists() or local_props.read_text(encoding="utf-8").strip() != sdk_line:
        local_props.write_text(sdk_line + "\n", encoding="utf-8")

    ANDROID_USER_HOME.mkdir(parents=True, exist_ok=True)
    GRADLE_USER_HOME.mkdir(parents=True, exist_ok=True)
    # AGP 9 的 generateDebugGlobalSynthetics 会往系统 Temp 写中间文件，沙箱里会被拒；
    # 把 java.io.tmpdir 指到工作区内（写进 GRADLE_USER_HOME 的 gradle.properties，不入库）
    tmp = TOOLING / "tmp"
    tmp.mkdir(parents=True, exist_ok=True)
    user_props = GRADLE_USER_HOME / "gradle.properties"
    line = f"systemProp.java.io.tmpdir={tmp.as_posix()}"
    old = user_props.read_text(encoding="utf-8") if user_props.exists() else ""
    if line not in old:
        user_props.write_text((old.rstrip() + "\n" + line + "\n").lstrip(), encoding="utf-8")
    env = os.environ.copy()
    env.update({
        "JAVA_HOME": str(JDK_DIR),
        "ANDROID_HOME": str(SDK_DIR),
        "ANDROID_SDK_ROOT": str(SDK_DIR),
        "ANDROID_USER_HOME": str(ANDROID_USER_HOME),
        "GRADLE_USER_HOME": str(GRADLE_USER_HOME),
        "TMP": str(tmp),
        "TEMP": str(tmp),
        "PATH": f"{JDK_DIR / 'bin'}{os.pathsep}{env.get('PATH', '')}",
    })
    return env


def version_name() -> str:
    txt = (WS / "app" / "build.gradle.kts").read_text(encoding="utf-8")
    m = re.search(r'versionName\s*=\s*"([^"]+)"', txt)
    return m.group(1) if m else "0.0.0"


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--release", action="store_true")
    ap.add_argument("--clean", action="store_true")
    ap.add_argument("--task", default=None, help="直接指定 gradle 任务，如 assembleDebug")
    ap.add_argument("--publish", action="store_true", help="release 出包后顺带发到 GitHub Release")
    ap.add_argument("--notes", default=None, help="配合 --publish：release notes（用 \\n 换行）")
    a = ap.parse_args()

    env = ensure_env()
    variant = "release" if a.release else "debug"
    if a.release and not (WS / "keystore" / "keystore.properties").exists():
        fail("没有发布签名。先跑 `python scripts/make_keystore.py`（只需一次），再回来构建 release。")
    tasks = [a.task] if a.task else [("assembleRelease" if a.release else "assembleDebug")]
    if a.clean:
        tasks.insert(0, "clean")

    cmd = [str(resolve_gradle()), "--no-daemon", "--console=plain", *tasks]
    print(f"[build] {' '.join(tasks)}  (variant={variant})", flush=True)
    t0 = time.time()
    r = subprocess.run(cmd, cwd=WS, env=env)
    if r.returncode != 0:
        fail(f"Gradle 失败，返回码 {r.returncode}")

    out_dir = WS / "app" / "build" / "outputs" / "apk" / variant
    apks = sorted(out_dir.glob("*.apk")) if out_dir.exists() else []
    if not apks:
        fail(f"构建成功但没找到 APK：{out_dir}")
    DIST.mkdir(exist_ok=True)
    stamp = time.strftime("%Y%m%d-%H%M%S")
    ver = version_name()
    results = []
    for apk in apks:
        if variant == "release":
            # 发布包按 AGENTS.md 的命名固定：应用内更新按这个名字匹配 asset
            dest = DIST / f"lumenbook-{ver}-arm64-v8a.apk"
        else:
            dest = DIST / f"lumenbook-{ver}-{variant}-{stamp}.apk"
        shutil.copy2(apk, dest)
        results.append(dest)
    print(f"\n[build] ✓ 耗时 {time.time() - t0:.0f}s")
    for d in results:
        print(f"[build] APK: {d}  ({d.stat().st_size / 2**20:.1f} MB)")
    if variant == "debug":
        print("[build] 注意：debug 包带 .debug 后缀的包名，正式更新用 release 包。")

    if a.publish:
        if variant != "release":
            fail("--publish 只对 release 包有意义（加 --release）")
        apk = results[0]
        notes = a.notes or "见仓库提交记录。"
        cmd = [
            "node", str(WS / "scripts" / "release_gh.mjs"),
            "--version", ver,
            "--apk", str(apk),
            "--notes", notes.replace("\n", "\\n"),
        ]
        print(f"\n[build] 发 Release v{ver} …", flush=True)
        if subprocess.run(cmd, cwd=WS).returncode != 0:
            fail("发 Release 失败（APK 已出，可稍后单独跑 scripts/release_gh.mjs）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
