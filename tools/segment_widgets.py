#!/usr/bin/env python3
"""Adds, next to every text of the widget layouts (res/layout/widget*.xml), an ImageView `<id>_seg` laid out like it
and hidden: SegmentViews shows the text drawn in the segment display font there (Android 9 widgets cannot use an app
font). Gives an id to the texts without one. Run again after changing a widget layout: the pictures are rebuilt."""
import glob
import os
import re
import sys

ROOT = sys.argv[1] if len(sys.argv) > 1 else os.path.join(os.path.dirname(__file__), "..")
TEXT = re.compile(r"(?P<indent>[ \t]*)<(?P<tag>TextView|TextClock|Chronometer|Button)\b(?P<attrs>.*?)/>", re.S)
SEG = re.compile(r"\n[ \t]*<ImageView\b[^>]*?android:id=\"@\+id/\w+_seg\"[^>]*?/>\n", re.S)
KEEP = re.compile(r"android:(layout_\w+|padding\w*|background|minWidth|minHeight)=\"[^\"]*\"")


def scale(attrs):
    """Shrunk to fit, never enlarged: centred, or at the start / end like the text when the height wraps it."""
    g = re.search(r"android:gravity=\"([^\"]*)\"", attrs)
    g = g.group(1) if g else ""
    wraps = "android:layout_height=\"wrap_content\"" in attrs
    if "center_horizontal" in g or "center" in g.split("|") or not wraps:
        return "centerInside"
    if "end" in g or "right" in g:
        return "fitEnd"
    return "fitStart"


def run(path):
    name = os.path.splitext(os.path.basename(path))[0]
    s = SEG.sub("\n", open(path).read())
    n = [0]

    def add(m):
        attrs = m.group("attrs")
        if "android:id" not in attrs:
            n[0] += 1
            attrs = "\n" + m.group("indent") + "    android:id=\"@+id/" + name + "_t" + str(n[0]) + "\"" + attrs
        tid = re.search(r"android:id=\"@\+id/(\w+)\"", attrs).group(1)
        ind = m.group("indent")
        keep = "".join("\n" + ind + "    " + k.group(0) for k in KEEP.finditer(attrs))
        image = (
            "\n\n" + ind + "<ImageView\n" + ind + "    android:id=\"@+id/" + tid + "_seg\"" + keep
            + "\n" + ind + "    android:adjustViewBounds=\"true\"\n" + ind + "    android:importantForAccessibility=\"no\""
            + "\n" + ind + "    android:scaleType=\"" + scale(attrs) + "\"\n" + ind + "    android:visibility=\"gone\" />"
        )
        return ind + "<" + m.group("tag") + attrs + "/>" + image

    s = TEXT.sub(add, s)
    s = re.sub(r"\n{3,}", "\n\n", s)
    open(path, "w").write(s)


for p in sorted(glob.glob(os.path.join(ROOT, "app/src/main/res/layout/widget*.xml"))):
    run(p)
