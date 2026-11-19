"""LinkedIn document post (carousel) for SweepScan: 8 pages, 1080 x 1350, as one PDF.

    python tools/make_carousel.py            -> dist/SweepScan-carousel.pdf (+ PNG pages in build/carousel/)

Screenshots come from build/phone/ (pulled from the device tests: shot-*.png, shot-3d-*.png).
Texts are Turkish; edit PAGES below to change them.
"""
import os
import sys

from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SHOTS = os.path.join(ROOT, "build", "phone")
OUT_DIR = os.path.join(ROOT, "build", "carousel")
PDF = os.path.join(ROOT, "dist", "SweepScan-carousel.pdf")

W, H = 1080, 1350
BG = (14, 16, 19)
CARD = (27, 31, 36)
BRAND = (198, 232, 27)
AMBER = (255, 179, 0)
TEXT = (255, 255, 255)
DIM = (166, 173, 180)
FONTS = r"C:\Windows\Fonts"


def font(size, bold=False):
    name = "seguisb.ttf" if bold else "segoeui.ttf"
    try:
        return ImageFont.truetype(os.path.join(FONTS, name), size)
    except OSError:
        return ImageFont.load_default(size)


def wrap(draw, text, f, width):
    lines = []
    for para in text.split("\n"):
        words = para.split(" ")
        line = ""
        for w in words:
            test = (line + " " + w).strip()
            if draw.textlength(test, font=f) <= width:
                line = test
            else:
                lines.append(line)
                line = w
        lines.append(line)
    return lines


def text_block(draw, xy, text, f, fill, width, gap=10):
    x, y = xy
    for line in wrap(draw, text, f, width):
        draw.text((x, y), line, font=f, fill=fill)
        y += f.size + gap
    return y


def rounded(img, radius):
    mask = Image.new("L", img.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, img.size[0] - 1, img.size[1] - 1), radius, fill=255)
    out = Image.new("RGBA", img.size)
    out.paste(img, (0, 0), mask)
    return out


def phone(page, shot, box):
    """A screenshot in a phone-like frame, fitted into box = (x, y, w, h), with a soft shadow."""
    x, y, w, h = box
    img = Image.open(shot).convert("RGB")
    scale = min((w - 28) / img.width, (h - 28) / img.height)
    img = img.resize((int(img.width * scale), int(img.height * scale)), Image.LANCZOS)
    fw, fh = img.width + 28, img.height + 28
    fx, fy = x + (w - fw) // 2, y + (h - fh) // 2
    shadow = Image.new("RGBA", (fw + 80, fh + 80), (0, 0, 0, 0))
    ImageDraw.Draw(shadow).rounded_rectangle((40, 50, fw + 40, fh + 50), 56, fill=(0, 0, 0, 170))
    shadow = shadow.filter(ImageFilter.GaussianBlur(24))
    page.alpha_composite(shadow, (fx - 40, fy - 40))
    frame = Image.new("RGBA", (fw, fh), (0, 0, 0, 0))
    ImageDraw.Draw(frame).rounded_rectangle((0, 0, fw - 1, fh - 1), 52, fill=(8, 9, 11, 255), outline=(60, 66, 74, 255), width=3)
    page.alpha_composite(frame, (fx, fy))
    page.alpha_composite(rounded(img.convert("RGBA"), 40), (fx + 14, fy + 14))


def picture(page, shot, box, radius=36):
    x, y, w, h = box
    img = Image.open(shot).convert("RGB")
    scale = max(w / img.width, h / img.height)
    img = img.resize((int(img.width * scale) + 1, int(img.height * scale) + 1), Image.LANCZOS)
    left = (img.width - w) // 2
    top = (img.height - h) // 2
    img = img.crop((left, top, left + w, top + h))
    page.alpha_composite(rounded(img.convert("RGBA"), radius), (x, y))


def header(draw, n, total, kicker):
    draw.text((72, 64), kicker.upper(), font=font(30, True), fill=BRAND)
    draw.text((W - 72, 64), f"{n}/{total}", font=font(28), fill=DIM, anchor="ra")


def page_base():
    page = Image.new("RGBA", (W, H), BG + (255,))
    return page, ImageDraw.Draw(page)


def shot(name):
    p = os.path.join(SHOTS, name)
    if not os.path.exists(p):
        sys.exit(f"missing screenshot: {p} (run the device tests and pull build/phone/ first)")
    return p


TOTAL = 8


def cover():
    page, d = page_base()
    picture(page, shot("shot-3d-end.png"), (0, 0, W, 760), radius=0)
    fade = Image.new("RGBA", (W, 360), (0, 0, 0, 0))
    for i in range(360):
        ImageDraw.Draw(fade).line((0, i, W, i), fill=BG + (int(255 * i / 359),))
    page.alpha_composite(fade, (0, 400))
    d.text((72, 800), "SweepScan", font=font(110, True), fill=TEXT)
    text_block(d, (72, 950), "Depoda bir kutuyu saniyeler içinde bul.", font(54), BRAND, W - 144)
    text_block(d, (72, 1110), "Ayakkabı mağazası deposu için kendi yazdığım Android uygulaması: kamera ile tarama, "
               "raf hafızası, canlı talepler, 3D yol gösterme.", font(34), DIM, W - 144)
    return page


def problem():
    page, d = page_base()
    header(d, 2, TOTAL, "Sorun")
    y = text_block(d, (72, 180), "Bir kutuyu bulmak dakikalar sürüyordu.", font(76, True), TEXT, W - 144, 14)
    y += 50
    for line in [
        "Mağazadan soru geliyor: \"0171 3310, 39 numara var mı?\"",
        "Depoda üç oda, yüzlerce raf, binlerce kutu.",
        "Kimin neyi nereye koyduğu kâğıtta da sistemde de yok.",
        "Müşteri kasada bekliyor.",
    ]:
        d.ellipse((72, y + 18, 92, y + 38), fill=AMBER)
        y = text_block(d, (120, y), line, font(42), TEXT, W - 200, 8) + 30
    return page


def feature(n, kicker, title, body, screen):
    page, d = page_base()
    header(d, n, TOTAL, kicker)
    y = text_block(d, (72, 150), title, font(64, True), TEXT, W - 144, 10)
    y = text_block(d, (72, y + 16), body, font(36), DIM, W - 144, 8)
    phone(page, shot(screen), (140, y + 30, W - 280, H - y - 70))
    return page


def flight():
    page, d = page_base()
    header(d, 5, TOTAL, "3D yol gösterme")
    y = text_block(d, (72, 150), "Kamera rafa kendisi gider.", font(64, True), TEXT, W - 144, 10)
    y = text_block(d, (72, y + 16), "Girişten süzülür, rotayı izler, rafın önünde durur. Aranan kutular parlar; "
                   "yanında rafın son fotoğrafı.", font(36), DIM, W - 144, 8)
    picture(page, shot("shot-3d-start.png"), (72, y + 40, W - 144, 360))
    picture(page, shot("shot-3d-end.png"), (72, y + 430, W - 144, H - y - 500))
    d.text((96, y + 60), "1  Giriş", font=font(30, True), fill=TEXT)
    d.text((96, y + 450), "2  Raf", font=font(30, True), fill=TEXT)
    return page


def tech():
    page, d = page_base()
    header(d, 7, TOTAL, "Nasıl yapıldı")
    y = text_block(d, (72, 150), "Tek kişi, sahada test edilerek.", font(64, True), TEXT, W - 144, 10) + 40
    rows = [
        ("Görüntü", "CameraX · ML Kit + zxing-cpp · OpenCV optik akış · kendi çoklu barkod takipçim"),
        ("Veri", "Room (göçler test edildi) · raf fotoğraf hafızası · eğimden raf katı"),
        ("Arayüz", "Jetpack Compose + Material 3 · MVVM, StateFlow"),
        ("3D", "Filament (PBR, gölge, bloom) · sahne saf Kotlin'de üretilen glTF"),
        ("Ağ", "Yerel ağda mDNS ile bulunan sunucu · PIN · tarayıcıda panel"),
        ("Kalite", "130+ JVM testi · telefondan kayıtlar her derlemede tekrar oynatılıyor · R8"),
    ]
    for k, v in rows:
        d.rounded_rectangle((72, y, W - 72, y + 150), 28, fill=CARD)
        d.text((104, y + 26), k, font=font(32, True), fill=BRAND)
        text_block(d, (104, y + 70), v, font(30), TEXT, W - 210, 4)
        y += 170
    return page


def end():
    page, d = page_base()
    d.text((72, 420), "Depoda her gün", font=font(80, True), fill=TEXT)
    d.text((72, 520), "kullanılıyor.", font=font(80, True), fill=BRAND)
    text_block(d, (72, 680), "Kod ve kurulum dosyası GitHub'da. Geri bildirim, soru ve iş birliği için mesaj atın.",
               font(40), DIM, W - 144, 10)
    d.text((72, H - 120), "SweepScan · Android · Kotlin", font=font(30), fill=DIM)
    return page


def main():
    pages = [
        cover(),
        problem(),
        feature(3, "Talep", "Mağazadan soru, depoya anında.", "Talepler bildirimle düşer; cevap tek dokunuşla geri gider.", "shot-home.png"),
        feature(4, "Cevap", "Sıra, raf katı, rota.", "Harita girişten rotayı çizer; kart rafın neresinde ve kaçıncı katta olduğunu söyler.", "shot-search.png"),
        flight(),
        feature(6, "Raf tarama", "Bir kere tara, hep hatırlasın.", "Kamera kutuları sayar; her modelin yeri ve rafın fotoğrafı kaydedilir.", "shot-camera-map.png"),
        tech(),
        end(),
    ]
    os.makedirs(OUT_DIR, exist_ok=True)
    os.makedirs(os.path.dirname(PDF), exist_ok=True)
    rgb = [p.convert("RGB") for p in pages]
    for i, p in enumerate(rgb, 1):
        p.save(os.path.join(OUT_DIR, f"page-{i}.png"))
    rgb[0].save(PDF, save_all=True, append_images=rgb[1:], resolution=144)
    print("OK:", PDF)


if __name__ == "__main__":
    main()
