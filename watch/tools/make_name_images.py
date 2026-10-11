# Usage: python3 make_name_images.py /path/to/Silkscreen-Bold.ttf
# Draws the app name as a white spine along the screen edge, one size per screen. The Pebble
# SDK can't draw rotated text, so the name ships as small images. Needs Pillow.
# Silkscreen is under the SIL Open Font License: https://fonts.google.com/specimen/Silkscreen
#
# Nothing runs this automatically: the build and CI use the PNGs committed in resources/images.
# After changing this script, run it by hand and commit the regenerated images with it.
import os, sys
from PIL import Image, ImageDraw, ImageFont

NAME = "NACASHU"
# Screen: (screen height, spine width, pixel scale, letter gap, text offset from the inner edge).
# A scale of (2, 3) makes each font pixel 2 wide along the spine and 3 tall across it.
# On the round screen the spine runs past the curve, so the offset sets how far out the text sits.
SCREENS = {
    "large": (228, 28, (3, 4), 4, None),
    "small": (168, 22, (2, 3), 4, None),
    "round": (260, 46, (2, 4), 4, 5),
}

font = ImageFont.truetype(sys.argv[1], 8)  # Silkscreen's native pixel size
letters = []
for ch in NAME:
    img = Image.new("1", (16, 16), 0)
    ImageDraw.Draw(img).text((2, 2), ch, font=font, fill=1)
    letters.append(img.crop(img.getbbox()))

out = os.path.join(os.path.dirname(__file__), "..", "resources", "images")
os.makedirs(out, exist_ok=True)
for screen, (length, width, scale, gap, offset) in SCREENS.items():
    h = max(l.height for l in letters)
    text = Image.new("1", (sum(l.width for l in letters) + gap * (len(letters) - 1), h), 0)
    x = 0
    for l in letters:
        text.paste(l, (x, h - l.height))
        x += l.width + gap
    sx, sy = scale
    text = text.resize((text.width * sx, text.height * sy), Image.NEAREST)
    spine = Image.new("1", (length, width), 1)
    y = (width - text.height) // 2 if offset is None else offset
    spine.paste(0, ((length - text.width) // 2, y), text)
    # Reads top to bottom on the left edge and bottom to top on the right, so it's upright when
    # you tilt the watch towards you: left wrist with lefty off, right wrist with lefty on.
    spine.rotate(-90, expand=True).save(os.path.join(out, f"name_down_{screen}.png"))
    spine.rotate(90, expand=True).save(os.path.join(out, f"name_up_{screen}.png"))
