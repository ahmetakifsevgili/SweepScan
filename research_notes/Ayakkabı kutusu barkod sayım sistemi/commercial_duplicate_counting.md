# How Commercial Barcode SDKs Count Items With Identical Barcodes

Research date: 2026-11-14. Doc pages carry SDK version numbers (Scandit 6.14 to 8.3, Scanbot 7.x to 9.x), not publication dates. Where a page shows a date, it is given. "Marketing" marks vendor claims that no published data backs up.

## 1. Scandit (MatrixScan Count / Barcode Count, MatrixScan tracking, patents)

### Takeaway
Scandit's counting product, MatrixScan Count (API name `BarcodeCount`), uses a shutter model. The user holds the phone still and taps "scan", and the engine finds, tracks and decodes every barcode in the scene during one scan phase. AR icons then mark counted items, and an expected list can drive a progress bar plus "wrong item / missing item" flags. Scandit says plainly that continuous scanning while moving would hurt performance. Duplicates are counted by default. An `expectsOnlyUniqueBarcodes` flag turns that off for speed, and duplicates can be flagged as mislabeling when every item should be unique. The underlying tracking (Scandit patents with a 2020 priority date) is "decode once, then track by position": a correlation filter, a homography prediction, and treating the codes as one rigid structure. Identity therefore comes from position, not from the decoded value.

### Cited Findings
**Product and UX (MatrixScan Count)**
- The user taps a shutter button ("Tap shutter to scan batch") and a loading indicator follows. The user must hold the phone still. Scandit says this "static scanning" frees compute and improves accuracy and range, and that letting the user move the phone continuously while scanning would reduce performance. — [Scandit MatrixScan Count technical brief](https://www.scandit.com/resources/guides/matrixscan-count-technical-brief/)
- After a scan, AR icons appear over the barcodes, with blue check marks on counted ones. A count badge shows how many codes were scanned. Barcode filtering puts a white AR overlay on unneeded barcodes, so only one barcode per item is scanned. — [Scandit technical brief](https://www.scandit.com/resources/guides/matrixscan-count-technical-brief/)
- Expected-list mode: inventory software sets a list of expected barcodes, a progress bar shows how many are counted, wrong items (not on the list) and missing items (on the list but not scanned) are flagged, and items sharing one barcode can be tracked individually with expected vs counted quantities. — [Scandit technical brief](https://www.scandit.com/resources/guides/matrixscan-count-technical-brief/)
- Duplicates can be flagged as mislabeling when every item should carry a unique barcode. — [Scandit technical brief](https://www.scandit.com/resources/guides/matrixscan-count-technical-brief/)
- Intro docs list the same flow: shutter tap ("Tap shutter to scan items"), a hold-still loading indicator, a calibration step if the user is too close or too far, AR icons for scanned / unscannable / should-not-be-here items, a counter badge (a progress bar when scanning against a list), and auto-advance when every item is scanned. — [Scandit docs: About MatrixScan Count (iOS)](https://docs.scandit.com/sdks/ios/matrixscan-count/intro/)
- The built-in UI has "List" and "Exit" buttons. `reset()` "clears the list of barcodes scanned and all the AR overlays". Results are read in a listener callback that "is called when the scan phase has finished". The session is reachable only inside that callback, so apps should copy `recognizedBarcodes` into their own storage. — [Scandit Get Started with MatrixScan Count (iOS)](https://docs.scandit.com/sdks/ios/matrixscan-count/get-started)
- API: `startScanningPhase()`, `endScanningPhase()` (since 6.15), `setBarcodeCountCaptureList()` for scanning against a list (since 6.15), and `setAdditionalBarcodes()` (since 6.16) to "inject barcodes that will be used as partial scanning result". In list scanning, "each entry in this list will only count as 1 towards the expected total", so duplicates need one entry per instance. `reset()` "clear[s] the history of tracked and unscanned barcodes". `beginClusterEditing()` arrived in 7.1. — [Scandit API: BarcodeCount (Android 7.6)](https://docs.scandit.com/7.6/data-capture-sdk/android/barcode-capture/api/barcode-count.html)
- `expectsOnlyUniqueBarcodes`: if enabled, optimizations can be used. The docs say: "Do not enable this if you are expecting to scan multiple barcodes with the same content within one batch." So duplicate counting is the default behavior. — [Scandit BarcodeCountSettings (Android)](https://docs.scandit.com/data-capture-sdk/android/barcode-capture/api/barcode-count-settings.html); [Get Started (iOS)](https://docs.scandit.com/sdks/ios/matrixscan-count/get-started)
- Other settings: `disableModeWhenCaptureListCompleted` (default false) and `mappingEnabled` (barcode mapping, beta, default false). — [Scandit BarcodeCountSettings (KMP)](https://docs.scandit.com/data-capture-sdk/kmp/barcode-capture/api/barcode-count-settings.html); [.NET iOS](https://docs.scandit.com/data-capture-sdk/dotnet.ios/barcode-capture/api/barcode-count-settings.html)
- Advanced features: clustering (DISABLED / MANUAL / AUTO / AUTO_WITH_MANUAL_CORRECTION), tote mapping (BarcodeSpatialGrid), a floating shutter button for strap mode, filtering by symbology or regex (filtered codes shown transparent), a "clear highlights" button that clears AR overlays but keeps the scanned list, and tap callbacks on recognized and unrecognized barcodes. Scandit warns: "The UI is an integral part of MatrixScan Count and we do not recommend that you use it without it." — [Scandit MatrixScan Count Advanced (Cordova)](https://docs.scandit.com/sdks/cordova/matrixscan-count/advanced/)
- Speed claims (marketing): "speed-ups of up to 10x" versus dedicated scanners (one hour down to six minutes), and a typical 3x for medium-to-large packages. The brief gives no accuracy percentage. — [Scandit technical brief](https://www.scandit.com/resources/guides/matrixscan-count-technical-brief/); "up to 10 times" also appears in [About MatrixScan Count](https://docs.scandit.com/sdks/ios/matrixscan-count/intro/)
- Performance improves when the barcodes face the same direction. — [Scandit technical brief](https://www.scandit.com/resources/guides/matrixscan-count-technical-brief/)

**Blog (2026-02-17, "Making Sense of Multiple Barcodes With AI")**
- The post says CV models "track each one across frames" and the engine "remembers each barcode's spatial position" after a code leaves the field of view, so the user need not recapture it. This "allows the software to count duplicates"; where items share a SKU, "each one must be counted, not dropped". — [Scandit blog](https://www.scandit.com/blog/multiple-barcodes-with-ai/)
- The post says scene evaluation uses barcode positions, angles, distance, focus **and device motion-sensor data**, and that the AR layer is synchronized so overlays stay anchored without flicker. It gives no accuracy numbers and no algorithm detail (marketing level). — [Scandit blog](https://www.scandit.com/blog/multiple-barcodes-with-ai/)

**Patents (Scandit AG; inventors Bloch, Floerkemeier, Nater, Schoner)**
- US10963658B1, "Image analysis for tracking, decoding, and positioning multiple optical patterns": priority 2020-04-01, filed 2020-07-02, granted 2021-03-30. "barcodes are tracked in several frames but decoded only periodically." Tracking uses a correlation filter (features moved to the frequency domain by FFT, a response map, the filter "continuously updated" for perspective change). It can be combined with a "homographic prediction algorithm", and the codes can be tracked "as a single rigid structure". Overlay state changes from red (seen, too far to decode) to green (decoded). — [Google Patents US10963658B1](https://patents.google.com/patent/US10963658B1/en)
- Family members include US11886954B2, US11216628B2, US11514665B2, US11295163B1, US11803718B1, US11900653B1, US12488211B1, US12374078B1 and WO2021202762A1. — [Google Patents US10963658B1](https://patents.google.com/patent/US10963658B1/en)
- US11886954B2, "Image analysis for mapping objects in an arrangement": filed 2020-12-31, granted 2024-01-30. It maps shelf codes to x/y positions on a shelf diagram, treating shelves as planar ("shelves are imaged as two-dimensional structures"). Images are stitched by feature matching with "RANSAC ... for homography". Known barcode width serves as a scale reference. "Tracking the code ... does not decode the code." — [Google Patents US11886954B2](https://patents.google.com/patent/US11886954B2/en)
- US11216628 ("High-speed scanning of optical patterns using a digital camera") tracks codes in the second frame without decoding, using a correlation filter. — [Justia US11216628](https://patents.justia.com/patent/11216628)
- In the parts read (the first ~100k characters of each Google Patents page), neither patent says how two identical values are kept as separate objects. — same sources

**Older Scandit (5.x SDK)**
- Legacy scan sessions filtered duplicates by default: same symbology and data decoded less than 500 ms ago. This is the classic time-window dedup, unsuitable for counting. — [Scandit 5.3.3 SBSScanSession.h](https://media.cellsignal.com/www/third-party/scandit-barcodescanner-phonegap_5.3.3/src/ios/sbs-ios_5.3.3/ScanditBarcodeScanner.framework/Headers/SBSScanSession.h)

### Inferences
- Scandit's flagship counting product deliberately avoids continuous sweeping. It runs a short, stationary "scan phase" per area, triggered by the shutter, and in that window the tracker sees a nearly static scene, so identical codes are easy to tell apart by position. This is the strongest commercial evidence for a "stop, scan, verify on screen, move on" interaction model for SweepScan, as an alternative or fallback to a free sweep.
- Identity in the patents comes from position (correlation filter, homography, rigid structure), with the decoded value attached to a track once. This matches SweepScan's design: track, decode once, then carry the value. The "rigid structure" and planar-homography ideas map directly onto a flat column face of shoe boxes. SweepScan's integrated scene coordinates are the same concept as Scandit's shelf-map vectors.
- `setAdditionalBarcodes` plus copying `recognizedBarcodes` out of the session suggests that apps merge across scan phases themselves (summing, or injecting earlier results). Scandit offers mapping (beta) and tote/spatial-grid features for spatial layouts. This reading comes from the API shape and is not documented as a workflow.
- UX to copy: a count badge, per-code AR icons (counted vs not), an expected-list progress bar with wrong/missing flags, "clear highlights while keeping the list", a calibration hint when the user is too near or too far, and a hold-still loading indicator.

### Gaps
- No public Scandit document explains the dedup algorithm for identical values within or across scan phases. Whether counts carry over automatically between two shutter taps of overlapping areas is not documented in the pages read.
- I did not confirm whether a scan phase uses one high-res still or a short burst of video frames. The docs say only "hold still".
- No Scandit accuracy figure for counting was found, only speed multipliers.
- I did not confirm the MatrixScan "Barcode Tracking" to "Barcode Batch" rename or its docs.
- The duplicate-handling sections of the patents were not read (the pages were truncated).

## 2. Other vendors: Scanbot, Dynamsoft, Zebra, barKoder, Cognex, Anyline, Honeywell

### Takeaway
Most vendors dedupe by **value plus time**, not by physical item. Scanbot's COUNTING mode re-counts the same value after a `countingRepeatDelay`. barKoder offers a "duplicates delay" and a result cache, and tells apps to keep their own value set. Zebra MultiBarcode keeps one of each symbology-plus-data and drops the rest. None of these can count two identical boxes reliably in a sweep. The only vendor explicitly marketing spatial duplicate counting besides Scandit is Dynamsoft: its Batch Barcode Scanner uses an AR camera (iOS only) to tie codes to physical locations, plus panorama capture and cross-frame validation.

### Cited Findings
- **Scanbot:** `MultipleBarcodesScanningMode` has COUNTING ("Scan barcodes even if they have the same value and count the number of repeated barcodes") and UNIQUE ("Subsequent barcodes with the same values will be ignored"). COUNTING is the default on Android and .NET MAUI. — [Scanbot Android API 7.1.0](https://api-docs.scanbot.io/barcode-scanner-sdk/android/v7.1.0/-android%20-scanbot%20-barcode%20-s-d-k%20-a-p-i%20reference/io.scanbot.sdk.ui_v2.barcode.configuration/-multiple-barcodes-scanning-mode/-c-o-u-n-t-i-n-g/index.html); [Android 8.0.1 mode](https://api-docs.scanbot.io/document-scanner-sdk/android/v8.0.1/-android%20-scanbot%20-s-d-k/io.scanbot.sdk.ui_v2.barcode.configuration/-multiple-scanning-mode/mode.html); [.NET MAUI 9.0](https://api-docs.scanbot.io/barcode-scanner-sdk/dotnet-maui/v9.0.0/api/ScanbotSDK.MAUI/ScanbotSDK.MAUI.Barcode.MultipleScanningMode.html)
- **Scanbot:** COUNTING scans the same value again and increments the item count. `countingRepeatDelay` sets the milliseconds that must pass before a barcode is counted again. The results sheet has one entry per unique value with a count, shown as a collapsed sheet or a button with the total. — [Scanbot iOS Scan & Count](https://docs.scanbot.io/ios/barcode-scanner-sdk/ready-to-use-ui/scanning-modes/scan-and-count/)
- **Dynamsoft** (blog, 2025-04-11): AR Camera mode uses spatial tracking to link each barcode to a physical location. It is labeled "Best for Duplicate Detection" for "counting multiple units of the same item". The standard Barcode Reader "struggles to distinguish" duplicates. AR is iOS only, with Android and Web "yet to come" (as of April 2025). Overlapping captures are cross-validated and failures flagged as questionable. Users can add, delete or edit barcodes on the panoramic image before export. — [Dynamsoft blog: bulk barcode scanning accuracy](https://www.dynamsoft.com/blog/product-features/bulk-barcode-scanning-accuracy/)
- **Zebra** MultiBarcode mode: the device scans a programmed number (2 to 100) of **unique** barcodes, and "only one of the duplicate barcodes is decoded and the remainder are ignored" (same symbology and data). It reports nothing until all are captured or a timeout occurs. — [Zebra TC72/TC77 PRG: Operational Modes](https://docs.zebra.com/us/en/mobile-computers/handheld/tc7-series/tc72-tc77-product-reference-guide/data-capture/operational-modes.html)
- **Zebra:** MultiBarcode has been part of NextGen SimulScan since DataWedge 8.0 (no template needed). Zebra's developer blog notes caveats with Instant Reporting. — [Zebra DataWedge 8.0 Barcode Input](https://techdocs.zebra.com/datawedge/8-0/guide/input/barcode/); [Zebra dev blog](https://developer.zebra.com/blog/scanning-multiple-barcodes-single-trigger-pull)
- **barKoder** Batch MultiScan: settings include a "Delay before the same code is reported again" (examples use 0), a multi-code result cache of 3,000 ms in the examples, and a max results count of 200 on mobile. The FAQ leaves dedup to the app ("keep a set of values already received"). AR mode outlines each code and marks decoded ones ("Users see at a glance what the batch still misses"). The page makes no claim about counting same-value items separately. — [barKoder Batch MultiScan](https://barkoder.com/batch-multiscan)
- **LEADTOOLS** (still-image SDK) exposes `DuplicateCount` / `GetDuplicatedCount` for duplicate codes in one image. — [LEADTOOLS GetDuplicatedCount](https://www.leadtools.com/help/sdk/barcode/clib/lbarcode-getduplicatedcount.html)

### Inferences
- A time-window repeat delay (Scanbot, barKoder, old Scandit 500 ms) is the industry's simple dedup. In a sweep it fails both ways: one box seen longer than the delay is counted twice, and two identical adjacent boxes inside the window count as one. SweepScan's track-based identity is strictly better. A repeat delay is worth having only as a "manual single-scan" fallback mode.
- Zebra's "unique values only" is the opposite of what SweepScan needs and confirms that hardware imagers don't solve this.
- Dynamsoft's approach (AR world anchors, panorama, cross-frame validation, editable review) is conceptually the closest to SweepScan. On Android the analogue would be ARCore pose plus planar mapping. SweepScan's optical-flow scene integration is a lighter substitute.
- Editable review of a stitched panorama is a UX idea worth copying: show the integrated column image with a marker per counted box, and let the user tap to add or remove.

### Gaps
- No usable public info was found on Cognex Mobile Barcode SDK (Manatee), Anyline or Honeywell duplicate counting. Searches returned nothing relevant.
- I did not check Dynamsoft Barcode Reader's cross-frame filter / `duplicateForgetTime` parameters or the current (2026) Android status of AR mode.
- Scanbot's AR overlay behavior with COUNTING across frames (whether one physical code is re-counted after the delay) is not documented on the page read.

## 3. "Freeze one high-res frame and count every label in it": is it used commercially, and how are snapshots merged?

### Takeaway
Partly. Scandit MatrixScan Count's shutter / scan-phase model is the closest commercial match: hold still, tap, get every label in view counted, verify with AR icons. The docs don't say whether it uses one still or a short burst. Dynamsoft's batch scanner works panoramically, combining overlapping captures with cross-validation and an editable panorama. Scandit's patents describe stitching overview images by feature matching and RANSAC homography onto a planar shelf map. No vendor documents an automatic "merge several snapshots without double counting identical codes" algorithm.

### Cited Findings
- Scandit MatrixScan Count: a static shutter-triggered scan of a batch, with continuous movement said to reduce performance. — [Scandit technical brief](https://www.scandit.com/resources/guides/matrixscan-count-technical-brief/)
- Results are delivered per finished scan phase. Apps copy `recognizedBarcodes` out. `setAdditionalBarcodes` injects earlier partial results. `reset()` clears tracked history. — [Scandit Get Started (iOS)](https://docs.scandit.com/sdks/ios/matrixscan-count/get-started); [Scandit BarcodeCount API](https://docs.scandit.com/7.6/data-capture-sdk/android/barcode-capture/api/barcode-count.html)
- The Scandit blog claims spatial memory of codes that leave the field of view (marketing level). — [Scandit blog 2026-02-17](https://www.scandit.com/blog/multiple-barcodes-with-ai/)
- The Scandit mapping patent stitches images with feature matching and RANSAC homography, rectifies overviews and aligns them with shelf rows, and registers close-ups into the overview. — [US11886954B2](https://patents.google.com/patent/US11886954B2/en)
- Dynamsoft cross-validates overlapping captures and allows manual edits on the panoramic image. — [Dynamsoft blog](https://www.dynamsoft.com/blog/product-features/bulk-barcode-scanning-accuracy/)

### Inferences
- A practical hybrid for SweepScan: per column, take one or a few still "scan phases" (say a top half and a bottom half), count the distinct labels in each by position, and merge overlapping snapshots with a planar homography between them (feature matching and RANSAC, as in Scandit's patent). Same-value labels that map to the same location are treated as one. Same-frame-distinct is your existing rule.
- The S23 Ultra's high-res stills suit this approach, since small Code 128 labels decode better at high resolution than from video frames. This is an inference; I didn't test it.

### Gaps
- I found no explicit commercial documentation of a snapshot-merge algorithm for identical codes.
- I couldn't confirm whether Scandit's scan phase uses a single still image.

## 4. Pricing and licensing (brief)

### Takeaway
All of these are commercial SDKs with sales-quoted or paid annual licenses. None of the sources found offers a free tier for personal or non-commercial production use. Only time-limited trials exist. That supports building SweepScan's own pipeline.

### Cited Findings
- Scandit: pricing is not public and commercial licensing goes through sales. A third-party listing reports a 30-day SDK trial. MatrixScan Count needs a license that includes it (the MatrixScan AR add-on). — [FitGap Scandit listing](https://us.fitgap.com/products/007562/scandit-barcode-scanning); [Scandit technical brief](https://www.scandit.com/resources/guides/matrixscan-count-technical-brief/)
- Scanbot: a fixed annual fee, a 7-day self-service trial, and without a license the SDK runs one minute per session. The search result banner says Scanbot SDK has been acquired by Apryse. — [FitGap Scanbot listing](https://us.fitgap.com/products/021088/scanbot-barcode-scanner-sdk); [scanbot.io](https://scanbot.io/scanbot-sdk-vs-dynamsoft/)
- Dynamsoft: from $1,499/year, with a 30-day trial. — [Dynamsoft pricing](https://www.dynamsoft.com/barcode-reader/pricing/); [Dynamsoft purchasing FAQ](https://www.dynamsoft.com/barcode-reader/purchasing-faqs)

### Inferences
- For a personal or portfolio project, recurring license costs and trial expiry make commercial SDKs impractical, apart from benchmarking during a trial.

### Gaps
- No non-commercial or free license was found for any vendor. barKoder, Cognex, Anyline and Honeywell pricing was not checked. Third-party pricing listings may be out of date.
