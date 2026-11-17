# AGENTS.md — 鎏书 LumenBook

给在这个仓库里干活的 agent 的规则。开工先读 `PROGRESS.md`，收尾把进展写回去。

## 这是什么

安卓电子书阅读器，Apple Books 的观感 + 安卓的交互逻辑。纯离线；联网只用于「检查 GitHub 更新 + 下载新 APK」。
技术栈：Kotlin + Jetpack Compose（App 本体）、foliate-js（EPUB/MOBI/AZW3/FB2/CBZ 排版引擎，跑在 WebView 里）、
`android.graphics.pdf.PdfRenderer`（PDF）、Python（`scripts/` 里的构建/发布/预处理工具）。

## 版本锁定（别乱升，升级前先读这段）

| 组件 | 版本 | 备注 |
|---|---|---|
| JDK | Temurin 21 | `.tooling/jdk-21` |
| Gradle | 9.9.1 | `.tooling/gradle/gradle-9.9.1` |
| AGP | 9.5.1 | 8.13.2 是退路（配 Gradle 8.14.3） |
| Kotlin / compose plugin | 2.4.21 | 两者必须同版本 |
| Compose BOM | 2026.09.00 | |
| compileSdk / targetSdk / minSdk | 36 / 36 / 26 | |

升级任一版本前，先跑一次 `python scripts/build_apk.py` 确认能出包；升完再跑一次。

## 环境事实（本机，2026-11）

- 工作区即仓库根：`C:\Users\asus\Documents\deepseek-harness\DSH-creation`。工具链全在 `.tooling/`（gitignore）。
- **PowerShell / `Invoke-WebRequest` / `curl.exe` 的 TLS 在这个机器上会失败**；下载一律用 Python 或 Node。
- `GRADLE_USER_HOME` 与 `ANDROID_USER_HOME` 必须指到 `.tooling/` 下（默认的 `~/.gradle`、`~/.android` 在沙箱里写不进去）。
- 构建统一走 `python scripts/build_apk.py`，它会设好上述环境变量。不要直接裸跑 `gradle`。

## 代码约定

- 包名 `com.lumenbook.reader`；单模块 `:app`。
- 依赖注入用手写的 `AppContainer`，不引 Hilt。
- 界面：Compose + Material 3，固定米色主题（不跟随壁纸动态取色，这是 Books 的风格）；中文排版用宋体类字重。
- 文件读写一律走 `filesDir/library/` 私有副本 + 记住原 SAF Uri；写回原文件前必须备份到 `filesDir/backup/`。
- 元数据改写默认 `APP_ONLY`；`WRITE_BACK` 必须二次确认。EPUB 重打包必须保证 `mimetype` 是第一条且 STORED。
- 新增格式化逻辑（编码探测、拆章、重打包）时，先在 `scripts/txt_split_proto.py` 这类 Python 参考实现里跑通并产出测试向量，
  再在 Kotlin 里对齐实现，两边用同一批向量做单测。

## 发布

- 只有用户明确要求才发 Release。用 `python scripts/release_gh.py`（走 GitHub API，不依赖 git push）。
- Release notes 里必须带上：本版改了什么、装不上怎么办、上次的数据会不会丢。
- APK 命名：`lumenbook-<versionName>-arm64-v8a.apk`（应用内更新按这个模式匹配 asset）。

## 不许做的事

- 不把 `.tooling/`、`keystore/`、`local.properties` 提交进仓库。
- 不在 App 里加除 `INTERNET`、`REQUEST_INSTALL_PACKAGES` 以外的权限，除非用户明确同意（SAF 不需要存储权限）。
- 不引入收集用户行为的 SDK / 打点 / 崩溃上报。
