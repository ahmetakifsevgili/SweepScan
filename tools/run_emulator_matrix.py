"""Records SweepScan on the emulator over the box-wall scenes and sweep paths, and scores each run.

For every (scene, path) pair: reset the app, put the scene on the virtual wall, start a field
recording, replay the camera path (pressing Pause/Resume between stacks for the "column" path),
stop, pull the files, and compare what the app counted with the scene's ground truth.

Needs a running emulator with the app installed (docs/emulator-test.md).

    python tools/run_emulator_matrix.py samples                 # the default nine runs
    python tools/run_emulator_matrix.py samples koridor:snake   # just these
"""
import json
import os
import re
import subprocess
import sys
import time
from collections import Counter
from pathlib import Path

HERE = Path(__file__).resolve().parent
ADB = os.environ.get("ADB") or str(Path(os.environ.get("LOCALAPPDATA", "")) / "Android/Sdk/platform-tools/adb.exe")
ENV = dict(os.environ, MSYS_NO_PATHCONV="1")
APP = "com.sweepscan.debug"  # the matrix installs the debug build (own package since 0.3.7)
DEVICE_DIR = "/sdcard/Download/SweepScan"

# Screen positions on the 1080 x 2400 test device.
BTN_PAUSE = (124, 2180)
BTN_SETTINGS = (955, 2180)

DEFAULT_RUNS = [
    ("koridor", "column"), ("koridor", "snake"), ("koridor", "fast"), ("koridor", "revisit"), ("koridor", "wide"),
    ("zor", "column"), ("zor", "snake"),
    ("ikiz", "column"), ("ikiz", "snake"),
]
PATH_NAMES = {"column": "Y1", "snake": "Y2", "fast": "Y3", "revisit": "Y4", "wide": "Y5"}


def adb(*args: str, binary: bool = False):
    result = subprocess.run([ADB, *args], capture_output=True, env=ENV)
    return result.stdout if binary else result.stdout.decode("utf-8", "replace")


def tap(point: tuple[int, int]) -> None:
    adb("shell", "input", "tap", str(point[0]), str(point[1]))


def ui_nodes() -> list[tuple[str, tuple[int, int, int, int]]]:
    adb("shell", "uiautomator", "dump", "/sdcard/ui.xml")
    xml = adb("shell", "cat", "/sdcard/ui.xml")
    pattern = r'<node[^>]*?text="([^"]*)"[^>]*?class="([^"]*)"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'
    return [(m.group(1), m.group(2), tuple(int(m.group(i)) for i in range(3, 7))) for m in re.finditer(pattern, xml)]


def tap_text(text: str, klass: str = "") -> bool:
    """Taps the first node whose text contains [text] (and whose class contains [klass])."""
    for _ in range(4):
        for label, node_class, (x1, y1, x2, y2) in ui_nodes():
            if text.lower() in label.lower() and klass in node_class:
                tap(((x1 + x2) // 2, (y1 + y2) // 2))
                return True
        time.sleep(0.7)
    return False


def reset_app() -> None:
    adb("shell", "am", "force-stop", APP)
    adb("shell", "pm", "clear", APP)
    adb("shell", "rm", "-rf", DEVICE_DIR)
    for permission in ("CAMERA", "BLUETOOTH_CONNECT", "BLUETOOTH_ADVERTISE"):
        adb("shell", "pm", "grant", APP, f"android.permission.{permission}")
    adb("shell", "am", "start", "-n", f"{APP}/.ui.MainActivity")


def emu(*args: str) -> None:
    reply = adb("emu", *args)
    if "KO" in reply:
        raise RuntimeError(f"emulator refused {' '.join(args)}: {reply.strip()}")


def play(macro: Path) -> None:
    emu("automation", "play", str(macro).replace("\\", "/"))


def start_recording(tag: str) -> None:
    tap(BTN_SETTINGS)
    time.sleep(1.2)
    if not tap_text("field recording"):
        raise RuntimeError("settings dialog did not open")
    time.sleep(1.2)
    tap_text("", klass="EditText")           # focus selects the default name, typing replaces it
    time.sleep(0.4)
    adb("shell", "input", "text", tag)
    time.sleep(0.4)
    if not tap_text("START FIELD RECORDING", klass="Button"):
        raise RuntimeError("could not start the recording")


def stop_recording() -> None:
    tap(BTN_SETTINGS)
    time.sleep(1.2)
    tap_text("Stop field recording")
    time.sleep(2.5)


def run(scene: str, kind: str, root: Path) -> dict:
    scene_json = root / "scenes" / f"{scene}.json"
    truth = json.loads(scene_json.read_text(encoding="utf-8"))
    macro = root / "paths" / f"{scene}-{kind}.macro"
    macro.parent.mkdir(parents=True, exist_ok=True)
    adb("emu", "automation", "stop-play")     # a macro still playing keeps its file locked
    time.sleep(0.5)
    out = subprocess.run([sys.executable, str(HERE / "make_camera_path.py"), str(scene_json), kind, str(macro)],
                         capture_output=True, text=True, check=True).stdout
    timeline = json.loads(out)
    marks = dict((name, t) for t, name in timeline["marks"])

    name = f"{scene}-{PATH_NAMES[kind]}"
    print(f"== {name}: {timeline['duration_s']} s", flush=True)
    emu("virtualscene-image", "wall", str(root / "scenes" / f"{scene}.png").replace("\\", "/"))
    reset_app()
    # Park the camera at the first stack before anything is recorded.
    park = root / "paths" / "park.macro"
    subprocess.run([sys.executable, str(HERE / "make_camera_path.py"), str(scene_json), "goto", str(park),
                    "--at", "-3", "0"], capture_output=True, check=True)   # beside the wall: nothing readable
    play(park)
    time.sleep(9)
    start_recording(name)
    time.sleep(1.5)

    t0 = time.time()
    play(macro)
    if kind == "column":
        # The documented way of working: pause at the end of a stack, resume on the next one.
        stacks = len([m for m in marks if m.endswith(":end")])
        for i in range(stacks - 1):
            wait_until(t0 + marks[f"stack{i}:end"] + 0.4)
            tap(BTN_PAUSE)
            wait_until(t0 + marks[f"stack{i + 1}:start"] - 0.5)
            tap(BTN_PAUSE)
    wait_until(t0 + timeline["duration_s"] + 2.0)
    stop_recording()

    dest = root / "recordings" / name
    if dest.exists():
        for old in sorted(dest.rglob("*"), reverse=True):
            old.unlink() if old.is_file() else old.rmdir()
    dest.mkdir(parents=True, exist_ok=True)
    adb("pull", DEVICE_DIR + "/.", str(dest))
    return score(name, truth, dest)


def wait_until(deadline: float) -> None:
    delay = deadline - time.time()
    if delay > 0:
        time.sleep(delay)


def score(name: str, truth: dict, dest: Path) -> dict:
    logs = sorted(dest.glob("sweepscan-*.jsonl"))
    if not logs:
        return {"name": name, "error": "no recording pulled"}
    log = logs[0]
    lines = [json.loads(line) for line in log.read_text(encoding="utf-8").splitlines() if line.strip()]
    frames = [l for l in lines if l.get("type") == "frame"]
    counted = Counter(v for f in frames for v in f["counted"])
    expected = Counter(b["barcode"] for b in truth["boxes"] if b["readable"])
    (log.with_suffix(".expected")).write_text(
        "\n".join([str(sum(expected.values()))] + [f"{k} {v}" for k, v in sorted(expected.items())]) + "\n", encoding="utf-8")
    over = sum((counted - expected).values())
    under = sum((expected - counted).values())
    span_s = (frames[-1]["t"] - frames[0]["t"]) / 1000 if len(frames) > 1 else 0
    result = {
        "name": name, "log": log.name,
        "expected": sum(expected.values()), "counted": sum(counted.values()),
        "over": over, "under": under,
        "unknown_values": sorted(set(counted) - set(expected)),
        "frames": len(frames), "fps": round(len(frames) / span_s, 1) if span_s else 0,
        "images": len(list(dest.rglob("f*.png"))),
        "has_tracks": any("tr" in f for f in frames),
        "pauses": sum(1 for l in lines if l.get("type") == "event" and l.get("ev") == "pause"),
    }
    (dest / "result.json").write_text(json.dumps(result, indent=1), encoding="utf-8")
    print("   ", json.dumps(result), flush=True)
    return result


def main() -> None:
    root = Path(sys.argv[1]).resolve()     # the emulator opens these files itself: it needs full paths
    runs = [tuple(arg.split(":")) for arg in sys.argv[2:]] or DEFAULT_RUNS
    results = [run(scene, kind, root) for scene, kind in runs]
    (root / "recordings" / "results.json").write_text(json.dumps(results, indent=1), encoding="utf-8")
    print("\nname            expected counted  over under  fps")
    for r in results:
        if "error" in r:
            print(f"{r['name']:<15} {r['error']}")
        else:
            print(f"{r['name']:<15} {r['expected']:>8} {r['counted']:>7} {r['over']:>5} {r['under']:>5} {r['fps']:>5}")


if __name__ == "__main__":
    main()
