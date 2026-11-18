# EPUB 字体修改参考（以「宋体」批量为例）

> 2026-11-18 · 配套脚本在 `scripts/`，批量结果在 `out-宋体版/`

## 结论先说

- **书里写死的字体，翻多少章都不会掉**；在 Apple Books 的 Aa 面板里选的字体是「外挂样式」，换一章就丢。
  这不是书的问题，是 iBooks 只对当前文档注入用户样式的老毛病。
- 所以批量改书的做法就是：**把字体声明写进每一本书自己的 CSS**，让它跟着每个章节文档一起加载。
- 机器上 `D:\vivo办公\接收到的文件` 里 8 本 EPUB 已全部改成宋体，输出在 `out-宋体版/`。

---

## 一、原理（一句话版）

```
Apple Books 每章 = 一个独立文档（spine item）
你在 Aa 里选的字体 → 运行时临时注入当前文档 → 换章后新文档里没有这段注入 → 掉字
写在书 CSS 里的字体 → 每个文档的样式表里本来就有 → 换章不掉
```

给 LumenBook 的提醒：用户字体在 foliate 里换 document 之后必须重新注入一次。

---

## 二、两种改法怎么选

| | 名字法（默认） | 嵌入法 |
|---|---|---|
| 命令 | `--family "Songti SC"` | `--family MyFont --font-file x.ttf` |
| 体积 | **0 增长** | +10～24MB |
| 前提 | 设备上本来有这个字体 | 无 |
| 适合 | iOS/macOS 自带的常用字体 | 思源、霞鹜文楷、方正/汉仪这类 |

### iOS 自带的中文字体（CSS 里直接写这些名字）

| 中文名 | CSS 名 | 备注 |
|---|---|---|
| 苹方 | `PingFang SC` | 系统默认，最不容易出问题 |
| 宋体 | `Songti SC` | **这次用的就是它** |
| 楷体 | `Kaiti SC` | 带手写笔意，正文略累眼 |
| 黑体 | `Heiti SC` | 标题常用 |
| 圆体 | `Yuanti SC` | 偏可爱 |
| 冬青黑体 | `Hiragino Sans GB` | 印刷感强，正文耐读 |

> 字体名写错**不会报错**，只会悄悄回退到 `serif`。所以换字体后一定要在 iPad 上亲眼看一眼。

---

## 三、命令速查

```bash
# 单本
python scripts/epub_font_inject.py in.epub out.epub --family "Songti SC" --keep-headings

# 批量（整个文件夹，输出到新目录）
python scripts/epub_font_batch.py "D:\vivo办公\接收到的文件" -o "out-宋体版" --family "Songti SC"

# 先预演，不写文件
python scripts/epub_font_batch.py "D:\in" -o "D:\out" --family "Kaiti SC" --dry-run

# 换字体：只改 --family
python scripts/epub_font_batch.py "D:\in" -o "out-楷体" --family "Kaiti SC"

# 嵌字体文件（设备上没有这个字体时）
python scripts/epub_font_inject.py in.epub out.epub --family BookKai --font-file C:\Windows\Fonts\simkai.ttf

# 标题也一起统一（默认保留原书的黑体标题设计）
python scripts/epub_font_batch.py "D:\in" -o out --family "Songti SC" --all-headings

# 重新体检 / 验证
python scripts/epub_font_stats.py "out-宋体版"
python scripts/epub_check_package.py "out-宋体版/某本.epub"
```

参数：

| 参数 | 作用 |
|---|---|
| `--family` | 写进 CSS 的字体名 |
| `--font-file` | 嵌入字体文件（不传就只写名字） |
| `--fallback` | 兜底字体，默认 `serif`，传空串不要兜底 |
| `--keep-headings` / `--all-headings` | 标题保留原设计 / 一起统一 |
| `--suffix` | 输出文件名后缀，默认按字体名自动生成（`-宋体`） |
| `--dry-run` | 只看会做什么 |
| `--recursive` | 子目录也扫 |

---

## 四、这次 8 本的体检结果

| 书 | 正文文档 | 原书字体声明 | 结果 |
|---|---|---|---|
| 中国2185 | 3 | 0 | ✅ |
| 二十四史（含清史稿） | **3223** | 4 + 1 个 @font-face | ✅ 43MB 一次过 |
| 刘慈欣短篇科幻小说合集 | 120 | **19** | ✅ |
| 战争与和平 | 25 | 0 | ✅ |
| 毛远新的回忆 | 29 | 2 | ✅ |
| 淫荡少妇白洁 | 167 | 1 | ✅ |
| 红星照耀中国 | 80 | 13 + 1 个 @font-face | ✅ |
| 钢铁是怎样炼成的 | 11 | 13 | ✅ |

注意那几本带字体声明的：注入规则用了 `!important`，元素级选择器压得住书里的普通类规则
（`.kt`、`.note`、`.chapter_color` 之类全部被覆盖）。全部 8 本复查：**0 处**书内 `!important` 抢字体、
**0 处**内联 `style="font-family"`。

原书里没有任何一本书自带内嵌字体文件，所以不存在"嵌入字体和注入打架"的情况。

---

## 五、改完在 iPad 上怎么用（关键）

1. 把 `out-宋体版/*.epub` 传到 iPad，导入 Books（iCloud 云盘 / 文件 App 都行）。
2. 打开书 → 点 **Aa** → **字体** → 选 **「原书字体 / Original」**。
3. **之后不要再手选系统字体。** 一旦手选，Books 又会加一层外挂覆盖，换章照样掉
   （只是掉回去的是你的宋体，不是默认字体，观感能接受）。

字号、行距、页边距、主题、翻页方向——这些照常调，不受影响，掉的只有「字体」这一项。

---

## 六、校验清单

每本改完会自动核对：

- `mimetype` 必须是 zip 第一条且 **STORED**（不压缩），否则 Books 直接不认
- 样式表里必须有 `/* LBFONT-INJECT v1 */` 标记
- 已注入过的书会被识别并跳过，不会重复叠加
- 批量结束会在输出目录写一份 `报告.md`（成功/跳过/失败 + 体积变化 + 备注）

```bash
python scripts/epub_check_package.py "out-宋体版/红星照耀中国 (埃德加·斯诺著,董乐山,墨盟译)-宋体.epub"
```

---

## 七、坑与限制

1. **最重要**：Aa 里选「原书字体」，别手选。手选 = 又回到外挂覆盖。
2. 字体名写错是**静默失败**，只会回退到 serif，必须上机看。
3. 不嵌文件时，iPad 上没这个字体就等于没改。
4. **`.ttc` 不能直接嵌**（iOS 的 WebKit 不认字体集合），要先用工具拆成 `.ttf`/`.otf`。
5. **可变字体**（思源 SC 的 `*-VF.ttf`）在 iOS 16 上有风险，优先用静态字重。
6. **固定版式书**（漫画、画册，`rendition:layout: pre-paginated`）会**自动跳过**——注入字体没意义。
7. **图片版 EPUB**（扫描书，正文其实是图片）改不了，没有任何 CSS 能让图片换字体。
8. **PDF 不适用**，它不解析 CSS。
9. 嵌入字体有版权问题：**自己看没问题，别往外发**。
10. 想要小体积就得做子集化（只留书里出现的字），需要 `fonttools`；本机 pip 被沙箱挡了装不上，
    暂时都是全量嵌入。真要省空间得另找能装 pip 的环境。
11. 少数书把正文塞在 `<svg>` 或 `<table>` 里，注入规则已包含 `li/td/th/span`，但 `<svg>` 里的
    `text` 元素压不住——遇到再说。

---

## 八、脚本清单

| 脚本 | 干什么 |
|---|---|
| `scripts/epub_font_inject.py` | 单本注入（同时是库，被 batch 复用） |
| `scripts/epub_font_batch.py` | 批量注入 + 出报告 |
| `scripts/epub_font_stats.py` | 一本书一行：分几章、有没有自带字体、是否已注入 |
| `scripts/epub_font_audit.py` | 单本详细体检（CSS 全文、逐章字数、目录） |
| `scripts/epub_check_package.py` | 校验包结构（mimetype、HTML 良构、注入标记） |
| `scripts/epub_merge_probe.py` | 把多文档并成单文档（做「跨章掉字」对照实验用） |
