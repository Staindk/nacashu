# Usage: update_screenshots.py NEW_DIR DOCS_DIR
# Copies each PNG and GIF from NEW_DIR into DOCS_DIR when it's new or its pixels differ,
# so CI only commits screenshots that actually changed.
import shutil, sys
from pathlib import Path
from PIL import Image, ImageSequence


def frames(path):
    with Image.open(path) as image:
        return [(f.convert("RGBA").tobytes(), f.info.get("duration")) for f in ImageSequence.Iterator(image)]


new_dir, docs_dir = Path(sys.argv[1]), Path(sys.argv[2])
for new in sorted([*new_dir.glob("*.png"), *new_dir.glob("*.gif")]):
    old = docs_dir / new.name
    if old.exists() and frames(old) == frames(new):
        continue
    shutil.copyfile(new, old)
    print("updated", old)
