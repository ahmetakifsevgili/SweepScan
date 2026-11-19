"""Cut labelled barcode crops out of phone recordings, for the on-device decode benchmark.

    python tools/make_label_corpus.py <recordings folder> [out folder]

Every sampled frame (fNNNNN.png + fNNNNN.json) lists the detections in upright image
coordinates and the tracks the app followed. A crop gets its true value from

  * its own decode ("read" crops: the app read this frame), or
  * an overlapping track that was read in other frames ("unread" crops: the app located the
    barcode here but could not read it - the hard cases worth improving on).

Crops keep the decoders' padding (25 % across the bars, 60 % along them) and are written as
grayscale PNGs named  <value>__<read|unread>__<recording>_<frame>_<n>.png .
Default output: app/src/androidTest/assets/labels/
"""
import glob
import json
import os
import sys

from PIL import Image

MAX_READ_PER_VALUE = 3
MAX_UNREAD = 80


def iou(a, b):
    l, t = max(a[0], b[0]), max(a[1], b[1])
    r, bt = min(a[2], b[2]), min(a[3], b[3])
    if r <= l or bt <= t:
        return 0.0
    inter = (r - l) * (bt - t)
    return inter / ((a[2] - a[0]) * (a[3] - a[1]) + (b[2] - b[0]) * (b[3] - b[1]) - inter)


def main(src, out):
    os.makedirs(out, exist_ok=True)
    read_per_value = {}
    unread = 0
    written = 0
    for side in sorted(glob.glob(os.path.join(src, "**", "f*.json"), recursive=True)):
        png = side[:-5] + ".png"
        if not os.path.exists(png):
            continue
        meta = json.load(open(side, encoding="utf-8"))
        dets = meta.get("dets") or []
        if not dets:
            continue
        tracks = [r for r in meta.get("tr") or [] if r[0] >= 0 and r[5]]
        image = Image.open(png).convert("L").rotate(-meta.get("rot", 0), expand=True)
        rec = os.path.basename(os.path.dirname(side)).replace("sweepscan-", "")
        frame = os.path.basename(side)[1:-5]
        for n, d in enumerate(dets):
            b = d["b"]
            if d.get("v") and d.get("r", "OK") == "OK":
                value, kind = d["v"], "read"
                if read_per_value.get(value, 0) >= MAX_READ_PER_VALUE:
                    continue
                read_per_value[value] = read_per_value.get(value, 0) + 1
            elif d.get("r") == "LOCATED":
                match = max(tracks, key=lambda r: iou(b, r[1:5]), default=None)
                if match is None or iou(b, match[1:5]) < 0.3 or unread >= MAX_UNREAD:
                    continue
                value, kind = match[5], "unread"
                unread += 1
            else:
                continue
            if not value.isdigit():
                continue
            w, h = b[2] - b[0], b[3] - b[1]
            box = (max(0, b[0] - 0.25 * w), max(0, b[1] - 0.6 * h),
                   min(image.width, b[2] + 0.25 * w), min(image.height, b[3] + 0.6 * h))
            image.crop(tuple(int(v) for v in box)).save(
                os.path.join(out, f"{value}__{kind}__{rec}_{frame}_{n}.png"), optimize=True)
            written += 1
    print(f"{written} crops ({sum(read_per_value.values())} read, {unread} unread, "
          f"{len(read_per_value)} distinct values) -> {out}")


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)
    here = os.path.dirname(os.path.abspath(__file__))
    default = os.path.join(here, "..", "app", "src", "androidTest", "assets", "labels")
    main(sys.argv[1], sys.argv[2] if len(sys.argv) > 2 else default)
