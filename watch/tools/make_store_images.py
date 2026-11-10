# Usage: make_store_images.py PIXEL_FONT SANS_FONT [OUT_DIR]
# Redraws the Google Play images in fastlane/metadata/android/en-US/images/ from the
# watchapp screenshots in docs/ (kept up to date by CI) and the 32x32 icon in docs/icon.png.
# Run it after the watchapp's look changes. Fonts aren't committed; use Silkscreen
# (https://fonts.google.com/specimen/Silkscreen) and any plain sans such as Inter.
# Phone app screenshots (4-*.png onwards) are real captures and are left alone.
# Also draws docs/header.png (top of the README) and docs/pebble-banner.png (720x320, for the
# Pebble appstore listing), and the appstore's icons: pebble-icon-80.png and pebble-icon-144.png
# (watchapp) and inverted-pebble-companion-icon-144.png (the Android companion, drawn inverted).
import math
import sys
from pathlib import Path
from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = Path(__file__).resolve().parents[2]
DOCS = ROOT / "docs"
pixel_font, sans_font = sys.argv[1], sys.argv[2]
out = Path(sys.argv[3]) if len(sys.argv) > 3 else ROOT / "fastlane/metadata/android/en-US/images"

BLACK, WHITE, GREY, DARK = (0, 0, 0), (255, 255, 255), (85, 85, 85), (51, 51, 51)
LIGHT = (170, 170, 170)
BAYER = [[0, 8, 2, 10], [12, 4, 14, 6], [3, 11, 1, 9], [15, 7, 13, 5]]


def font(path, size):
    return ImageFont.truetype(path, size)


def screen(name, scale):
    img = Image.open(DOCS / f"{name}.png").convert("RGB")
    return img.resize((img.width * scale, img.height * scale), Image.NEAREST)


def watch(img, scale, round_screen=False, pad=None):
    """The screenshot inside a plain dark bezel, like the watch seen head-on."""
    pad = 6 * scale if pad is None else pad
    w, h = img.width + 2 * pad, img.height + 2 * pad
    body = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    d = ImageDraw.Draw(body)
    if round_screen:
        d.ellipse((0, 0, w - 1, h - 1), fill=DARK)
        mask = Image.new("L", img.size, 0)
        ImageDraw.Draw(mask).ellipse((0, 0, img.width - 1, img.height - 1), fill=255)
        body.paste(img, (pad, pad), mask)
    else:
        d.rounded_rectangle((0, 0, w - 1, h - 1), radius=4 * scale, fill=DARK)
        body.paste(img, (pad, pad))
    return body


def family(w, h, cell):
    """A family of four as pictograms (round heads, A-line dresses, a cap, a bob, a bun), holding
    hands on a hill, with a big white sun behind them. Drawn on a grid of cell px squares."""
    gw, gh = w // cell, h // cell
    small = Image.new("RGB", (gw, gh), LIGHT)
    px, d = small.load(), ImageDraw.Draw(small)
    for y in range(36):  # dithered sky fading into the flat pale band behind the people
        for x in range(gw):
            px[x, y] = LIGHT if 0.05 + 0.95 * y / 36 > BAYER[y % 4][x % 4] / 16 else GREY

    def disc(cx, cy, r, colour=BLACK):
        d.ellipse((cx - r, cy - r, cx + r, cy + r), fill=colour)

    def box(x0, y0, x1, y1):
        d.rectangle((x0, y0, x1, y1), fill=BLACK)

    def arm(a, b):
        d.line((a[0], a[1], b[0], b[1]), fill=BLACK, width=2)

    def ground(x):
        return 94 + int(((x - 33) / 33) ** 2 * 5)

    disc(32, 60, 20, WHITE)
    for x in range(gw):
        d.line((x, ground(x), x, gh), fill=BLACK)

    def person(cx, kind, height):
        """Feet planted 3 cells into the hill. Returns the left and right shoulder points."""
        r, body, shoulder, hem = {"man": (5, 0.40, 6, 6), "woman": (5, 0.48, 3, 7),
                                  "boy": (4, 0.40, 4, 4), "girl": (3, 0.44, 2, 5)}[kind]
        feet = ground(cx) + 3
        head = feet - height + r
        top = head + r + 2  # a 1-cell gap between head and body, as on a sign
        legs = top + round(height * body)
        disc(cx, head, r)
        if kind == "boy":  # cap: flat crown, 2-cell brim, a gap under the brim
            box(cx - r, head - r - 1, cx + r, head - 2)
            box(cx, head - 3, cx + r + 3, head - 2)
            d.rectangle((cx + r + 1, head - 1, cx + r + 3, head), fill=LIGHT)
        if kind == "woman":  # bun
            d.ellipse((cx - 3, head - r - 4, cx + 1, head - r), fill=BLACK)
        if kind == "girl":  # bob
            d.rounded_rectangle((cx - r - 1, head - r + 1, cx + r + 1, head + r - 1), radius=2, fill=BLACK)
        if kind in ("man", "boy"):
            d.rounded_rectangle((cx - shoulder, top, cx + shoulder, legs), radius=2, fill=BLACK)
            box(cx - shoulder + 1, legs, cx - 2 + (kind == "boy"), feet)
            box(cx + 1 + (kind != "boy"), legs, cx + shoulder - 1, feet)
        else:
            for i in range(legs - top + 1):
                half = shoulder + (hem - shoulder) * i / (legs - top)
                box(round(cx - half), top + i, round(cx + half), top + i)
            if kind == "woman":
                box(cx - 3, legs, cx - 2, feet)
                box(cx + 2, legs, cx + 3, feet)
            else:
                box(cx - 2, legs, cx - 2, feet)
                box(cx + 2, legs, cx + 2, feet)
        return (cx - shoulder, top + 1), (cx + shoulder, top + 1)

    people = [person(11, "girl", 29), person(24, "man", 50), person(41, "woman", 46), person(57, "boy", 32)]
    for (_, right), (left, _) in zip(people, people[1:]):  # hand in hand
        hx, hy = (right[0] + left[0]) // 2, max(right[1], left[1]) + 9
        arm((right[0] + 1, right[1]), (hx, hy))
        arm((left[0] - 1, left[1]), (hx, hy))
        box(hx - 1, hy - 1, hx + 1, hy + 1)
    (x, y), _ = people[0]  # the free arms angle out from the shoulder
    arm((x - 1, y + 1), (x - 5, y + 7))
    box(x - 6, y + 7, x - 4, y + 9)
    _, (x, y) = people[-1]
    arm((x + 1, y + 1), (x + 4, y + 9))
    box(x + 3, y + 9, x + 5, y + 11)
    return small.resize((gw * cell, gh * cell), Image.NEAREST)


def couple(w, h, cell):
    """Two pictograms (a man and a woman, as in family()) holding hands and waving at the camera
    a few metres away, in front of a snowy mountain, the sun low on the left. Their shadows fall
    towards the camera; white focus brackets frame them. Drawn on a grid of cell px squares."""
    gw, gh = w // cell, h // cell
    small = Image.new("RGB", (gw, gh), LIGHT)
    px, d = small.load(), ImageDraw.Draw(small)
    horizon, feet = 80, 94

    def dith(x, y, level, hi, lo):
        px[x, y] = hi if level > BAYER[y % 4][x % 4] / 16 else lo

    def box(x0, y0, x1, y1, colour=BLACK, draw=d):
        draw.rectangle((min(x0, x1), min(y0, y1), max(x0, x1), max(y0, y1)), fill=colour)

    for y in range(gh):  # sky: sparse grey dots clearing to flat pale grey
        for x in range(gw):
            dith(x, y, 0.82 + 0.18 * y / (horizon - 30), LIGHT, GREY)
    sun_x, sun_y, sun_r = 12, 14, 6
    d.ellipse((sun_x - sun_r, sun_y - sun_r, sun_x + sun_r, sun_y + sun_r), fill=WHITE)
    for x in range(gw):  # far ridge
        for y in range(horizon - 12 - int(4 * math.sin(x / 6 + 1) + 3 * math.sin(x / 2.7)), horizon + 1):
            dith(x, y, 0.85, LIGHT, GREY)
    peak_x, peak_y, spread, snow = 38, 18, 44, 16  # the mountain: snow cap, lit left face, shaded right
    for x in range(gw):
        t = abs(x - peak_x) / spread
        if t > 1:
            continue
        jag = 1.5 * math.sin(x * 1.3 + 0.7) + math.sin(x * 2.9 + 1.4) if t > 0.08 else 0
        for y in range(max(0, int(peak_y + (horizon - peak_y) * t ** 1.2 + jag)), horizon + 1):
            lit = x <= peak_x + (y - peak_y) * 0.35
            if y < peak_y + snow + int(2 * math.sin(x * 1.1 + 0.7)):
                px[x, y] = WHITE if lit or BAYER[y % 4][x % 4] < 8 else LIGHT
            elif lit:
                dith(x, y, 0.62, LIGHT, GREY)
            else:
                dith(x, y, 0.55, GREY, DARK)
    for y in range(horizon + 1, gh):  # meadow, darkening towards the camera
        for x in range(gw):
            if y == horizon + 1:
                px[x, y] = GREY
            elif y < horizon + 22:
                dith(x, y, 0.8 - (y - horizon) / 45, LIGHT, GREY)
            else:
                dith(x, y, 0.5, GREY, DARK)

    man, woman, clasp_x = 20, 36, 28

    def people(draw):
        """Limbs are 2 cells thick."""
        def row(x, y, side):  # one 2-cell slice of a slanted arm
            box(x, y, x + side, y, draw=draw)

        def wave(x, y, side):  # upper arm up and out, then the forearm up beside the head
            elbow = (x + side * 3, y - 2)
            for a, b in ((((x, y) if side > 0 else (x - 1, y)), elbow), (elbow, (elbow[0] + side * 2, elbow[1] - 6))):
                n = max(abs(b[0] - a[0]), abs(b[1] - a[1]))
                for i in range(n + 1):
                    cx, cy = round(a[0] + (b[0] - a[0]) * i / n), round(a[1] + (b[1] - a[1]) * i / n)
                    box(cx, cy, cx + 1, cy + 1, draw=draw)

        # The man: round head, sloped shoulders, rectangle torso, two legs.
        head, top = feet - 27 + 3, feet - 27 + 8
        legs = top + round(27 * 0.42)
        draw.ellipse((man - 3, head - 3, man + 3, head + 3), fill=BLACK)
        box(man - 2, top, man + 2, top, draw=draw)
        box(man - 3, top + 1, man + 3, top + 1, draw=draw)
        box(man - 4, top + 2, man + 4, legs, draw=draw)
        box(man - 3, legs, man - 1, feet, draw=draw)
        box(man + 1, legs, man + 3, feet, draw=draw)
        wave(man - 5, top + 3, -1)
        # The woman: round head, A-line dress, two legs.
        w_head, w_top = feet - 25 + 3, feet - 25 + 8
        w_legs = w_top + round(25 * 0.5)
        draw.ellipse((woman - 3, w_head - 3, woman + 3, w_head + 3), fill=BLACK)
        rows = w_legs - w_top
        for i in range(rows + 1):
            half = 2 + 4 * i / rows
            box(round(woman - half), w_top + i, round(woman + half), w_top + i, draw=draw)
        box(woman - 3, w_legs, woman - 2, feet, draw=draw)
        box(woman + 2, w_legs, woman + 3, feet, draw=draw)
        wave(woman + 3, w_top + 1, 1)
        # Hand in hand: his arm runs straight from the shoulder to the clasp, hers follows her dress.
        clasp_y = legs - 1
        n = clasp_y - (top + 2)
        for i in range(n + 1):
            row(round(man + 5 + (clasp_x - man - 6) * i / n), top + 2 + i, 1)
        box(clasp_x - 1, clasp_y, clasp_x, clasp_y + 1, draw=draw)
        box(woman - 2, w_top, woman - 4, w_top + 1, draw=draw)
        for i in range(clasp_y - w_top + 2):
            x = round(woman - (4 + 4 * i / rows)); row(x, w_top + i, -1)
        box(x, clasp_y, clasp_x, clasp_y + 1, draw=draw)

    # A lighter halo around the pair keeps their arms readable against the dither.
    mask = Image.new("RGB", (gw, gh), WHITE)
    people(ImageDraw.Draw(mask))
    ink = mask.convert("L").point(lambda v: 255 if v < 128 else 0)
    halo = ink.filter(ImageFilter.MaxFilter(5)).load()
    for y in range(feet + 1):
        for x in range(gw):
            if halo[x, y] and px[x, y] != WHITE:
                dith(x, y, 0.88, LIGHT, GREY)
    for cx in (man, woman):  # long shadows, cast away from the sun: towards the camera and right
        for r in range(6):
            for x in range(cx - 3 + 2 * r, cx - 3 + 2 * r + max(2, 7 - r // 2)):
                px[x, feet + 1 + r] = GREY
    people(d)
    # Focus brackets around the pair, kept clear of the watch in the corner.
    x0, y0, x1, y1 = ink.getbbox()
    x0, y0, x1, y1 = max(1, x0 - 5), max(1, y0 - 5), min(gw - 2, x1 + 5), feet + 3
    right = x1 - 5
    while math.hypot(x1 - 60, y1 - 120) < 31 and x1 > right:
        x1 -= 1
    while math.hypot(x1 - 60, y1 - 120) < 31:
        y1 -= 1
    for cx, cy, sx, sy in ((x0, y0, 1, 1), (x1, y0, -1, 1), (x0, y1, 1, -1), (x1, y1, -1, -1)):
        box(cx, cy, cx + sx * 4, cy, WHITE)
        box(cx, cy, cx, cy + sy * 4, WHITE)
    return small.resize((gw * cell, gh * cell), Image.NEAREST)


def ink_left(text, f):
    """How far right of x the first lit pixel of text lands (the font's left side bearing)."""
    probe = Image.new("L", (int(f.getlength(text)) + 200, f.size * 2))
    ImageDraw.Draw(probe).text((100, 0), text, font=f, fill=255)
    return probe.getbbox()[0] - 100


def centred(d, y, text, f, w, fill=WHITE):
    x = (w - d.textlength(text, font=f)) / 2
    d.text((x, y), text, font=f, fill=fill)


def camera_phone(img, x, y, cell, view):
    """A phone running its camera app, its corner at x, y: view draws the 70x141 cell viewfinder on
    cell px squares, and the bezel, corners and shutter scale with it (cell 10 is 760x1470).
    Returns the shutter's centre and radius."""
    d = ImageDraw.Draw(img)
    pw, ph = 76 * cell, 147 * cell
    d.rounded_rectangle((x, y, x + pw, y + ph), radius=7 * cell, fill=DARK)
    view = view(70 * cell, 141 * cell, cell)
    mask = Image.new("L", view.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, view.width - 1, view.height - 1), radius=round(4.5 * cell), fill=255)
    img.paste(view, (x + 3 * cell, y + 3 * cell), mask)
    cx, cy, r = x + pw // 2, y + ph - 17 * cell, 9 * cell  # the shutter, centred as on every phone
    for radius, colour in ((r, WHITE), (round(7.2 * cell), BLACK), (round(6.2 * cell), WHITE)):
        d.ellipse((cx - radius, cy - radius, cx + radius, cy + radius), fill=colour)
    return cx, cy, r


def phone_screenshot(lines, shot, path, view, watch_scale, round_screen=False):
    """The camera phone with the watch in the bottom right corner, in front of the shutter.
    watch_scale may be fractional, so the square and round watches can look the same size."""
    W, H = 1080, 1920  # 9:16; Play rejects anything longer than 2:1
    img = Image.new("RGB", (W, H), BLACK)
    d = ImageDraw.Draw(img)
    big = font(pixel_font, 96)
    for i, line in enumerate(lines):
        centred(d, 110 + i * 120, line, big, W)
    camera_phone(img, 160, 390, 10, view)
    shot = screen(shot, 1)
    shot = shot.resize((round(shot.width * watch_scale), round(shot.height * watch_scale)), Image.NEAREST)
    w = watch(shot, round(watch_scale), round_screen, pad=round(6 * watch_scale))
    img.paste(w, (W - w.width - 20, H - w.height - 30), w)
    img.save(path, optimize=True)


def remote_shutter(path):
    """Screenshot 1: the Help screen's chunky watch, its face just a big shutter, with a dotted line
    from its SELECT button (which fires the shutter) to the camera phone's shutter, on a grid of dots."""
    W, H = 1080, 1920
    img = Image.new("RGB", (W, H), BLACK)
    d = ImageDraw.Draw(img)
    for y in range(20, H, 40):  # a sparse grid of dark dots, like a pixel screen
        for x in range(20, W, 40):
            d.rectangle((x, y, x + 7, y + 7), fill=DARK)
    big = font(pixel_font, 78)
    centred(d, 100, "YOUR PHONE'S", big, W)
    centred(d, 200, "REMOTE SHUTTER", big, W)
    centred(d, 315, "FOR PEBBLE WATCHES", font(pixel_font, 50), W, fill=LIGHT)  # less prominent, per Pebble's trademark rules
    cell = 9
    cx, cy, r = camera_phone(img, 24, H - 147 * cell - 30, cell, couple)
    # The watch in front of the phone, at 1.5x the Help screen's size: 15 px cells around a
    # 300x342 face (emery at 1.5x) that shows only the shutter.
    face = Image.new("RGB", (300, 342), BLACK)
    fd = ImageDraw.Draw(face)
    for radius, colour in ((99, WHITE), (84, BLACK), (74, WHITE)):
        fd.ellipse((150 - radius, 171 - radius, 150 + radius, 171 + radius), fill=colour)
    w = pixelated_watch(face, 15, gap=6)
    wx, wy = W - w.width - 100, 410
    img.paste(w, (wx, wy), w)
    bx, by = wx + w.width, wy + w.height // 2  # SELECT, the right middle button, being pressed
    for a in (-40, 0, 40):  # click marks, fanned evenly round the button
        t = math.radians(a)
        d.line((bx + 26 * math.cos(t), by + 26 * math.sin(t), bx + 70 * math.cos(t), by + 70 * math.sin(t)),
               fill=WHITE, width=11)
    # Square dots along a curve from the button, down the right edge, to the phone's shutter.
    (x0, y0), (x1, y1), (x2, y2) = (bx + 14, by + 40), (W - 30, cy - 160), (cx + r + 78, cy)
    n = int((math.hypot(x1 - x0, y1 - y0) + math.hypot(x2 - x1, y2 - y1)) / 30)
    for i in range(1, n):
        t = i / n
        x = (1 - t) ** 2 * x0 + 2 * (1 - t) * t * x1 + t * t * x2
        y = (1 - t) ** 2 * y0 + 2 * (1 - t) * t * y1 + t * t * y2
        d.rectangle((x - 7, y - 7, x + 7, y + 7), fill=WHITE)
    for ring in (1, 2):  # the shutter going off: two dotted rings
        radius = r + 30 * ring
        count = int(2 * math.pi * radius / 26)
        for i in range(count):
            a = 2 * math.pi * i / count
            x, y = cx + radius * math.cos(a), cy + radius * math.sin(a)
            d.rectangle((x - 5, y - 5, x + 5, y + 5), fill=WHITE)
    img.save(path, optimize=True)


def pixelated_watch(shot, cell, gap=4, strap_h=4):
    """The Help screen's chunky watch (android .../WatchDiagram.kt): icon-style body, grey straps,
    four buttons, the shot (a face image) in its window with gap px around it. Cells are cell px."""
    frame, button_w, button_h = 3, 2, 4
    in_w, in_h = (shot.width + 2 * gap + cell - 1) // cell, (shot.height + 2 * gap + cell - 1) // cell
    body_w, body_h = in_w + 2 * frame, in_h + 2 * frame
    img = Image.new("RGBA", ((body_w + 2 * button_w) * cell, (body_h + 2 * strap_h) * cell), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)

    def cells(x, y, w, h, colour):
        d.rectangle((x * cell, y * cell, (x + w) * cell - 1, (y + h) * cell - 1), fill=colour)

    strap_w = body_w // 2
    if strap_h:
        cells(button_w + (body_w - strap_w) // 2, 0, strap_w, strap_h, GREY)
        cells(button_w + (body_w - strap_w) // 2, strap_h + body_h, strap_w, strap_h, GREY)
    cells(button_w + 2, strap_h, body_w - 4, body_h, WHITE)
    cells(button_w + 1, strap_h + 1, body_w - 2, body_h - 2, WHITE)
    cells(button_w, strap_h + 2, body_w, body_h - 4, WHITE)
    cells(button_w + frame, strap_h + frame, in_w, in_h, BLACK)
    img.paste(shot, ((button_w + frame) * cell + (in_w * cell - shot.width) // 2,
                     (strap_h + frame) * cell + (in_h * cell - shot.height) // 2))
    middle, step = strap_h * 2 + body_h, body_h // 4
    for left, offset in [(False, -1), (False, 0), (False, 1), (True, -1)]:
        cells(0 if left else button_w + body_w, (middle - button_h) // 2 + offset * step, button_w, button_h, WHITE)
    return img


def dither(w, h, cell, top, bottom, light=GREY):
    """Ordered (Bayer) dither from top to bottom, black to light, in square cells."""
    small = Image.new("RGB", (w // cell + 1, h // cell + 1), BLACK)
    px = small.load()
    for y in range(small.height):
        level = top + (bottom - top) * y / small.height
        for x in range(small.width):
            if level > BAYER[y % 4][x % 4] / 16:
                px[x, y] = light
    return small.resize((small.width * cell, small.height * cell), Image.NEAREST).crop((0, 0, w, h))


def name_block(d, left, top, title_size, line_size):
    """NACASHU, then "Native camera shutter" with Na/ca/shu underlined, then "for Pebble watches".
    All three line up on left by ink, not by text origin."""
    title, f = font(pixel_font, title_size), font(sans_font, line_size)
    d.text((left - ink_left("NACASHU", title), top), "NACASHU", font=title, fill=WHITE)
    x, y = left - ink_left("N", f), top + title_size * 95 // 72
    line_y = y + f.getmetrics()[0] + 2
    for text, underline in [("Na", 1), ("tive ", 0), ("ca", 1), ("mera ", 0), ("shu", 1), ("tter", 0)]:
        width = d.textlength(text, font=f)
        d.text((x, y), text, font=f, fill=WHITE)
        if underline:
            d.rectangle((x, line_y, x + width - 1, line_y + max(2, line_size // 9)), fill=(150, 150, 150))
        x += width
    d.text((left - ink_left("f", f), y + line_size * 54 // 34), "for Pebble watches", font=f, fill=(150, 150, 150))


def feature_graphic(path):
    """Play's 1024x500 banner: name on the left, the pixel watch on the right, a dithered ground."""
    W, H = 1024, 500
    img = Image.new("RGB", (W, H), BLACK)
    img.paste(dither(W, 200, 8, 0.0, 0.7), (0, 300))
    name_block(ImageDraw.Draw(img), 64, 110, 72, 34)
    # Same proportions as the Help screen (10 px cells, screenshot at 1x). A 2x screenshot needs a
    # watch taller than the banner, and any in-between size scales the watch screen unevenly.
    w = pixelated_watch(screen("emery-1-ready", 1), 10)
    img.paste(w, (W - w.width - 64, (H - w.height) // 2), w)
    img.save(path, optimize=True)


def centred_banner(W, H, path):
    """Centred name on a black plate over a dithered sky: README header and Pebble appstore banner."""
    img = dither(W, H, 8, 0.85, 0.0)
    d = ImageDraw.Draw(img)
    title_size = H * 96 // 500
    line_size = title_size * 34 // 96
    # Measure the block to centre it and size its plate.
    probe = Image.new("RGB", (W, H))
    name_block(ImageDraw.Draw(probe), 0, 0, title_size, line_size)
    bw, bh = probe.getbbox()[2], probe.getbbox()[3]
    left, top = (W - bw) // 2, (H - bh) // 2
    pad = line_size
    d.rectangle((left - pad, top - pad, left + bw + pad, top + bh + pad), fill=BLACK)
    name_block(d, left, top, title_size, line_size)
    img.save(path, optimize=True)


out.mkdir(parents=True, exist_ok=True)
(out / "phoneScreenshots").mkdir(exist_ok=True)
Image.open(DOCS / "icon.png").convert("RGB").resize((512, 512), Image.NEAREST).save(out / "icon.png", optimize=True)
feature_graphic(out / "featureGraphic.png")
remote_shutter(out / "phoneScreenshots/1-remote-shutter.png")
phone_screenshot(["SNAP FROM", "YOUR WRIST"], "emery-2-snap", out / "phoneScreenshots/2-snap.png", family, 2.25)
phone_screenshot(["YOUR PHONE'S", "NATIVE CAMERA"], "gabbro-1-ready", out / "phoneScreenshots/3-processing.png", couple, 1.85,
                 round_screen=True)
def pebble_icon(size, path, invert=False):
    """The 32x32 icon at the biggest whole-number scale that fits, centred on a square of size.
    invert swaps black and white (the grey straps stay grey)."""
    icon = Image.open(DOCS / "icon.png").convert("RGB")
    if invert:
        icon = Image.eval(icon, lambda v: 255 - v if v in (0, 255) else v)
    scale = size // icon.width
    img = Image.new("RGB", (size, size), WHITE if invert else BLACK)
    big = icon.resize((icon.width * scale, icon.height * scale), Image.NEAREST)
    img.paste(big, ((size - big.width) // 2, (size - big.height) // 2))
    img.save(path, optimize=True)


centred_banner(1280, 400, DOCS / "header.png")
pebble_icon(80, DOCS / "pebble-icon-80.png")
pebble_icon(144, DOCS / "pebble-icon-144.png")
pebble_icon(144, DOCS / "inverted-pebble-companion-icon-144.png", invert=True)
centred_banner(720, 320, DOCS / "pebble-banner.png")
print("wrote", out, "and docs/header.png, docs/pebble-banner.png")
