# Usage: update_screenshots.py NEW_DIR DOCS_DIR
# Copies each PNG from NEW_DIR into DOCS_DIR when it's new or its pixels differ,
# so CI only commits screenshots that actually changed.
import shutil, sys
from pathlib import Path
from PIL import Image

new_dir, docs_dir = Path(sys.argv[1]), Path(sys.argv[2])
for new in sorted(new_dir.glob("*.png")):
    old = docs_dir / new.name
    if old.exists():
        a, b = Image.open(old).convert("RGBA"), Image.open(new).convert("RGBA")
        if a.size == b.size and a.tobytes() == b.tobytes():
            continue
    shutil.copyfile(new, old)
    print("updated", old)
