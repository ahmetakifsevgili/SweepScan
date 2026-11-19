"""Printable QR location labels for the stock room ("which model is in which row and column").

    tools\\.venv\\Scripts\\python tools\\make_location_labels.py labels --rows 1-2 --cols 1-20 [--size 75]
    tools\\.venv\\Scripts\\python tools\\make_location_labels.py test

`labels` writes samples/labels/konum-etiketleri.pdf: one label per (row, column) position, A4, with
cut lines. `test` writes samples/labels/okuma-testi.pdf: the same code at 50, 75 and 100 mm, to find
out from how far the phone reads which size while sweeping.

The QR holds `LOC<depot><row><col>` (e.g. `LOCB307` = big depot, row 3, column 07): QR version 1,
error correction Q, a 4-module quiet zone, alphanumeric mode. The app (0.6.0+) also prints these
labels itself (Konum ara → Menü → Etiket bas); this script is the PC fallback.
Print at 100 % ("actual size"), not "fit to page", or the printed size is wrong.

Setup once:  python -m venv tools\\.venv  &&  tools\\.venv\\Scripts\\pip install segno pillow
"""
import argparse
import os
import sys

import segno
from PIL import Image, ImageDraw, ImageFont

DPI = 300
A4 = (2480, 3508)  # 210 x 297 mm at 300 dpi
MARGIN = 118       # 10 mm
FONT_BOLD = "C:/Windows/Fonts/arialbd.ttf"
FONT = "C:/Windows/Fonts/arial.ttf"
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "samples", "labels")


def mm(px: float) -> float:
    return px * 25.4 / DPI


def px(mm_: float) -> int:
    return round(mm_ * DPI / 25.4)


def payload(row: str, col: int, depot: str = "B") -> str:
    """Letters and digits only ("LOCB307"): a Bluetooth scanner on a Turkish keyboard layout mangles
    ':' and '-'. Same format as LocationCode.payload in the app."""
    return f"LOC{depot}{row}{col:02d}"


def qr_image(data: str, target_mm: float) -> Image.Image:
    """QR with a whole number of pixels per module, as close to target_mm (incl. quiet zone) as possible."""
    code = segno.make(data, error="q", micro=False, boost_error=False)
    modules = code.symbol_size(border=4)[0]
    scale = max(1, round(px(target_mm) / modules))
    img = Image.new("L", (modules * scale, modules * scale), 255)
    draw = ImageDraw.Draw(img)
    for y, row in enumerate(code.matrix_iter(border=4)):
        for x, dark in enumerate(row):
            if dark:
                draw.rectangle([x * scale, y * scale, (x + 1) * scale - 1, (y + 1) * scale - 1], fill=0)
    return img


def font(path: str, size_px: int) -> ImageFont.FreeTypeFont:
    return ImageFont.truetype(path, size_px)


def label(row: str, col: int, size_mm: float) -> Image.Image:
    qr = qr_image(payload(row, col), size_mm)
    big = font(FONT_BOLD, int(qr.width * 0.22))
    small = font(FONT, int(qr.width * 0.075))
    text = f"{row} - {col:02d}"
    caption = f"SATIR {row} · SÜTUN {col:02d}"
    w = qr.width + px(6)
    tb = big.getbbox(text)
    cb = small.getbbox(caption)
    h = qr.height + (tb[3] - tb[1]) + (cb[3] - cb[1]) + px(14)
    img = Image.new("L", (w, h), 255)
    img.paste(qr, ((w - qr.width) // 2, px(2)))
    d = ImageDraw.Draw(img)
    y = qr.height + px(4)
    d.text(((w - (tb[2] - tb[0])) // 2 - tb[0], y - tb[1]), text, font=big, fill=0)
    y += (tb[3] - tb[1]) + px(4)
    d.text(((w - (cb[2] - cb[0])) // 2 - cb[0], y - cb[1]), caption, font=small, fill=0)
    return img


def pages(items, cut_lines=True):
    """Lays labels out row by row on A4 pages; returns the pages."""
    out = []
    page = Image.new("L", A4, 255)
    d = ImageDraw.Draw(page)
    x, y, line_h = MARGIN, MARGIN, 0
    for img in items:
        if x + img.width > A4[0] - MARGIN:
            x, y, line_h = MARGIN, y + line_h + px(4), 0
        if y + img.height > A4[1] - MARGIN:
            out.append(page)
            page = Image.new("L", A4, 255)
            d = ImageDraw.Draw(page)
            x, y, line_h = MARGIN, MARGIN, 0
        page.paste(img, (x, y))
        if cut_lines:
            d.rectangle([x - px(1), y - px(1), x + img.width + px(1), y + img.height + px(1)], outline=170, width=2)
        x += img.width + px(4)
        line_h = max(line_h, img.height)
    out.append(page)
    return out


def save(pages_, name):
    os.makedirs(OUT, exist_ok=True)
    path = os.path.abspath(os.path.join(OUT, name))
    first, rest = pages_[0].convert("RGB"), [p.convert("RGB") for p in pages_[1:]]
    first.save(path, save_all=True, append_images=rest, resolution=DPI)
    return path


def parse_range(spec: str):
    out = []
    for part in spec.split(","):
        if "-" in part:
            a, b = part.split("-")
            out += list(range(int(a), int(b) + 1))
        else:
            out.append(int(part))
    return out


def main(argv):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    lab = sub.add_parser("labels", help="one label per row/column position")
    lab.add_argument("--rows", required=True, help="row numbers, e.g. 1-2 or 1,3")
    lab.add_argument("--cols", required=True, help="column numbers, e.g. 1-20")
    lab.add_argument("--size", type=float, default=75, help="QR side incl. quiet zone, mm (default 75)")
    sub.add_parser("test", help="50 / 75 / 100 mm reading-distance test sheet")
    a = ap.parse_args(argv)

    if a.cmd == "labels":
        items = [label(str(r), c, a.size) for r in parse_range(a.rows) for c in parse_range(a.cols)]
        path = save(pages(items), "konum-etiketleri.pdf")
        q = qr_image(payload("1", 1), a.size)
        print(f"{len(items)} labels, QR {mm(q.width):.1f} mm ({q.width // 29} px/module at {DPI} dpi) -> {path}")
    else:
        items = []
        for size in (50, 75, 100):
            items.append(label("T", size, size))
        path = save(pages(items), "okuma-testi.pdf")
        for size in (50, 75, 100):
            q = qr_image(payload("T", size), size)
            print(f"test {size} mm -> printed {mm(q.width):.1f} mm, code {payload('T', size)}")
        print("->", path)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
