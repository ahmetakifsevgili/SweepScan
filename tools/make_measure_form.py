"""Printable A4 measuring sheet for the stock rooms (docs/olcum-formu.md as a form with boxes).

    python tools/make_measure_form.py   -> dist/olcum-formu.pdf
"""
import os

from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PDF = os.path.join(ROOT, "dist", "olcum-formu.pdf")
W, H = 1654, 2339  # A4 at 200 dpi
INK = (20, 20, 20)
GREY = (120, 120, 120)
LINE = (170, 170, 170)
FONTS = r"C:\Windows\Fonts"

DEPOTS = [
    ("Büyük depo", [("1", "15 m · 4 kat"), ("2", "15,5 m · 4 kat"), ("3", "28 m · 4 kat"), ("4", "19,4 m · 4 kat"),
                    ("5", "6,5 m · 4 kat"), ("6", "2,6 m · 4 kat"), ("7", "14 m · 4 kat"), ("8", "14 m · 4 kat"),
                    ("9", "11,8 m · 4 kat"), ("10", "6 m · 4 kat")]),
    ("Küçük depo", [("A", "24 m · 5 kat"), ("B", "19 m · 5 kat")]),
    ("Orta alan deposu", [("C", "22 m · tek kat"), ("D", "4 m · tek kat")]),
]
GENERAL = [
    "Giriş kapısı hangi depoda, hangi duvarda?",
    "Kapıdan ilk sıraya kaç metre?",
    "Ana koridor genişliği (m)",
    "Depolar arası geçiş nerede?",
    "Haritada olmayan sıra / ada var mı?",
]
COLS = [("Sıra", 110), ("Şu an haritada", 300), ("Uzunluk (m)", 230), ("Kat", 140), ("Raf arası (cm)", 250), ("Not", 0)]


def font(size, bold=False):
    return ImageFont.truetype(os.path.join(FONTS, "seguisb.ttf" if bold else "segoeui.ttf"), size)


def table(d, x0, y, rows, width):
    widths = [w for _, w in COLS]
    widths[-1] = width - sum(widths[:-1])
    row_h = 74
    x = x0
    for (name, _), w in zip(COLS, widths):
        d.text((x + 12, y + 18), name, font=font(30, True), fill=INK)
        x += w
    y += row_h
    for name, now in rows:
        d.line((x0, y, x0 + width, y), fill=LINE, width=2)
        d.text((x0 + 12, y + 16), name, font=font(34, True), fill=INK)
        d.text((x0 + widths[0] + 12, y + 20), now, font=font(28), fill=GREY)
        y += row_h
    d.line((x0, y, x0 + width, y), fill=LINE, width=2)
    x = x0
    for w in widths[:-1]:
        x += w
        d.line((x, y - row_h * (len(rows) + 1) + row_h, x, y), fill=LINE, width=2)
    return y


def page1():
    img = Image.new("RGB", (W, H), "white")
    d = ImageDraw.Draw(img)
    m = 110
    d.text((m, 100), "Depo ölçüm formu", font=font(72, True), fill=INK)
    d.text((m, 200), "Uzunluk: metre ya da adım (1 adım ≈ 0,75 m). Kat: raf tahtası sayısı. Raf arası: iki tahta arası.",
           font=font(30), fill=GREY)
    y = 290
    for name, rows in DEPOTS:
        d.text((m, y), name, font=font(44, True), fill=INK)
        y = table(d, m, y + 70, rows, W - 2 * m) + 60
    return img


def page2():
    img = Image.new("RGB", (W, H), "white")
    d = ImageDraw.Draw(img)
    m = 110
    d.text((m, 100), "Genel", font=font(56, True), fill=INK)
    y = 200
    for q in GENERAL:
        d.text((m, y), q, font=font(34), fill=INK)
        d.line((m, y + 110, W - m, y + 110), fill=LINE, width=2)
        y += 150
    d.text((m, y + 30), "Kroki (kuşbakışı: giriş kapısı, sıralar, koridorlar, depolar arası geçiş)", font=font(36, True), fill=INK)
    d.rectangle((m, y + 100, W - m, H - 140), outline=LINE, width=3)
    d.text((m, H - 110), "Doldurup fotoğrafını gönder; ölçüleri haritaya ben işlerim.", font=font(28), fill=GREY)
    return img


def main():
    os.makedirs(os.path.dirname(PDF), exist_ok=True)
    pages = [page1(), page2()]
    pages[0].save(PDF, save_all=True, append_images=pages[1:], resolution=200)
    print("OK:", PDF)


if __name__ == "__main__":
    main()
