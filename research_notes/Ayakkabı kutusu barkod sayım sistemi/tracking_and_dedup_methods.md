# Tracking and Deduplicating Identical Static Barcode Labels from a Handheld Moving Phone Camera

Scope note: these notes were collected in November 2026 within a 15-call research budget. Items marked "(not fetched this session)" are canonical references from background knowledge. Their URLs are given so the writer can check them, but their contents were not re-verified here. Numeric estimates in the Inferences sections are my own calculations, and their assumptions are stated.

## 1. Which multi-object tracking (MOT) ideas fit static objects seen by a moving camera, and is a Kalman filter needed?

### Takeaway
BoT-SORT's camera-motion compensation (GMC) is the most relevant MOT idea. It estimates a global affine motion with RANSAC from sparse optical flow and warps track predictions with it, and that alone gives a measurable gain on moving-camera benchmarks. When every target is static, apparent motion is camera motion, so the object-level Kalman velocity model adds little. The better design is to estimate camera motion robustly and keep each label as a fixed point in a shared reference frame. SweepScan already uses ByteTrack-style two-stage association, which is a reasonable fit.

### Cited Findings
- BoT-SORT's CMC extracts "good features to track" (Shi-Tomasi) keypoints, tracks them with pyramidal Lucas-Kanade sparse optical flow, rejects outliers, and solves a 2x3 affine matrix A = [M | T] with RANSAC. It follows OpenCV's video stabilization affine approach. Sparse keypoints are used so that moving objects can largely be ignored. — [BoT-SORT, ar5iv HTML of arXiv 2206.14651](https://ar5iv.labs.arxiv.org/html/2206.14651)
- BoT-SORT applies the affine to the Kalman prediction as x' = M~ x + T~ and P' = M~ P M~^T. M~ is the 2x2 rotation/scale block M lifted to an 8x8 block diagonal, and T~ carries the translation. The paper says the full correction, including velocity, matters at high camera speeds, and the covariance correction can be skipped when the camera moves slowly relative to the frame rate. — [BoT-SORT](https://ar5iv.labs.arxiv.org/html/2206.14651)
- BoT-SORT changes the Kalman state to [xc, yc, w, h, vx, vy, vw, vh] (width and height instead of aspect ratio). Q and R are scaled by box size, with sigma_p = 0.05, sigma_v = 0.00625 and sigma_m = 0.05, values taken from DeepSORT for 30 fps video. — [BoT-SORT](https://ar5iv.labs.arxiv.org/html/2206.14651)
- BoT-SORT ablation on MOT17 val (MOTA / IDF1 / HOTA):
  - ByteTrack baseline: 77.66 / 79.77 / 67.88
  - With the updated Kalman filter: 77.67 / 79.89 / 68.12
  - With CMC added: 78.31 / 81.51 / 69.06
  - CMC accounts for about +1.6 IDF1 and +0.9 HOTA.
  — [BoT-SORT](https://ar5iv.labs.arxiv.org/html/2206.14651)
- BoT-SORT says GMC can be slow on large images, but its runtime is negligible next to detector inference and it can run in a separate thread. Its stated limitation is that camera motion estimation can fail in dense scenes with few background keypoints. — [BoT-SORT](https://ar5iv.labs.arxiv.org/html/2206.14651)
- BoT-SORT reports MOT17 test results of 80.5 MOTA, 80.2 IDF1 and 65.0 HOTA, ranking first on MOT17 and MOT20 at the time. — [arXiv abstract 2206.14651](https://arxiv.org/abs/2206.14651)
- OC-SORT targets occlusion and non-linear object motion and stays "simple, online, and real-time". It has two mechanisms:
  - Observation-Centric Re-Update (ORU), formerly Observation-Centric Recovery. When a lost track is re-found, ORU builds a virtual trajectory between the last-seen and re-activating observations to correct the Kalman drift that built up while the track was lost.
  - Observation-Centric Momentum (OCM), a direction-consistency term in the association cost.
  Official code is at noahcao/OC_SORT. — [OC-SORT arXiv 2203.14360](https://arxiv.org/pdf/2203.14360); [ar5iv](https://ar5iv.labs.arxiv.org/html/2203.14360)
- Fruit counting is a close analogue: many near-identical static objects, a moving monocular camera, and counting each one once. Liu et al. (IROS 2018) track detections with the Hungarian algorithm, using a cost from a Kalman-corrected KLT tracker. They then run Structure from Motion to get relative 3D positions and sizes, which they use to "reject outliers and double counted fruit tracks". — [Robust Fruit Counting, arXiv 1804.00307](https://arxiv.org/pdf/1804.00307)
- A third-party summary reports that the 3D correction in Liu et al. cut mean count error for oranges from 17.2% to -0.2%. This was not verified against the paper. — [emergentmind summary](https://emergentmind.com/papers/1804.00307)
- Checkout-counting work in the CVPR AI City challenge uses detector + DeepSORT tracking "to avoid duplicated counting" of products in video. — [Shoman et al., CVPRW 2022](https://openaccess.thecvf.com/content/CVPR2022W/AICity/papers/Shoman_A_Region-Based_Deep_Learning_Approach_to_Automated_Retail_Checkout_CVPRW_2022_paper.pdf); [Vats et al., CVPRW 2023](https://openaccess.thecvf.com/content/CVPR2023W/AICity/papers/Vats_Enhancing_Retail_Checkout_Through_Video_Inpainting_YOLOv8_Detection_and_DeepSort_CVPRW_2023_paper.pdf)
- Background references (not fetched this session):
  - ByteTrack associates high-score detections first, then low-score detections to the remaining tracks. — [arXiv 2110.06864](https://arxiv.org/abs/2110.06864)
  - StrongSORT adds appearance and ECC camera-motion compensation to DeepSORT. — [arXiv 2202.13514](https://arxiv.org/abs/2202.13514)
  - Norfair is a lightweight Python tracker with pluggable distance functions and a camera-motion module. — [tryolabs/norfair](https://github.com/tryolabs/norfair)

### Inferences
- **Appearance re-identification (DeepSORT/StrongSORT) cannot help.** The labels are visually and semantically identical, so only geometry can tell them apart. The useful parts of the MOT literature are:
  - GMC/CMC (BoT-SORT, StrongSORT's ECC)
  - two-stage association of low-confidence (located-but-undecoded) detections (ByteTrack)
  - re-anchoring lost tracks on their last true observation rather than a drifted prediction (OC-SORT's ORU)
  - a 3D-consistency pass to remove double counts (fruit-counting SfM)
- **For static targets, the Kalman "object" model can be replaced.** Treat each label as a constant point in a global/mosaic coordinate system, with a running mean and covariance of its position. Put all the dynamics into a camera-motion estimate (a global homography or similarity per frame). That is what BoT-SORT's CMC approximates, taken to its limit. A Kalman filter is still useful on the camera motion itself, smoothing frame-to-frame transform parameters or fusing gyro and vision, but not per box.
- **SweepScan's current design already matches this pattern.** It has an integrated scene offset, gated association, two-stage association, count-after-3-reads, 5 s re-identification in scene coordinates, and a co-visibility constraint. The main gap compared with BoT-SORT is the motion model: translation only, with no RANSAC affine.
- **Co-visibility as a hard "must be distinct" constraint is a strong prior.** It is similar to a cannot-link constraint and is worth keeping in any redesign.

### Gaps
- I found no MOT paper that targets identical static objects with a handheld camera specifically. The closest analogues are fruit counting and checkout counting.
- OC-SORT's handling of camera motion (it has none built in) and Norfair's camera-motion module details were not fetched.
- The exact Liu et al. error numbers were not checked against the PDF.

## 2. Global motion models, RANSAC estimation, and panorama/mosaic approaches (including retail shelf research)

### Takeaway
A RANSAC-estimated similarity (estimateAffinePartial2D, 4 DoF) or full affine between consecutive frames is a well-proven step up from pure translation (it is BoT-SORT's GMC). A homography is the exact model only for a planar scene or pure rotation. A column of boxes is roughly planar, but near and far stacks cause parallax that no single 2D model removes. Retail shelf research mostly works on stitched panoramas or single images, and patents describe stitching consecutive shelf images. No academic paper found combines video mosaicking with deduplicated counting of identical items.

### Cited Findings
- **When a single homography is exact.** For a purely rotating camera, or for a distant or planar scene, the motion between two frames is exactly a homography. — [MIT computational photography notes, video stabilization chapter](https://people.csail.mit.edu/fredo/comp-photo-book/12-video-05-video-stabilization-and-rolling-shutter-correction.html)
- **BoT-SORT's GMC is an affine model.** The 2x2 M block holds rotation and scale, and T holds translation, estimated with RANSAC on sparse LK flow, following OpenCV videostab. — [BoT-SORT](https://ar5iv.labs.arxiv.org/html/2206.14651)
- **Planogram compliance with RANSAC.** Laitala & Ruotsalainen (Applied Sciences 2023) detect products, then use RANSAC pose estimation to compare shelf layout with the planogram. — [Applied Sciences 13(18):10145, via DOAJ](https://doaj.org/article/c985c217aa894643918eaac9f706a9d0)
- **Shelves larger than one image.** Ou et al. (Scientific Reports, Dec 2025) note that automated planogram verification usually assumes the whole shelf fits in a single image, which is the limitation they address with "virtual shelves". — [Sci Rep, DOAJ](https://doaj.org/article/ab8a602260bc47d2a15fbdffb8eb2507); [Research Square preprint](https://assets-eu.researchsquare.com/files/rs-7463367/v1/b6b8ee24864039ffd9bffdd1.html)
- **Shelf-stitching patents.** These describe:
  - stitching consecutive shelf images into a panorama before recognition — [US 10769582](https://uspto.report/patent/grant/10769582)
  - "panoramic image stitching using objects", a sequence of images of a scene — [US 9911213](https://patents.google.com/patent/US9911213)
  - a preview UI for linear panoramic capture, noting that stitching artifacts can interfere with recognition — [US 9961260](https://image-ppubs.uspto.gov/dirsearch-public/print/downloadPdf/9961260)
- **Robot-based stock assessment.** A QUT paper does shelf stock assessment from a monocular camera on a mobile robot, without depth sensors, from single images rather than tracking. — [QUT eprints 204527](https://eprints.qut.edu.au/204527/1/68086240.pdf)
- **Background references (not fetched this session):**
  - SKU-110K is a dense shelf-product detection dataset of about 11.7k images with very densely packed, near-identical objects. — [arXiv 1904.00853](https://arxiv.org/abs/1904.00853)
  - OpenCV documents `cv::estimateAffinePartial2D` (4-DoF similarity with RANSAC/LMEDS), `cv::estimateAffine2D` (6 DoF) and `cv::findHomography` (8 DoF, RANSAC/RHO/LMEDS). — [OpenCV calib3d docs](https://docs.opencv.org/4.x/d9/d0c/group__calib3d.html)

### Inferences
- **Recommended motion model: a RANSAC similarity first, then a homography.**
  - Per frame, estimate a similarity (estimateAffinePartial2D) with RANSAC from two sources: LK-tracked Shi-Tomasi corners on the box surfaces (cardboard print, edges and labels give plenty of texture), plus the four corners of every barcode that appears in both frames.
  - The barcode corners are high-precision, outlier-resistant correspondences. Identical values make them ambiguous, but the current same-value pair voting already resolves that, and the RANSAC inlier set then confirms which pairing is right.
  - Move to a homography (findHomography with RANSAC) only when enough inliers spread across the frame, for example at least 4 well-separated barcodes or at least 30 features. Otherwise fall back to similarity, then translation. This "degrade gracefully" ladder copies the existing translation -> LK -> velocity fallback.
- **Scale change from walking toward the boxes is captured by similarity or homography, not translation.** This is the current model's main weakness. Scaling the gates by barcode width/height partly compensates, but transformed positions still drift.
- **Parallax between near and far stacks breaks any single global 2D model.** Two practical mitigations:
  - **Column-by-column scanning.** Within one column all labels lie on roughly one plane, so one homography per column is close to exact. Reset the reference frame at each new column.
  - **Local transforms.** Instead of one global transform, estimate a transform per region, or per neighbourhood of nearby barcodes, as in mesh-based stabilization. This is more complex and speculative for SweepScan.
- **Mosaic coordinates stop drift better than chaining frame-to-frame transforms.** Keep a keyframe. Register each new frame to the current keyframe (or to the mosaic of label positions) rather than only to the previous frame. When overlap falls below a threshold, promote a new keyframe. This is standard keyframe-based stitching, and it bounds drift to the keyframe-to-keyframe chain.
- **Long periods with no labels are handled by the LK feature path.** Box-surface texture plus a gyro prior (section 3) should cover these. Pure "last velocity" extrapolation should be a last resort with a short time limit.

### Gaps
- No academic paper was found that does "video sweep, mosaic, dedup count of identical items on shelves". The precedent is mainly patents and commercial SDKs.
- SKU-110K and OpenCV API details were not fetched.
- No measured accuracy figures were found for similarity vs homography on box walls.

## 3. Sensor fusion: gyroscope/rotation-vector prediction of image motion and Android timestamp synchronization

### Takeaway
Gyro-predicted rotation homographies (H = K R K^-1) are a proven technique from video stabilization (Karpenko et al., 2011). They are good for predicting motion during fast swings and when vision fails. They model rotation only, though, and at 0.5–1.5 m the operator's sideways translation is a large part of the apparent motion, so gyro should be the prior and vision the correction. Android supports aligning gyro and camera clocks when the camera reports `SENSOR_INFO_TIMESTAMP_SOURCE = REALTIME` (CLOCK_BOOTTIME), with caveats about the ImageReader timebase.

### Cited Findings
- **Gyro stabilization is proven.** Karpenko, Jacobs, Baek and Levoy (Stanford, 2011) show real-time video stabilization and rolling-shutter correction using commodity phone gyroscopes. Their warp maps the observed camera motion to a smoothed, global-shutter synthetic camera.
  - From a single recording of about 10 s they calibrate focal length, rolling-shutter readout time, gyro-to-camera time delay, gyro drift and axis alignment.
  — [Stanford project page](https://graphics.stanford.edu/papers/stabilization/); [PDF](https://formaurbis.stanford.edu/papers/stabilization/karpenko_gyro.pdf)
- **Better motion filters exist.** Bell, Troccoli and Pulli (ECCV 2014) propose a non-linear filter for gyro-based stabilization that removes shake better than earlier gyro methods. — [NVIDIA Research PDF](https://research.nvidia.com/sites/default/files/pubs/2014-09_A-Non-Linear-Filter/bell_troccoli_pulli_eccv2014.pdf)
- **Online calibration.** Later work calibrates gyro/camera parameters online from homographies of matched feature points under a pure-rotation assumption. — [UT Austin autocalibration](https://users.ece.utexas.edu/~bevans/papers/2015/autocalibration/index.html)
- **The pure-rotation homography is exact for rotation-only motion** (or distant/planar scenes). — [MIT comp-photo notes](https://people.csail.mit.edu/fredo/comp-photo-book/12-video-05-video-stabilization-and-rolling-shutter-correction.html)
- **Camera clock: REALTIME means CLOCK_BOOTTIME.** Camera2 timestamp source REALTIME corresponds to `SystemClock.elapsedRealtime()` / CLOCK_BOOTTIME, while UNKNOWN uses an unspecified clock. The key was renamed from TIMESTAMP_CALIBRATION to TIMESTAMP_SOURCE. — [Android framework commits (e/OS mirror)](https://gitlab.e.foundation/e/os/android_frameworks_base/-/commit/abd9d3c5c7100c45812ee80975ac59f5b1902a71)
- **ImageReader caveat.** Images produced by ImageReader are reported to be roughly on the `uptimeMillis` timebase rather than `elapsedRealtimeNanos`, even when the source is REALTIME. Check which output path's timestamp you use. — [Android framework docs, via search snippet of e/OS mirror](https://gitlab.e.foundation/e/os/android_frameworks_base/-/commit/38b71d70f3bb8b56303a586b59092a83c7a0e4fa). Caveat: this was seen only in a search snippet and not verified on developer.android.com. The `CaptureResult.SENSOR_TIMESTAMP` from the capture callback is the safer source to pair with each frame.
- **Existing synchronized recording.** The OpenCamera Sensors project records synchronized camera and IMU data on Android and requires the device to report "realtime" timestamp source. — [prime-slam/OpenCamera-Sensors](https://github.com/prime-slam/OpenCamera-Sensors)
- **CTS check.** Android camera ITS includes a sensor-fusion test that checks camera and gyro timestamp alignment. — [Camera ITS tests](https://source.android.com/compatibility/cts/camera-its-tests?hl=pt-br)

### Inferences
- **Recommended use of gyro: prediction and gating, not position integration.**
  - Integrate gyroscope (or GAME_ROTATION_VECTOR) between the two frames' `SENSOR_TIMESTAMP`s to get the relative rotation R.
  - Form H_gyro = K R K^-1, with K from Camera2 `LENS_INTRINSIC_CALIBRATION` or estimated from the FOV.
  - Use H_gyro to pre-warp LK search windows, so LK converges during fast swings.
  - Use H_gyro as the RANSAC model prior and gate check: reject a vision estimate that disagrees wildly with the gyro.
  - Use H_gyro as the fallback when vision fails, instead of "last velocity".
- **Translation share, rough calculation (assumptions mine).** Moving the phone sideways by d at distance Z shifts the image like a rotation of about atan(d/Z). A 10 cm side-step at 1 m equals about 5.7° of rotation. Operators sweeping a column typically both rotate and translate, so gyro alone will mispredict by a share comparable to the true motion. Gyro catches rapid wrist rotations (the main cause of "fast swing" failures), and vision handles the rest.
- **Clock alignment in practice.** Check `SENSOR_INFO_TIMESTAMP_SOURCE` on the S23 Ultra at runtime. SensorEvent timestamps are typically on elapsedRealtimeNanos/BOOTTIME (not verified this session; see Gaps). Expect a small fixed offset (a few ms). Rolling-shutter readout of about 10–30 ms also matters during fast swings. Estimate the offset once per session by cross-correlating gyro angular rate with vision-estimated rotation rate, as Karpenko's calibration does.

### Gaps
- No primary Android documentation page confirming the SensorEvent.timestamp timebase was fetched.
- The S23 Ultra's `SENSOR_INFO_TIMESTAMP_SOURCE` value and rolling-shutter skew were not found.
- No published accuracy figures for gyro-predicted homography on Samsung devices were found.

## 4. ARCore: 6DoF pose + depth/hit-test anchors per label, and feasibility with high-resolution barcode decoding on the Galaxy S23 Ultra

### Takeaway
ARCore officially supports the Galaxy S23 Ultra, including the Depth API. Its documented ML pattern (CPU image -> 2D detection -> `Frame.hitTest` -> `DepthPoint` -> `createAnchor`) would give each label a 3D world position for deduplication. The blocker is image resolution:
- The ARCore CPU image defaults to 640x480. Higher CPU-resolution configs cost an extra stream.
- The Shared Camera API allows extra app surfaces, but high-end phones are only guided to about two YUV CPU streams (for example 640x480 + 1920x1080).
- Small Code 128 labels at 1–1.5 m likely need more than 1080p to decode.

ARCore-based dedup is therefore plausible but speculative and higher-risk. It is best kept as a prototype experiment, not the main path for the Tuesday field test.

### Cited Findings
- **Device support.** The ARCore supported-devices list includes "Samsung | Galaxy S23 Ultra | Supports Depth API". S23, S23+ and S23 FE are listed the same way. — [ARCore supported devices](https://developers.google.com/ar/devices)
- **CPU image resolution and cost.**
  - "By default, the CPU image has a resolution of VGA (640x480)."
  - A higher resolution is chosen via `Session.getSupportedCameraConfigs()` + `setCameraConfig()`.
  - The default VGA stream is free because ARCore uses it for tracking, but "requesting a stream with a different resolution may be expensive" because an extra stream must be captured.
  - Doubling width and height quadruples the pixels.
  — [ARCore ML guide](https://developers.google.com/ar/develop/java/machine-learning)
- **Mapping 2D detections to 3D.**
  1. Take the detection center in `IMAGE_PIXELS`.
  2. Convert it with `Frame.transformCoordinates2d(IMAGE_PIXELS -> VIEW)`.
  3. Call `Frame.hitTest(x, y)` and pick a hit whose trackable is a `DepthPoint`.
  4. Call `createAnchor()`.
  The official sample is arcore-ml-sample / ml_kotlin, which uses ML Kit. — [ARCore ML guide](https://developers.google.com/ar/develop/java/machine-learning)
- **CameraConfig options.** CameraConfig exposes camera ID, depth sensor usage, facing, FPS range, CPU image size, GPU texture size and stereo use. ARCore prefers 60 fps configs on devices that support it. The page does not list the available CPU resolutions. — [ARCore camera configs](https://developers.google.com/ar/develop/java/camera-configs)
- **Shared Camera API.**
  - Apps get Camera2 access while ARCore shares the camera, and can switch between AR and non-AR mode at runtime (`sharedSession.pause()` / `resume()`).
  - By default ARCore requests a 640x480 YUV CPU stream for tracking and a GPU stream of typically 1920x1080.
  - Apps can add their own `ImageReader` surfaces via `setAppSurfaces()`.
  - Guidance for high-end phones: two YUV CPU streams (for example 640x480 and 1920x1080), one GPU stream, and an occasional high-resolution JPEG still. Mid-tier phones get fewer.
  - "Requesting additional custom surfaces increases the performance demands of the device."
  - Capture requests use `TEMPLATE_RECORD`. The page does not mention CameraX.
  — [ARCore Shared Camera](https://developers.google.com/ar/develop/java/camera-sharing)
- **Developer reports of problems.** Unity/AR Foundation developers reported that after selecting a larger CPU config, `TryAcquireLatestCpuImage` returned false or errored after the first frame. These posts are old. — [Unity forum thread](https://forum.unity.com/threads/ar-foundation-camera-resolution.866743/)
- **Open-source ARCore + barcode example.** A Dynamsoft tutorial ("spatial barcode scanner") decodes barcodes from the ARCore camera frame's Y plane and places anchors. It deduplicates in two layers: about 100 px in 2D, and a 5 cm (`MIN_ANCHOR_DISTANCE = 0.05f`) check between anchors in 3D. It needs plane detection first. The tutorial does not show how the 3D pose is computed, and does not state the resolution used. — [Dynamsoft codepool tutorial](https://www.dynamsoft.com/codepool/augmented-reality-arcore-barcode-qr-detection.html)

### Inferences
- **Decoding resolution (rough calculation; assumptions mine, not from a source).**
  - The S23 Ultra main camera is about 24 mm equivalent, so horizontal FOV is about 70–75°.
  - At 1.5 m the frame spans about 2.2 m. At 1920 px that is about 0.9 px/mm; at 0.5 m it is about 2.6 px/mm.
  - Code 128 X-dimension (narrow bar) is commonly 0.25–0.5 mm. Decoders generally want at least about 1.5–2 px per module.
  - So 1080p decodes small labels only near 0.5 m, and 640x480 essentially never at these distances.
  - This suggests the ARCore CPU stream is enough for detection and localization (finding the label rectangle) but not for decoding. Decoding needs a separate high-res stream, likely 4K or zoom, which exceeds the documented "two YUV CPU streams" guidance.
  - Validate this on the device before deciding.
- **Feasible hybrid designs, all speculative.**
  1. ARCore in shared-camera mode with a 1080p app `ImageReader` for decoding near labels, plus the 640x480 tracking stream. Use 3D anchors for dedup (cluster labels within about 3–5 cm in world space). Undecoded far labels inherit values from nearby decoded reads of the same value.
  2. Alternate modes: pause ARCore during a high-res still capture of a column, then resume. Tracking may be lost across the pause.
  3. Use ARCore only for pose. Project barcode corners onto a fitted box-face plane (ray-plane intersection with a plane from depth or plane detection) instead of per-detection hit tests.
- **Risks.**
  - ARCore tracking and SLAM need translation and texture.
  - Depth from motion is noisy at 1–1.5 m on box faces.
  - Running SLAM, depth and decoding together raises power and thermal load.
  - Shared Camera is Camera2-only, so SweepScan's camera pipeline would need porting if it currently uses CameraX.
  - None of these risks are quantified in the sources found.
- **Main benefit.** ARCore turns dedup into a 3D-distance problem, which removes the 2D drift, scale and parallax issues and gives near-perfect "same physical label" decisions within a session. This is the same principle as the SfM step that fixed double counting in fruit counting.

### Gaps
- The actual list of supported CPU image resolutions for the S23 Ultra under ARCore was not found. It must be queried on the device with `getSupportedCameraConfigs()`.
- No measured battery or thermal numbers for ARCore with an extra stream were found.
- Depth API accuracy at 1–1.5 m on cardboard was not found.
- No primary documentation on ARCore + CameraX interop was found; the Shared Camera page is Camera2-only.
- No open-source project was found that does ARCore + ML Kit barcode with fully documented 3D placement. The Dynamsoft tutorial omits the pose code.

## 5. Practical UX constraints and product precedent

### Takeaway
Commercial SDKs (Scandit MatrixScan Count, Scanbot counting mode) explicitly support counting non-unique (identical) barcodes, which shows the use case is product-proven. Their exact algorithms are not published. UX choices from the literature and products point toward structured capture (linear or column-wise, freeze-frame or batch view, user confirmation of uncertain items) rather than free-form video.

### Cited Findings
- **Scandit MatrixScan Count supports non-unique items.** The brochure lists "count non-unique items", for example multiple packages of the same product, and a "report mislabeling" case for when duplicates appear but items should be unique. — [Scandit MatrixScan Count brochure](https://www.scandit.com/resources/guides/matrixscan-count-product-brochure/)
- **Scandit claims per-barcode tracking.** It says its engine identifies and tracks each barcode uniquely across frames, which reduces false positives even when items share the same barcode data (vendor claim, method not disclosed). — [Scandit blog: multiple barcodes with AI](https://www.scandit.com/blog/multiple-barcodes-with-ai/)
- **List-based counting caveat.** In Scandit Barcode Count, when scanning against an expected list, each list entry counts only once toward the expected total even if several instances of the same barcode are expected. — [Scandit Barcode Count docs](https://docs.scandit.com/data-capture-sdk/cordova/barcode-capture/api/barcode-count.html)
- **Scanbot counting mode.** Scanbot's SDK has a `COUNTING` multiple-barcode mode that scans barcodes even when they share a value and counts repeats. — [Scanbot Android API reference](https://api-docs.scanbot.io/barcode-scanner-sdk/android/v7.1.0/-android%20-scanbot%20-barcode%20-s-d-k%20-a-p-i%20reference/io.scanbot.sdk.ui_v2.barcode.configuration/-multiple-barcodes-scanning-mode/-c-o-u-n-t-i-n-g/index.html)
- **Linear-panorama capture UI patent.** A patent for retail shelf capture describes a preview UI that guides linear capture, motivated by stitching artifacts that hurt recognition. — [US 9961260](https://image-ppubs.uspto.gov/dirsearch-public/print/downloadPdf/9961260)

### Inferences
- **Recommended UX for SweepScan.**
  - **Column-by-column, one direction (for example top-to-bottom).** This makes the scene nearly planar (section 2) and lets the tracker reset its reference frame per column. It also gives the operator a natural "column done -> confirm count" checkpoint.
  - **Freeze-frame option per stack.** Take one high-resolution still of a whole column. The S23 Ultra main sensor can capture far more pixels than a video stream (sensor specs not fetched this session). Decode all labels in that single image: within one image, co-visibility guarantees every detection is distinct, so dedup is trivial. Only overlaps between adjacent stills need geometric matching. This matches Scandit-style "batch view" counting. Whether a still decodes every label at 1–1.5 m must be tested.
  - **User confirmation of uncertain items.** Overlay counted, pending (fewer than 3 reads) and located-but-undecoded labels in different colors on the live preview or freeze-frame. Let the user tap to confirm or reject. Scandit's MatrixScan Count UI follows this "AR overlay + review" pattern; its exact UI details were not fetched.
  - **Speed guidance.** Show a "slow down" hint when gyro angular rate exceeds a threshold. This directly targets the fast-swing failure mode.

### Gaps
- No academic user study on sweep vs snapshot counting UX was found.
- Scandit's actual dedup algorithm and accuracy figures are not published; only marketing claims were found.
- No data was found on how many labels a single S23 Ultra still decodes at 1–1.5 m.

## 6. Evaluation methodology: replaying recorded logs/videos with ground-truth counts, and suitable metrics

### Takeaway
For SweepScan, the primary metric should be **count error per column or session** (signed and absolute, with over-counts and under-counts reported separately), measured on a fixed replay corpus of recorded sweeps with hand-counted ground truth. Standard MOT metrics (MOTA, IDF1, HOTA) need per-frame box annotations and measure identity continuity more than "counted exactly once". They are useful for diagnosis but expensive to annotate.

### Cited Findings
- **MOT benchmarks report MOTA, IDF1 and HOTA.** BoT-SORT's ablation shows camera-motion compensation mainly improves IDF1 (+1.6) and HOTA (+0.9) rather than MOTA (+0.6). Identity-preservation metrics are therefore the ones sensitive to motion-model quality. — [BoT-SORT](https://ar5iv.labs.arxiv.org/html/2206.14651)
- **Fruit counting evaluates count error.** It reports the error between the predicted count and the hand-counted ground truth per dataset, with and without the 3D correction step. A third-party summary quotes 17.2% vs -0.2% mean error for oranges. — [Liu et al., arXiv 1804.00307](https://arxiv.org/pdf/1804.00307); [emergentmind summary](https://emergentmind.com/papers/1804.00307)
- **Checkout counting scores unique-item counts per video.** The AI City Challenge checkout track scores how accurately unique items observed in the video are counted. — [Vats et al., CVPRW 2023](https://openaccess.thecvf.com/content/CVPR2023W/AICity/papers/Vats_Enhancing_Retail_Checkout_Through_Video_Inpainting_YOLOv8_Detection_and_DeepSort_CVPRW_2023_paper.pdf)
- **HOTA background (not fetched this session).** HOTA (Luiten et al., IJCV 2021) decomposes tracking quality into detection, association and localization accuracy. — [arXiv 2009.07736](https://arxiv.org/abs/2009.07736); TrackEval reference implementation: [JonathonLuiten/TrackEval](https://github.com/JonathonLuiten/TrackEval)

### Inferences
- **Recommended evaluation protocol.**
  1. **Build a replay corpus.** Record raw sweeps on the S23 Ultra in the target stock room. Store per-frame detections (decoded + undecoded corners), camera timestamps, and gyro/rotation-vector streams with timestamps, plus the video if storage allows. Cover the known failure modes: fast swing, walking toward the boxes, near/far stacks, long label-free gaps, and partial occlusion.
  2. **Ground truth.** For each sweep, hand-count labels per column (cheap). For a smaller subset, annotate each physical label's identity across frames (expensive), so ID metrics can be computed.
  3. **Deterministic replay.** Feed logged detections and IMU data through the tracker offline (JVM unit test or desktop harness), so every algorithm change (translation -> similarity -> homography -> gyro prior) is compared on identical inputs.
- **Primary metrics.**
  - Signed count error per column and per session.
  - Mean absolute count error.
  - Percent of columns counted exactly right.
  - Over-count rate (duplicates) and under-count rate (misses) reported separately, since they have different causes (drift/ID switch vs missed detection or the 3-read threshold).
- **Track-level precision/recall.** Match each counted track to at most one GT label. Duplicate tracks count as false positives and unmatched GT labels as false negatives. This is the "counted exactly once" metric.
- **Diagnostic metrics on the annotated subset.**
  - ID switches and fragmentations (a re-identified lost track that becomes a new ID is a likely over-count).
  - IDF1 or HOTA's association accuracy (AssA). MOTA is less relevant because detection is mostly reliable for barcodes.
- **Robustness tests.** Synthetically perturb the logged data (drop frames, add jitter, remove all labels for N seconds) to stress the drift handling, which the current tracker is known to be weak at.

### Gaps
- No published benchmark or dataset for identical-barcode counting with handheld video was found.
- HOTA/TrackEval details and AI City scoring formulas were not fetched this session.

## Overall recommendation (synthesis of the inferences above; ordered by cost)

1. **Lowest cost, highest expected gain.** Replace the pure-translation scene model with a RANSAC similarity, or a homography when well constrained.
   - Estimate it from LK features plus barcode corners.
   - Register each frame to a keyframe or label-mosaic rather than chaining frame-to-frame.
   - Reset per column.
   - Keep the existing gating, two-stage association, 3-read counting and co-visibility constraint.
   - Proven pattern: BoT-SORT GMC and OpenCV videostab.
2. **Low-to-medium cost.** Add a gyro/rotation-vector prior (H = K R K^-1) using the same clock as camera `SENSOR_TIMESTAMP`. Use it for fast swings and label-free gaps, and to pre-warp LK and to gate. Add a "slow down" UI hint. Proven technique in stabilization (Karpenko 2011); its integration in SweepScan is new.
3. **Medium cost (mostly UX).** Use column-by-column scanning, an optional freeze-frame still per column with single-image decoding, and an end-of-column confirmation screen for uncertain items. Product precedent: Scandit and Scanbot counting modes.
4. **High cost, speculative.** Use ARCore Shared Camera with 3D anchors for dedup. S23 Ultra support (Depth API) is confirmed. Decoding resolution, stream limits, Camera2 porting and power are open risks. Prototype after the Tuesday field test, not before.
5. **Throughout.** Build a replay harness and measure signed count error, over- and under-count, and duplicate-track rate on a fixed corpus before and after each change.
