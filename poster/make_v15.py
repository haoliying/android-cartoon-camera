import re, os
from PIL import Image, ImageDraw, ImageFont
from fractions import Fraction

JAVA = '/data/android-cartoon-camera/app/src/main/java/com/qoder/cartooncamera/ScenePreset.java'
src = open(JAVA, encoding='utf-8').read()
body = src.split('ALL = {', 1)[1]

FOCUS = {'FOCUS_INFINITY': '∞', 'FOCUS_AUTO': '自动', 'FOCUS_MACRO': '微距', 'FOCUS_MANUAL': '手动'}

def shutter(ns):
    s = ns / 1e9
    if s >= 1:
        return ('%gs' % s)
    den = round(1 / s)
    return '1/%ds' % den

scenes = []
raw = []
pat = re.compile(r'new ScenePreset\("([^"]+)", "([^"]+)", (true|false), (\d+), ns\(([^)]+)\), (-?[\d.]+)f, (\d+), (true|false), CaptureParams\.(\w+), ([\d.]+)f\)')
for m in pat.finditer(body):
    name, hint, manual, iso, sec, ev, kel, wbauto, focus, diop = m.groups()
    sec = eval(sec, {'__builtins__': {}}, {})
    ns = sec * 1e9
    raw.append((m.start(), (name, int(iso), shutter(ns), float(ev), int(kel), FOCUS[focus], float(diop))))

auto_pat = re.compile(r'auto\("([^"]+)", "([^"]+)", (-?[\d.]+)f, (\d+)\)')
for m in auto_pat.finditer(body):
    name, hint, ev, kel = m.groups()
    raw.append((m.start(), (name, 200, '自动', float(ev), int(kel), '自动', 0.0)))
scenes = [v for _, v in sorted(raw)]

print(len(scenes), [s[0] for s in scenes])

W = 1320
BG = (15, 16, 22)
CARD = (26, 28, 38)
CARD2 = (32, 35, 47)
WHITE = (245, 246, 250)
GREY = (160, 166, 182)
ACCENT = (255, 59, 92)      # app record red
BLUE = (86, 156, 255)
GREEN = (60, 210, 150)
YELLOW = (255, 200, 80)

title_f = ImageFont.truetype('/usr/share/fonts/opentype/noto/NotoSansCJK-Bold.ttc', 40)
sub_f = ImageFont.truetype('/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc', 21)
mode_f = ImageFont.truetype('/usr/share/fonts/opentype/noto/NotoSansCJK-Bold.ttc', 27)
mode_sub_f = ImageFont.truetype('/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc', 17)
body_f = ImageFont.truetype('/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc', 15)
scene_f = ImageFont.truetype('/usr/share/fonts/opentype/noto/NotoSansCJK-Bold.ttc', 22)
param_f = ImageFont.truetype('/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc', 13)
label_f = ImageFont.truetype('/usr/share/fonts/opentype/noto/NotoSansCJK-Bold.ttc', 17)

H = 1372
img = Image.new('RGB', (W, H), BG)
d = ImageDraw.Draw(img)

# subtle background glow
glow = Image.new('RGB', (W, H), BG)
gd = ImageDraw.Draw(glow)
for i, r in enumerate(range(700, 0, -8)):
    a = int(18 * (1 - r / 700))
    gd.ellipse([W - 260 - r, -r + 120, W - 260 + r, 120 + r], fill=(BG[0] + a, BG[1] + a // 4, BG[2] + a))
img = Image.blend(img, glow, 0.55)
d = ImageDraw.Draw(img)

# header
d.ellipse([48, 54, 76, 82], fill=ACCENT)
d.text((92, 40), '拍照三模式 · 普通 / 专业 / 傻瓜', font=title_f, fill=WHITE)
d.text((94, 98), 'v1.5  拍照页可切换模式；傻瓜模式内置 25 个场景，选好场景就自动套用专业参数，不用手调', font=sub_f, fill=GREY)

def card(x, y, w, h, color=CARD, r=16):
    d.rounded_rectangle([x, y, x + w, y + h], radius=r, fill=color)

# ----- mode cards -----
mx, my, mw, mh, gap = 48, 150, 400, 196, 20
cards = [
    ('普通', BLUE, '全自动', [
        'AE / AWB / AF 全部自动',
        '按下快门即拍，零设置',
        '照片沿用当前卡通风格与强度',
        '小精灵按开关决定是否叠加',
    ]),
    ('专业', ACCENT, '手动控制', [
        'ISO 100–6400 / 快门 1/1000s–2s',
        '曝光补偿 ±2.0（0.1 步进）',
        '白平衡 2300–7900K 或自动',
        '对焦：自动 / ∞ / 微距 / 手动 10cm–∞',
    ]),
    ('傻瓜', GREEN, '25 场景直出', [
        '日出、日落、月亮、星空、夜景…',
        '一键选场景，自动匹配 ISO/快门/EV/WB',
        '风景自动对无穷远，花草自动微距',
        '不用懂参数，按下快门就是成片',
    ]),
]
for i, (name, color, tag, lines) in enumerate(cards):
    x = mx + i * (mw + gap)
    card(x, my, mw, mh)
    d.rounded_rectangle([x, my, x + mw, my + 5], radius=3, fill=color)
    d.text((x + 22, my + 24), name, font=mode_f, fill=color)
    tw = d.textlength(tag, font=label_f)
    d.rounded_rectangle([x + mw - tw - 40, my + 26, x + mw - 18, my + 56], radius=15, fill=(color[0] // 5 + 18, color[1] // 5 + 18, color[2] // 5 + 18))
    d.text((x + mw - tw - 29, my + 31), tag, font=label_f, fill=color)
    for j, ln in enumerate(lines):
        d.text((x + 22, my + 74 + j * 29), '· ' + ln, font=body_f, fill=(206, 210, 222))

# ----- scene grid -----
gy = 382
d.text((48, gy - 34), '傻瓜模式 · 25 个场景预设（选中即生效）', font=mode_f, fill=WHITE)
d.text((760, gy - 25), 'ISO · 快门 · EV · 白平衡 · 对焦', font=body_f, fill=GREY)

cols, rows = 5, 5
cx0, cy0 = 48, gy + 14
cw, ch, cgap = 232, 148, 14
for idx, (name, iso, sh, ev, kel, focus, diop) in enumerate(scenes[:25]):
    r, c = divmod(idx, cols)
    x = cx0 + c * (cw + cgap)
    y = cy0 + r * (ch + cgap)
    card(x, y, cw, ch, CARD2, 14)
    # scene number
    d.text((x + 16, y + 14), name, font=scene_f, fill=WHITE)
    n = '%02d' % (idx + 1)
    nw = d.textlength(n, font=param_f)
    d.text((x + cw - nw - 14, y + 20), n, font=param_f, fill=(90, 96, 112))
    evs = ('EV%+.1f' % ev) if abs(ev) > 0.001 else 'EV 0'
    focus_txt = focus if focus != '微距' else ('微距 %gcm' % round(100 / diop) if diop > 0 else '微距')
    lines = [
        ('ISO', ('自动' if sh == '自动' else str(iso)), '快门', sh),
        ('白平衡', ('自动' if name in ('人像', '逆光') else '%dK' % kel), '曝光', evs),
        ('对焦', focus_txt, '', ''),
    ]
    for j, (k1, v1, k2, v2) in enumerate(lines):
        yy = y + 54 + j * 26
        d.text((x + 16, yy), k1, font=param_f, fill=(120, 126, 142))
        d.text((x + 16 + 44, yy), v1, font=param_f, fill=(228, 232, 240))
        if k2:
            d.text((x + 118, yy), k2, font=param_f, fill=(120, 126, 142))
            d.text((x + 118 + 44, yy), v2, font=param_f, fill=(228, 232, 240))

# ----- footer -----
fy = cy0 + rows * ch + (rows - 1) * cgap + 20
card(48, fy, W - 96, H - fy - 26)
d.text((76, fy + 20), '小精灵是独立配置项', font=label_f, fill=YELLOW)
d.text((76, fy + 50), '关闭后，相机实时预览、录像、拍照、相册图片与视频处理都不再叠加小精灵；开启后才会出现。设置会记住，下次打开保持不变。',
       font=body_f, fill=(206, 210, 222))
d.text((76, fy + 84), '说明：手机实际可用范围取决于机型（手动 ISO/快门的上下限会自动按相机能力裁剪）；照片为相机原生 JPEG 再经同一套 3 Pass 着色器输出。',
       font=body_f, fill=(150, 156, 172))

out = '/data/android-cartoon-camera/preview_modes_v15.png'
img.save(out, optimize=True)
print('saved', out, img.size)
