"""Writes camera paths for the Android emulator's virtual scene, as macro files that
`adb emu automation play <file>` replays.

A macro is a stream of records: a 4-byte big-endian length, then a protobuf message with a delay
in nanoseconds (field 1) and a physical-model event (field 50) holding a parameter type and its
target value as floats. Types used here: 0 = position (metres), 1 = rotation (degrees).

The paths sweep the phone over the box wall made by make_scene.py the way a person would: close
to the wall, down one stack of boxes, then on to the next. See docs/emulator-test.md.

    python tools/make_camera_path.py scene.json column  out.macro   # Y1: stack by stack, top to bottom
    python tools/make_camera_path.py scene.json snake   out.macro   # Y2: down one stack, up the next
    python tools/make_camera_path.py scene.json fast    out.macro   # Y3: snake at twice the speed
    python tools/make_camera_path.py scene.json revisit out.macro   # Y4: every stack down and back up
    python tools/make_camera_path.py scene.json wide    out.macro   # Y5: from further away, two stacks in view

Prints the timeline (when each stack starts and ends), which the test runner uses to press
Pause/Resume between stacks.
"""
import argparse
import json
import math
import struct
from pathlib import Path

# The wall poster of the default scene (emulator/resources/Toren1BD.posters).
POSTER_CENTRE = (-0.807, 0.320, 5.316)
POSTER_YAW_DEG = -150.0

# Measured on the emulator: with the pose above, the picture's centre lands this far from the
# aimed point (metres on the wall; the sideways part grows with distance). Subtracted when aiming.
AIM_ERROR_X = (0.05, 0.137)       # constant, per metre of distance
AIM_ERROR_Y = -0.03

POSITION, ROTATION = 0, 1
STEP_S = 0.05

# First record of every macro shipped with the emulator (format version + initial state).
HEADER = bytes.fromhex("00000018" "0802" "1214" "1212" "0806" "1206" "0a04" "00507d44" "1a06" "0a04" "00507d44")


def varint(value: int) -> bytes:
    out = bytearray()
    while True:
        byte = value & 0x7F
        value >>= 7
        out.append(byte | (0x80 if value else 0))
        if not value:
            return bytes(out)


def event(delay_s: float, kind: int, values: tuple[float, float, float]) -> bytes:
    floats = struct.pack("<3f", *values)
    target = b"\x0a" + varint(len(floats)) + floats                  # ParameterValue.data (packed floats)
    model = b"\x08" + varint(kind) + b"\x1a" + varint(len(target)) + target
    record = b"\x08" + varint(round(delay_s * 1e9)) + b"\x92\x03" + varint(len(model)) + model
    return struct.pack(">I", len(record)) + record


def camera_position(x_m: float, y_m: float, distance_m: float) -> tuple[float, float, float]:
    """World position from which the camera, facing the wall squarely, has the poster point
    (x right, y up, metres from the poster's centre) in the middle of the picture."""
    yaw = math.radians(POSTER_YAW_DEG)
    right = (math.cos(yaw), 0.0, -math.sin(yaw))
    normal = (math.sin(yaw), 0.0, math.cos(yaw))                     # out of the wall, into the room
    cx, cy, cz = POSTER_CENTRE
    x_m -= AIM_ERROR_X[0] + AIM_ERROR_X[1] * distance_m
    y_m -= AIM_ERROR_Y
    return (
        cx + right[0] * x_m + normal[0] * distance_m,
        cy + y_m,
        cz + right[2] * x_m + normal[2] * distance_m,
    )


class Path2D:
    """Waypoints on the wall plane (metres), walked at a given speed; keeps a timeline of named marks."""

    def __init__(self, distance_m: float, speed_m_s: float):
        self.distance = distance_m
        self.speed = speed_m_s
        self.points: list[tuple[float, float, float]] = []           # (t, x, y)
        self.marks: list[tuple[float, str]] = []
        self.t = 0.0

    def start(self, x: float, y: float, settle_s: float = 3.0) -> None:
        self.points.append((0.0, x, y))
        self.t = settle_s
        self.points.append((self.t, x, y))

    def mark(self, name: str) -> None:
        self.marks.append((round(self.t, 2), name))

    def go(self, x: float, y: float, speed: float | None = None) -> None:
        _, x0, y0 = self.points[-1]
        self.t += math.hypot(x - x0, y - y0) / (speed or self.speed)
        self.points.append((self.t, x, y))

    def hold(self, seconds: float) -> None:
        _, x, y = self.points[-1]
        self.t += seconds
        self.points.append((self.t, x, y))

    def macro(self) -> bytes:
        out = bytearray(HEADER)
        out += event(0.0, ROTATION, (0.0, POSTER_YAW_DEG, 0.0))
        t, i = 0.0, 0
        first = True
        while t <= self.t + 1e-9:
            while i + 1 < len(self.points) and self.points[i + 1][0] < t:
                i += 1
            t0, x0, y0 = self.points[i]
            t1, x1, y1 = self.points[min(i + 1, len(self.points) - 1)]
            f = 0.0 if t1 <= t0 else min(1.0, max(0.0, (t - t0) / (t1 - t0)))
            x, y = x0 + (x1 - x0) * f, y0 + (y1 - y0) * f
            out += event(0.0 if first else STEP_S, POSITION, camera_position(x, y, self.distance))
            first = False
            t += STEP_S
        return bytes(out)


def stacks(scene: dict) -> list[list[tuple[float, float]]]:
    """Label centres per stack, top to bottom; boxes without a label get the stack's usual x."""
    out = []
    for col in range(scene["cols"]):
        boxes = sorted((b for b in scene["boxes"] if b["col"] == col), key=lambda b: b["row"])
        xs = [b["label_m"][0] for b in boxes if "label_m" in b]
        x_usual = sum(xs) / len(xs)
        px_per_m = scene["size_px"] / scene["poster_m"]
        centres = []
        for b in boxes:
            if "label_m" in b:
                centres.append(tuple(b["label_m"]))
            else:
                cy = (b["box_px"][1] + b["box_px"][3]) / 2
                centres.append((x_usual, (scene["size_px"] / 2 - cy) / px_per_m))
        out.append(centres)
    return out


def build(scene: dict, kind: str, distance_m: float, speed_m_s: float) -> Path2D:
    cols = stacks(scene)
    face_h = scene["face_m"][1]
    lead = face_h * 0.9                                               # start above / end below the stack
    if kind == "fast":
        speed_m_s *= 2
    if kind == "wide":
        distance_m *= 2
        # Halfway between two neighbouring label columns, so both are in view.
        cols = [[((a[0] + b[0]) / 2, a[1]) for a, b in zip(cols[i], cols[i + 1])] for i in range(0, len(cols) - 1, 2)] + \
               ([cols[-1]] if len(cols) % 2 else [])

    path = Path2D(distance_m, speed_m_s)
    x0 = cols[0][0][0]
    path.start(x0, cols[0][0][1] + lead)
    for i, col in enumerate(cols):
        down = kind in ("column", "revisit", "wide") or i % 2 == 0
        order = col if down else col[::-1]
        sign = 1 if down else -1
        x = sum(p[0] for p in col) / len(col)
        # Move to the start of this stack quickly (the user would have paused), then sweep it.
        path.go(x, order[0][1] + sign * lead, speed=speed_m_s * 3)
        path.hold(1.0)
        path.mark(f"stack{i}:start")
        for px, py in order:
            path.go(px, py)
        path.go(x, order[-1][1] - sign * lead)
        if kind == "revisit":
            for px, py in order[::-1]:
                path.go(px, py)
            path.go(x, order[0][1] + sign * lead)
        path.mark(f"stack{i}:end")
        path.hold(1.5)
    return path


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("scene_json")
    parser.add_argument("kind", choices=["column", "snake", "fast", "revisit", "wide", "goto"])
    parser.add_argument("out")
    parser.add_argument("--distance", type=float, default=0.55, help="camera to wall, metres")
    parser.add_argument("--speed", type=float, default=0.06, help="sweep speed along the wall, m/s")
    parser.add_argument("--at", type=float, nargs=2, default=(0.0, 0.0), help="goto: point on the wall, metres")
    args = parser.parse_args()

    scene = json.loads(Path(args.scene_json).read_text(encoding="utf-8"))
    if args.kind == "goto":
        path = Path2D(args.distance, args.speed)
        path.start(args.at[0], args.at[1], settle_s=1.0)
    else:
        path = build(scene, args.kind, args.distance, args.speed)
    Path(args.out).write_bytes(path.macro())
    timeline = {"duration_s": round(path.t, 1), "marks": path.marks}
    Path(args.out + ".json").write_text(json.dumps(timeline), encoding="utf-8")
    print(json.dumps(timeline))


if __name__ == "__main__":
    main()
