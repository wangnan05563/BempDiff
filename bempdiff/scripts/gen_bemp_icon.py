# -*- coding: utf-8 -*-
"""生成 BEMP 应用图标（PNG 多分辨率 + ICO 多分辨率）。

设计：扁平品牌蓝圆角方块背景，两个错位叠放的白色"文档/包"卡片（象征对比的两个版本），
中心绿色徽章内嵌左右双向箭头（象征 diff/比对）。

产物：
  bempdiff/bempdiff-logo.png                         # 高清母版（≥1024），electron-builder 的 build.win.icon 直接用（也供 logo 展示）
  bempdiff/assets/bempdiff.ico                       # 遗留：旧 jpackage --icon（已退役；assets/ 仍有效，仅留作参考）

依赖：Pillow（managed Python: pip install pillow）
运行：python bempdiff/scripts/gen_bemp_icon.py
"""
from PIL import Image, ImageDraw

ROOT = "D:/code/otherProjects/18_comparePakage/prototype"
ICO_PATH = ROOT + "/assets/bempdiff.ico"
PNG_PATH = ROOT + "/bempdiff-logo.png"

TOP = (37, 99, 235)       # #2563EB
BOT = (30, 64, 175)       # #1E40AF
CARD = (255, 255, 255)
CODE_LINE = (173, 196, 235, 255)
BADGE = (16, 185, 129, 255)   # #10B981


def lerp(a, b, t):
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3))


def draw_icon(s):
    """矢量式按尺寸独立绘制，保证各分辨率锐利。"""
    S = s
    base = Image.new('RGBA', (S, S), (0, 0, 0, 0))

    # 圆角渐变背景
    bg = Image.new('RGBA', (S, S), (0, 0, 0, 0))
    bd = ImageDraw.Draw(bg)
    for y in range(S):
        col = lerp(TOP, BOT, y / (S - 1))
        bd.line([0, y, S - 1, y], fill=(col[0], col[1], col[2], 255))
    mask = Image.new('L', (S, S), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, S - 1, S - 1], radius=int(S * 0.22), fill=255)
    bg.putalpha(mask)
    base.alpha_composite(bg)

    d = ImageDraw.Draw(base)
    card = S * 0.52
    cr = int(card * 0.12)
    lw = max(1, int(S / 64))
    aw = max(1, int(S / 48))

    ax0, ay0 = S * 0.13, S * 0.16
    ax1, ay1 = ax0 + card, ay0 + card
    d.rounded_rectangle([ax0, ay0, ax1, ay1], radius=cr, fill=CARD + (235,))
    bx0, by0 = S * 0.35, S * 0.31
    bx1, by1 = bx0 + card, by0 + card
    d.rounded_rectangle([bx0, by0, bx1, by1], radius=cr, fill=CARD + (252,))

    for (x0, y0, x1, y1) in [(ax0, ay0, ax1, ay1), (bx0, by0, bx1, by1)]:
        for i in range(1, 5):
            ly = y0 + card * (i / 5.0)
            d.line([x0 + card * 0.14, ly, x1 - card * 0.14, ly], fill=CODE_LINE, width=lw)

    cx, cy = S * 0.5, S * 0.5
    rr = S * 0.17
    d.ellipse([cx - rr, cy - rr, cx + rr, cy + rr], fill=BADGE)
    d.ellipse([cx - rr * 0.55, cy - rr * 0.62, cx + rr * 0.12, cy - rr * 0.05], fill=(255, 255, 255, 45))

    alen = rr * 0.58
    d.line([cx - alen, cy, cx + alen, cy], fill=(255, 255, 255, 255), width=aw)
    ah = rr * 0.30
    d.line([cx - alen, cy, cx - alen + ah, cy - ah], fill=(255, 255, 255, 255), width=aw)
    d.line([cx - alen, cy, cx - alen + ah, cy + ah], fill=(255, 255, 255, 255), width=aw)
    d.line([cx + alen, cy, cx + alen - ah, cy - ah], fill=(255, 255, 255, 255), width=aw)
    d.line([cx + alen, cy, cx + alen - ah, cy + ah], fill=(255, 255, 255, 255), width=aw)
    return base


SIZES = [16, 24, 32, 48, 64, 128, 256]
frames = {s: draw_icon(s) for s in SIZES}

# 高清母版（≥1024），供 electron-builder 生成多分辨率安装包图标，源越大越锐利
MASTER = 1024
frames[MASTER] = draw_icon(MASTER)
frames[MASTER].save(PNG_PATH, format='PNG')

# 遗留 ICO（旧 jpackage 链路，已退役；assets/ 仍有效，保留仅供参考）
frames[256].save(ICO_PATH, sizes=[(s, s) for s in SIZES])
print("PNG(高清母版 %dx%d) ->" % (MASTER, MASTER), PNG_PATH)
print("ICO ->", ICO_PATH, "entries:", len(SIZES))
