# PROGRESS — 鎏书 LumenBook

> 收尾必写。给下一个会话的 agent 看：做到哪了、怎么跑、哪块是坑。
> 最近更新：2026-11-17 22:44（阶段 2 书库完成，等真机验收）

## 一句话状态

阶段 0（工具链 + 可构建骨架）已完成并在真机跑通；**阶段 2（书库 + 导入 + 元数据 + 主题/深色）代码已完成、APK 已出包**，等用户在手机上验收。
阅读内核（阶段 3）还没接，点书只弹「阅读内核还没接」。

## 阶段表

| 阶段 | 内容 | 状态 |
|---|---|---|
| 0 | 工具链、可构建 Compose 骨架、hello APK、git/GitHub | ✅ 完成，真机验证通过 |
| 1 | HTML 原型（书库/阅读页/设置/操作面板）→ `webproto/index.html` | ✅ 完成，截图在 `dist/proto-*.png` |
| 2 | 书库：导入、封面、元数据抠取、重命名、删除、统计、设置、深色主题 | ✅ 代码完成，APK 0.2.0 已出包 |
| 3 | 阅读内核：WebView + foliate-js 承载 EPUB/FB2/CBZ，TXT 转派生 EPUB | ⬜ 未开始 |
| 4 | PDF 阅读（PdfRenderer） | ⬜ |
| 5 | MOBI/AZW3 | ⬜ |
| 6 | 元数据写回原文件（APP_ONLY / WRITE_BACK 双模式） | ⬜ |
| 7 | GitHub Releases 应用内更新 | ⬜ |
| 8 | 打磨（进度同步、书签、字号行距、背景图） | ⬜ |

## 怎么跑

```powershell
python scripts\build_apk.py            # 出 debug 包到 dist\
python scripts\bump_version.py --set 0.2.0
python scripts\vendor_foliate.py       # 把 foliate-js 放进 assets（阶段 3 用）
```

- 工具链全在 `.tooling/`（gitignore，不进仓库）。`build_apk.py` 会设好 `JAVA_HOME` / `ANDROID_HOME` / `GRADLE_USER_HOME` / `TMP`，**不要裸跑 gradle**。
- 仓库：https://github.com/Yan-Congliu/lumenbook （public）。remote 用 https + token。
- **推 GitHub 首选 `git push`；连不上时用 `node scripts/gh_push.mjs`**（走 api.github.com + Git Data API）。
  这台机器上 github.com:443 经常被 reset（`Recv failure` / `Empty reply`），而 api.github.com 一直通。
  API 建出来的提交时间会被规范成 UTC，sha 与本地不同，脚本用 `.git/lumenbook-sync.json` 记 `远端sha -> 本地sha` 映射；
  因此 `git status` 可能一直显示 ahead 1，属正常——以远端页面和脚本输出为准。

## 阶段 2 做了什么（代码地图）

```
app/src/main/java/com/lumenbook/reader/
├── MainActivity.kt            外观 collect → LumenTheme → AppRoot
├── LumenApp.kt                Application，持有 AppContainer
├── data/
│   ├── Book.kt                Book 实体、BookFormat、Appearance
│   ├── LibraryDb.kt           SQLiteOpenHelper（单表 books，刻意不用 Room）
│   ├── LibraryRepository.kt   StateFlow 书列表、import/rename/remove/markOpened
│   ├── Prefs.kt               DataStore（外观）
│   └── AppContainer.kt        手写 DI
├── importer/BookImporter.kt   格式探测、EPUB/FB2/TXT 元数据抠取、编码探测
└── ui/
    ├── AppRoot.kt             底部三标签 + SAF 多选导入 + Toast
    ├── theme/LumenColors.kt   浅色米纸/深色墨纸两套色板 + 封面 12 色散列
    ├── theme/Background.kt    LumenBackground（Paper/Solid/Image）← 未来换背景的接口
    ├── theme/Theme.kt         LumenTheme、Typographic
    ├── library/BookCover.kt   真封面解码，没有就用书名散列渐变
    ├── library/LibraryScreen.kt  3 列网格、分段筛选、长按操作面板、重命名对话框
    ├── settings/SettingsScreen.kt 外观三选、背景（预留）、关于
    └── stats/StatsScreen.kt   藏书/在读/读完 + 格式分布
```

数据目录：文件副本 `filesDir/library/<uuid>.<ext>`，封面 `filesDir/covers/`，库 `filesDir/../databases/lumenbook.db`。

## 关键决定（为什么这么做）

- **不用 Room**：AGP 9 内置 Kotlin 2.2.10 后 KSP 版本匹配是额外风险，单表用 SQLiteOpenHelper 够用。
- **导入即复制 + 记住 SAF Uri**：复制保证文件一直在，Uri 留给阶段 6 写回原文件。
- **改书名只改库里的记录**，对话框里明说"不动原文件"；写回是阶段 6 的独立功能（要二次确认）。
- **主题不跟随壁纸取色**：Apple Books 的观感来自固定纸色，动态取色会破坏它。
- **背景留了三态接口**（Paper/Solid/Image），启动时固定 Paper；设置页「自定义背景图」置灰占位。

## 版本锁定（实测可用，2026-11-17）

| 组件 | 版本 |
|---|---|
| JDK | Temurin 21.0.13 |
| Gradle | 9.9.1 |
| AGP | 9.5.1 |
| 内置 Kotlin / compose 插件 | 2.2.10（必须一致） |
| compileSdk / targetSdk / minSdk | 37 / 37 / 26 |
| Compose BOM | 2026.09.00 |
| core-ktx / activity-compose / datastore | 1.19.1 / 1.13.0 / 1.3.0 |

**AGP 8.x 彻底不可用**（AndroidX 1.19 硬要求 AGP ≥ 9.1 + compileSdk 37）。
**AGP 9 内置 Kotlin**，不能再加 `org.jetbrains.kotlin.android` 插件，否则报 "no longer required"。

## 坑 / 教训

- PowerShell、`Invoke-WebRequest`、`curl.exe` 在这台机器上 TLS 必挂 → 一切下载用 Python。
- Windows 控制台是 GBK：Python 脚本开头必须 `sys.stdout.reconfigure(encoding="utf-8")`，否则打印 ✓ 直接 UnicodeEncodeError。
  已修：`build_apk.py`、`bump_version.py`（先改文件再打印，所以崩了但版本号已经改了）。
- Gradle 编译产物会写到 `C:\Users\asus\AppData\Local\Temp`，沙箱里 AccessDenied →
  `systemProp.java.io.tmpdir` 指到 `.tooling/tmp`，并设 `TMP`/`TEMP` 环境变量（已在 `build_apk.py` 里）。
- Edge headless 截图窗口最小宽度约 500px，用 `--window-size=520,1040`（430 会右侧被裁）。
- 本机 **没有连接安卓设备**（`adb devices` 为空），所以 UI 只能靠编译 + 原型截图自检，真机验证必须找用户。
- Python 的 urllib 在这台机器上发 POST 会 `SSL: UNEXPECTED_EOF_WHILE_READING`（GET 正常）；Node 的 fetch 正常。
  所以 `gh_push.mjs` 用 Node 写，不用 Python。

## 下一步（阶段 3）

1. `python scripts/vendor_foliate.py` 拉 foliate-js 到 `assets/reader/vendor/`。
2. 写 `assets/reader/index.html` + `ReaderBridge.kt`（WebView、`addJavascriptInterface`、消息通道）。
3. TXT：`scripts/txt_split_proto.py` 先做编码探测 + 拆章，产出测试向量，再在 Kotlin 里对齐。
4. 书卡点击 → 打开阅读页；进度写回 `books.progress`。
