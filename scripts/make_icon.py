"""生成鎏书 LumenBook 的启动器图标（自适应 + 传统 + 单色）。

设计：C 书脊 + 鎏金（米纸底）
- 三本书立在同一基线，中间那本整条是磨光金箔，两侧书脊用鎏金压条收边，带金粉。
- 自适应图标：前景 = 三本书（透明底，画在 66/108 的安全区），背景 = 米纸色。
- 传统图标（API 26 以下 / 部分启动器）：米纸圆角方块 + 三本书，另出圆形版。
- 单色版（Android 13 主题图标）：只有 alpha 轮廓。

用法：python scripts/make_icon.py [--out app/src/main/res]
"""
import argparse
import os

import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

PAPER = (247, 244, 239)
INK = (38, 33, 28)
BROWN = (169, 118, 77)
BROWN_DARK = (124, 80, 49)
SHADOW = (206, 198, 186)

GOLD_STOPS = [
    (0.00, (108, 68, 22)),
    (0.14, (168, 116, 34)),
    (0.34, (214, 170, 78)),
    (0.50, (250, 230, 168)),
    (0.66, (228, 186, 100)),
    (0.84, (172, 120, 36)),
    (1.00, (132, 86, 28)),
]

ART = 1024      # 图形的设计画布
SS = 4          # 超采样倍数


def gold_image(w, h, angle=14.0, seed=7):
    w, h = max(int(w), 2), max(int(h), 2)
    xs = np.linspace(0.0, 1.0, w, dtype=np.float32)
    arr = np.zeros((h, w, 3), dtype=np.float32)
    for k in range(3):
        chan = np.zeros_like(xs)
        for i in range(len(GOLD_STOPS) - 1):
            p0, c0 = GOLD_STOPS[i]
            p1, c1 = GOLD_STOPS[i + 1]
            sel = (xs >= p0) & (xs <= p1)
            if not sel.any():
                continue
            t = (xs[sel] - p0) / max(p1 - p0, 1e-6)
            chan[sel] = c0[k] + (c1[k] - c0[k]) * t
        arr[:, :, k] = chan[None, :]
    rows = np.arange(h, dtype=np.float32)
    arr += ((np.sin(rows * 0.6) * 0.5 + 0.5) * 5.0 - 2.5)[:, None, None]
    rng = np.random.default_rng(seed)
    arr += rng.normal(0, 1.2, size=(h, w, 1)).astype(np.float32)
    yy, xx = np.mgrid[0:h, 0:w].astype(np.float32)
    band = (xx / w) * np.cos(np.deg2rad(angle)) + (yy / h) * np.sin(np.deg2rad(angle))
    arr += (np.exp(-((band - 0.60) ** 2) / (2 * 0.07 ** 2)) * 42)[:, :, None]
    return Image.fromarray(np.clip(arr, 0, 255).astype(np.uint8), "RGB")


class Art:
    """在 ART*SS 的画布上画三本书，坐标系按 1024 设计稿。"""

    def __init__(self, scale=SS):
        self.ss = scale
        self.S = ART * scale
        self.img = Image.new("RGBA", (self.S, self.S), (0, 0, 0, 0))
        self.d = ImageDraw.Draw(self.img)

    def px(self, v):
        return int(round(v * self.ss))

    def paste_rounded(self, piece, xy, radius):
        w, h = piece.size
        mask = Image.new("L", (w, h), 0)
        ImageDraw.Draw(mask).rounded_rectangle([0, 0, w - 1, h - 1], radius=radius, fill=255)
        self.img.paste(piece, xy, mask)

    def spine(self, x, y, w, h, color, gold, mono=False):
        r = self.px(6)
        if mono:
            self.d.rounded_rectangle(
                [self.px(x), self.px(y), self.px(x + w), self.px(y + h)],
                radius=r, fill=(255, 255, 255, 255),
            )
            return
        if gold == "full":
            self.paste_rounded(gold_image(self.px(w), self.px(h)), (self.px(x), self.px(y)), r)
            d = ImageDraw.Draw(self.img)
            for yy in (y + 18, y + h - 22):
                d.rounded_rectangle(
                    [self.px(x + 6), self.px(yy), self.px(x + w - 6), self.px(yy + 5)],
                    radius=self.px(2), fill=(96, 60, 20, 255),
                )
            return

        body = Image.new("RGBA", (self.px(w), self.px(h)), color + (255,))
        bd = ImageDraw.Draw(body)
        for i in range(0, int(w * 0.5)):
            a = i / (w * 0.5)
            light = 24 * (1 - a) ** 1.5
            bd.rectangle(
                [self.px(i), 0, self.px(i) + 1, self.px(h)],
                fill=tuple(min(255, int(c + light)) for c in color) + (255,),
            )
            bd.rectangle(
                [self.px(w - 1 - i), 0, self.px(w - i), self.px(h)],
                fill=tuple(max(0, int(c - 16 * a)) for c in color) + (255,),
            )
        self.paste_rounded(body, (self.px(x), self.px(y)), r)
        d = ImageDraw.Draw(self.img)

        cap = 20
        for cy in (y + 30, y + h - 30 - cap):
            self.paste_rounded(
                gold_image(self.px(w - 12), self.px(cap)).convert("RGBA"),
                (self.px(x + 6), self.px(cy)), self.px(3),
            )
        for cy in (y + 18, y + h - 22):
            d.rounded_rectangle(
                [self.px(x + 6), self.px(cy), self.px(x + w - 6), self.px(cy + 4)],
                radius=self.px(2), fill=(198, 160, 74, 255),
            )

    def sparkle(self, cx, cy, r, alpha=235):
        if alpha <= 0:
            return
        layer = Image.new("RGBA", (self.S, self.S), (0, 0, 0, 0))
        d = ImageDraw.Draw(layer)
        pts = [(cx, cy - r), (cx + r * 0.2, cy - r * 0.2), (cx + r, cy),
               (cx + r * 0.2, cy + r * 0.2), (cx, cy + r), (cx - r * 0.2, cy + r * 0.2),
               (cx - r, cy), (cx - r * 0.2, cy - r * 0.2)]
        d.polygon([(self.px(a), self.px(b)) for a, b in pts],
                  fill=(255, 244, 200, alpha))
        self.img = Image.alpha_composite(self.img, layer)
        self.d = ImageDraw.Draw(self.img)

    def shadow(self, x0, x1, y, color=SHADOW, alpha=70):
        sh = Image.new("L", (self.S, self.S), 0)
        ImageDraw.Draw(sh).rounded_rectangle(
            [self.px(x0), self.px(y - 2), self.px(x1), self.px(y + 18)],
            radius=self.px(12), fill=alpha,
        )
        sh = sh.filter(ImageFilter.GaussianBlur(self.px(9)))
        tint = Image.new("RGBA", (self.S, self.S), color + (255,))
        self.img.paste(tint, (0, 0), sh)
        self.d = ImageDraw.Draw(self.img)


def build_art(mono=False, with_shadow=True, sparkle=True):
    a = Art()
    if with_shadow and not mono:
        a.shadow(268, 790, 806)
    specs = [
        (272, 366, 128, 440, BROWN_DARK),
        (456, 252, 156, 554, BROWN),
        (656, 396, 122, 410, INK),
    ]
    for i, (x, y, w, h, color) in enumerate(specs):
        a.spine(x, y, w, h, color, "full" if i == 1 else "caps", mono=mono)
    if sparkle and not mono:
        a.sparkle(720, 300, 30)
        a.sparkle(830, 366, 16, 200)
        a.sparkle(246, 330, 14, 190)
    return a.img


def place(art, canvas_px, art_fraction):
    """把 1024 设计稿等比缩放后居中放进 canvas_px 的画布。"""
    size = int(round(canvas_px * art_fraction))
    scaled = art.resize((size, size), Image.LANCZOS)
    out = Image.new("RGBA", (canvas_px, canvas_px), (0, 0, 0, 0))
    off = (canvas_px - size) // 2
    out.paste(scaled, (off, off), scaled)
    return out


def rounded_bg(size, color=PAPER, radius_ratio=0.225):
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    ImageDraw.Draw(img).rounded_rectangle(
        [0, 0, size - 1, size - 1], radius=int(size * radius_ratio), fill=color + (255,)
    )
    return img


def circle_bg(size, color=PAPER):
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    ImageDraw.Draw(img).ellipse([0, 0, size - 1, size - 1], fill=color + (255,))
    return img


DENSITIES = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default="app/src/main/res")
    args = ap.parse_args()
    res = args.out

    art = build_art()
    mono_art = build_art(mono=True)

    # 自适应图标前景：108dp，图形占 66/108（安全区内）
    fg_fraction = 0.78
    for dens, mult in DENSITIES.items():
        px = int(round(108 * mult))
        out = place(art, px, fg_fraction)
        d = os.path.join(res, f"mipmap-{dens}")
        os.makedirs(d, exist_ok=True)
        out.save(os.path.join(d, "ic_launcher_foreground.png"))
        place(mono_art, px, fg_fraction).save(
            os.path.join(d, "ic_launcher_monochrome.png")
        )

    # 传统图标：48dp 圆角方 + 48dp 圆
    legacy_fraction = 0.84
    for dens, mult in DENSITIES.items():
        px = int(round(48 * mult))
        d = os.path.join(res, f"mipmap-{dens}")
        os.makedirs(d, exist_ok=True)
        for name, bg in (("ic_launcher", rounded_bg(px)), ("ic_launcher_round", circle_bg(px))):
            canvas = bg.copy()
            art_px = place(art, px, legacy_fraction)
            canvas.alpha_composite(art_px)
            canvas.save(os.path.join(d, f"{name}.png"))

    print("icons written to", os.path.abspath(res))


if __name__ == "__main__":
    main()
