"""Draws a wall of stacked shoe boxes for testing SweepScan on the Android emulator.

The emulator's virtual-scene camera can show an image on a 2 m x 2 m wall of its 3D room. This
script renders stacks of boxes the way they stand in the stock room: dark box, lime lid, a white
label on the right of the short face with model code, size, colour and a low 13-digit Code 128.
Stacks are slightly out of line, as in real life. It also writes the ground truth (which box is
where, which barcode it carries, whether its label can be read at all), so every recording made
on the scene has a known correct count. See docs/emulator-test.md.

    python tools/make_scene.py koridor out_dir      # ordinary delivery
    python tools/make_scene.py zor out_dir          # blurred, torn, tilted and missing labels
    python tools/make_scene.py ikiz out_dir         # worst case for identity: runs of one barcode

Writes <scene>.png, <scene>.json (ground truth) and <scene>-preview.jpg.
"""
import argparse
import json
import random
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter, ImageFont

# Code 128 bar/space widths for symbol values 0..105, then the stop pattern.
PATTERNS = (
    "212222 222122 222221 121223 121322 131222 122213 122312 132212 221213 221312 231212 112232 "
    "122132 122231 113222 123122 123221 223211 221132 221231 213212 223112 312131 311222 321122 "
    "321221 312212 322112 322211 212123 212321 232121 111323 131123 131321 112313 132113 132311 "
    "211313 231113 231311 112133 112331 132131 113123 113321 133121 313121 211331 231131 213113 "
    "213311 213131 311123 311321 331121 312113 312311 332111 314111 221411 431111 111224 111422 "
    "121124 121421 141122 141221 112214 112412 122114 122411 142112 142211 241211 221114 413111 "
    "241112 134111 111242 121142 121241 114212 124112 124211 411212 421112 421211 212141 214121 "
    "412121 111143 111341 131141 114113 114311 411113 411311 113141 114131 311141 411131 211412 "
    "211214 211232"
).split()
STOP = "2331112"
START_C, CODE_B = 105, 100

POSTER_M = 2.0  # side of the emulator's wall poster, metres
CHARCOAL = (44, 45, 49)
LIME = (203, 222, 44)
COLOURS = ["SİYAH", "TABA", "BEYAZ", "LACİVERT", "SEDEF", "HAKİ", "KAHVE", "GRİ"]


def gs1_13(first12: str) -> str:
    """Appends the GS1 check digit to 12 digits."""
    total = sum(int(c) * (3 if i % 2 else 1) for i, c in enumerate(first12))
    return first12 + str((10 - total % 10) % 10)


def code128_symbols(digits: str) -> list[int]:
    """Set C for digit pairs; an odd last digit is sent in set B (how label printers do it)."""
    symbols = [START_C]
    pairs = len(digits) // 2
    symbols += [int(digits[2 * i:2 * i + 2]) for i in range(pairs)]
    if len(digits) % 2:
        symbols += [CODE_B, ord(digits[-1]) - 32]
    checksum = (symbols[0] + sum(i * v for i, v in enumerate(symbols[1:], start=1))) % 103
    return symbols + [checksum]


def modules(digits: str) -> list[int]:
    """Bar (1) / space (0) per module, without quiet zones."""
    out = []
    for pattern in [PATTERNS[s] for s in code128_symbols(digits)] + [STOP]:
        for i, width in enumerate(pattern):
            out += [1 - i % 2] * int(width)
    return out


def font(size: int, bold: bool = False):
    try:
        return ImageFont.truetype("arialbd.ttf" if bold else "arial.ttf", size)
    except OSError:
        return ImageFont.load_default()


class Product:
    def __init__(self, rng: random.Random, digits: int = 13):
        self.model = "%04d %d" % (rng.randint(17, 399), rng.randint(70, 9999))
        self.colour = rng.choice(COLOURS)
        self.size = rng.randint(36, 45)
        body = "".join(rng.choice("0123456789") for _ in range(12))
        self.barcode = gs1_13(body) if digits == 13 else body[:8]


def draw_label(product: Product, w: int, module: int, damage: str | None) -> Image.Image:
    """The white sticker: model code, size box, colour, barcode, number, origin line."""
    h = int(w * 0.50)
    label = Image.new("RGB", (w, h), (250, 250, 248))
    d = ImageDraw.Draw(label)
    d.rectangle([0, 0, w - 1, h - 1], outline=(120, 120, 120), width=max(1, w // 300))
    d.text((int(w * 0.05), int(h * 0.07)), product.model, fill="black", font=font(int(h * 0.15), bold=True))
    sx = int(w * 0.76)
    d.rectangle([sx, int(h * 0.06), int(w * 0.95), int(h * 0.28)], outline="black", width=max(2, w // 200))
    d.text((sx + int(w * 0.035), int(h * 0.075)), str(product.size), fill="black", font=font(int(h * 0.16), bold=True))
    d.text((int(w * 0.05), int(h * 0.31)), product.colour, fill="black", font=font(int(h * 0.11)))

    bars = modules(product.barcode)
    bw = len(bars) * module
    bx = (w - bw) // 2
    by = int(h * 0.47)
    bh = int(bw * 0.17)  # the real labels carry a low barcode, about 6:1
    for i, bit in enumerate(bars):
        if bit:
            d.rectangle([bx + i * module, by, bx + (i + 1) * module - 1, by + bh], fill="black")
    number_font = font(int(h * 0.085))
    d.text((w // 2, by + bh + int(h * 0.015)), product.barcode, fill="black", font=number_font, anchor="ma")
    d.text((int(w * 0.05), int(h * 0.87)), "ÜRETİM YERİ: TÜRKİYE", fill=(40, 40, 40), font=font(int(h * 0.07)))

    region = (bx - 4 * module, by - 2, bx + bw + 4 * module, by + bh + 2)
    if damage == "blur":
        # Smeared across the bars, as from a shaky print or a scuffed label: found, not decodable.
        label.paste(label.crop(region).filter(ImageFilter.GaussianBlur(module * 2.2)), region)
    elif damage == "torn":
        d.polygon([(bx + int(bw * 0.52), by - 6), (region[2] + 6, by - 6), (region[2] + 6, by + bh + 8),
                   (bx + int(bw * 0.62), by + bh + 8)], fill=CHARCOAL)
    return label


def draw_face(product: Product | None, w: int, h: int, module: int, rng: random.Random, damage: str | None,
              label_share: float) -> tuple[Image.Image, tuple[int, int, int, int] | None]:
    """One box end. Returns the image and the label rectangle inside it (None for a box with no label)."""
    face = Image.new("RGB", (w, h), CHARCOAL)
    d = ImageDraw.Draw(face)
    lid = int(h * 0.24)
    d.rectangle([0, 0, w, lid], fill=LIME)
    d.line([0, lid, w, lid], fill=(20, 20, 20), width=max(2, h // 120))
    d.rectangle([0, 0, w - 1, h - 1], outline=(15, 15, 15), width=max(2, h // 150))

    if product is None or damage == "nolabel":
        # A box turned sideways: the long side shows the striped lid and no label.
        stripe = max(8, w // 14)
        for x in range(-h, w, stripe * 2):
            d.polygon([(x, 0), (x + stripe, 0), (x + stripe + lid, lid), (x + lid, lid)], fill=CHARCOAL)
        d.text((int(w * 0.36), int(h * 0.48)), "derinet", fill=LIME, font=font(int(h * 0.20), bold=True))
        return face, None

    d.text((int(w * 0.06), int(h * 0.50)), "derinet", fill=LIME, font=font(int(h * 0.19), bold=True))
    lw = int(w * label_share)
    label = draw_label(product, lw, module, damage)
    angle = rng.uniform(4.0, 7.0) * rng.choice([-1, 1]) if damage == "tilt" else rng.uniform(-0.8, 0.8)
    label = label.rotate(angle, resample=Image.BICUBIC, expand=True, fillcolor=CHARCOAL)
    lx = w - label.width - int(w * 0.03)
    ly = lid + (h - lid - label.height) // 2
    face.paste(label, (lx, ly))
    if damage == "glare":
        shine = Image.new("RGB", face.size, (255, 255, 255))
        mask = Image.new("L", face.size, 0)
        ImageDraw.Draw(mask).polygon([(int(w * 0.45), 0), (int(w * 0.62), 0), (int(w * 0.92), h), (int(w * 0.75), h)], fill=95)
        face.paste(shine, (0, 0), mask.filter(ImageFilter.GaussianBlur(w // 40)))
    return face, (lx, ly, lx + label.width, ly + label.height)


def layout(scene: str, cols: int, rows: int, rng: random.Random) -> list[list[tuple[Product | None, str | None]]]:
    """grid[col][row] = (product, damage). Row 0 is the top box of a stack."""
    def fresh(n: int, digits: int = 13) -> list[Product]:
        return [Product(rng, digits) for _ in range(n)]

    grid: list[list[tuple[Product | None, str | None]]] = [[(None, None)] * rows for _ in range(cols)]
    cells = [(c, r) for c in range(cols) for r in range(rows)]

    if scene == "ikiz":
        a, b, c, d2, e = fresh(5)
        for r in range(rows):
            grid[0][r] = (a, None)                                   # a whole stack of one barcode
            grid[1][r] = (b if r % 2 == 0 else c, None)              # two barcodes alternating
            grid[2][r] = (c if r % 2 == 0 else b, None)              # ... and checkerboarded next to it
        pairs = fresh(rows // 2)
        for r in range(rows):
            grid[3][r] = (pairs[r // 2], None)                       # twins in pairs: AA BB CC DD
        rest = fresh(rows)
        for col in range(4, cols):
            for r in range(rows):
                grid[col][r] = (rest[r] if col == 4 else rng.choice([d2, e, a]), None)
        return grid

    # koridor / zor: about a third of the boxes share a barcode with another box.
    products: list[Product] = []
    order = cells[:]
    while len(products) < len(cells):
        p = Product(rng, 8 if len(products) in (9, 23) else 13)
        products += [p] * rng.choice([1, 1, 1, 1, 2, 2, 3])
    products = products[:len(cells)]
    # Most twins stand together (stacked or side by side); a few are moved elsewhere.
    for _ in range(4):
        i, j = rng.randrange(len(order)), rng.randrange(len(order))
        products[i], products[j] = products[j], products[i]
    for (col, row), p in zip(order, products):
        grid[col][row] = (p, None)

    if scene == "zor":
        picks = rng.sample(cells, 11)
        damages = ["blur"] * 3 + ["nolabel"] * 2 + ["tilt"] * 2 + ["torn"] + ["glare"] * 3
        for (col, row), damage in zip(picks, damages):
            grid[col][row] = (grid[col][row][0], damage)
    return grid


def render(scene: str, cols: int, module: int, barcode_share: float, seed: int) -> tuple[Image.Image, dict]:
    rng = random.Random(seed)
    # Size everything from the barcode: 13 digits are 123 modules plus quiet zones.
    bar_px = 123 * module
    label_share = 0.46
    face_w = round(bar_px / barcode_share / 16) * 16
    face_h = int(face_w * 0.60)          # a shoe-box end is roughly 20 cm x 12 cm
    size = face_w * cols
    rows = size // face_h
    top = (size - rows * face_h) // 2
    grid = layout(scene, cols, rows, rng)

    wall = Image.new("RGB", (size, size), (28, 28, 32))
    px_per_m = size / POSTER_M
    boxes = []
    # Draw order matters where stacks overlap: the label sits at the right end of a box, so a box
    # must lie on top of its right-hand neighbour, never under it. The random choices are made in
    # reading order first so a scene does not change with the drawing order.
    leans = [rng.uniform(-0.012, 0.012) for _ in range(cols)]         # a stack leans a little as it goes up
    seeds = [[rng.random() for _ in range(rows)] for _ in range(cols)]
    for col in reversed(range(cols)):
        lean = leans[col]
        for row in range(rows):
            rng = random.Random(seeds[col][row])
            product, damage = grid[col][row]
            face, label_rect = draw_face(product, face_w, face_h, module, rng, damage, label_share)
            jitter = int(face_w * (rng.uniform(-0.025, 0.025) + lean * (rows - row)))
            x = col * face_w + jitter
            y = top + row * face_h
            wall.paste(face, (x, y))
            readable = product is not None and damage not in ("blur", "torn", "nolabel")
            entry = {
                "col": col, "row": row,
                "box_px": [x, y, x + face_w, y + face_h],
                "barcode": None if product is None or damage == "nolabel" else product.barcode,
                "readable": readable,
                "damage": damage,
            }
            if label_rect:
                lx0, ly0, lx1, ly1 = label_rect
                entry["label_px"] = [x + lx0, y + ly0, x + lx1, y + ly1]
                # Where the camera should look, in metres from the poster's centre (x right, y up).
                cx, cy = x + (lx0 + lx1) / 2, y + (ly0 + ly1) / 2
                entry["label_m"] = [round((cx - size / 2) / px_per_m, 4), round((size / 2 - cy) / px_per_m, 4)]
            boxes.append(entry)

    truth = {
        "scene": scene, "seed": seed, "size_px": size, "poster_m": POSTER_M, "cols": cols, "rows": rows,
        "face_m": [round(face_w / px_per_m, 4), round(face_h / px_per_m, 4)],
        "barcode_m": round(bar_px / px_per_m, 4), "module_px": module,
        "boxes": boxes,
        "readable_boxes": sum(1 for b in boxes if b["readable"]),
    }
    return wall, truth


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("scene", choices=["koridor", "zor", "ikiz"])
    parser.add_argument("out_dir")
    parser.add_argument("--cols", type=int, default=5)
    parser.add_argument("--module", type=int, default=3, help="width of the thinnest bar in texture pixels")
    parser.add_argument("--barcode-share", type=float, default=0.30, help="barcode width / box-face width")
    parser.add_argument("--seed", type=int, default=7)
    args = parser.parse_args()

    wall, truth = render(args.scene, args.cols, args.module, args.barcode_share, args.seed)
    out = Path(args.out_dir)
    out.mkdir(parents=True, exist_ok=True)
    wall.save(out / f"{args.scene}.png")
    (out / f"{args.scene}.json").write_text(json.dumps(truth, indent=1, ensure_ascii=False), encoding="utf-8")
    preview = wall.copy()
    preview.thumbnail((1600, 1600))
    preview.save(out / f"{args.scene}-preview.jpg", quality=85)
    counts: dict[str, int] = {}
    for b in truth["boxes"]:
        if b["readable"]:
            counts[b["barcode"]] = counts.get(b["barcode"], 0) + 1
    print(f"{args.scene}: {truth['size_px']} px, {truth['cols']} x {truth['rows']} boxes, "
          f"{truth['readable_boxes']} readable, {len(counts)} barcodes, "
          f"box {truth['face_m'][0] * 100:.0f} x {truth['face_m'][1] * 100:.0f} cm, barcode {truth['barcode_m'] * 100:.1f} cm")


if __name__ == "__main__":
    main()
