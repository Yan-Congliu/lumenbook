# PROGRESS — 鎏书 LumenBook

> 收尾必写。给下一个会话的 agent 看：做到哪了、怎么跑、哪块是坑。
> 最近更新：2026-11-18 00:45（阶段 6 元数据写回、阶段 7 应用内更新 + 发布签名，0.6.0 出包）

## 一句话状态

阶段 0/2/3(EPUB) 已在真机跑通：书卡点开 → WebView + foliate-js 分页排版、左右翻页、目录跳转、进度（CFI）落库、深浅两套主题、字号/行距/边距面板。
真机上踩穿了三个坑（JS 接口类必须 public、paginator 的 blob iframe 在安卓不触发 load、书页容器高度算成 0），现已稳定。
阶段 4 PDF 阅读已写完（原生 PdfRenderer + HorizontalPager + 缩放 + 进度记忆），APK 0.4.0 等真机确认；
TXT 转派生 EPUB、MOBI/AZW3 还没验证。

## 阶段表

| 阶段 | 内容 | 状态 |
|---|---|---|
| 0 | 工具链、可构建 Compose 骨架、hello APK、git/GitHub | ✅ 完成，真机验证通过 |
| 1 | HTML 原型（书库/阅读页/设置/操作面板）→ `webproto/index.html` | ✅ 完成，截图在 `dist/proto-*.png` |
| 2 | 书库：导入、封面、元数据抠取、重命名、删除、统计、设置、深色主题 | ✅ 代码完成，APK 0.2.0 已出包 |
| 3 | 阅读内核：WebView + foliate-js 承载 EPUB/FB2/CBZ，TXT 转派生 EPUB | 🟡 EPUB 真机可读（0.3.7）；TXT 已实现（0.4.1，桌面验证通过）；MOBI/AZW3 待验证 |
| 4 | PDF 阅读（PdfRenderer） | 🟡 代码完成，APK 0.4.0 待真机验收 |
| 5 | MOBI/AZW3 | 🟡 自造 MOBI6 样书（带 guide 目录）已跑通：标题、4 节、3 个 toc、跳章都对；AZW3/KF8 与真实书籍仍待验证 |
| 6 | 元数据写回原文件（APP_ONLY / WRITE_BACK 双模式） | 🟡 EPUB 写回已实现 + 12 个单测通过，APK 0.5.0 待真机验收 |
| 7 | GitHub Releases 应用内更新 | 🟡 检查/下载/安装链路 + 6 个单测通过；release 签名已配好（0.6.0） |
| 8 | 打磨（进度同步、书签、字号行距、背景图） | ⬜ |

## 怎么跑

```powershell
python scripts\build_apk.py            # 出 debug 包到 dist\
python scripts\bump_version.py --set 0.3.0
python scripts\vendor_foliate.py       # 重新拉 foliate-js（自动打排版补丁）
python scripts\reader_smoke.py         # 生成桌面冒烟站点（加 --serve 起 8899 端口）
python scripts\make_test_pdf.py        # 生成 4 页测试 PDF
python scripts\txt_split_proto.py dist\sample-book.txt -o dist\txt.epub   # TXT→EPUB 参考实现
```

单测（JVM，不需要真机）：

```powershell
$ws = (Get-Location).Path
$env:JAVA_HOME="$ws\.tooling\jdk-21"; $env:ANDROID_HOME="$ws\.tooling\android-sdk"
$env:GRADLE_USER_HOME="$ws\.tooling\gradle-home"; $env:TMP="$ws\.tooling\tmp"; $env:TEMP=$env:TMP
& "$ws\.tooling\gradle\gradle-9.9.1\bin\gradle.bat" --no-daemon :app:testDebugUnitTest
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

## 阶段 3 做了什么（阅读内核）

```
app/src/main/assets/reader/
├── index.html            WebView 入口：只放一个 <foliate-view>
├── reader.css            --paper/--ink/--lumen-gap/--lumen-margin 这些排版变量
├── reader.js             window.lumen.{open,applyTheme,goToHref,goToFraction,next,prev}
│                         并回桥：Android.onPageLoad / onReady / onRelocate / onError / onToggleChrome
└── vendor/foliate/       foliate-js 1.0.1（MIT），由 scripts/vendor_foliate.py 拉

app/src/main/java/com/lumenbook/reader/ui/reader/ReaderScreen.kt
    WebViewAssetLoader（域名 appassets.androidplatform.net）
      /assets/ → assets/reader/    /book/ → 书库私有副本（同源，免 CORS）
    AndroidView 里挂 WebView；addJavascriptInterface(bridge, "Android")
    顶部栏（返回/书名+章节/目录）、底部进度滑条、目录 ModalBottomSheet
    BookFormat.usesFoliate() 之外的格式走 UnsupportedFormatScreen 占位
```

- 打开流程：JS `bridge('onPageLoad')` → Kotlin 收到 `ui.pageLoaded` → `window.lumen.open(url, cfi, theme)` → JS 里 `makeBook → view.open → view.init({lastLocation}) → applyTheme()` → 回调 `onReady`（目录 + fraction）→ 每页 `onRelocate(cfi, {fraction,label})` → Kotlin 节流 2 秒写库 `books.location`（DB VERSION=2，`ALTER TABLE books ADD COLUMN location TEXT`）。
- 书库点书 → `AppRoot.readingId` → `ReaderScreen`，返回时 `refresh()` 让进度/最近阅读即时更新。

## 阶段 4 做了什么（PDF 阅读）

`ui/pdf/PdfReaderScreen.kt`（新建，约 400 行）：

```
PdfDocument(file)          ParcelFileDescriptor + PdfRenderer，Mutex 串行渲染（PdfRenderer 非线程安全）
  render(index, widthPx)   suspend + Dispatchers.IO → RGB_565 白底 Bitmap，按视口宽度缩放
PdfReaderScreen            BookFormat.PDF 时由 ReaderScreen 直接转进来
  rememberPagerState(initialPage = savedPage(book.location))   // location = "page:N"
  HorizontalPager(beyondViewportPageCount = 1)                  // 预渲染左右各一页
  PdfPage                   单页 Bitmap + detectTransformGestures 双指缩放（1..4 倍）+ detectTapZones
  PdfTopBar / PdfBottomBar  书名 + 「页码 / 总页数」，底部滑条按页跳转
  翻页落库                  snapshotFlow { pagerState.settledPage } → saveLocation("page:N", N/(count-1))
```

- 轻点分区：左 30% 上一页、右 30% 下一页、中间呼出/隐藏工具栏（放大到 >1.01 倍后轻点只收起工具栏，避免和拖动打架）。
- `ReaderScreen` 顶部改成先判 PDF：`if (book.format == BookFormat.PDF) { PdfReaderScreen(...); return }`。
- 测试用 PDF：`python scripts/make_test_pdf.py -o dist/test-pdf.pdf --pages 4`（手拼 PDF 语法，纯 ASCII，不依赖第三方库），Edge 里确认 4 页能正常渲染。

## 阶段 3 补充：TXT 转派生 EPUB（0.4.1）

TXT 没有分页信息，直接扔进阅读器会变成一条竖着几万字的「长卷」，翻页手感全无。做法：

```
importer/TxtToEpub.kt
  ensure(context, book)     缓存 filesDir/derived/<bookId>.epub（源文件 mtime 变了才重新生成）
  decode(bytes)             BOM → 严格 UTF-8 → GB18030 兜底
  split(text)               认「第 X 章/回/卷…」「Chapter N」「序章/楔子/尾声/番外」为章标题；
                            一行标题都认不出就整篇一章；单章 > 1 万字再按段落切块（5000 字左右）
  writeEpub(...)            java.util.zip 手写最小 EPUB3：mimetype 必须第一条且 STORED
ui/reader/TxtReaderScreen.kt  转码期显示进度 → 转完把派生 EPUB 当普通 EPUB 交给 ReaderScreen
```

- `scripts/txt_split_proto.py` 是同一套规则的 Python 参考实现，用来在没有真机时验证产出的 EPUB 能不能被 foliate 正确分页（已验证：3 章、目录正确、正文正常排版）。
- 单测 `app/src/test/java/com/lumenbook/reader/importer/TxtToEpubTest.kt`（7 个用例）覆盖拆章、切块、编码探测、EPUB 结构与 mimetype 顺序；跑法见「怎么跑」。
- 踩过的坑：开头第一行就是「第一章 xxx」时，旧逻辑要求 `body.isNotEmpty()` 才算标题，结果第一章标题被当成正文吞掉；现在改成「标题行永远优先，缓冲区非空才 flush」。

## 阶段 6：元数据写回（0.5.0）

用户最早就提过：改书名/作者/封面时，可以选择「只改应用里的显示」还是「改原文件」。现在的实现：

```
ui/library/LibraryScreen.kt   EditBookDialog：书名 / 作者 / 换封面 / 「同时写回文件」开关
data/LibraryRepository.kt     rename（只改库）、setCover（换封面图）、bytesOf（读用户选的图）
                              writeBackMeta：备份 → EpubRewriter.rewrite → 改写库 → 尝试同步回源 Uri
importer/EpubRewriter.kt      OPF 里换 dc:title / dc:creator / 封面；整包重打包，mimetype 仍第一条且 STORED
```

- 只有 **EPUB** 支持写回；PDF/MOBI/AZW3/FB2/TXT 在对话框里开关是灰的，并写明原因（TXT 甚至会转成派生 EPUB，写回没有意义）。
- 写回前一定先 `copyTo(filesDir/backup/<id>-<时间戳>.epub)`，写坏了还能捞回来。
- 私有副本改完后再尝试 `contentResolver.openOutputStream(sourceUri, "wt")` 同步回用户原文件；没有写权限就只改书库副本，并明确告诉用户。
- 换封面：用户挑的图按 480px 采样解码预览，原图字节存到 `filesDir/covers/custom-<id>.<ext>`；写回时同一份字节塞进 EPUB 包。
- 单测 `app/src/test/java/com/lumenbook/reader/importer/EpubRewriterTest.kt`（12 个用例，含 5 个写回用例）覆盖：书名/作者写进去、旧值消失、mimetype 仍是首条 STORED、章节正文一字不丢、封面条目与 `cover-image` 声明、空作者删掉 `dc:creator`、重打包后 OPF 仍是合法 XML。

## 阶段 7：应用内更新（0.6.0）

- `data/UpdateChecker.kt`：`GET https://api.github.com/repos/Yan-Congliu/lumenbook/releases/latest`（8s 超时），`parse(json, currentVersion)` 选资产（优先 `-arm64-v8a.apk`，退任意 `.apk`），`normalize()` 把 `v0.6.0` / `0.6.0-debug` / `0.6.0 (16)` 削成 `0.6.0`，`compare()` 按段比数字。仓库没发过版本时返回「仓库还没有发布过版本」。
- `data/ApkInstaller.kt`：`canInstall` / `openInstallPermission`（跳「安装未知应用」开关）/ `download`（下到 `filesDir/updates/`，先写 `.part`，带进度）/ `install`（FileProvider + `ACTION_VIEW`）。
- 权限只加了 `INTERNET` + `REQUEST_INSTALL_PACKAGES`；`res/xml/file_paths.xml` 只暴露 `updates/`。
- 设置页「更新」分区：检查 → 弹出 release notes → 显示下载进度 → 拉起系统安装。
- **发布签名**：`python scripts/make_keystore.py` 生成 `keystore/lumenbook.jks` + `keystore.properties`（随机密码，已 gitignore）；`app/build.gradle.kts` 在文件存在时自动给 release 配 signingConfig。
- 出包：debug `python scripts/build_apk.py`，发布 `python scripts/build_apk.py --release` → `dist/lumenbook-<ver>-arm64-v8a.apk`（8.9 MB，包名 `com.lumenbook.reader`，V2 签名）。
- **坑**：Kotlin DSL 里写 `java.util.Properties()` 会被解析成 `JavaPluginExtension`，必须 `import java.util.Properties` 后用 `Properties()`。

## 关键决定（为什么这么做）

- **不用 Room**：AGP 9 内置 Kotlin 2.2.10 后 KSP 版本匹配是额外风险，单表用 SQLiteOpenHelper 够用。
- **导入即复制 + 记住 SAF Uri**：复制保证文件一直在，Uri 留给阶段 6 写回原文件。
- **改书名只改库里的记录**，对话框里明说"不动原文件"；写回是阶段 6 的独立功能（要二次确认）。
- **主题不跟随壁纸取色**：Apple Books 的观感来自固定纸色，动态取色会破坏它。
- **背景留了三态接口**（Paper/Solid/Image），启动时固定 Paper；设置页「自定义背景图」置灰占位。
- **排版交给 foliate-js，不自己写分页**：分页 + CFI 定位 + 多格式解析是重活，MIT 且体积小（0.1 MB）。
- **书文件用 WebViewAssetLoader 的虚拟域名喂给 WebView**：同源，`fetch` 直接能读，不用 base64 或 file:// 的权限坑。

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
- **`addJavascriptInterface` 暴露的类必须是 public**：Kotlin 顶层 `private class` 编译成 package-private，
  `android.webkit` 跨包反射会抛 `IllegalAccessException`，表现是「JS 明明调了，App 一点反应都没有」，
  桌面浏览器测不出来（没有 Android 对象），只能靠真机。`ReaderBridge` 现在是 public class + 回调函数。
  用 `javap -cp app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes com.lumenbook.reader.ui.reader.ReaderBridge`
  看第一行是 `public final class` 才算对。
- **真机上看不到日志**：阅读页自带看门狗（9 秒没 ready 就在屏幕上写「卡在哪一步」）+ JS 的
  `window.onerror`/`unhandledrejection` 都会通过桥回报并显示，不要瞎猜。
- 打开书有两条路径：JS 的 `onPageLoad` 回调，和 WebView 的 `onPageFinished` 里兜底 `evaluateJavascript`。
  后者不依赖 JS 桥，桥挂了也还能打开书（只是收不到进度回调）。
- **foliate 的 `setStyles()` 必须在 `view.init()` 之后调**：渲染器的文档和 style 槽位那时才建好，
  早调只会被存起来然后丢掉，表现是「字号/纸色全没生效」但没有任何报错。
- **foliate 把 `--_gap`/`--_margin`/`--_max-inline-size` 写死在 closed shadow 的 `#top` 上**，
  子元素自己的声明会盖过继承值，从外面改不动 → 给 `paginator.js` 打了补丁改成读 `--lumen-*`
  （`scripts/vendor_foliate.py` 里的 `patch_paginator()` 会在重新拉取后自动补上；升级 foliate 要确认它还能匹配）。
- **`--_gap` 是百分数**（`parseFloat(value)/100`），写成 `26px` 会算出荒谬的边距。
- 桌面调试入口：`assets/reader/index.html?book=<url>` 会自动打开，配合 `python scripts/reader_smoke.py --serve`
  起本地站点（`.tooling/smoke/`，含一本生成的测试 EPUB），再用 ego 浏览器看效果——不用装 APK 就能验证排版。
- **paginator 用 `blob:` URL 当 iframe.src，安卓 WebView 不触发 `load` 事件** → `view.init()` 永不 resolve，
  表现是永远「卡在排版」。修法：`scripts/vendor_foliate.py` 的 `patch_paginator_load()` 把
  `#iframe.src = src` 换成 `fetch(src).then(r=>r.text()).then(t => { #iframe.srcdoc = t })`，整段包 try/catch 并在出错时 reject。
- **安卓 WebView 一开始给网页 0 高度**，`html/body{height:100%}` 一路算成 0，多栏排版会把整本书摊成
  134 万 px 宽、39px 高的一条横线（症状：能打开、没文字）。修法：`foliate-view` 用 `position:fixed;inset:0`
  贴视口，`syncViewSize()` 直接拿 `innerWidth/innerHeight` 写 px，并在 `resize`/重排后再钉一次。
- **安卓 WebView 的 font boosting 会把字号偷偷放大**（19px → 21.28px），一行只剩 12 个字：
  `settings.textZoom = 100` + CSS `-webkit-text-size-adjust: 100%`（`reader.css` 和注入正文的 `contentCss()` 都要加）。
- 诊断卡只在 `index.html?diag=1` 时出现，别常驻屏幕中间。
- **`WindowInsets` 给的是物理像素，WebView 里的 CSS px 等于 dp**。把 `statusBars.getTop(density)`
  原样传给 `window.lumen.setInsets()` 会放大 2~4 倍，症状是「正文从屏幕中间往下开始、底部还被切」。
  必须先 `/ density.density` 换成 dp 再传（0.6.1 修）。

## 阶段 8：书签（0.6.1）

```
data/Bookmark.kt            location 一体两用：EPUB 存 CFI，PDF 存 "page:N"
data/LibraryDb.kt           VERSION = 3，表 bookmarks(id, book_id, location, chapter, created_at)
                            bookmarks()/findBookmark()/addBookmark()/removeBookmark()；删书连带删书签
data/LibraryRepository.kt   bookmarks()/toggleBookmark()/removeBookmark()
ui/reader/ReaderScreen.kt   顶栏书签按钮（自绘 BookmarkGlyph：core 图标集里没有 Bookmark，
                            引 material-icons-extended 会多十几 MB），目录面板顶部多一段「书签」
ui/pdf/PdfReaderScreen.kt   同样一枚按钮 + 书签列表（点了 scrollToPage）
assets/reader/reader.js     window.lumen.goToCfi(cfi) → view.goTo(cfi)
```

桌面验证：`goToCfi` 从第三章跳回 `epubcfi(/6/4!/4/2,,/10/1:6)`，fraction 0.661、章节回到「第二章 灯下」。

## 阶段 8 夜间进展（0.6.2 / 0.6.3 / 0.6.4）

用户睡前说「这个显示还是有问题，你先做，我睡了，你尽可能多做一些」——于是把不依赖真机的打磨项做完：

```
设置 → 页面背景           data/BackgroundStore.kt（采样 1600px、压 JPEG 88、只留一张）
                          设置里三行：纸张 / 自定义背景图（换一张）/ 清除
                          铺在所有页最底层（LumenBackground.Image，压暗 0.30 保证文字可读）
书库搜索 + 排序           LibraryScreen：标题栏放大镜 + OutlinedTextField 过滤书名/作者
                          ⋯ 菜单里四档排序：最近阅读 / 书名 / 作者 / 加入时间
CBZ / FB2 验证            scripts/make_test_misc.py 自造样书，桌面全部跑通
发布脚本                  scripts/release_gh.mjs（Node，走 API 建 release + 传 APK）
                          python scripts/build_apk.py --release --publish 一步到位
```

- **CBZ 本来就是坏的**：foliate 的 `fixed-layout.js` 第一行是裸 import `construct-style-sheets-polyfill`，
  WebView 里解析不了 → `scripts/vendor_polyfill.py` 把它从 npm 拉下来放进 `vendor/foliate/vendor/` 并改成相对路径。
  教训：vendor 第三方库时**必须把所有裸模块名都换成本地文件**，浏览器只在真跑的时候才暴露。
- 已发 **v0.6.3 Release**（https://github.com/Yan-Congliu/lumenbook/releases/tag/v0.6.3），资产 `lumenbook-0.6.3-arm64-v8a.apk`，
  应用内更新的链路这就算真正闭环了。
- PDF 目录做不了：`PdfRenderer` 不暴露 outline/bookmark 树，要做得引 PDFium 或另写 PDF 解析，先记着。
- **0.6.5**：漫画封面 —— CBZ 没有元数据，封面取包内按文件名排序最靠前的图片（>6MB 放弃），
  `BookImporter.sniffCbz()`；单测 `CbzCoverTest`（4 个用例，含损坏包不抛异常）。
- **0.6.6（PDF 修好）**：真机「PDF 无法渲染」的根因是 `Bitmap.Config.RGB_565` —— **PdfRenderer 只接受
  ARGB_8888**，给 RGB_565 会抛异常，而旧代码 `catch { null }` 把异常吞了，界面永远停在「正在渲染…」。
  现在改成 ARGB_8888，并且失败时把 `openPage 失败 / render 失败：异常类名 + message` 画在页面上 + 一个「重试」按钮，
  打开失败也会显示原因。**教训：凡是 catch 完就返回 null 的渲染路径，真机上等于「永远转圈」，一定要把原因给出来。**

## 下一步

1. 用户验收 0.6.3/0.6.4：正文起始位置、书签、设置里的背景图、书库搜索排序、CBZ 漫画。
2. 用户从 debug 切到正式包：手动装一次 `dist/lumenbook-0.6.4-arm64-v8a.apk`（包名 `com.lumenbook.reader`），之后才能靠应用内更新。
3. MOBI/AZW3 用真实样书验证（自造样书已确认解析链路可用）。
4. 阶段 8 剩下：PDF 目录（需要换 PDF 解析库）、按标签/系列分组、阅读页页码（foliate 只给百分比）。


