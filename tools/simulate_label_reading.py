"""How far away can the phone read a location QR of a given printed size? A simulation.

    tools\\.venv\\Scripts\\python tools\\simulate_label_reading.py

Renders the real label (make_location_labels.py), "photographs" it as the Galaxy S23 Ultra's main
camera would at each distance (focal 6.3 mm, sensor 9.792 mm wide; analysis stream 1080p or 1440p;
1x / 1.5x / 2x zoom), adds what a sweep adds (4 ms exposure at ~15 cm/s, slight optical blur, ISO
1600-like noise, viewing angle), and decodes it with zxing-cpp and OpenCV as stand-ins for ML Kit
(ML Kit does not run on a PC). Prints the farthest distance each setting still read.

This is an estimate to choose what to print and test first, not a replacement for trying it on the phone.
"""
import math
import random
import sys

import cv2
import numpy as np
import zxingcpp
from PIL import Image, ImageFilter

sys.path.insert(0, __file__.rsplit("\\", 1)[0] if "\\" in __file__ else ".")
import make_location_labels as labels  # noqa: E402

FOCAL_MM = 6.3
SENSOR_W_MM = 9.792
SWEEP_MM_S = 150.0       # phone moving along a column
EXPOSURE_S = 0.004
NOISE_SD = 8.0
DISTANCES_M = [0.5, 0.75, 1.0, 1.25, 1.5, 2.0, 2.5, 3.0, 3.5, 4.0]


def px_per_mm(distance_m: float, zoom: float, long_side_px: int) -> float:
    """Image pixels per mm on the label: focal x zoom / distance, in sensor pixels."""
    return FOCAL_MM * zoom / (distance_m * 1000.0) * (long_side_px / SENSOR_W_MM)


def photograph(label_img: Image.Image, scale: float, angle_deg: float, seed: int) -> np.ndarray:
    """Label (300 dpi) -> what the analysis frame sees; scale = output px per label px."""
    w = max(8, round(label_img.width * scale * math.cos(math.radians(angle_deg))))
    h = max(8, round(label_img.height * scale))
    img = label_img.resize((w, h), Image.LANCZOS)
    canvas = Image.new("L", (w + 80, h + 80), 120)  # grey box face around the white label
    canvas.paste(img, (40, 40))
    canvas = canvas.filter(ImageFilter.GaussianBlur(0.7))  # optics / slight defocus
    a = np.asarray(canvas, dtype=np.float32)
    # Vertical motion blur of the sweep.
    blur_px = SWEEP_MM_S * EXPOSURE_S * (scale * labels.DPI / 25.4)
    k = max(1, int(round(blur_px)))
    if k > 1:
        a = cv2.filter2D(a, -1, np.ones((k, 1), np.float32) / k)
    rng = np.random.default_rng(seed)
    a = a + rng.normal(0, NOISE_SD, a.shape)
    return np.clip(a, 0, 255).astype(np.uint8)


def read_zxing(a: np.ndarray, want: str) -> bool:
    return any(r.text == want for r in zxingcpp.read_barcodes(a, formats=zxingcpp.BarcodeFormat.QRCode))


_detector = cv2.QRCodeDetector()


def read_opencv(a: np.ndarray, want: str) -> bool:
    text, _, _ = _detector.detectAndDecode(a)
    return text == want


def farthest(size_mm: float, zoom: float, long_side: int, angle: float, reader) -> float:
    row, col = "2", 7
    want = labels.payload(row, col)
    img = labels.label(row, col, size_mm)
    best = 0.0
    for d in DISTANCES_M:
        scale = px_per_mm(d, zoom, long_side) / (labels.DPI / 25.4)
        ok = sum(reader(photograph(img, scale, angle, seed), want) for seed in range(5))
        if ok >= 4:  # 4 of 5 noisy frames
            best = d
    return best


def main():
    random.seed(1)
    print("Farthest distance (m) read in >= 4 of 5 simulated sweep frames")
    print(f"{'label':>6} {'zoom':>5} {'stream':>7} {'angle':>6} {'zxing':>6} {'opencv':>7}  px/module at 1 m")
    for size in (50, 75, 100):
        for zoom in (1.0, 1.5, 2.0):
            for long_side, name in ((1920, "1080p"), (2560, "1440p")):
                for angle in (0, 30, 45):
                    if angle and (long_side != 2560 or zoom != 1.5):
                        continue  # angle only for the app's default setting
                    zx = farthest(size, zoom, long_side, angle, read_zxing)
                    cvd = farthest(size, zoom, long_side, angle, read_opencv)
                    ppm = px_per_mm(1.0, zoom, long_side) * size / 29
                    print(f"{size:>4}mm {zoom:>4}x {name:>7} {angle:>5}° {zx:>6} {cvd:>7}  {ppm:5.1f}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
