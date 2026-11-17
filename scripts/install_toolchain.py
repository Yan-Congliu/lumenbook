#!/usr/bin/env python3
"""LumenBook 工具链安装器（Windows / 纯 Python，不依赖 PowerShell 或 curl）。

为什么不用 Invoke-WebRequest / curl：本机这两者的 TLS 握手会失败，Python 的
OpenSSL 后端与 Node 是通的。

安装内容（全部落在工作区 .tooling/ 下，不进系统目录）：
  .tooling/jdk-21/          Temurin JDK 21
  .tooling/android-sdk/     Android cmdline-tools + platform-tools + platform-36 + build-tools
  .tooling/gradle/          Gradle 发行版
  .tooling/android-home/    ANDROID_USER_HOME（避免写 ~/.android）
  .tooling/gradle-home/     GRADLE_USER_HOME（避免写 ~/.gradle）

用法：
  python scripts/install_toolchain.py            # 全装（幂等，已装则跳过）
  python scripts/install_toolchain.py --check    # 只报告状态
  python scripts/install_toolchain.py --step jdk # 单独装某一步：jdk|cmdline|sdk|gradle
"""
from __future__ import annotations

import argparse
import os
import re
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.request
import zipfile
from pathlib import Path

WS = Path(__file__).resolve().parent.parent
TOOLING = WS / ".tooling"
JDK_DIR = TOOLING / "jdk-21"
SDK_DIR = TOOLING / "android-sdk"
GRADLE_DIR = TOOLING / "gradle"
ANDROID_USER_HOME = TOOLING / "android-home"
GRADLE_USER_HOME = TOOLING / "gradle-home"
DL = TOOLING / "downloads"

JDK_URL = ("https://api.adoptium.net/v3/binary/latest/21/ga/windows/x64/jdk/"
           "hotspot/normal/eclipse")
CMDLINE_VERSION = "13114758"
CMDLINE_URLS = [
    f"https://mirrors.cloud.tencent.com/AndroidSDK/commandlinetools-win-{CMDLINE_VERSION}_latest.zip",
    f"https://dl.google.com/android/repository/commandlinetools-win-{CMDLINE_VERSION}_latest.zip",
]
GRADLE_VERSION = "9.9.1"
GRADLE_URLS = [
    f"https://mirrors.cloud.tencent.com/gradle/gradle-{GRADLE_VERSION}-bin.zip",
    f"https://services.gradle.org/distributions/gradle-{GRADLE_VERSION}-bin.zip",
]
SDK_PACKAGES = ["platform-tools", "platforms;android-36", "build-tools;36.0.0"]
UA = {"User-Agent": "lumenbook-bootstrap/1.0"}


def log(msg: str) -> None:
    print(msg, flush=True)


def download(url: str, dest: Path, tries: int = 3) -> Path:
    """带进度与重试的下载；已存在且大小匹配则跳过。"""
    dest.parent.mkdir(parents=True, exist_ok=True)
    for attempt in range(1, tries + 1):
        try:
            req = urllib.request.Request(url, headers=UA)
            with urllib.request.urlopen(req, timeout=60) as r:
                total = int(r.headers.get("Content-Length") or 0)
                if dest.exists() and total and dest.stat().st_size == total:
                    log(f"  已存在，跳过：{dest.name}")
                    return dest
                tmp = dest.with_suffix(dest.suffix + ".part")
                got = 0
                last = -10
                with open(tmp, "wb") as f:
                    while True:
                        chunk = r.read(1 << 20)
                        if not chunk:
                            break
                        f.write(chunk)
                        got += len(chunk)
                        if total:
                            pct = got * 100 // total
                            if pct >= last + 10:
                                last = pct
                                log(f"  {dest.name}: {pct}% ({got // 1048576}/{total // 1048576} MB)")
                tmp.replace(dest)
                log(f"  完成：{dest.name} ({got // 1048576} MB)")
                return dest
        except (urllib.error.URLError, OSError, TimeoutError) as e:
            log(f"  下载失败（第 {attempt}/{tries} 次）：{type(e).__name__}: {e}")
            if attempt == tries:
                raise
            time.sleep(3 * attempt)
    raise RuntimeError("unreachable")


def download_any(urls: list[str], dest: Path) -> Path:
    """按顺序试多个镜像（国内镜像优先），最后一个失败才抛错。"""
    last: Exception | None = None
    for url in urls:
        try:
            return download(url, dest)
        except (urllib.error.URLError, OSError, TimeoutError) as e:
            last = e
            log(f"  该源失败，换下一个：{url.split('/')[2]}")
    raise RuntimeError(f"全部下载源都失败：{last}")


def unzip(src: Path, dst: Path) -> None:
    dst.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(src) as z:
        z.extractall(dst)


def find_dir(root: Path, pattern: str) -> Path | None:
    for p in sorted(root.iterdir()):
        if p.is_dir() and re.match(pattern, p.name, re.I):
            return p
    return None


def step_jdk() -> Path:
    if (JDK_DIR / "bin" / "java.exe").exists():
        log(f"[jdk] 已安装：{JDK_DIR}")
        return JDK_DIR
    log(f"[jdk] 下载 Temurin JDK 21 …")
    z = download(JDK_URL, DL / "jdk21.zip")
    log("[jdk] 解压 …")
    tmp = TOOLING / "_jdk_tmp"
    if tmp.exists():
        shutil.rmtree(tmp)
    unzip(z, tmp)
    inner = find_dir(tmp, r"jdk.*")
    if inner is None:
        raise RuntimeError(f"JDK 压缩包结构异常：{[p.name for p in tmp.iterdir()]}")
    if JDK_DIR.exists():
        shutil.rmtree(JDK_DIR)
    inner.rename(JDK_DIR)
    shutil.rmtree(tmp)
    log(f"[jdk] 完成：{JDK_DIR}")
    return JDK_DIR


def step_cmdline() -> Path:
    target = SDK_DIR / "cmdline-tools" / "latest"
    if (target / "bin" / "sdkmanager.bat").exists():
        log(f"[cmdline] 已安装：{target}")
        return target
    log("[cmdline] 下载 Android cmdline-tools …")
    z = download_any(CMDLINE_URLS, DL / "cmdline-tools.zip")
    log("[cmdline] 解压 …")
    tmp = TOOLING / "_cmd_tmp"
    if tmp.exists():
        shutil.rmtree(tmp)
    unzip(z, tmp)
    inner = find_dir(tmp, r"cmdline-tools")
    if inner is None:
        raise RuntimeError(f"cmdline-tools 压缩包结构异常：{[p.name for p in tmp.iterdir()]}")
    (SDK_DIR / "cmdline-tools").mkdir(parents=True, exist_ok=True)
    if target.exists():
        shutil.rmtree(target)
    inner.rename(target)
    shutil.rmtree(tmp)
    log(f"[cmdline] 完成：{target}")
    return target


def sdkmanager_env() -> dict:
    env = os.environ.copy()
    env["JAVA_HOME"] = str(JDK_DIR)
    env["ANDROID_HOME"] = str(SDK_DIR)
    env["ANDROID_SDK_ROOT"] = str(SDK_DIR)
    env["ANDROID_USER_HOME"] = str(ANDROID_USER_HOME)
    env["PATH"] = f"{JDK_DIR / 'bin'}{os.pathsep}{env.get('PATH', '')}"
    ANDROID_USER_HOME.mkdir(parents=True, exist_ok=True)
    return env


def step_sdk() -> None:
    cmdline = step_cmdline()
    sm = cmdline / "bin" / "sdkmanager.bat"
    if not sm.exists():
        raise RuntimeError(f"sdkmanager 不存在：{sm}")
    env = sdkmanager_env()
    log("[sdk] 接受许可协议 …")
    subprocess.run([str(sm), f"--sdk_root={SDK_DIR}", "--licenses"],
                   input="y\n" * 60, text=True, env=env, shell=False,
                   stdout=subprocess.DEVNULL, stderr=subprocess.STDOUT, check=False)
    installed = []
    for pkg in SDK_PACKAGES:
        log(f"[sdk] 安装 {pkg} …")
        r = subprocess.run([str(sm), f"--sdk_root={SDK_DIR}", "--install", pkg],
                           input="y\n" * 20, text=True, env=env, shell=False,
                           capture_output=True)
        tail = (r.stdout or "")[-500:]
        if r.returncode != 0:
            log(tail)
            log(f"[sdk] ⚠ {pkg} 返回码 {r.returncode}，继续（可能已安装）")
        else:
            installed.append(pkg)
            log(f"[sdk] ok {pkg}")
    log(f"[sdk] 完成，平台目录：{[p.name for p in (SDK_DIR / 'platforms').glob('*')] if (SDK_DIR / 'platforms').exists() else '无'}")


def step_gradle() -> Path:
    exe = GRADLE_DIR / f"gradle-{GRADLE_VERSION}" / "bin" / "gradle.bat"
    if exe.exists():
        log(f"[gradle] 已安装：{exe}")
        return exe
    log(f"[gradle] 下载 Gradle {GRADLE_VERSION} …")
    z = download_any(GRADLE_URLS, DL / f"gradle-{GRADLE_VERSION}-bin.zip")
    log("[gradle] 解压 …")
    GRADLE_DIR.mkdir(parents=True, exist_ok=True)
    unzip(z, GRADLE_DIR)
    if not exe.exists():
        raise RuntimeError(f"Gradle 解压后找不到 {exe}")
    log(f"[gradle] 完成：{exe}")
    return exe


def check() -> int:
    rows = [
        ("JDK 21", JDK_DIR / "bin" / "java.exe"),
        ("cmdline-tools", SDK_DIR / "cmdline-tools" / "latest" / "bin" / "sdkmanager.bat"),
        ("platform-tools", SDK_DIR / "platform-tools" / "adb.exe"),
        ("platform-36", SDK_DIR / "platforms" / "android-36"),
        ("build-tools", SDK_DIR / "build-tools"),
        ("gradle", GRADLE_DIR / f"gradle-{GRADLE_VERSION}" / "bin" / "gradle.bat"),
    ]
    missing = 0
    for name, p in rows:
        ok = p.exists()
        if not ok:
            missing += 1
        log(f"  {'OK  ' if ok else 'MISS'} {name:16s} {p}")
    log(f"\nWORKSPACE     = {WS}")
    log(f"JAVA_HOME     = {JDK_DIR}")
    log(f"ANDROID_HOME  = {SDK_DIR}")
    log(f"GRADLE_USER_HOME = {GRADLE_USER_HOME}")
    log(f"下载缓存      = {DL}")
    free = shutil.disk_usage(WS).free
    log(f"工作区剩余空间 = {free / 2**30:.1f} GB")
    return 1 if missing else 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--check", action="store_true")
    ap.add_argument("--step", choices=["jdk", "cmdline", "sdk", "gradle"])
    a = ap.parse_args()
    TOOLING.mkdir(parents=True, exist_ok=True)
    DL.mkdir(parents=True, exist_ok=True)
    if a.check:
        return check()
    t0 = time.time()
    if a.step in (None, "jdk"):
        step_jdk()
    if a.step in (None, "cmdline"):
        step_cmdline()
    if a.step in (None, "sdk"):
        step_sdk()
    if a.step in (None, "gradle"):
        step_gradle()
    log(f"\n耗时 {time.time() - t0:.0f}s")
    return check()


if __name__ == "__main__":
    sys.exit(main())
