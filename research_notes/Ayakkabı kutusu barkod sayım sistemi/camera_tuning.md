# Camera tuning for reading small Code 128 barcodes from a moving phone (Galaxy S23 Ultra, CameraX 1.5.2)

Scope: SweepScan. LifecycleCameraController with Preview (16:9), ImageAnalysis (YUV, KEEP_ONLY_LATEST, 1080p default, 1440p/4K optional), optional VideoCapture, continuous AF, pinch zoom. The phone is swept over stacked shoe boxes 0.5–1.5 m away, under LED ceiling spots, in Turkey (50 Hz mains). Labels are white, barcodes about 5 cm wide.

Note on sourcing: the S23 Ultra's Camera2 capability dump (hardware level, MANUAL_SENSOR, available FPS ranges, YUV sizes, max ISO and exposure) was **not found in any public source**. All S23U-specific numbers below are marked "VERIFY ON DEVICE". Geometry and optics numbers are my own calculations from stated assumptions. They are listed under Inferences.

---

## 1. Motion blur: exposure time, capping exposure in CameraX, LED flicker, Samsung manual control

### Takeaway
Auto exposure at 30 fps indoors will use exposures near 1/30–1/60 s. That smears a hand sweep over many barcode modules. You need roughly ≤1 ms (better 0.5 ms) when the motion runs across the bars. You only need a few ms when the motion runs along the bars, so tell users to sweep parallel to the bars (vertically along a column of boxes when barcodes are horizontal). There are two ways to force short exposure. One is `CONTROL_AE_PRIORITY_MODE = SENSOR_EXPOSURE_TIME_PRIORITY`, which keeps AE running and fixes the shutter (Android 16+, if the device lists it). The other is `CONTROL_AE_MODE_OFF` with manual `SENSOR_EXPOSURE_TIME` and `SENSOR_SENSITIVITY` (needs the MANUAL_SENSOR capability). Apply either through `Camera2CameraControl` on the controller's camera.

### Cited Findings
- `CONTROL_AE_TARGET_FPS_RANGE` is "the range over which auto-exposure can adjust the capture frame rate to maintain good exposure". In practice the AE cannot expose longer than the frame duration of the minimum FPS. Raising the minimum FPS caps the longest exposure, but it does **not** force short exposures. — [Android CaptureRequest reference](https://developer.android.com/reference/android/hardware/camera2/CaptureRequest)
- `SENSOR_EXPOSURE_TIME` (Long, nanoseconds) and `SENSOR_SENSITIVITY` (ISO) only apply when AE is off (`CONTROL_AE_MODE_OFF`). Manual exposure generally requires `REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR`. — [Android CaptureRequest reference](https://developer.android.com/reference/android/hardware/camera2/CaptureRequest)
- `CONTROL_AE_PRIORITY_MODE` has the values `OFF`, `SENSOR_EXPOSURE_TIME_PRIORITY` (AE stays active and honours the app-chosen `SENSOR_EXPOSURE_TIME`) and `SENSOR_SENSITIVITY_PRIORITY`. The reference page excerpt did not state the API level. — [Android CaptureRequest reference](https://developer.android.com/reference/android/hardware/camera2/CaptureRequest)
- `CONTROL_AE_EXPOSURE_COMPENSATION` is an index multiplied by `CONTROL_AE_COMPENSATION_STEP`. CameraX exposes it as `CameraControl.setExposureCompensationIndex()`, with the range in `ExposureState.getExposureCompensationRange()`. — [CameraX configuration guide](https://developer.android.com/media/camera/camerax/configuration)
- `CONTROL_AE_ANTIBANDING_MODE` values are `OFF`, `50HZ`, `60HZ` and `AUTO` (AUTO adapts to the detected illumination). `CaptureResult.STATISTICS_SCENE_FLICKER` reports detected 50/60 Hz flicker. — [Android CaptureRequest reference](https://developer.android.com/reference/android/hardware/camera2/CaptureRequest)
- CameraX developers set FPS through `Camera2Interop.Extender(builder).setCaptureRequestOption(CONTROL_AE_TARGET_FPS_RANGE, Range(…))` on the use-case builder, or through `Camera2CameraControl.setCaptureRequestOptions(...)` at runtime. A mid-session FPS change can briefly freeze the preview on some devices. The range should come from `CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES`. — [camerax-developers group threads via search](https://groups.google.com/a/android.com/g/camerax-developers/c/PbAXFt2ZELs), [setTargetFrameRate thread](https://groups.google.com/a/android.com/g/camerax-developers/c/d0SDE4sA7-c)
- `setTargetFrameRate` uses CameraX's closest-match logic and can pick a different range than requested (the example asked for 14 fps and got [10,30]). Read the actual range back from `CaptureResult.CONTROL_AE_TARGET_FPS_RANGE` in a session capture callback. — [setTargetFrameRate thread](https://groups.google.com/a/android.com/g/camerax-developers/c/d0SDE4sA7-c)
- CameraX 1.5.0 (Sep 2025) added a deterministic frame-rate API: `CameraInfo.getSupportedFrameRateRanges(sessionConfig)` and `SessionConfig.Builder.setExpectedFrameRateRange` / `setFrameRateRange(Range)`. CameraX 1.5.0-alpha02 fixed Camera2Interop FPS being overwritten on LEGACY devices and fixed frame rate with stream sharing. — [CameraX release notes](https://developer.android.com/jetpack/androidx/releases/camera)
- Scandit recommends sharp frames with no motion blur, uncompressed input (not JPEG) and no app-side pre-processing. Its SDK has an "adaptive exposure" option that trades frame rate for brightness by allowing longer exposures in low light, which is the opposite of what a fast sweep needs. — [Scandit low-level API performance](https://docs.scandit.com/stable/c_api/performance.html), [Scandit camera settings](https://docs.scandit.com/data-capture-sdk/kmp/core/api/camera-settings.html)
- An industry source says a barcode shifting by only 1–2 px during exposure can hurt decoding, and that removing motion blur needs short exposures plus enough light. — [tera-digital, barcode scanner exposure](https://tera-digital.com/blogs/barcodes/barcode-scanner-exposure-settings)
- The S23 Ultra's stock camera offers "true manual controls" for photo and video (Pro / Pro Video mode). This shows the hardware supports manual shutter and ISO, but it does not prove the same is exposed to third-party Camera2 apps. — [Tom's Guide S23 Ultra review](https://tomsguide.com/features/ive-been-using-samsung-galaxy-s23-ultra-for-nearly-a-year-5-camera-upgrades-the-galaxy-s24-ultra-needs)
- A FULL hardware level implies MANUAL_SENSOR. User reports claim that some Samsung (mostly Exynos or A-series) phones expose only LIMITED. No authoritative S23 Ultra hardware-level report was found. — [Samsung EU community thread](https://eu.community.samsung.com/t5/galaxy-a-series/camera2-api-support-full-or-level-3-support/m-p/7563026), [/e/ framework commit](https://gitlab.e.foundation/e/os/android_frameworks_base/-/commit/ed2fdb56564189a568e11611f726bde5d0916d0a)
- The S23 Ultra main camera is a 200 MP 1/1.3" ISOCELL HP2. — [PetaPixel](https://petapixel.com/2023/02/01/samsung-unveils-new-galaxy-s23-devices-flagship-packs-200mp-sensor)

### Inferences

**Geometry assumptions.** These are my numbers, so verify FOV on the device from `SENSOR_INFO_PHYSICAL_SIZE` and `LENS_INFO_AVAILABLE_FOCAL_LENGTHS`.
- The main lens is about 24 mm equivalent, which gives a horizontal FOV of about 72° for a 16:9 crop.
- Scene width covered: about 0.72 m at 0.5 m, 1.44 m at 1.0 m and 2.2 m at 1.5 m.
- At 1920 px across, that is about 2.7, 1.33 and 0.89 px per mm.
- The focal length in pixels at 1080p is f_px ≈ 960 / tan(36°) ≈ 1330 px.

**Image motion from a sweep.**
- Sliding the phone sideways at speed v (translation): image speed ≈ v × px/mm. At 0.5 m/s (0.5 mm/ms) and 1 m distance, that is about 0.67 px/ms at 1080p. At 0.5 m it is about 1.3 px/ms. At 4K, double these.
- Turning the phone (panning) at ω: image speed ≈ ω × f_px. At 30°/s that is about 0.7 px/ms. At 90°/s it is about 2.1 px/ms.
- Measured in barcode modules (more meaningful): with X ≈ 0.41 mm (see section 3), 0.5 m/s ≈ 1.2 modules per ms. Keeping blur ≤ 0.5 module across the bars needs an exposure of about 0.4 ms (≈1/2500 s). Keeping it ≤ 1 module needs about 0.8 ms (≈1/1250 s).
- With AE at 1/30–1/60 s indoors, blur is 20–40 modules, so a barcode crossed perpendicular to its bars is unreadable during motion.

**Sweep direction matters more than any setting.**
- Blur parallel to the bars (moving along the bar length) barely affects 1D decoding.
- If labels are horizontal (bars vertical), sweeping up and down a stack of boxes moves along the bars and tolerates exposures of several ms.
- Sweeping sideways along a row moves across the bars and needs ≤1 ms.
- Recommend a vertical, column-by-column sweep in the UX, and test both directions on Tuesday.

**Light budget (rough).**
- Assume a stock room at about 300–500 lux and f/1.7.
- Correct exposure at ISO 100 is about 1/40–1/70 s.
- 1/1000 s needs about ISO 1500–2500. 1/2000 s needs about ISO 3000–5000.
- Noise at those ISOs is acceptable for high-contrast black-on-white bars. A good target is 1/1000–1/2000 s with ISO capped near the device maximum, because noise hurts 1D decoding less than blur does.
- Read `SENSOR_INFO_SENSITIVITY_RANGE` and `SENSOR_INFO_EXPOSURE_TIME_RANGE` on the S23U. VERIFY ON DEVICE.

**Recommended CameraX implementation order.** Check each step's capability first.
1. `Camera2CameraInfo.from(controller.cameraInfo).getCameraCharacteristic(...)`. Read `INFO_SUPPORTED_HARDWARE_LEVEL`, `REQUEST_AVAILABLE_CAPABILITIES` (look for MANUAL_SENSOR), `CONTROL_AE_AVAILABLE_PRIORITY_MODES` (Android 16 key; VERIFY that it exists on the S23U's One UI build), `CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES` and the sensitivity/exposure ranges.
2. Preferred: `Camera2CameraControl.from(controller.cameraControl!!).setCaptureRequestOptions(CaptureRequestOptions.Builder().setCaptureRequestOption(CONTROL_AE_PRIORITY_MODE, SENSOR_EXPOSURE_TIME_PRIORITY).setCaptureRequestOption(SENSOR_EXPOSURE_TIME, 1_000_000L /*1 ms*/).setCaptureRequestOption(CONTROL_AE_TARGET_FPS_RANGE, Range(30,30)).setCaptureRequestOption(CONTROL_AE_ANTIBANDING_MODE, CONTROL_AE_ANTIBANDING_MODE_50HZ).build())`. AE then still adjusts ISO automatically.
3. Fallback when MANUAL_SENSOR is present: `CONTROL_AE_MODE_OFF` plus `SENSOR_EXPOSURE_TIME` (e.g. 1 ms) plus `SENSOR_SENSITIVITY` (e.g. 1600–3200). Also set `SENSOR_FRAME_DURATION` = 33,333,333 ns for 30 fps. The app then owns brightness, so add a simple app-side loop that adjusts ISO from the mean Y of the label ROI every ~0.5 s. Note that AE off disables CameraX exposure compensation.
4. Last resort with no manual control: `CONTROL_AE_TARGET_FPS_RANGE = [30,30]`, or [60,60] if listed, plus negative exposure compensation (e.g. -1 to -2 EV). This makes AE choose a shorter shutter and lower brightness, which works because the white labels are often over-exposed anyway. It cannot reach 1 ms, but [60,60] caps exposure at ≤16.7 ms.
- With `LifecycleCameraController` there is no use-case builder, so `Camera2Interop.Extender` cannot be used. Use `Camera2CameraControl` after the camera is bound (`controller.cameraControl` is non-null once initialized, e.g. after `controller.initializationFuture` completes). Re-apply after lens switches or rebinds, since options can be reset when the camera closes. VERIFY.

**LED flicker.**
- Turkey's 50 Hz mains gives 100 Hz light ripple on LEDs with poor drivers. Anti-banding works by making exposure a multiple of 10 ms, which is impossible at 1 ms.
- So with short exposures expect horizontal brightness bands from the rolling shutter. The band period is about 10 ms of readout, so a few broad bands per frame.
- For 1D barcodes broad, smooth bands are mostly harmless because decoding uses local contrast. A deep ripple can make some frames or regions dark, and multi-frame voting (section 5) compensates.
- Still set `ANTIBANDING_MODE_50HZ` (or AUTO) so that AE in fallback mode avoids 1/120 s-style beat patterns.
- Check `STATISTICS_SCENE_FLICKER` in the capture callback to log whether the stock room flickers.

### Gaps
- S23 Ultra Camera2 hardware level, MANUAL_SENSOR flag, `CONTROL_AE_AVAILABLE_PRIORITY_MODES`, ISO/exposure ranges and the available FPS ranges for the logical back camera: no public dump found. Measure with a probe app or by logging `CameraCharacteristics` (VERIFY ON DEVICE).
- The exact API level of `CONTROL_AE_PRIORITY_MODE` was not confirmed from the fetched excerpt. My understanding is Android 16 (API 36), so check the per-field "Added in".
- The real lux level in the stock room and whether its LED drivers flicker are unknown. Measure with a lux meter app and the `STATISTICS_SCENE_FLICKER` log.
- No measured sweep speeds for stock-room workers were found. The 0.3–1 m/s and 30–90°/s figures are assumptions.

---

## 2. Focus: CONTINUOUS_VIDEO vs CONTINUOUS_PICTURE, fixed focus, hyperfocal, hunting, metering regions

### Takeaway
On the main (1x) lens at 0.5–1.5 m, depth of field is wide enough that a fixed focus at about 0.8–1.0 m (≈1.0–1.25 diopters) covers most of the range and removes hunting completely. That is my estimate, so verify it with a focus sweep test. Keep continuous AF only as an option, preferably `CONTINUOUS_PICTURE`, which is faster, plus a centre AF/AE metering region. Tele lenses have much shallower depth of field and need AF.

### Cited Findings
- `CONTINUOUS_VIDEO` and `CONTINUOUS_PICTURE` both keep adjusting the lens continuously. VIDEO is tuned for smooth, slow lens movement to avoid visible hunting in recordings. PICTURE is tuned to focus quickly for stills. With `AF_MODE_OFF`, the app controls `LENS_FOCUS_DISTANCE`. — [Android CaptureRequest reference](https://developer.android.com/reference/android/hardware/camera2/CaptureRequest)
- `LENS_FOCUS_DISTANCE` is measured from the front lens surface in diopters (1/m) when `LENS_INFO_FOCUS_DISTANCE_CALIBRATION` is APPROXIMATE or CALIBRATED. If UNCALIBRATED, the values have no physical unit. — [Android CaptureRequest reference](https://developer.android.com/reference/android/hardware/camera2/CaptureRequest)
- CameraX `startFocusAndMetering(FocusMeteringAction)` sets AF/AE/AWB regions from `MeteringPoint`s (default size 0.15 of the frame). The flags are FLAG_AF, FLAG_AE and FLAG_AWB. Auto-cancel defaults to 5 s and can be changed with `setAutoCancelDuration`. CameraX uses as many points as the device supports. `SurfaceOrientedMeteringPointFactory` maps ImageAnalysis coordinates. — [CameraX configuration guide](https://developer.android.com/media/camera/camerax/configuration)
- ML Kit says poor focus reduces accuracy. — [ML Kit barcode scanning, Android](https://developers.google.com/ml-kit/vision/barcode-scanning/android)

### Inferences

**Depth-of-field estimate, main camera.**
- Assumptions: about 24 mm equivalent at f/1.7, and a 1/1.3" sensor (crop factor about 3.5–4), so roughly f/6–f/7 equivalent depth of field.
- With a strict circle of confusion of 1 px at 1080p (about 0.019 mm full-frame equivalent), the hyperfocal distance is about 4–5 m.
- Focused at about 0.9 m, acceptable sharpness spans roughly 0.75–1.1 m. With a looser circle of confusion of 2 px, it spans about 0.65–1.5 m.
- So one fixed focus covers most of the 0.5–1.5 m working range at the barcode's needed sharpness. Very near (0.5 m) or far (1.5 m) boxes may be slightly soft.

**Implementation for fixed focus.**
- `Camera2CameraControl` with `CONTROL_AF_MODE = CONTROL_AF_MODE_OFF` and `LENS_FOCUS_DISTANCE = 1.1f` (diopters ≈ 0.9 m).
- Requires `LENS_INFO_MINIMUM_FOCUS_DISTANCE > 0` and a calibration of APPROXIMATE or CALIBRATED. Check this on the S23U (VERIFY ON DEVICE).
- Offer a "focus distance" slider (0.6–1.5 m) in the debug UI and run the Tuesday test at 2–3 settings.

**Why continuous AF struggles here.**
- Phase-detect AF on phones hunts when the scene changes quickly: box edges, glossy black surfaces and specular highlights from LED spots.
- VIDEO mode is deliberately slow to react, so it lags behind a sweep. PICTURE mode reacts faster but may refocus mid-sweep.
- If AF is kept, use `CONTINUOUS_PICTURE` and a centre metering point (FLAG_AF|FLAG_AE, size about 0.3) with auto-cancel disabled (`disableAutoCancel()`). This makes AF and AE ignore the glossy black/yellow box faces and the ceiling spots around the label.
- Note that CameraController's built-in tap-to-focus triggers its own FocusMeteringAction and may override these settings.

**Hybrid option.** Lock AF once at the start of a column with `CONTROL_AF_TRIGGER_START`, or do a quick tap-to-focus. Then keep it fixed during the sweep.

**AE metering.** The scene is dark glossy boxes plus small white labels plus bright ceiling spots. Centre-weighted metering on the frame average will over-expose the white labels and wash out thin bars. A centre AE region plus a little negative exposure compensation keeps the labels from clipping.

### Gaps
- No published data was found on the S23 Ultra main-lens focus calibration quality (APPROXIMATE vs CALIBRATED), minimum focus distance values, or exact focal length and aperture as reported by Camera2.
- No vendor source was found that quantifies AF hunting during a sweep. The above is reasoning, not measurement.

---

## 3. Resolution vs frame rate; pixels per module; stream combinations; cropping

### Takeaway
ML Kit needs at least 2 px per narrow bar (module). A 5 cm Code 128 carrying 13 digits has about 123 modules (Code Set C) to 178 modules (Set B), so X ≈ 0.28–0.41 mm, and the barcode needs about 250–360 px along its length. At 1080p with the 1x lens, this is met only up to about 0.4–0.5 m. At 1 m you need about 2x zoom on the 1080p stream (real optical-equivalent detail from the 200 MP sensor's oversampled readout, see section 4), or 4K analysis, or the 3x tele. Hold the phone so the long image axis lies along the barcode. In portrait, a horizontal barcode gets only 1080 px across, not 1920.

### Cited Findings
- ML Kit says the smallest meaningful unit should be at least 2 px wide. For EAN-13, the 1–4 unit bars should be at least 2/4/6/8 px, and the full 95-module code at least 190 px wide. — [ML Kit barcode scanning, Android](https://developers.google.com/ml-kit/vision/barcode-scanning/android)
- ML Kit recommends 1280x720 or 1920x1080 for typical apps so barcodes can be read from farther away. Do not use native resolution (>10 MP). Request about 2 MP or less. — [ML Kit barcode scanning, Android](https://developers.google.com/ml-kit/vision/barcode-scanning/android)
- GS1: the X-dimension should never be below 0.250 mm. For general-distribution scanning, GS1-128 X is 0.495–1.016 mm, and the minimum symbol height is 31.75 mm. — [GS1 UK GS1-128 sizing](https://www.gs1uk.org/knowledge-hub/barcodes/how-big-should-a-gs1-128-barcode-used-in-general-distribution-be)
- An older GS1 logistic table gives GS1-128 X as min 0.495, target 0.940 and max 1.016 mm. Smaller is allowed only when the item physically cannot carry the full size. — [GS1 GSCN 16-434](https://ref.gs1.org/standards/genspecs/gscn/2016/GSCN_16-434_Table2and5.pdf)
- CameraX ImageAnalysis maximum resolution is the device's maximum YUV_420_888 output size from `StreamConfigurationMap.getOutputSizes()`. CameraX may compromise on resolution to make the session work, so always check the delivered `ImageProxy` size. — [CameraX configuration guide](https://developer.android.com/media/camera/camerax/configuration)
- Camera2 guaranteed stream tables:
  - LIMITED includes `PRIV PREVIEW + YUV RECORD + JPEG RECORD`.
  - FULL includes `YUV 640x480 + PRIV PREVIEW + YUV MAXIMUM`, and 2-stream `PRIV PREVIEW + YUV MAXIMUM`.
  - LEVEL_3 adds `PRIV PREVIEW + PRIV 640x480 + YUV MAXIMUM + RAW MAXIMUM`.
  - In the regular tables, no three-stream combination of PRIV PREVIEW + YUV (analysis) + PRIV RECORD (video) is guaranteed. Such combinations appear only for 10-bit-capable devices (`PRIV PREVIEW + PRIV RECORD + YUV RECORD`) and STREAM_USE_CASE-capable devices.
  - PREVIEW = min(screen size, 1080p), RECORD = max CamcorderProfile size, MAXIMUM = max output size. Combinations outside the tables may still work, so check them with `isSessionConfigurationSupported`.
  — [Android CameraDevice reference](https://developer.android.com/reference/android/hardware/camera2/CameraDevice)
- CameraX `ViewPort` + `UseCaseGroup` make all use cases' crop rects point to the same sensor area. `PreviewView.getViewPort()` gives a WYSIWYG viewport. — [CameraX configuration guide](https://developer.android.com/media/camera/camerax/configuration)
- CameraX 1.5.0 added `ImageAnalysis.Builder.setOutputImageFormat(OUTPUT_IMAGE_FORMAT_NV21)`. — [CameraX release notes](https://developer.android.com/jetpack/androidx/releases/camera)

### Inferences

**Module count for a 13-digit Code 128.** Calculated from the Code 128 structure: 11 modules per symbol, 13-module stop, plus quiet zones of ≥10X on each side, which are not counted in the 5 cm.
- **Code Set C** (digit pairs; an encoder handles 13 digits as 6 pairs plus 1 digit with a code-set switch): Start + 6 + switch + 1 + check = 10 symbols × 11 + 13 = **123 modules**.
- **Code Set B** (one digit per symbol): 15 × 11 + 13 = **178 modules**.
- A GS1-128 FNC1 adds 11 modules.
- In a 50 mm width, X ≈ 0.41 mm (Set C) or 0.28 mm (Set B).
- **Measure on a real label:** photograph it with a ruler and count the module width, or decode it and check which code set the encoder used.

**Required pixels.**
- At 2 px/module (ML Kit), the barcode needs about 250 px (Set C) or about 360 px (Set B) along its length. That means about 4.9 px/mm (Set C) or about 7.1 px/mm (Set B) at the label.
- Main 1x lens, long axis along the barcode, at 1 m:

  | Stream | px/mm | px/module, Set C | px/module, Set B |
  |---|---|---|---|
  | 1080p | 1.33 | 0.55 | 0.37 |
  | 4K | 2.7 | 1.1 | 0.75 |

  At 1080p even 0.5 m gives only about 1.1 px/module.
- Conclusion: **at the 0.5–1.5 m distance with 1x and 1080p, the barcodes are under-sampled by 2–5x.** You need some combination of a shorter distance (about 0.3–0.5 m), zoom (2–3x) and higher analysis resolution.
- These numbers depend on the assumed FOV and on the real X dimension. If decoding already works in the field, the true X is larger or the distance is shorter than assumed.
- Decoders sometimes read 1D codes at about 1.2–1.5 px/module when sharp. Treat 2 px as the reliable target.

**Orientation.**
- ML Kit and the camera deliver the sensor-native landscape buffer, 1920 px wide. In portrait UI, a horizontal barcode lies along the 1080 px axis.
- Either use the app in landscape, or design the sweep so barcodes lie along the long axis.
- This alone is a 1.8x gain in px/module.

**Frame rate vs resolution.**
- ImageAnalysis at 4K is 4x the pixels of 1080p. Copy, YUV conversion and ML Kit detection costs scale roughly with pixel count, so expect analysis throughput to drop to roughly a quarter to a half, plus more heat. Measure ML Kit latency per frame on the S23U at each size and log it (VERIFY ON DEVICE).
- Ask for 1440p (`ResolutionSelector` with `ResolutionStrategy(Size(2560,1440), FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)`) as a middle ground.
- With `LifecycleCameraController`, use `setImageAnalysisResolutionSelector(...)`. CameraX 1.5's `SessionConfig.setFrameRateRange` / `getSupportedFrameRateRanges` can tell whether 30 fps holds at that size. It is not clear whether CameraController exposes SessionConfig (VERIFY in the 1.5.2 API).

**Crop to a central band at high resolution.**
- `ImageProxy.cropRect` / ViewPort only sets metadata. `InputImage.fromMediaImage()` sends the full buffer to ML Kit. I believe it ignores the crop rect (VERIFY).
- To analyse only a central band (e.g. the middle 1/3 of height of a 4K frame) you must copy that ROI's Y and interleaved VU planes into an NV21 byte array and call `InputImage.fromByteArray(roi, w, h, rotation, InputImage.IMAGE_FORMAT_NV21)`. ML Kit 1D decoding can also work on Y-only data treated as NV21 with neutral chroma.
- A 3840×720 band costs about the same as 1080p but keeps 4K horizontal sampling: 2x px/module versus 1080p at the same zoom. This is probably the best resolution/FPS trade-off for a horizontal-barcode, landscape sweep.

**Stream combinations.**
- Preview (PRIV) + ImageAnalysis (YUV) at 1080p is safe on any level.
- Adding VideoCapture (PRIV RECORD) makes three streams with no guaranteed table entry, so CameraX will either find a device-specific supported combination or fall back to StreamSharing (section 6).
- 4K YUV analysis + preview resembles FULL's `PRIV PREVIEW + YUV MAXIMUM`, but "MAXIMUM" streams are not guaranteed at 30 fps. Check `StreamConfigurationMap.getOutputMinFrameDuration(YUV_420_888, size)` for 3840x2160 and 2560x1440. It should be ≤ 33.3 ms (VERIFY ON DEVICE).

### Gaps
- No public list was found of the S23 Ultra's YUV output sizes and min frame durations per physical or logical camera, or of whether Preview + 4K YUV + 1080p Video binds without StreamSharing.
- No benchmark was found of ML Kit barcode latency vs input size on Snapdragon 8 Gen 2.
- The text of the current GS1 General Specifications table 5.12.3 (e.g. table 9 for GS1-128) was not retrieved. Only GS1 UK summaries and an older table were found.
- Not confirmed whether ML Kit honours `ImageProxy.cropRect`.

---

## 4. Zoom and lenses on the S23 Ultra

### Takeaway
CameraX sees one logical back camera. `setZoomRatio()` lets the Samsung HAL switch physical lenses (ultra-wide, main, 3x, 10x), and the switch point and light-based fallback are vendor-controlled. For SweepScan, a 1.5–2.5x zoom on the main sensor is the sweet spot at 1080p. The 3x tele (f/2.4, smaller sensor, about 22 cm minimum focus measured on the S22U) helps at 1–1.5 m but is darker. The 10x (f/4.9, about 45 cm minimum focus on the S22U) is too dark indoors and has too narrow a FOV.

### Cited Findings
- Samsung phones expose one LOGICAL camera for seamless lens switching, plus PHYSICAL camera IDs for individual lenses. Samsung does not document which ID maps to which lens. — [Samsung developer forum](https://forum.developer.samsung.com/t/where-do-i-find-the-camera-lens-ids-for-various-galaxy-phones/37971)
- The physical cameras of a logical multi-camera are queried via `getPhysicalCameraIds()`. Physical-only IDs are not in `getCameraIdList()`. — [Android multi-camera API](https://developer.android.com/media/camera/camera2/multi-camera)
- The S23 Ultra has a 10 MP 3x telephoto at f/2.4 and a 10 MP 10x folded telephoto at f/4.9. — [soyacincau S23 Ultra article](https://soyacincau.com/2023/04/26/samsung-galaxy-s23-ultra-200-mp-megapixel-camera/)
- The S23 Ultra kept the S22 Ultra's non-main sensors unchanged. — [Android Central: Galaxy S23 cameras explained](https://www.androidcentral.com/phones/galaxy-s23-cameras-explained)
- Measured minimum focus distances on the S22 Ultra: 3x tele about 220 mm, 10x tele about 450 mm. No S23 Ultra measurement was published. — [ProVideo Coalition](https://www.provideocoalition.com/do-you-know-how-close-your-smartphones-lenses-focus/)
- CameraX zoom: `setZoomRatio()` must be within `ZoomState.minZoomRatio..maxZoomRatio`. `setLinearZoom()` is 0..1. A new zoom call cancels the pending one. — [CameraX configuration guide](https://developer.android.com/media/camera/camerax/configuration)
- ML Kit auto-zoom (bundled 17.2.0+ / unbundled 18.3.0+) calls a `ZoomCallback` with a suggested zoom when barcodes are too far away. Set `setMaxSupportedZoomRatio()` from the hardware. — [ML Kit barcode scanning, Android](https://developers.google.com/ml-kit/vision/barcode-scanning/android)

### Inferences

**Digital zoom gives real detail up to about 2x at 1080p.**
- The 200 MP main sensor normally reads out binned at about 12.5 MP (≈4000×3000), and the ISP scales that down to the 1920-wide analysis stream.
- A 2x zoom ratio crops a ≈2000-px-wide region and scales it to 1920, so pixel density doubles with almost no interpolation.
- At 4K analysis, 1x already uses about the full binned width, so digital zoom adds no new information.
- This is my reasoning about typical ISP pipelines. Verify with a resolution chart or by comparing barcode edge sharpness at 1x/4K vs 2x/1080p.

**Switching to the 3x tele.**
- It happens around zoom ratio 3.0 on the logical camera.
- Samsung's HAL may stay on the main sensor (cropped) when the subject is closer than the tele's minimum focus or in low light. Log `CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID` (API 29+) through a Camera2Interop session capture callback to see which lens is active (VERIFY ON DEVICE).
- The tele gathers about 1 stop less light (f/2.4 vs f/1.7) and has a much smaller sensor. With exposure capped at about 1 ms, ISO and noise rise noticeably.

**Working-range math with zoom.**
- 2x at 1080p, 1 m: about 2.7 px/mm, which is 1.1 px/module (Set C).
- 3x tele at 1080p, 1 m: about 4 px/mm, which is 1.6 px/module.
- 3x at 4K, or 3x with a 4K band crop: about 3.3 px/module, which works.
- Practical plan: default 2x zoom at 0.5–0.8 m. Offer 3x for top shelves at 1–1.5 m.
- More zoom narrows the FOV, so fewer boxes per frame and a faster apparent motion (px/ms scales with zoom). Shutter needs to shrink proportionally.

**Pinch zoom during a sweep** changes the motion scale and can trigger a lens switch with a brief AE/AF re-convergence. Prefer fixed preset zoom buttons (1x/2x/3x).

**ML Kit auto-zoom** may be useful for the "too far" case. It conflicts with user pinch, so pick one.

### Gaps
- No S23 Ultra measurement of 3x or 10x minimum focus distance was found (the S22U figure is a proxy).
- No source on the S23U HAL's zoom-switch thresholds or on whether third-party apps get Samsung's 200 MP in-sensor "2x crop" mode (the stock app's lossless 2x).
- Physical camera IDs for the S23U are not documented.

---

## 5. Vendor tips (Scandit, Dynamsoft, ML Kit): torch, frame dropping, single in-flight processing, thermal, battery

### Takeaway
Keep the analysis pipeline to one frame in flight and drop the rest. Restrict ML Kit to Code 128. Confirm each decode across 2+ frames. Throw away blurry frames cheaply before decoding. Use the torch selectively, because glossy boxes reflect it. Watch thermal status and step resolution or FPS down before the OS throttles.

### Cited Findings
- ML Kit:
  - Do not send frames while the detector is busy; drop them instead.
  - With CameraX, keep `STRATEGY_KEEP_ONLY_LATEST` and close each `ImageProxy` after processing.
  - Wait for the same value across several consecutive frames before trusting a result.
  - Use YUV_420_888 / NV21.
  - Render the overlay together with the result.
  — [ML Kit barcode scanning, Android](https://developers.google.com/ml-kit/vision/barcode-scanning/android)
- ML Kit `enableAllPotentialBarcodes` (bundled 17.1.0+ / unbundled 18.2.0+) returns detected-but-undecodable barcodes with their bounding boxes. This is useful for "slow down / come closer" hints and for counting labels that were seen but not decoded. — [ML Kit barcode scanning, Android](https://developers.google.com/ml-kit/vision/barcode-scanning/android)
- Dynamsoft Camera Enhancer's frame filter runs a quick sharpness check on each frame and discards low-sharpness frames, so the decoder does not waste time on blur. A "sensor filter" uses motion sensors to drop frames while the device is shaking. Both need a license in DCE. — [Dynamsoft DCE enhanced features](https://www.dynamsoft.com/camera-enhancer/docs/core/enums/enhanced-features.html), [Dynamsoft accuracy guide](https://www.dynamsoft.com/barcode-reader/docs/core/performance/accuracy.md)
- Scandit recommends using the SDK's `recommendedCameraSettings` rather than custom camera tuning. It also recommends sharp, uncompressed frames and no pre-processing. — [Scandit camera settings](https://docs.scandit.com/data-capture-sdk/ios/core/api/camera-settings.html), [Scandit performance](https://docs.scandit.com/stable/c_api/performance.html)
- CameraX: `CameraControl.enableTorch(boolean)`, `CameraInfo.hasFlashUnit()`, and torch strength via `setTorchStrengthLevel(int)` on supported devices. Low-light boost uses `enableLowLightBoostAsync` (Android 15+, CameraX 1.5). — [CameraX configuration guide](https://developer.android.com/media/camera/camerax/configuration), [CameraX release notes](https://developer.android.com/jetpack/androidx/releases/camera)

### Inferences

**Do not use low-light boost.** It works by lengthening exposure or lowering the frame rate, which is the opposite of what a sweep needs.

**Torch.**
- At 0.5–1 m the phone LED adds meaningful light to the label, which lets the shutter get shorter.
- Glossy black/yellow boxes and laminated labels can produce a specular hotspot right on the barcode when the phone is perpendicular to it. Tilt the phone 10–15°.
- A torch strength below max reduces heat and battery drain (`CameraInfo.getMaxTorchStrengthLevel()`, Android 15+; VERIFY that the S23U supports it).
- A/B test torch on vs off on Tuesday.

**Cheap blur gate (DIY version of Dynamsoft's filter).**
- Before ML Kit, compute a sharpness score on a downsampled Y-plane ROI, such as the variance of the horizontal gradient or the Laplacian.
- Skip frames whose score falls below a running percentile.
- Optionally skip frames when gyroscope angular rate exceeds about 60–90°/s (DIY sensor filter).

**Pipeline.**
- One `BarcodeScanner` instance, created with `BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_CODE_128)`. Restricting formats is standard ML Kit API and reduces work and false positives.
- Process on a single-thread executor and close the `ImageProxy` in `addOnCompleteListener`.
- Track decodes per box with a short-term deduplication and voting window.

**Thermal and battery.**
- Camera + ISP + ML at 4K, + encoder + torch heats an S23U within minutes.
- Use `PowerManager.addThermalStatusListener` / `getCurrentThermalStatus()` (API 29+) and `getThermalHeadroom()` (API 30+). These are standard Android APIs but not sourced here. On MODERATE, drop the analysis size to 1080p and the video to off or a lower bitrate. On SEVERE, stop torch and recording.
- Keep the screen brightness moderate.

### Gaps
- No ML Kit page specifically on thermal throttling was found. Thermal-API recommendations come from general Android knowledge, not a fetched source.
- No quantitative data on how much light the torch adds at 1 m on the S23U, or on its current draw.
- Scandit/Dynamsoft do not publish their exact exposure/focus values for "recommended settings".

---

## 6. Recording video at the same time for benchmarking

### Takeaway
Binding VideoCapture alongside Preview and YUV ImageAnalysis exceeds the guaranteed stream tables. CameraX then either uses a device-specific combination or StreamSharing (GPU compositing of Preview and Video from one stream), which costs GPU and heat. ImageAnalysis or Video resolution may also be reduced. Encoded video is a lossy, pessimistic proxy for analysis frames. For reproducible benchmarks, either record at a high bitrate and accept the bias, or save the analysed frames themselves at intervals.

### Cited Findings
- CameraX StreamSharing uses OpenGL to share one PRIV stream between Preview and VideoCapture, enabling use-case combinations beyond the device's native capabilities. — [CameraX architecture / camerax-developers discussion via search](https://groups.google.com/a/android.com/g/camerax-developers/c/eI5fOQl1Otg)
- On a LIMITED device, binding YUV ImageAnalysis limited VideoCapture's PRIV stream to ≤ PREVIEW size (≤1080p). Without sharing, only three use cases fit the guaranteed table. The suggested alternative was to analyse frames from Preview if 4K video is essential. — [camerax-developers: binding failed with VideoCapture and ImageAnalysis](https://groups.google.com/a/android.com/g/camerax-developers/c/eI5fOQl1Otg)
- CameraX 1.5.x fixes:
  - 1.5.1 (Oct 2025) fixed Preview not choosing 16:9 and VideoCapture not recording QUALITY_1080P when four use cases were bound (StreamSharing), and fixed metering coordinates with four use cases.
  - 1.5.0-beta01 fixed preview freezes with ImageAnalysis + TEMPLATE_RECORD on certain Samsung mid-range models.
  - 1.5.0-alpha02 fixed frame rate with stream sharing.
  - 1.5.0-beta02 fixed a StreamSharing memory leak.
  — [CameraX release notes](https://developer.android.com/jetpack/androidx/releases/camera)
- A Google issue reported problems on a Samsung model with ImageAnalysis plus a VideoCapture or shared Preview/Video stream. The workaround was an extra ImageCapture with the VideoCapture capture intent set to preview. This was later fixed. — [Google issue tracker 395822788](https://issuetracker.google.com/issues/395822788?hl=pt)
- Scandit notes that compressed input (JPEG) has block artifacts and decodes worse than raw frames. The same applies to H.264/HEVC frames extracted from video. — [Scandit performance](https://docs.scandit.com/stable/c_api/performance.html)
- A Samsung developer forum thread reports high-speed recording issues on the S23 series. — [Samsung developer forum](https://forum.developer.samsung.com/t/high-speed-video-recording-issue-on-samsung-s23-series/25983)

### Inferences
- **Stay on CameraX 1.5.2 or later.** It includes the StreamSharing and four-use-case fixes. Log the actual sizes delivered to each use case (Preview resolution, `ImageProxy` width/height, the Recorder's output size) and the measured analysis FPS with and without VideoCapture bound.
- **Expected impact with video on.**
  - Possible StreamSharing: an extra GPU pass, so more heat and some preview latency.
  - Video encoder load.
  - CameraX may lower ImageAnalysis or Video resolution to make the session work. This matters if analysis is set to 4K.
  - Analysis FPS itself is bounded by ML Kit, so it may barely drop until thermal throttling begins.
  - Measure, don't assume (VERIFY ON DEVICE).
- **Settings shared with recording.** Short-exposure and fixed-focus settings applied through `Camera2CameraControl` affect the whole session, video included. That is good for benchmarking, because the video shows exactly the blur the analyser saw.
- **Benchmark-grade recording.**
  - Use 1080p30 with a high target bitrate (`Recorder.Builder().setTargetVideoEncodingBitRate(…)`, e.g. 30–50 Mbps; standard CameraX API, VERIFY the value is honoured) so thin bars survive compression.
  - Better and cheaper: save the analysed frames themselves, e.g. every Nth frame or every frame around a decode or near-miss, as Y-plane PNG or raw NV21 with timestamp, exposure, ISO, focus distance and zoom from the capture result. That makes offline re-runs exact.
  - Keep VideoCapture as an optional "field evidence" toggle, off by default during counting.
- **If a 4K benchmark video is mandatory,** consider Preview + VideoCapture only, with analysis on Preview frames via a `CameraEffect` or `PreviewView.getBitmap()`. This is slower and lower quality, so it is only for offline evaluation sessions.

### Gaps
- No public measurement of analysis FPS drop on the S23 Ultra with Preview + ImageAnalysis + VideoCapture bound in CameraX.
- Not confirmed whether the S23U binds Preview + 1080p YUV + 1080p Video natively (no StreamSharing). Check via CameraX logs, which print the chosen stream specs, or with `isSessionConfigurationSupported`.
