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
- **0.6.7（PDF 能滑着翻页）**：Pager 的每一页里挂 `detectTransformGestures` 会把**单指拖动也吃掉**，
  `HorizontalPager` 收不到拖动事件 → 只能拖进度条换页。改用
  `Modifier.transformable(state = transformState, canPan = { scale > 1.01f })`：没放大时单指拖动放行给 Pager，
  放大后才由 transformable 接管平移；双指缩放始终可用。

## 旁支：Apple Books 跨章掉字体（2026-11-18）

用户拿 iPad 上的真问题来对：改字体后读完一章、翻到下一章字体回到默认，退出重进又好了。
拆了两本书，结论都指向 **Apple Books 自己**，不是书：

```
中国2185（calibre 2.82 输出）   2 个正文文件（3.6万/5.4万字，按大小硬切，切点在清单中间还重复一段）
                                font-family 0 处、@font-face 0 个、内嵌字体 0 个、内联样式 0 处
红星照耀中国（多看转出）        79 个正文文件（一章一文件，4-6千字），TOC 75 条
                                font-family 13 处但全在特殊块上：h2 黑体、126 个 p.kt 楷体引文
                                正文 <p> 没有字体设置；@font-face 的 30 个 url() 全是 XXXXXXXXXXXXXXXX（占位符）
```

- 共同点：**正文都没有字体声明**，所以"改的字体被顶回去"不可能是 CSS 造成的；
  两本唯一能对上的触发点就是 **跨 spine 文档**（中国2185 只有 1 个交界、红星 79 个）。
- 旁证：Apple 社区 [thread/255614451](https://discussionschinese.apple.com/thread/255614451?page=1&sortBy=rank)，
  主题/字体还原为默认、关书重开恢复，和现象完全一致。
- 新增 `scripts/epub_font_audit.py`：扫 font-family / @font-face / 内嵌字体 / 内联 style、列 spine 各文档字数与标题、
  导出 NCX 目录，`-o` 出 Markdown 报告。以后审任何一本 EPUB 都用它。
- `scripts/epub_merge_probe.py`：把 calibre 硬切出来的多个 XHTML 并成单文档（顺带去掉切点重复段），
  用来做"文档交界是不是触发器"的对照实验。
- **用户实测确认**：`中国2185-单文档测试.epub`（单个正文文档、零交界）读完字体**不掉**；
  红星照耀中国（79 个文档）每章一交界就掉。→ **触发点就是 spine 文档切换**，与书的 CSS 无关。
- 结论落地：想让某本书固定用某个字体，唯一稳的办法是**把字体写进书自己的 CSS**（跟着每个文档走），
  App 里手选的字体是外挂注入，换文档必丢。
- 新增 `scripts/epub_font_inject.py`：`--family "Songti SC"` 只写名字（0 体积，要求设备上有该字体）；
  `--font-file x.ttf` 把字体打进包并在 OPF manifest 登记（10-20MB）。同时注入
  `META-INF/com.apple.ibooks.display-options.xml`（specified-fonts=true），并在不嵌文件时放一条 `local()` 的
  `@font-face` —— 有 @font-face，Apple Books 的字体菜单才会出现「Original / 原书字体」。
  用的时候 Aa 面板必须停在 Original，手选系统字体仍会跨章丢。
- 新增 `scripts/epub_check_package.py`：校验 mimetype 首位/STORED、display-options、注入后的 CSS 尾部、各 HTML 良构。
- 顺带发现：红星那本本身有 2 个文档不良构（`text00005.html` 文件尾有杂内容、`text00074.html` 标签不闭合），
  iOS 宽容解析无碍，但属于隐患。
- 新增 `scripts/epub_font_stats.py`：一本书一行（正文文档数 / 字体声明数 / @font-face / 内嵌字体 / 内联字体 / 是否已注入 / 固定版式），批量体检用。
- 新增 `scripts/epub_font_batch.py`：整目录批量注入 + 输出 `报告.md`（成功/跳过/失败、体积变化），自动跳过固定版式与已注入的书。
  实测 `D:\vivo办公\接收到的文件` 8 本全过（含 3223 文档 / 43MB 的二十四史），输出 `out-宋体版/`。
  8 本复查：书内 `!important` 抢字体 0 处、内联 `style="font-family"` 0 处、内嵌字体 0 个 —— 所以元素级 `!important`
  注入在这个书源上足够。写文档 `EPUB字体修改参考.md`（宋体批量全流程 + iOS 字体名表 + 坑清单）。
- **对 LumenBook 的意义**：用户字体在所有 spine 文档上都必须重新注入 —— foliate 里换文档要确认
  `renderer.setStyles` / 用户样式注入跟着新 document 走，别学 iBooks 只对当前文档生效。

## 阶段 8：EPUB 轻扫翻页（0.6.8）

用户反馈：翻页要划好几次、或者第一次就得划很大幅度，页面跟着手指走、一松手又弹回。

- 根因在 foliate 的 `paginator.js`：`snap()` 用 `(start + end) / 2 + velocity * size` 定页，
  `velocity` 是 px/ms，乘一页宽之后等于要求「拖满一整页」或「手速 0.8 px/ms 以上」才肯翻，
  轻扫必然弹回。
- 改为「位移优先」判定：`drag = (start + end) / 2 - settled - size / 2`，
  `byDrag = |drag| / size >= 0.10`，`move = byDrag || |v| >= 0.20`，方向由 `byDrag ? drag : v` 决定。
  `settled = Math.abs(#scrollBounds[0])` 就是起手那页的滚动位置，不需要额外记状态。
- **两个必须记住的坑**：
  1. `_lumenDebug` 探针一度写在 `const target` 之前 → `ReferenceError: Cannot access 'target' before initialization`，
     而它是在 `#onTouchEnd` 的 `requestAnimationFrame` 里抛的，被静默吞掉，表现为「页面卡在半路、不回弹也不翻页」。
  2. foliate 原本把 `snap()` 放在 `requestAnimationFrame` 里，WebView / 后台标签页 rAF 被节流就不翻页。
     现在 `#onTouchEnd` 同步调用 `snap()`（`patch_paginator_touchend()`）。
- 补丁全部落在 `scripts/vendor_foliate.py`（`patch_paginator_snap()` / `patch_paginator_touchend()`），
  重新 vendor 后自动重放；桌面验证脚本 `scripts/reader_smoke.py --serve` 现在发 `Cache-Control: no-store`，
  否则改完 `paginator.js` 刷新页面还在跑浏览器缓存里的旧模块。
- 桌面实测（360×800 模拟、页宽 324）：前翻 11% → 翻页；后翻 11% → 回退；3% 慢拖 → 不翻。

## 阶段 8：导入改成「选文件夹 → 自动扫描」（0.6.9）

真实用户反馈（2026-11-18）：给他发了 APK，他说「导入不了」，问是不是找不到文件，答「对」；
把书放到手机存储根目录新建的「我的图书」文件夹后就能找到了，并说「他放在太里头就找不见」。

- 根因：导入走的是系统 SAF 选择器（`OpenMultipleDocuments`），它默认停在「最近」，
  书放在下载目录或几层子文件夹里时翻不到；另外按 8 种 MIME 过滤，未知类型的书还会变灰不可选。
- 改成自家导入页：
  - `app/src/main/java/com/lumenbook/reader/importer/ImportScanner.kt`：拿 `ACTION_OPEN_DOCUMENT_TREE` 授权，
    自己用 `DocumentsContract` 递归遍历（深度上限 12、最多 3000 个文件、跳过 `.` 开头），
    按扩展名收 EPUB/PDF/TXT/MOBI/AZW3/FB2/CBZ，并记相对路径与大小。
  - `app/src/main/java/com/lumenbook/reader/ui/imports/ImportScreen.kt`：选文件夹 → 扫描进度 → 带勾选的清单
    （全选/全不选、来源路径、大小）→ 「导入 N 本」；另有「只选几个文件」备用入口（走系统选择器，放宽到 `*/*`）。
  - `ui/AppRoot.kt`：书库的导入入口改为打开全屏 `ImportScreen`，导入完成后刷新书库。
- 教训：**依赖系统文件选择器的导入流程在国产 ROM 上不可靠**，用户不会去理解「最近/浏览」的区别，
  导入要自己扫目录。

## 阶段 8：模拟器实测 + 漫画/点击手势修好（0.6.10）

用户（2026-11-18 19:17）：「虚拟机运行一下看看」。本机有 `.tooling/android-sdk/emulator`（Android 16 / API 36 / x86_64 /
`emulator-5554`，1080x2400、density 420），用 `adb shell input tap|swipe` + `screencap` 驱动，装 debug 包实测。

实测通过的：

- 导入页 → 选文件夹 → 深目录扫描：`我的图书/小说/短篇/雪夜读书记.txt`、`Download/deep/a/b/说明文档.pdf` 都扫到了，
  还能正确显示来源相对路径；FB2 的作者也解析出来了。导入后书库立刻可见。
- TXT 转派生 EPUB、EPUB 翻页/位置记忆、PDF 渲染 + 滑动翻页 + 页码记忆（重装 APK 后仍回到第 3/4 页）。
- **漫画 CBZ 打不开（新 bug）**：界面永远停在「正在排版」，20 秒后报「排版 超时」。
  根因与 0.3.3 那次的 EPUB 一模一样 —— `vendor/foliate/fixed-layout.js` 的 `#createFrame()` 也是
  `iframe.src = 一个 blob: URL` 然后等 load 事件，安卓 WebView 里这个 load 不来。
  修法：新增 `patch_fixed_layout()`（`scripts/vendor_foliate.py`），blob 先 `fetch().text()` 再塞 `iframe.srcdoc`；
  logcat 里能看到 `about:srcdoc` 就说明补丁生效了。
- **漫画/PDF 的点击分区在 WebView 里没反应**：正文在 iframe 里，`pointerdown/pointerup` 挂在 window 上收不到
  （iframe 事件不冒泡，同一文档源也不行）。修法：`app/src/main/assets/reader/reader.js` 新增
  `attachDocGestures(doc)`，在每个内容文档的 `defaultView` 上挂 `touchstart/touchend`（桌面无触摸才退 pointer 事件），
  在 `view.addEventListener('load', e => attachDocGestures(e.detail.doc))` 与 `open()` 完成后各补一次。
  - 轻点：左 28% 上一页 / 右 72% 下一页 / 中间呼出工具栏。
  - 横滑：只有固定版式（`renderer.tagName` 含 `FXL`/`FIXED`，即漫画 CBZ）才由我们翻，位移 ≥12% 页宽就翻；
    可重排的书（EPUB/FB2/TXT）的拖动仍然交给 paginator，避免抢手势。
  - **坑**：WebView 的 iframe 里 `pointerup` 收不到（触摸一滑就 pointercancel），必须用 touch 事件。
- 导入页补了一句提示：安卓不允许直接选「下载」根目录（真机上会报「无法使用此文件夹」），要么选子文件夹，
  要么新建「我的图书」。这条是模拟器实测发现的，不是猜的。

## 阶段 8：导入页「常用位置」直达（0.6.11）

朋友给的判断：手机的遍历/选择器不会把每个文件夹都打开到底，而且 QQ、微信、浏览器下载的文件位置是**固定**的，
应该让 App 直接去读那些固定目录。这个判断一半对：

- 对的一半：书在哪儿取决于**各个 App 自己存到哪**，让用户自己去一层层翻不现实。
- 不对的一半：鎏书自己的扫描没有「只扫几层」的问题（`MAX_DEPTH = 12`，模拟器里三层深的 `小说/短篇/` 照样扫到）。
  真正的卡点是**系统文件夹选择器一打开停在哪儿**。

所以做的是「常用位置」四个快捷按钮（`ui/imports/ImportScreen.kt`）：

- `initialTreeUri(path)` = `DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:<path>")`，
  直接作为 `ActivityResultContracts.OpenDocumentTree()` 的 input，系统选择器就停在那一步。
- 四档：`我的图书`、`Download`、`tencent/QQfile_recv`（QQ 接收的文件）、`tencent/MicroMsg/Download`（微信下载）。
- 目录不存在时 DocumentsUI 退回上一级，不会崩。
- 模拟器实测：点「QQ 接收的文件」→ 选择器直接停在 `tencent/QQfile_recv` → 授权 → 扫到 `群里发的小说.epub` → 导入成功。

**注意**：新版 QQ/微信把文件放进了 `Android/data/<包名>/...`，那个位置安卓 11+ 任何第三方 App 都读不到，
SAF 里也选不了 —— 只能让用户先在 QQ/微信里「用其他应用打开 → 保存到本机」。

## 阶段 8：点击分区改成 30/40/30 + 轻点翻页不再被 snap 抢（0.6.12）

用户反馈（原话）：「点击翻页只能向后，不能向前，只有很靠左点击的时候才会向后翻页，而且点击次数多了还会没反应。
应该设置为左侧 30% 向左翻页，右侧 30% 向右翻页，中间 40% 点击后出现左上右上角的退出和章节目录还有下面的进度条。
保留小说名和章节名。同时出现前后均不能遮挡正文，还有加上能根据自带的可以点击的目录跳转到相应位置的功能，并保留右上角的目录。」

三个真问题（都在模拟器上复现了）：

1. **第一下点击被吃掉**：安卓 WebView 没拿到焦点时，第一次触摸只用来「聚焦」，那一下不进正文。
   → `ReaderScreen.kt` 在 `onPageFinished` 里 `view.isFocusableInTouchMode = true; view.requestFocus()`。
2. **轻点翻页被 paginator 的 snap() 抢回去**：`next()`/`prev()` 之后同一次 touchend 还会跑到
   paginator 的 `snap()`，它按「位移 0」判定不翻页，把刚翻过去的那页又滚回来 —— 表现就是「点很多次才成功」。
   → `reader.js` 的 `tapZone()` 在调 next/prev 前打 `globalThis.__lumenTapFlipAt = Date.now()`；
   `paginator.js` 补丁里 `snap()` 开头 `if (Date.now() - (globalThis.__lumenTapFlipAt || 0) < 300) return`
   （`scripts/vendor_foliate.py` 的 `NEW_SNAP` 常量同步改了，重新 vendor 会带上）。
3. **点击分区的比例算错**：原来用**外层 window 宽度**去除 clientX，但正文 iframe 的排版宽度比外层窄 20 多像素
   （留给滚动条），结果右 25% 被算成中间区、点了只呼出工具栏。
   → `attachDocGestures` 里改用**正文文档自己的 `win.innerWidth`**。

分区常量（`reader.js`）：`ZONE_LEFT = 0.30`、`ZONE_RIGHT = 0.70`（左 30% 上一页、右 30% 下一页、中间 40% 呼出/收起工具栏）。

模拟器实测（Android 16，1080x2400）：
- 右 75% 点一下 → 第一章 37% → 第二章 69%；左 18% 点一下 → 回到第一章 37%（release 包同样通过）。
- 中间点一下 → 工具栏隐藏（正文顶到状态栏下面），再点 → 出来，正文被推开、不遮挡。
- 右上角目录 → 三个章节列表 → 点「第三章 长夜」→ 直接跳过去（100%）。

## 阶段 8：长文档点右边反而往前翻（0.6.13）

0.6.12 发出去后用户说「你自己跑一下试试，似乎有问题」。在模拟器里用《中国2185》（整本书只有一个 xhtml 的超长文档）
复现出来了，两个真问题：

1. **超长单文档书的点击坐标是「长带坐标」，不是屏幕坐标**。这类书 foliate 把整本排成一条 10 万像素宽的横向长带，
   正文 iframe 跟着变成 104568px 宽、由外层容器横向滚动。于是 `clientX` 是长带里的坐标（点一下是 5479~6644），
   除以 `innerWidth` 永远落在左侧 30% —— 表现为「点右边反而往前翻」「点十下也不动」。
   修法：用 `win.frameElement.getBoundingClientRect()` 把坐标换算回屏幕坐标再算比例：
   ```js
   const r = frame.getBoundingClientRect()
   const scale = r.width && win.innerWidth ? r.width / win.innerWidth : 1
   screenX = r.left + x * scale     // r.left 是负的滚动偏移
   tapZone(screenX / (window.innerWidth || 360))
   ```
   普通书 `frameLeft = 0`，行为不变；长文档 `frameLeft = -5170` 时 screenX 才落回 0~412。
2. **动画期间连点会丢**：foliate 在翻页动画（约 300ms）里会忽略新的 `next()/prev()`。
   修法：`flip(dir)` 串行化 + 最多记一个待办 —— 动画结束后立刻补上，点几下就翻几页。

实测（release 包，Android 16 模拟器）：《中国2185》45% → 连点 4 下 → 46%（约 4 页）；左 18% 连点回翻；中间点呼出工具栏正常。
普通书（TXT 派生 EPUB / EPUB 3 章）点击分区与上一版一致。

## 阶段 8：工具栏改成覆盖层 + 手势分家（0.6.14）

用户反馈（原话）：「我滑动翻页的时候还会触发点击出现菜单栏，改一下，滑动的时候不要点击触发菜单栏，菜单栏出现后再次点击中间或者执行翻页动作就收回，
有过渡动画，渐入渐出吧，无人触碰就 6 秒后自动消失。还有，菜单栏出现的时候排版会有改变，改成菜单栏出现后排版不变，遮挡就遮挡吧，有个遮挡的柔和的过渡动画就行，
不然点几次文章就不知道跑哪里去了。」

四件事，全部落地：

1. **滑动不再被当成点击**：`attachDocGestures` 现在用 `touchmove` 记**手指最大位移**（`down.max`），
   只有 `maxDist <= 8px && 停留 <= 400ms` 才算轻点；横滑/轻扫一律不触发工具栏。
2. **工具栏出现后不再重排正文**：删掉了「把栏高推给网页」的 `setInsets(statusBar+56, navBar+44)`，
   改成只让开状态栏和导航栏（`setInsets(statusBarDp, navBarDp)`，`LaunchedEffect(ui.ready)` 只调一次）。
   工具栏变成纯覆盖层：`AnimatedVisibility(fadeIn(tween(190)) / fadeOut(tween(170)))`，
   里面是两道柔和渐变遮罩（顶部 150dp 从 `colors.page` 96% 透明度渐隐，底部 92% 渐显）。
3. **翻页/点中间就收回**：`tapZone()` 在左右区翻页前 `bridge('onHideChrome')`；中间区仍是 `onToggleChrome`。
4. **6 秒无人触碰自动消失**：JS 在 `touchstart`/`touchend` 调 `bridge('onActivity')`，Kotlin 的
   `LaunchedEffect(ui.chromeVisible, ui.activity) { delay(6_000); chromeVisible = false }` 每次触摸重新计时。

实测（release 包 0.6.14，Android 16 模拟器，用用户提供的 `out-宋体版/*.epub`）：
- 连划两次 → 页翻了、工具栏**没**出现；连点中间 → 淡入、再点 → 淡出。
- 展开工具栏后等 7 秒 → 自动收起；**收起后的截图与出现前的截图 sha256 完全一致**，证明没有任何重排。
- 42 MB 的《二十四史（含清史稿）》能正常导入并打开（显示「主目录」页）。

## 阶段 8：外观设置 + Apple Books 式信息层（0.6.15）

用户要的（原话）：「模仿apple book，阅读时左上角常驻书名，顶上中部常驻章节名，底部的中部常驻页码。
单击中40%打开菜单栏后页码转变为"当前页码/总页数"，右下角合适位置出现菜单按钮点击后再出现目录、主题设置、书签，
左上角原位置点击后出现退出箭头。字体族那里再加一个仿宋。」

### 做了什么

- **数据层**（`data/Prefs.kt` 重写）：`ReaderTheme`（SYSTEM/PAPER/WHITE/GREEN/BLUE/INK/BLACK）、
  `ReaderFont`（SONG/HEI/KAI/**FANG 仿宋**/MONO）、`ReaderAlign`（JUSTIFY/LEFT）、
  `ReaderStyle(fontSize, lineHeight, margin, font, align, indent)`、
  `LibraryStyle(columns 3|4, coverRadius, showAuthor)`、`backgroundDim`；全部走 DataStore。
- **配色**（`ui/theme/LumenColors.kt`）：新增白纸/护眼绿/灰蓝/纯黑四套，`lumenColorsFor(theme, systemDark)`；
  `Theme.kt` 提供 `rememberLumenColors(theme)`，整个 App（书库、设置、阅读页）都跟着换。
- **设置页**：外观区 = 「跟随系统」+ 6 个纸色圆；新增「书库外观」（三列/四列、封面圆角滑条、显示作者开关）；
  有自定义背景图时多一条「压暗程度」。
- **书库**：列数/圆角/作者行都按 `LibraryStyle` 走。
- **阅读页**：常驻信息层（左上书名、顶中章节名、底中页码）；点中 40% 淡入工具栏 —— 底部细进度条 +
  页码变「15 / 2728」+ 右下 ☰ 菜单按钮；☰ 打开底部菜单（目录 / 书签 / 主题与字体 / 给这一页加书签）；
  点左上书名换成圆形返回箭头，再点出书库。
- 「主题与字体」面板：6 种纸色、5 种字体（含仿宋）、字号/行距/页边距/首行缩进滑条、对齐两档。

### 两个真坑

1. **书名被竖向裁成半行**：信息层那一行原来写了 `height(20.dp)`，11sp 的字加上上下 padding 超过 20dp 就被裁掉。
   改成不给固定高度，书名/章节名各自 `padding(vertical = 5.dp)`。
2. **换主题后「中间绿、四周米」**：`openScript = remember(book.id, readerStyle, colors)`，
   换主题会让 openScript 变 → 重新打开整本书，新文档外层 `html/body` 回到默认米纸色（只有 iframe 正文拿到新 CSS）。
   改成 `remember(book.id, book.localPath)`，换主题/字号只走 `window.lumen.applyTheme(...)`，不再重开书。

实测（Android 16 模拟器，debug 包）：护眼绿整页统一 `#E7EFDD`、楷体生效、页码 11；
中央点击出「11 / 2341」与 ☰；菜单四项正常；左上书名→返回箭头→回到书库；
设置页四列 + 圆角 + 隐藏作者后书库立即变 4 列。

## 阶段 8：护眼绿/灰蓝跟随系统深色模式（0.6.16）

用户问：「护眼绿 这个颜色能不能做相应的跟随系统深色模式的适配」。

- `ui/theme/LumenColors.kt` 新增 `GreenDarkLumenColors`（page `0xFF1A211A`、字 `0xFFDDE6D8`、accent `0xFF7FA86A`）与
  `BlueDarkLumenColors`（page `0xFF171D23`、字 `0xFFDCE3EA`、accent `0xFF77A3C7`）。
- `lumenColorsFor(theme, systemDark)`：GREEN / BLUE 在 `systemDark` 时返回深色版；米纸、白纸保持纸感不变，
  墨黑、纯黑本来就是深色，「跟随系统」照旧完全跟随。
- 设置页 `ThemeSwatch` 与阅读页主题面板的小圆点改用 `lumenColorsFor(theme, isSystemInDarkTheme()).page`，
  深色模式下圆点直接显示深色纸。

实测（Android 16 模拟器，`adb shell cmd uimode night yes/no`）：主题选「护眼绿」时，
浅色模式是亮绿纸 `#E7EFDD`，切到深色模式整页变墨绿（书库、设置、正文一起变），切回浅色又恢复；
两种模式来回切都停在第 4 页，阅读位置不丢。

## 阶段 8：阅读页工具栏收敛（0.6.17）

用户看完 0.6.16 的截图后提了四条（原话）：「中央下部已经有了单击中40%打开菜单栏后页码转变为"当前页码/总页数"，
所以取消掉那个进度条。右下角的菜单里面取消给这一页加书签。采用前几版本的右上角书签图标来实现功能。
同时单击中部40%后退出按钮不见了。改下这些」。

改动（都在 `ui/reader/ReaderScreen.kt`）：
- 删掉 `ReaderSeekBar` 与整段「底部进度条」容器，只剩上下两道光渐变 + 右下角菜单按钮。
- `ReaderMenuSheet` 去掉 `bookmarked` / `onToggleBookmark` 参数与「给这一页加书签」那一行，菜单只剩
  目录 / 书签(计数) / 主题与字体。
- `ReaderMetaLayer` 去掉 `backShown` / `onTitleTap`：`chrome` 为真时左上角**直接**显示返回箭头，
  右上角同时显示书签按钮（`BookmarkGlyph`，已加书签时实心 + `colors.accent`）。
- `ReaderUi.backShown` 字段一并删除。

实测（Android 16 模拟器，debug 包）：点中间 → 左上返回箭头 + 右上空心书签 + 底「19 / 6967」+ 右下 ☰，没有进度条；
点右上书签 → 图标变实心；点 ☰ → 菜单只有 目录 / 书签(1) / 主题与字体。

## 阶段 8：纸张主题改成横向可滑动（0.6.18）

用户发来真机截图：设置页「纸张主题」六个圆点排太满，「纯黑」的标签被挤成「纯 / 黑」两行；
要求「给他做一个左右滑动的，这样子后面加了更多颜色也能滑一下就看见」。

- `ui/settings/SettingsScreen.kt`：主题行改成 `Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()))`，
  `horizontalArrangement = spacedBy(6.dp)`；`ThemeSwatch` 固定 `width(52.dp)`，标签 `maxLines = 1` / `softWrap = false`。
- `ui/reader/ReaderScreen.kt` 的「主题与字体」面板同样处理（每个纸色列 `width(50.dp)`、标签不换行）。

实测（Android 16 模拟器，深色模式）：六个纸色一行排开、标签单行，行本身可横滑；圆点显示的是各主题在深色模式下的实际纸色
（护眼绿=墨绿、灰蓝=深蓝、墨黑/纯黑=各自深色）。

## 阶段 8：优化一轮（0.6.19）

用户点了五项性能优化，另加三条自定义要求。

### 功能改动

1. **全书搜索**：菜单里「目录」和「书签」之间加了「搜索」，点开是「搜索这本书」面板 —— 输入关键词，
   下面按章列出命中片段（命中词用【】标出）、显示章节名，点一条直接跳过去。
   - `reader.js`：`searchBook(query, limit = 80)` 走 `view.search({ query })` 的 async generator
     （每章先 yield `{ label, subitems }`，最后 yield `'done'`）；`excerptText()` 把 foliate 的
     `{ pre, match, post }` 拍平成「前文【命中】后文」，截 120 字。
   - Kotlin：`ReaderSearchSheet`（ModalBottomSheet + OutlinedTextField + LazyColumn），
     `ReaderBridge.onSearchResults(json)` → `ui.searchHits`；点结果 `window.lumen.goToCfi(cfi)`。
2. **工具栏动画更柔**：`fadeIn(tween(260, FastOutSlowInEasing)) + slideInVertically(260, -it/12)`，
   收起 `fadeOut(240) + slideOutVertically(240)`。
3. **左上角返回箭头和书名同时显示**：`ReaderMetaLayer` 左上改成 Row（箭头 + 2dp 间隔 + 书名），
   书名一直在，箭头出现时整体右移一点。
4. **重复导入去重**：`Book.fingerprint` + `LibraryDb` VERSION=4（`fingerprint TEXT` 列 + 索引）、
   `findByFingerprint`、`updateFingerprint`；`LibraryRepository.fingerprintOf()` = 大小 + 前 64KB 的 SHA-256，
   导入前查指纹，命中就跳过并在 toast 里说「跳过 N 本已有的」。
   老库里没有指纹的书由 `backfillFingerprints()` 在启动时补算（只补第一次出现的那条）。

### 性能改动

- **PDF 翻页不闪白**：`PdfDocument` 加 3 页的 LRU 位图缓存（`MAX_CACHED_PAGES = 3`，一页 ARGB_8888 约 6MB），
  另有 `prefetch()` 在翻页落定后预渲染前后各一页 → 翻过去直接有图。
- **书库滚动更跟手 / 省内存**：新增 `CoverCache`（`LruCache`，上限 24MB，按 RGB_565 每像素 2 字节折算），
  封面小图不再每次滚动都从磁盘重新解码。
- **批量导入更快**：`import()` 用 `coroutineScope + async + Semaphore(2)` 并发复制，计数走 `Mutex`。

实测（Android 16 模拟器，0.6.19 debug）：同一文件夹二次导入 → toast「跳过 6 本已有的」，书库数量不变；
菜单顺序为 目录 / 搜索 / 书签 / 主题与字体；工具栏展开时左上箭头 + 书名同显。
搜索链路的端到端验证在桌面 `reader_smoke.py` 的页面上做（模拟器 adb 没法可靠地给 Compose 输入框聚焦打字）：
`window.lumen.search('雪')` → 5 条命中，excerpt 形如「第一章 【雪】落」，chapter 为「第一章 雪落」。

## 下一步

1. 用户验收 0.6.15：外观设置（纸色/字体/排版/书库外观）+ 阅读页信息层与菜单按钮。
2. 用户从 debug 切到正式包：手动装一次 `dist/lumenbook-0.6.14-arm64-v8a.apk`（包名 `com.lumenbook.reader`），之后才能靠应用内更新。
3. MOBI/AZW3 真实样书暂不追（用户明确说不做），自造 MOBI6 样书已确认解析链路与目录。
4. 阶段 8 剩下：PDF 目录（需要换 PDF 解析库）、按标签/系列分组、阅读页页码（foliate 只给百分比）。




