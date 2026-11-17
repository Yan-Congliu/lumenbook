# 鎏书 LumenBook · PROGRESS

> 台账：每次开工读这里，收尾把「已完成 / 下一步 / 坑」写回来。

## 现在到哪了

**阶段 0 完成，阶段 1 待办** — 工具链装好，`assembleDebug` 通，第一个 hello-world APK 出包（`dist/lumenbook-0.1.0-debug-*.apk`）。

## 阶段表

| 阶段 | 内容 | 状态 |
|---|---|---|
| 0 | 工具链 + hello-world APK + 仓库骨架 | ✅ 完成 |
| 1 | HTML 书库/阅读页原型（Books 观感）+ Python 拆章原型 | 原型已完成，拆章原型待做 |
| 2 | Compose 书库、Room、SAF 导入、封面提取、重命名/删除 | 待办 |
| 3 | WebView + foliate-js 阅读内核（EPUB/TXT） | 待办 |
| 4 | PDF（PdfRenderer 分页、缩放、夜间反色） | 待办 |
| 5 | MOBI/AZW3/FB2/CBZ + 格式探测 | 待办 |
| 6 | 元数据编辑与写回（EPUB/FB2/PDF） | 待办 |
| 7 | GitHub 应用内更新 + 首个 Release | 待办 |
| 8 | 打磨（书签/空态/图标/启动图/崩溃兜底/docs） | 待办 |

## 可用版本组合（2026-11-17 实测出包）

| 组件 | 版本 | 备注 |
|---|---|---|
| JDK | Temurin 21.0.13 | `.tooling/jdk-21` |
| Gradle | 9.9.1 | `.tooling/gradle/gradle-9.9.1` |
| AGP | 9.5.1 | AGP 9 内置 Kotlin，**不要**再加 `org.jetbrains.kotlin.android` |
| 内置 Kotlin | 2.2.10 | AGP 9.5.1 的 pom 里就是 `kotlin-gradle-plugin 2.2.10` |
| Compose 编译器插件 | 2.2.10 | 必须等于内置 Kotlin 版本，否则报版本不匹配 |
| compileSdk / targetSdk / minSdk | 37 / 37 / 26 | `platforms/android-37.0`（目录就叫 37.0） |
| buildTools | 37.0.0 | |
| Compose BOM | 2026.09.00 | |
| core-ktx / activity-compose | 1.19.1 / 1.13.0 | 这两个都要求 AGP ≥ 9.1 且 compileSdk ≥ 37 |

## 阶段 1 状态

- `webproto/index.html` 已可看：书库（12 本书网格 + 分段控件 + 底部三标签）、阅读页（衬线正文、上下浮动工具条、页码进度）、
  Aa 排版抽屉（字号 5 档 + 主题 3 种 + 行距 3 档）、长按菜单（打开/更改信息/移到电脑/移除/删除）。
- 预览开关：`?shot=1` 去掉手机外框；`?view=reader|settings|actions` 直接进对应状态。
- 截图（520×1040，Edge headless）：`dist/proto-library.png`、`proto-reader.png`、`proto-settings.png`、`proto-actions.png`。
- 待做：TXT 拆章 Python 原型 + 测试向量（`scripts/txt_split_proto.py`）。

## 坑 / 教训

- 本机 `curl.exe`、`Invoke-WebRequest` 的 TLS 握手必失败（Schannel），下载必须用 Python/Node。
- 沙箱只允许写工作区 → `GRADLE_USER_HOME`、`ANDROID_USER_HOME` 都要重定向进 `.tooling/`，否则 Gradle 会在 `~/.gradle` 上撞墙。
- AGP 9 的 `generateDebugGlobalSynthetics` 会往系统 Temp 写中间文件并失败（`AccessDeniedException ...\Temp\global_synthetics_intermediate...`）；
  已由 `build_apk.py` 自动往 `.tooling/gradle-home/gradle.properties` 写 `systemProp.java.io.tmpdir=<工作区>/.tooling/tmp` 修掉。
- AGP 8.13.2 + Gradle 8.14.3 这套退路**已经不可用**：2026 年的 AndroidX（core-ktx 1.19.1、compose runtime 1.12.x）硬要求 AGP ≥ 9.1 + compileSdk 37。
- Windows 控制台是 GBK，Python 脚本里打 `✓`/`✗` 会 `UnicodeEncodeError`，脚本开头要 `reconfigure(encoding="utf-8")`。
- Edge headless 窗口最小宽度约 500px，`--window-size=430,...` 会被裁右边，截图用 520 宽。
