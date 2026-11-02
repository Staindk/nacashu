# Usage: make_store_images.py PIXEL_FONT SANS_FONT [OUT_DIR]
# Redraws the Google Play images in fastlane/metadata/android/en-US/images/ from the
# watchapp screenshots in docs/ (kept up to date by CI) and the 32x32 icon in docs/icon.png.
# Run it after the watchapp's look changes. Fonts aren't committed; use Silkscreen
# (https://fonts.google.com/specimen/Silkscreen) and any plain sans such as Inter.
# Phone app screenshots (4-*.png onwards) are real captures and are left alone.
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


def feature_graphic(path):
    W, H = 1024, 500
    img = Image.new("RGB", (W, H), BLACK)
    d = ImageDraw.Draw(img)
    icon = Image.open(DOCS / "icon.png").convert("RGB").resize((128, 128), Image.NEAREST)
    img.paste(icon, (48, 48))
    d.text((64, 205), "NACASHU", font=font(pixel_font, 72), fill=WHITE)
    # "Native camera shutter", with the letters the name comes from underlined.
    f = font(sans_font, 34)
    x, y = 64, 296
    line_y = y + f.getmetrics()[0] + 6
    for text, underline in [("Na", 1), ("tive ", 0), ("ca", 1), ("mera ", 0), ("shu", 1), ("tter", 0)]:
        width = d.textlength(text, font=f)
        d.text((x, y), text, font=f, fill=WHITE)
        if underline:
            d.rectangle((x, line_y, x + width - 1, line_y + 3), fill=WHITE)
        x += width
    d.text((64, 350), "for Pebble watches", font=f, fill=(150, 150, 150))
    w = watch(screen("emery-1-ready", 2), 2, pad=8)
    img.paste(w, (W - w.width - 48, (H - w.height) // 2), w)
    img.save(path, optimize=True)


out.mkdir(parents=True, exist_ok=True)
(out / "phoneScreenshots").mkdir(exist_ok=True)
Image.open(DOCS / "icon.png").convert("RGB").resize((512, 512), Image.NEAREST).save(out / "icon.png", optimize=True)
feature_graphic(out / "featureGraphic.png")
phone_screenshot(["SNAP FROM", "YOUR WRIST"], "emery-2-snap", out / "phoneScreenshots/1-snap.png")
phone_screenshot(["TIMER AND", "BURSTS"], "emery-3-choices", out / "phoneScreenshots/2-timer.png")
phone_screenshot(["YOUR CAMERA'S", "OWN PHOTOS"], "gabbro-1-ready", out / "phoneScreenshots/3-processing.png", round_screen=True)
print("wrote", out)
