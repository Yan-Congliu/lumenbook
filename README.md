# 鎏书 LumenBook

一个安卓电子书阅读器：**Apple Books 的观感 + 安卓的交互逻辑**。纯本地、不联网（联网只有一件事：检查 GitHub 上的新版本）。

- 支持 **EPUB / PDF / TXT / MOBI / AZW3 / FB2 / CBZ**
- 书库：三列封面墙、最近阅读 / 全部 / 已读完、搜索、排序（最近 / 书名 / 作者 / 加入时间）
- 阅读：翻页动画、字号行距边距可调、深色「墨纸」主题、书签、位置自动记住
- 书籍信息：改书名、作者、封面；可以选择**只改应用里**或**同时写回原文件**（写回前自动备份）
- 设置里可以挑一张自己的图当书库底色

## 安装

去 [Releases](https://github.com/Yan-Congliu/lumenbook/releases/latest) 下载 `lumenbook-<版本>-arm64-v8a.apk`。
这是正式签名包（包名 `com.lumenbook.reader`），可以直接覆盖安装旧版本。

> 大多数手机（近几年的高通/联发科机型）都是 arm64，下这一个就行。如果装不上，先在系统里允许「安装未知应用」。

## 应用内更新

设置 → 更新 → 检查更新：会去读本仓库的 latest release，发现新版本可以直接下载 APK 并拉起系统安装。

## 技术栈

| 层 | 选择 |
|---|---|
| 界面 | Kotlin + Jetpack Compose（Material 3），固定米纸配色，不跟随壁纸取色 |
| EPUB/MOBI/AZW3/FB2/CBZ 排版 | [foliate-js](https://github.com/johnfactotum/foliate-js)（MIT）跑在 WebView 里 |
| PDF | `android.graphics.pdf.PdfRenderer` 原生渲染 |
| TXT | 先按章节拆成派生 EPUB，再走同一套排版引擎 |
| 构建/发布 | Python 脚本（`scripts/`）：装工具链、出包、发 Release |

版本锁定：AGP 9.5.1 / Gradle 9.9.1 / 内置 Kotlin 2.2.10 / compileSdk 37 / minSdk 26。升级前先读 `AGENTS.md`。

## 从源码构建

```bash
python scripts/install_toolchain.py     # 首次：装 JDK 21 + Android SDK + Gradle 到 .tooling/
python scripts/build_apk.py             # debug 包 → dist/
python scripts/build_apk.py --release   # 正式签名包（需先 python scripts/make_keystore.py）
python scripts/build_apk.py --release --publish   # 出包并传到 GitHub Release
```

跑单测：

```bash
.tooling/gradle/gradle-9.9.1/bin/gradle.bat --no-daemon :app:testDebugUnitTest
```

（需要 `JAVA_HOME` / `ANDROID_HOME` / `GRADLE_USER_HOME` 指向 `.tooling/` 下的目录，`build_apk.py` 里有一份现成的环境变量。）

## 目录

```
app/src/main/java/com/lumenbook/reader/
  data/        书库 SQLite、Repository、设置、更新检查、APK 安装
  importer/    格式探测、EPUB 元数据/封面、TXT→EPUB、EPUB 写回
  ui/library   书库（封面墙、搜索、排序、编辑书籍信息）
  ui/reader    EPUB 阅读页（WebView + foliate 桥）
  ui/pdf       PDF 阅读页（PdfRenderer + Pager）
  ui/theme     配色、主题、背景图扩展点
app/src/main/assets/reader/   网页阅读器（reader.js 是 Kotlin ↔ 网页的桥）
scripts/      构建、发布、样书生成等 Python/Node 脚本
```

## 许可

代码仅用于个人学习与自用；foliate-js 按 MIT 使用（见 `app/src/main/assets/reader/vendor/foliate/LICENSE`）。
