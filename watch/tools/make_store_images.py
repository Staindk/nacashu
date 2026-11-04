# Usage: make_store_images.py PIXEL_FONT SANS_FONT [OUT_DIR]
# Redraws the Google Play images in fastlane/metadata/android/en-US/images/ from the
# watchapp screenshots in docs/ (kept up to date by CI) and the 32x32 icon in docs/icon.png.
# Run it after the watchapp's look changes. Fonts aren't committed; use Silkscreen
# (https://fonts.google.com/specimen/Silkscreen) and any plain sans such as Inter.
# Phone app screenshots (4-*.png onwards) are real captures and are left alone.
# Also draws docs/header.png (top of the README) and docs/pebble-banner.png (720x320, for the
# Pebble appstore listing), and the appstore's icons: pebble-icon-80.png and pebble-icon-144.png
# (watchapp) and pebble-companion-icon-144.png (the Android companion, drawn inverted).
import sys
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[2]
DOCS = ROOT / "docs"
pixel_font, sans_font = sys.argv[1], sys.argv[2]
out = Path(sys.argv[3]) if len(sys.argv) > 3 else ROOT / "fastlane/metadata/android/en-US/images"

BLACK, WHITE, GREY, DARK = (0, 0, 0), (255, 255, 255), (85, 85, 85), (51, 51, 51)
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


def scene(w, h, cell):
    """A dithered camera viewfinder: bright sky fading down, a grey hill, dark ground."""
    gw, gh = w // cell, h // cell
    small = Image.new("RGB", (gw, gh), BLACK)
    px = small.load()
    hill_top = int(gh * 0.48)
    for y in range(gh):
        for x in range(gw):
            t = BAYER[y % 4][x % 4] / 16
            if y < gh * 0.45:
                level = 1 - y / (gh * 0.45)
                px[x, y] = WHITE if level > t else BLACK
            ground = gh * 0.7
            hx = (x - gw * 0.3) / (gw * 0.35)
            hill = hill_top + int(gh * 0.12 * hx * hx)
            if y >= hill and y < ground:
                px[x, y] = GREY
            if y >= ground:
                level = 0.5 - (y - ground) / (gh - ground) * 0.5
                px[x, y] = GREY if level > t else (25, 25, 25)
    return small.resize((gw * cell, gh * cell), Image.NEAREST)


def ink_left(text, f):
    """How far right of x the first lit pixel of text lands (the font's left side bearing)."""
    probe = Image.new("L", (int(f.getlength(text)) + 200, f.size * 2))
    ImageDraw.Draw(probe).text((100, 0), text, font=f, fill=255)
    return probe.getbbox()[0] - 100


def centred(d, y, text, f, w, fill=WHITE):
    x = (w - d.textlength(text, font=f)) / 2
    d.text((x, y), text, font=f, fill=fill)


def phone_screenshot(lines, shot, path, round_screen=False):
    W, H = 1080, 1920  # 9:16; Play rejects anything longer than 2:1
    img = Image.new("RGB", (W, H), BLACK)
    d = ImageDraw.Draw(img)
    big = font(pixel_font, 96)
    for i, line in enumerate(lines):
        centred(d, 110 + i * 120, line, big, W)
    # Phone running the camera app.
    px, py, pw, ph = 160, 390, 760, 1470
    d.rounded_rectangle((px, py, px + pw, py + ph), radius=70, fill=DARK)
    view = scene(pw - 60, ph - 60, 15)
    mask = Image.new("L", view.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, view.width - 1, view.height - 1), radius=45, fill=255)
    img.paste(view, (px + 30, py + 30), mask)
    cx, cy = px + pw // 2, py + ph - 170
    d.ellipse((cx - 90, cy - 90, cx + 90, cy + 90), fill=WHITE)
    d.ellipse((cx - 72, cy - 72, cx + 72, cy + 72), fill=BLACK)
    d.ellipse((cx - 62, cy - 62, cx + 62, cy + 62), fill=WHITE)
    # The watch, overlapping the phone's right edge.
    w = watch(screen(shot, 3), 3, round_screen)
    img.paste(w, (W - w.width - 10, py + ph - w.height - 300), w)
    img.save(path, optimize=True)


def pixel_watch(name, cell, height):
    """The Help screen's chunky watch (android .../WatchDiagram.kt): icon-style body, grey straps,
    four buttons, the screenshot in its window. Drawn in cells, then scaled to height."""
    shot = Image.open(DOCS / f"{name}.png").convert("RGB")
    gap, frame, button_w, button_h, strap_h = 4, 3, 2, 4, 4
    in_w, in_h = (shot.width + 2 * gap + cell - 1) // cell, (shot.height + 2 * gap + cell - 1) // cell
    body_w, body_h = in_w + 2 * frame, in_h + 2 * frame
    img = Image.new("RGBA", ((body_w + 2 * button_w) * cell, (body_h + 2 * strap_h) * cell), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)

    def cells(x, y, w, h, colour):
        d.rectangle((x * cell, y * cell, (x + w) * cell - 1, (y + h) * cell - 1), fill=colour)

    strap_w = body_w // 2
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
    scale = height / img.height
    return img.resize((round(img.width * scale), height), Image.NEAREST)


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
    w = pixel_watch("emery-1-ready", 8, 450)
    img.paste(w, (W - w.width - 56, 25), w)
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
phone_screenshot(["SNAP FROM", "YOUR WRIST"], "emery-2-snap", out / "phoneScreenshots/1-snap.png")
phone_screenshot(["TIMER AND", "BURSTS"], "emery-3-choices", out / "phoneScreenshots/2-timer.png")
phone_screenshot(["YOUR CAMERA'S", "OWN PHOTOS"], "gabbro-1-ready", out / "phoneScreenshots/3-processing.png", round_screen=True)
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
pebble_icon(144, DOCS / "pebble-companion-icon-144.png", invert=True)
centred_banner(720, 320, DOCS / "pebble-banner.png")
print("wrote", out, "and docs/header.png, docs/pebble-banner.png")
