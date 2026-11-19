"""How fast does SweepScan see, read and count a label? Reads one or more field logs (.jsonl).

    python tools/latency_report.py <log.jsonl | folder> [...]

For every counted track it walks back from the first decode to the first frame in which a
located-but-unread barcode sat at the same spot (shifted by the logged scene motion), giving three
stages per label:

    seen  -> first decode   (camera sees a barcode but cannot read it yet: focus, blur, size)
    first decode -> counted (the tracker waits for minHits agreeing reads)

It also prints the frame rate, time spent per pipeline stage, exposure, ISO, focus movement and
how often a visible label was actually decoded (read rate per frame).
"""
import json
import os
import statistics
import sys


def load(path):
    meta, frames, events = None, [], []
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            try:
                o = json.loads(line)
            except json.JSONDecodeError:
                continue
            kind = o.get("type")
            if kind == "meta":
                meta = o
            elif kind == "frame":
                frames.append(o)
            elif kind == "event":
                events.append(o)
    return meta, frames, events


def pct(values, p):
    if not values:
        return float("nan")
    s = sorted(values)
    k = min(len(s) - 1, max(0, round(p / 100 * (len(s) - 1))))
    return s[k]


def summary(values, unit=""):
    if not values:
        return "-"
    return f"median {pct(values, 50):.0f}{unit}  p90 {pct(values, 90):.0f}{unit}  max {max(values):.0f}{unit}  (n={len(values)})"


def iou(a, b):
    l, t = max(a[0], b[0]), max(a[1], b[1])
    r, bt = min(a[2], b[2]), min(a[3], b[3])
    if r <= l or bt <= t:
        return 0.0
    inter = (r - l) * (bt - t)
    ua = (a[2] - a[0]) * (a[3] - a[1]) + (b[2] - b[0]) * (b[3] - b[1]) - inter
    return inter / ua if ua > 0 else 0.0


def label_stats(frames):
    # Per label: located -> first decode -> counted.
    by_id = {}
    for i, f in enumerate(frames):
        for row in f.get("tr") or []:
            tid, l, t, r, b, value, counted, visible = row[:8]
            if tid < 0:
                continue
            st = by_id.setdefault(tid, {"first": i, "counted_at": None, "box": [l, t, r, b], "visible": 0, "decoded": 0})
            if visible:
                st["visible"] += 1
            if counted and st["counted_at"] is None:
                st["counted_at"] = i
    # decoded hits per frame per track: a track is decoded in a frame when a det with a value overlaps it
    for i, f in enumerate(frames):
        dets = [d for d in f.get("dets") or [] if d.get("v") and d.get("r", "OK") == "OK"]
        for row in f.get("tr") or []:
            tid = row[0]
            if tid < 0 or tid not in by_id or not row[7]:
                continue
            box = row[1:5]
            if any(iou(box, d["b"]) > 0.3 for d in dets):
                by_id[tid]["decoded"] += 1

    seen_to_read, read_to_count, seen_to_count = [], [], []
    frames_to_count, rates = [], []
    for tid, st in by_id.items():
        if st["counted_at"] is None:
            continue
        first, counted = st["first"], st["counted_at"]
        # Walk back from the first decode, moving the box against the logged scene shift.
        box = list(st["box"])
        seen = first
        misses = 0
        for j in range(first - 1, max(-1, first - 150), -1):
            sh = frames[j + 1].get("shift") or {}
            dx, dy = sh.get("dx", 0) or 0, sh.get("dy", 0) or 0
            box = [box[0] - dx, box[1] - dy, box[2] - dx, box[3] - dy]
            located = [d["b"] for d in frames[j].get("dets") or []]
            hit = next((b for b in located if iou(box, b) > 0.15), None)
            if hit:
                seen, box, misses = j, list(hit), 0
            else:
                misses += 1
                if misses > 5:
                    break
        t_seen, t_first, t_count = frames[seen]["t"], frames[first]["t"], frames[counted]["t"]
        seen_to_read.append(t_first - t_seen)
        read_to_count.append(t_count - t_first)
        seen_to_count.append(t_count - t_seen)
        frames_to_count.append(counted - first + 1)
        if st["visible"]:
            rates.append(100 * st["decoded"] / st["visible"])
    return {"seen_to_read": seen_to_read, "read_to_count": read_to_count, "seen_to_count": seen_to_count,
            "frames_to_count": frames_to_count, "rates": rates, "by_id": by_id}


def report(path):
    meta, frames, events = load(path)
    print(f"=== {os.path.basename(path)}")
    if meta:
        s = meta.get("settings", {})
        cam = meta.get("camera", {})
        print(f"app {meta.get('app', {}).get('name')}  device {meta.get('device', {}).get('model')}  "
              f"analysis {cam.get('analysis')}  zoom {s.get('zoom')}  exposure {s.get('exposure')}->{cam.get('exposurePath')}  "
              f"focus {s.get('focus')}->{cam.get('focusPath')}  zxing {s.get('zxing')}  flow {s.get('flow')}  "
              f"sampleFrames {s.get('sampleFrames')}  minHits {meta.get('tracker', {}).get('minHits')}")
    if len(frames) < 2:
        print("too few frames\n")
        return
    ts = [f["t"] for f in frames]
    gaps = [b - a for a, b in zip(ts, ts[1:]) if b > a]
    dur = (ts[-1] - ts[0]) / 1000
    print(f"frames {len(frames)} in {dur:.1f} s = {len(frames) / dur:.1f} fps analysed; frame gap {summary(gaps, ' ms')}")
    for key, name in (("ml", "ML Kit"), ("zx", "zxing"), ("of", "flow"), ("tr", "tracker")):
        vals = [f["ms"][key] for f in frames if "ms" in f and key in f["ms"]]
        print(f"  {name:8s} {summary(vals, ' ms')}")
    busy = [sum(f["ms"].values()) for f in frames if "ms" in f]
    print(f"  total    {summary(busy, ' ms')}   (camera delivers a frame every ~33 ms)")

    caps = [f.get("cap") or {} for f in frames]
    exp = [c["exp"] / 1e6 for c in caps if c.get("exp")]
    iso = [c["iso"] for c in caps if c.get("iso")]
    fd = [c["fd"] for c in caps if c.get("fd") is not None]
    zoom = [c["zoom"] for c in caps if c.get("zoom") is not None]
    print(f"  exposure {summary(exp, ' ms')}")
    print(f"  ISO      {summary(iso)}")
    if fd:
        moves = sum(1 for a, b in zip(fd, fd[1:]) if abs(a - b) > 0.05)
        print(f"  focus    {min(fd):.2f}..{max(fd):.2f} diopters (= {1 / max(fd) if max(fd) else float('inf'):.2f}..{1 / min(fd) if min(fd) else float('inf'):.2f} m), "
              f"lens moved in {moves} of {len(fd) - 1} frames")
    afs = [c.get("afS") for c in caps if c.get("afS") is not None]
    if afs:
        # CONTROL_AF_STATE: 1 passive scan, 2 passive focused, 6 passive unfocused, 3 active scan, 4 locked
        scanning = sum(1 for a in afs if a in (1, 3))
        print(f"  AF scanning in {scanning} of {len(afs)} frames ({100 * scanning / len(afs):.0f} %)")
    if zoom:
        print(f"  zoom     {min(zoom)}..{max(zoom)}")
    blur = [f.get("blurH") for f in frames if f.get("blurH")]
    if blur:
        print(f"  motion blur estimate {summary(blur, ' px')}")

    lab = label_stats(frames)
    seen_to_read, read_to_count, seen_to_count = lab["seen_to_read"], lab["read_to_count"], lab["seen_to_count"]
    frames_to_count, rates, by_id = lab["frames_to_count"], lab["rates"], lab["by_id"]
    counts = [e for e in events if e.get("ev") == "count"]
    print(f"labels counted: {len(counts)}  (tracks with a count in 'tr': {len(seen_to_count)})")
    print(f"  seen -> first read   {summary(seen_to_read, ' ms')}")
    print(f"  first read -> count  {summary(read_to_count, ' ms')}  frames {summary(frames_to_count)}")
    print(f"  seen -> count        {summary(seen_to_count, ' ms')}")
    print(f"  read rate while visible {summary(rates, ' %')}")
    never = sum(1 for st in by_id.values() if st["counted_at"] is None)
    print(f"tracks never counted: {never}")
    print()
    tour_table(frames, events)


def tour_table(frames, events):
    steps = [e for e in events if e.get("ev") == "tour_step"]
    if not steps:
        return
    ends = [e["t"] for e in events if e.get("ev") == "tour_end"]
    bounds = [e["t"] for e in steps[1:]] + [ends[0] if ends else float("inf")]
    print("settings tour (one row per step; medians):")
    print(f"{'step':>4} {'zoom':>5} {'exposure':>9} {'path':>7} {'fps':>5} {'counts':>6} {'seen>count':>10} "
          f"{'read>count':>10} {'frames':>6} {'readrate':>8} {'blur px':>7} {'exp ms':>6} {'ISO':>5} {'AF move':>7}")
    for step, until in zip(steps, bounds):
        seg = [f for f in frames if step["t"] <= f["t"] < until]
        if len(seg) < 5:
            continue
        lab = label_stats(seg)
        dur = (seg[-1]["t"] - seg[0]["t"]) / 1000 or 1
        counts = sum(1 for e in events if e.get("ev") == "count" and step["t"] <= e["t"] < until)
        caps = [f.get("cap") or {} for f in seg]
        fd = [c["fd"] for c in caps if c.get("fd") is not None]
        moved = 100 * sum(1 for a, b in zip(fd, fd[1:]) if abs(a - b) > 0.05) / max(1, len(fd) - 1)
        exp = "auto" if step.get("exposure") == "AUTO" else f"{step.get('shortExposureUs', 0) / 1000:g} ms"
        med = lambda v: f"{pct(v, 50):.0f}" if v else "-"
        print(f"{step.get('index', 0) + 1:>4} {step.get('zoom', 0):>5} {exp:>9} {str(step.get('exposurePath', ''))[:7]:>7} "
              f"{len(seg) / dur:>5.1f} {counts:>6} {med(lab['seen_to_count']):>10} {med(lab['read_to_count']):>10} "
              f"{med(lab['frames_to_count']):>6} {med(lab['rates']):>7}% {med([f.get('blurH') for f in seg if f.get('blurH') is not None]):>7} "
              f"{med([c['exp'] / 1e6 for c in caps if c.get('exp')]):>6} {med([c['iso'] for c in caps if c.get('iso')]):>5} {moved:>6.0f}%")
    print()


def main(args):
    paths = []
    for a in args:
        if os.path.isdir(a):
            for root, _, files in os.walk(a):
                paths += [os.path.join(root, f) for f in files if f.startswith("sweepscan-") and f.endswith(".jsonl")]
        else:
            paths.append(a)
    if not paths:
        print(__doc__)
        return 1
    for p in sorted(paths):
        report(p)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
