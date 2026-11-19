# Testing on the Android emulator

> **After the first phone test (16 Nov, v0.3.7):** the emulator matrix below passed while the phone
> struggled. The emulator's camera renders every frame sharp and in focus; on the phone, auto
> exposure (20 ms) smeared the bars and close-up focus kept hunting. What the emulator cannot show
> is now covered by the decode benchmark (`tools\decode_bench.bat`: real label crops with added
> blur, defocus, distance and noise, run through the app's decoders on a phone or emulator) and by
> real phone recordings replayed in `ReplayTest`. The emulator remains the test for the counting
> logic. Note: since 0.3.7 the debug build is `com.sweepscan.debug`.
>
> `AppearanceBenchmarkTest` (on the phone) measured the MediaPipe embedder that 0.4.0 carries for
> re-identification: on 59 real label crops the same barcode across frames and different barcodes
> got the same cosine similarity (median 0.59 vs 0.59; 33 ms per crop). It stays off until a model
> trained on these labels exists.

No phone needed: the emulator's virtual-scene camera renders a 3D room, and one wall can show any
image. The tools here put a wall of stacked shoe boxes on it (drawn after photos of the stock room),
sweep the camera over it the way a person would, and score what the app counted against the
scene's ground truth. This exercises the real chain: CameraX → ML Kit → zxing-cpp → optical flow →
tracker → session → field log.

What it cannot test: Bluetooth typing into a PC, the real phone's exposure, focus and zoom, and
real optics. The emulated camera gives 720p at 4–7 analysed frames per second; the phone should
manage 20–30. `WarehouseSimulationTest` covers phone frame rates (300 boxes at 25 fps) on the PC.

## One-time setup

```bash
sdkmanager "system-images;android-36;default;x86_64"
avdmanager create avd -n sweeptest -k "system-images;android-36;default;x86_64" -d pixel_7
```

In `%USERPROFILE%\.android\avd\sweeptest.avd\config.ini` set `hw.camera.back=virtualscene`.
The image takes about 5 GB of disk; delete it afterwards with
`avdmanager delete avd -n sweeptest` and `sdkmanager --uninstall "system-images;android-36;default;x86_64"`.

## Run the test matrix

```bash
# 1. Emulator on the PC's graphics card (headless is fine)
emulator -avd sweeptest -no-window -no-audio -gpu host -camera-back virtualscene

# 2. App with the x86_64 natives (the phone build is arm64 only)
gradlew.bat :app:assembleDebug -Pemulator
adb install -r app/build/outputs/apk/debug/app-debug.apk

# 3. Scenes, then every scene x path (about 35 minutes)
python tools/make_scene.py koridor samples/scenes
python tools/make_scene.py zor samples/scenes
python tools/make_scene.py ikiz samples/scenes
python tools/run_emulator_matrix.py samples
```

`run_emulator_matrix.py` resets the app, puts the scene on the wall, starts a field recording,
replays the camera path (pressing Pause and Resume between stacks on the stack-by-stack path),
pulls `Download/SweepScan` into `samples/recordings/<scene>-<path>/`, writes the `.expected` file
from the ground truth and prints a table. `samples/` is not committed.

**Scenes** (`tools/make_scene.py`): 5 stacks of 8 boxes, drawn twice life size so the emulator's
2 m wall texture keeps the bars sharp (40 cm box ends, 12 cm barcodes; the camera stands twice as
far as in real life). Dark box, lime lid, white label on the right of the box end with model code,
size, colour and a 13-digit Code 128; stacks lean and are slightly out of line.

| Scene | What it tests |
|---|---|
| `koridor` | An ordinary delivery: 40 boxes, 27 barcodes, a third of the boxes share a barcode (stacked, side by side, in other stacks), two 8-digit barcodes |
| `zor` | 3 blurred, 1 torn and 2 missing labels (should not count: 34 boxes), 2 tilted labels and 3 with glare (should count) |
| `ikiz` | Identity worst case: a stack of 8 identical boxes, two barcodes alternating in two stacks (checkerboard), twins in pairs |

**Paths** (`tools/make_camera_path.py`): the camera faces the wall squarely, one box across the
picture, and sweeps at about 6 cm/s on the wall (3 cm/s life size).

| Path | Movement |
|---|---|
| Y1 | Stack by stack, top to bottom, Pause/Resume between stacks (the documented way of working) |
| Y2 | Down one stack, up the next, without pausing |
| Y3 | Y2 at twice the speed |
| Y4 | Every stack down and straight back up |
| Y5 | From twice as far, two stacks in view |

## Results (v0.3.3, 15 November 2026)

| Run | Should count | Counted | Over | Missing | Frames/s |
|---|---|---|---|---|---|
| koridor-Y1 | 40 | 40 | 0 | 0 | 4.5 |
| koridor-Y2 | 40 | 40 | 0 | 0 | 6.0 |
| koridor-Y3 | 40 | 40 | 0 | 0 | 5.8 |
| koridor-Y4 | 40 | 65 | 25 | 0 | 6.2 |
| koridor-Y5 | 40 | 1 | 0 | 39 | 6.9 |
| zor-Y1 | 34 | 34 | 0 | 0 | 4.8 |
| zor-Y2 | 34 | 34 | 0 | 0 | 6.1 |
| ikiz-Y1 | 40 | 40 | 0 | 0 | 4.5 |
| ikiz-Y2 | 40 | 40 | 0 | 0 | 5.7 |

- **Y4, going back over a stack, counts it again (fixed in 0.3.11:** lost tracks are now remembered
  for the whole sweep, after the same double count showed up on the phone; Y4 replays to 40). A box that left the picture is remembered for
  5 s, or longer only while the view stays put; sweeping back up a whole stack takes longer than
  that. Remembering lost boxes for the whole sweep (`longReid` in `ReplayTest`) brings this run to
  40 and changes none of the others, but the emulator's motion estimate is nearly perfect; on the
  phone the estimate drifts, and a long memory could then swallow an identical box. It stays a
  replay variant until field recordings show the drift. Until then: don't sweep back over counted
  boxes; if a stack needs a second pass, delete the sweep (hold Undo) and scan it again.
- **Y5, two stacks in view, reads almost nothing.** At that distance the barcode is about 100
  pixels wide in the 720p picture: less than one pixel per bar, where ML Kit needs two. The same
  arithmetic applies to the phone (see "Two boxes across" in `docs/saha-testi.md`).
- **The tilted, glared and 8-digit labels counted; the blurred, torn and missing ones were not
  counted** and raised 1–2 "seen but not read" warnings per run.

The viewer (`tools/viewer.html`) opens any of these folders.

## From a recording to a regression test

Copy a recording's `.jsonl` and `.expected` to `core/src/test/resources/field/`; `ReplayTest`
replays it under every tracker configuration on each build. Kept there: `emu-koridor-Y2`,
`emu-koridor-Y4` (the double count above), `emu-zor-Y1`, `emu-ikiz-Y1`, and the earlier
`emulator-walk*` recordings.

## What this found

Before it ever ran on the phone, running the app here turned up:

- controls hidden under the navigation bar and an action bar covering the review screen on
  Android 15+ (edge to edge);
- log files that MediaStore would have renamed to `.jsonl.txt`;
- a label re-counted every few seconds while the camera was held still;
- an undercount warning that grew without bound;
- a camera capability dump that was not valid JSON (`1/2` written bare);
- a screen-sized "unread" marker when the camera faced a plain surface;
- **a stack of identical boxes undercounted by up to 9 of 40**: going down the stack, "every label
  moved up by one box" got as many votes as the real, small motion, and the tracks hopped onto
  their neighbours. Label votes now have to agree with optical flow (or with the current velocity)
  first; `noPrior` in `ReplayTest` shows the old behaviour.
