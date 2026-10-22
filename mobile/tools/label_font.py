#!/usr/bin/env python3
"""
The Chinese label face holds only the characters the app's label strings use. Run this after changing any
Chinese string whose name starts with label_ (any file in androidApp/src/main/res/values-zh/) or is listed in EXTRA_NAMES.

    pip install fonttools
    python3 tools/label_font.py SmileySans-Oblique.ttf     # write res/font/dropit_label_zh.ttf
    python3 tools/label_font.py --check                    # exit 1 if a character is missing

The source is Smiley Sans v2.0.1 (https://github.com/atelier-anchor/smiley-sans/releases), under the SIL Open
Font License. "Smiley" and "得意黑" are reserved names, so the subset is renamed "dropit Label ZH"; its
licence is licenses/OFL-smiley-sans.txt.
"""
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
STRINGS_DIR = ROOT / "androidApp/src/main/res/values-zh"
# Page and group titles that are set in the label face but are not named label_*
EXTRA_NAMES = {"set_group_send", "set_repull", "set_default_to", "update_page_title", "plan_head"}
OUT = ROOT / "androidApp/src/main/res/font/dropit_label_zh.ttf"
FAMILY = "dropit Label ZH"


def label_chars():
    text = []
    for f in sorted(STRINGS_DIR.glob("*.xml")):
        for el in ET.parse(f).getroot():
            name = el.get("name", "")
            if name.startswith("label_") or name in EXTRA_NAMES:
                text.append("".join(el.itertext()))
    # Chinese and full-width characters; Latin, digits and ASCII punctuation come from the Latin face
    return sorted({c for c in "".join(text) if re.match(r"[　-鿿＀-￯]", c)})


def covered():
    from fontTools.ttLib import TTFont
    return set(TTFont(OUT).getBestCmap())


def main():
    chars = label_chars()
    if "--check" in sys.argv:
        missing = [c for c in chars if ord(c) not in covered()]
        if missing:
            print("✗ dropit_label_zh.ttf is missing: " + "".join(missing) + " — run tools/label_font.py <SmileySans-Oblique.ttf>")
            sys.exit(1)
        print(f"✅  label face covers all {len(chars)} characters")
        return
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    from fontTools import subset
    from fontTools.ttLib import TTFont
    font = TTFont(sys.argv[1])
    sub = subset.Subsetter(subset.Options(layout_features=["*"], name_IDs=[0, 1, 2, 3, 4, 5, 6], name_languages=[0x409]))
    sub.populate(text="".join(chars))
    sub.subset(font)
    name = font["name"]
    name.names = [n for n in name.names if n.platformID == 3 and n.nameID <= 6]
    for n in name.names:
        if n.nameID in (1, 4):
            n.string = FAMILY
        elif n.nameID == 3:
            n.string = "dropitLabelZH-android-subset"
        elif n.nameID == 6:
            n.string = "dropitLabelZH"
    font.save(OUT)
    print(f"wrote {OUT.relative_to(ROOT)}: {len(chars)} characters, {OUT.stat().st_size} bytes")


if __name__ == "__main__":
    main()
