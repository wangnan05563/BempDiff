# -*- coding: utf-8 -*-
"""Generate GitHub social preview (1280x640) for BempDiff from existing logo."""
from PIL import Image, ImageDraw, ImageFilter, ImageFont
import os

W, H = 1280, 640
# 路径由脚本位置推导（assets 目录），避免目录迁移后失效
_ASSETS = os.path.dirname(os.path.abspath(__file__))
LOGO = os.path.join(os.path.dirname(_ASSETS), "bempdiff-logo.png")
OUT = os.path.join(_ASSETS, "social-preview-1280x640.png")

BLUE_TOP = (43, 99, 236)      # #2B63EC
BLUE_BOT = (18, 41, 110)      # #12296E
GREEN = (16, 185, 129)        # #10B981
WHITE = (255, 255, 255)

def lerp(a, b, t):
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(len(a)))

def gradient_bg(w, h):
    im = Image.new("RGB", (w, h))
    px = im.load()
    for y in range(h):
        for x in range(0, w, 4):
            t = (x / w * 0.45) + (y / h * 0.55)
            c = lerp(BLUE_TOP, BLUE_BOT, t)
            for dx in range(4):
                if x + dx < w:
                    px[x + dx, y] = c
    return im

def font(path, size):
    return ImageFont.truetype(path, size)

def find_font(candidates):
    for p in candidates:
        if os.path.exists(p):
            return p
    raise FileNotFoundError(candidates)

F_BOLD_LATIN = find_font([
    "C:/Windows/Fonts/segoeuib.ttf",
    "C:/Windows/Fonts/arialbd.ttf",
])
F_SEMIBOLD_LATIN = find_font([
    "C:/Windows/Fonts/seguisb.ttf",
    "C:/Windows/Fonts/segoeuib.ttf",
    "C:/Windows/Fonts/arial.ttf",
])
F_CN_BOLD = find_font(["C:/Windows/Fonts/msyhbd.ttc", "C:/Windows/Fonts/msyh.ttc"])
F_CN = find_font(["C:/Windows/Fonts/msyh.ttc"])

bg = gradient_bg(W, H).convert("RGB")

# soft radial glow behind logo (screen-like lighten, no dark band)
glow = Image.new("L", (W, H), 0)
gd = ImageDraw.Draw(glow)
gd.ellipse([110, 90, 610, 590], fill=60)
glow = glow.filter(ImageFilter.GaussianBlur(110))
light = Image.new("RGB", (W, H), lerp(BLUE_TOP, (120, 165, 255), 0.35))
# lighten only: take max per pixel via mask threshold-free blend toward light color
bg = Image.blend(bg, light, 0.0)  # keep base
glow_rgb = Image.composite(light, bg, glow)
bg = Image.blend(bg, glow_rgb, 0.55)

draw = ImageDraw.Draw(bg)

# ---------- logo with soft shadow ----------
LOGO_SIZE = 400
lx, ly = 96, 120  # logo top-left
logo = Image.open(LOGO).convert("RGBA").resize((LOGO_SIZE, LOGO_SIZE), Image.LANCZOS)

# shadow (offset under the logo, not at canvas origin)
shadow = Image.new("RGBA", (W, H), (0, 0, 0, 0))
sh_alpha = logo.split()[3].point(lambda a: int(a * 0.45))
shadow.paste((0, 0, 0, 115), (lx + 10, ly + 14), sh_alpha)
shadow = shadow.filter(ImageFilter.GaussianBlur(18))
lx, ly = 96, 120  # logo top-left
bg.paste(Image.alpha_composite(bg.convert("RGBA"), shadow).convert("RGB"), (0, 0))
bg.paste(logo, (lx, ly), logo)
draw = ImageDraw.Draw(bg)

# ---------- text block ----------
tx = 560
# Title
title_font = font(F_BOLD_LATIN, 104)
title_y = 178
draw.text((tx, title_y), "BempDiff", font=title_font, fill=WHITE)
tw = draw.textlength("BempDiff", font=title_font)

# green accent bar under title
bar_y = title_y + 128
draw.rounded_rectangle([tx, bar_y, tx + 132, bar_y + 10], radius=5, fill=GREEN)

# tagline (Chinese)
tag_font = font(F_CN_BOLD, 46)
draw.text((tx, bar_y + 44), "软件构建包差异化对比工具", font=tag_font, fill=(232, 238, 250))

# feature line (keep within right margin: max x = 1216)
feat_font = font(F_CN, 28)
feat_text = "war / jar / 文件夹 · 反编译级 diff · AI 变更解读"
assert draw.textlength(feat_text, font=feat_font) <= 1216 - tx, "feature line overflows"
draw.text((tx, bar_y + 128), feat_text, font=feat_font, fill=(168, 184, 220))

# footer hint
foot_font = font(F_SEMIBOLD_LATIN, 26)
draw.text((tx, H - 72), "Java · Vue 3 · Electron", font=foot_font, fill=(140, 158, 205))

bg.save(OUT, "PNG", optimize=True)
print("saved:", OUT, bg.size)
