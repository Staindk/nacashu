# Usage: python3 make_name_images.py /path/to/Inter-Bold.otf
# Draws the app name turned on its side for the edge of the watch screen. The Pebble SDK
# can't draw rotated text, so the name ships as two small images. Needs Pillow.
# Inter is under the SIL Open Font License: https://github.com/rsms/inter
import os, sys
from PIL import Image, ImageDraw, ImageFont

NAME, SIZE = "Nacashu", 24
font = ImageFont.truetype(sys.argv[1], SIZE)
left, top, right, bottom = font.getbbox(NAME)
img = Image.new("L", (right - left + 2, bottom - top + 2), 0)
ImageDraw.Draw(img).text((1 - left, 1 - top), NAME, font=font, fill=255)
# Snap to Pebble's four greys: black, dark grey, light grey, white.
img = img.point(lambda v: round(v / 85) * 85)

out = os.path.join(os.path.dirname(__file__), "..", "resources", "images")
os.makedirs(out, exist_ok=True)
# Reads top to bottom on the left edge and bottom to top on the right, so it's upright when
# you tilt the watch towards you: left wrist with lefty off, right wrist with lefty on.
img.rotate(-90, expand=True).save(os.path.join(out, "name_down.png"), optimize=True)
img.rotate(90, expand=True).save(os.path.join(out, "name_up.png"), optimize=True)
