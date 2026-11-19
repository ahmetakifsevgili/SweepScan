# Open-source repos for multi-barcode tracking/counting, phone-as-HID scanners, inventory apps, and checks of earlier AI-report claims

Collected 2026-11-14. Repo numbers (stars, last push, license) come from the GitHub REST API (`api.github.com/repos/...`) on 2026-11-14 unless stated otherwise. "Last push" means `pushed_at` and covers any branch. The GitHub search API hit its unauthenticated rate limit near the end, so a few searches (Python/OpenCV trackers) relied on web search instead.

General caution: two of the most relevant hits, Rockyljewell/QR-GEN and Yangjunjie-Lin/Scanly, are new (created 2026-09 and 2026-02). Their README, issue and docs text is very long and reads as agent-written. Both say in their own text that they have not been validated on physical phones. Treat them as design references, not proven field code.

## 1. Open-source projects doing multi-barcode scanning with per-barcode tracking and counting of identical barcodes

### Takeaway
I found no mature, widely used open-source project that counts physical items sharing an identical barcode by tracking them across camera frames. The closest are two new, low-adoption, permissively licensed SDKs: Scanly (MIT) and QR-GEN (Apache-2.0). Both state that physical identity must not come from payload equality, and both admit they lack physical-device validation. Mainstream decoders and apps (zxing-cpp, ML Kit samples, BinaryEye, mobile_scanner, Quagga2) return detections per frame. Most demo apps dedupe by payload, so two boxes with the same barcode count as one.

### Cited Findings
**Directly relevant (tracking + same-payload instances)**
- **Yangjunjie-Lin/Scanly**: https://github.com/Yangjunjie-Lin/Scanly. MIT, 137 stars, created 2026-02-12, last push 2026-11-01. A "privacy-first, local-only barcode scanning SDK for Web, Node.js, React, iOS and Android". The full format set runs on a lazily loaded ZXing-C++ WASM engine, and native iOS/Android wrappers sit over a shared C++ core. Listed capabilities include "Multiple-code results, tracking, and batch identity". — [README](https://github.com/Yangjunjie-Lin/Scanly)
  - Its tracking design doc says `trackId` and `physicalInstanceId` "identify a physical observation path. Neither is derived from payload equality. Equal UPC values at different geometries therefore remain separate tracks". — [docs/beta2-tracking-batch.md](https://github.com/Yangjunjie-Lin/Scanly/blob/main/docs/beta2-tracking-batch.md)
  - Batch modes are `expected-count`, `checklist` and `unique-physical-instance`. Checklist quantities are physical counts: a quantity of 10 for one UPC needs ten confirmed physical tracks and "is not reduced to a Set(payload)". — [same doc](https://github.com/Yangjunjie-Lin/Scanly/blob/main/docs/beta2-tracking-batch.md)
  - A lost track that comes back within a grace window keeps its `physicalInstanceId` and "cannot advance a batch twice". Once a track is `retired`, new evidence starts a new identity. Longer re-entry continuity has to come from a longer bounded `maxMissedFrames`, "rather than deduplicate by payload or position". — [same doc](https://github.com/Yangjunjie-Lin/Scanly/blob/main/docs/beta2-tracking-batch.md)
  - The tracking benchmark has 34 ground-truth paths. It measures identity switches, fragmentation, false confirmed tracks, recall and precision, and "Track-count equality alone is not a pass condition." The doc also disclaims "physical-device coverage, or parity with a commercial MatrixScan product". — [same doc](https://github.com/Yangjunjie-Lin/Scanly/blob/main/docs/beta2-tracking-batch.md); benchmark code in `benchmark/tracking/*.ts` (repo tree)
- **Rockyljewell/QR-GEN** ("QRGEN"): https://github.com/Rockyljewell/QR-GEN. Apache-2.0, 0 stars, created 2026-09-26, last push 2026-11-09. It claims 26 symbologies, multi-code tracking and platforms from web to Python, and ships "Agent Skills" for coding agents. Its decoder is zxing-wasm (zxing-cpp). — [README](https://github.com/Rockyljewell/QR-GEN), [issue #32](https://github.com/Rockyljewell/QR-GEN/issues/32)
  - Issue #6 says spatial tracking runs on web/iOS/Android. The Python, React Native and Flutter helpers have *optional* short-lived spatial observations, but "Their defaults still aggregate by symbology and payload" and "Scan-event duplicate filtering remains payload-based". — [issue #6](https://github.com/Rockyljewell/QR-GEN/issues/6)
  - In its receiving/count session (PR #61), "repeated observations never increment quantities. Unique barcode values remain distinct from physical instances". Quantities are confirmed by explicit user confirmation, not by automatic counting. — [issue #6](https://github.com/Rockyljewell/QR-GEN/issues/6), [PR #61](https://github.com/Rockyljewell/QR-GEN/pull/61)
  - The tests cover "identical adjacent labels, short gaps, expiry" on generated images. "Physical camera movement, occlusion and identity swaps still require field validation", and "Short-lived tracks do not establish persistent off-screen physical identity". — [issue #6](https://github.com/Rockyljewell/QR-GEN/issues/6)
- **gkhnakbs/GAMultipleQRScannerAndTracker**: https://github.com/gkhnakbs/GAMultipleQRScannerAndTracker. MIT, 0 stars, created 2025-12-15, last push 2026-03-04. "Real-time multi-barcode detection for Android", built on CameraX, ML Kit and a Jetpack Compose overlay. The README (in Turkish) lists a "Benzersiz (unique) içerik listesi", a unique-content list, plus a point-in-polygon test and overlay performance tips. — [README](https://github.com/gkhnakbs/GAMultipleQRScannerAndTracker)

**Decoders and general scanners (per-frame multi-detection, no physical-instance counting)**
- **zxing-cpp/zxing-cpp**: Apache-2.0, 2,099 stars, last push 2026-11-13. This is the C++ engine behind BinaryEye, HID Barcode Scanner, zxing-wasm, Scanly and QR-GEN. — [repo](https://github.com/zxing-cpp/zxing-cpp)
- **Sec-ant/zxing-wasm**: MIT, 296 stars, last push 2026-10-31. A WebAssembly build of ZXing-C++. — [repo](https://github.com/Sec-ant/zxing-wasm)
- **markusfisch/BinaryEye**: MIT, 2,488 stars, last push 2026-11-13. An Android scanner using ZXing-C++; "continuous scanning uses the existing history" when no callback is set. — [README](https://github.com/markusfisch/BinaryEye)
- **googlesamples/mlkit**: Apache-2.0, 4,310 stars, last push 2026-10-26. Official ML Kit sample apps for Android and iOS. — [repo](https://github.com/googlesamples/mlkit)
- **juliansteenbakker/mobile_scanner** (Flutter): BSD-3-Clause, 1,179 stars, last push 2026-11-10. Uses CameraX/ML Kit on Android, AVFoundation/Apple Vision on iOS and macOS, and ZXing on web. Its controller exposes `detectionSpeed`. — [README](https://github.com/juliansteenbakker/mobile_scanner)
- **capawesome-team/capacitor-mlkit** (Capacitor): Apache-2.0, 216 stars, last push 2026-11-11. Returns ML Kit results with corner points (see section 4). — [repo](https://github.com/capawesome-team/capacitor-mlkit)
- **ericblade/quagga2** (JS): MIT, 915 stars, last push 2026-11-04, latest release 1.13.0 on 2026-10-21. It has its own locator and decoders and is 1D-focused (see section 4). — [repo](https://github.com/ericblade/quagga2), [releases](https://github.com/ericblade/quagga2/releases)

**Commercial-SDK samples (code is visible, but the SDK needs a license)**
- **Scandit/datacapture-android-samples**: 31 stars, last push 2026-11-12. The API reports no license, and it needs the commercial Scandit SDK (MatrixScan). — [repo](https://github.com/Scandit/datacapture-android-samples)
- **Dynamsoft/barcode-reader-mobile-samples**: 11 stars, license NOASSERTION, last push 2026-11-13. Needs the commercial Dynamsoft SDK (trial key). — [repo](https://github.com/Dynamsoft/barcode-reader-mobile-samples)
- **yushulx/Qt-QML-QR-code-scanner**: 10 stars, last push 2025-10-20, no license. A multi-barcode scanner built on Dynamsoft Barcode Reader. — [repo](https://github.com/yushulx/Qt-QML-QR-code-scanner)
- Dynamsoft's Python tutorials read multiple barcodes per webcam frame, with OpenCV handling only capture and display. I saw no cross-frame tracking in them. — [Dynamsoft blog](https://www.dynamsoft.com/codepool/python-camera-sdk-multi-barcode-scanner.html)

**Python/OpenCV**
- **AmilaCG/RealtimeBarcodeTracker** aims to track multiple barcodes, but according to the search-result snippet it currently handles only one barcode in real time. I did not open the repo itself. — [repo](https://github.com/AmilaCG/RealtimeBarcodeTracker)
- PyImageSearch's 2014 "real-time barcode detection in video" is a detection-only tutorial with no tracking or counting. — [PyImageSearch](https://pyimagesearch.com/2014/12/15/real-time-barcode-detection-video-python-opencv/)

### Inferences
- Reuse with MIT/Apache: Scanly (MIT), QR-GEN (Apache-2.0), GAMultiple (MIT), BinaryEye (MIT), zxing-cpp (Apache-2.0), googlesamples/mlkit (Apache-2.0), quagga2 (MIT) and mobile_scanner (BSD-3) can all be copied into an MIT/Apache project if you keep the notices. QR-GEN and zxing-cpp also need their NOTICE handling under Apache-2.0. The Scandit and Dynamsoft samples are only useful as UX references.
- The best ideas to port into SweepScan's Kotlin tracker are Scanly's contract: identity comes from geometric track association and never from payload, with tentative → confirmed → lost (grace window) → retired states, a bounded `maxMissedFrames`, and a "restore cannot double count" rule. Its benchmark metrics (ID switches, fragmentation, false confirmed tracks) suit an offline replay harness of S23 Ultra sweep videos. These ideas need no code copying.
- GAMultiple's "unique content list" most likely dedupes by payload. That is the wrong semantics for SweepScan, but the Compose overlay code may still be useful. I did not read its source; its default branch layout made the tree API return 404.
- QR-GEN's default behaviour (aggregate by payload, quantities set by explicit confirmation) is a warning example. Many off-the-shelf scanners will count two identical boxes as one.

### Gaps
- I could not confirm any widely adopted (>500 stars) open-source Android/iOS project that counts physical duplicates of one barcode across frames. If one exists, my searches did not find it.
- Scanly's and QR-GEN's tracker source quality, frame-time cost and real-phone behaviour are unverified. Both say themselves that physical-device validation is missing.
- Python/OpenCV MOT-style barcode counters (ByteTrack/SORT plus a decoder) were not found as dedicated repos. The GitHub search API was rate-limited at that point.

## 2. Phone-as-Bluetooth-keyboard scanner apps (architecture, HID descriptor, layouts, pacing, known issues, license)

### Takeaway
Fabi019/hid-barcode-scanner is the reference implementation. It is GPL-3.0 and actively maintained (push 2026-11-11, release v2.3.2 on 2026-09-13). It uses Android's `BluetoothHidDevice` API on Android 9+, a standard 8-byte keyboard report with report ID 8, asset-based keyboard layout files, and press/release pacing driven by a configurable `sendDelay`. All three candidates are GPL or AGPL, so you can learn from them, but you cannot copy their code into an MIT/Apache app.

### Cited Findings
- **Fabi019/hid-barcode-scanner** (app id `dev.fabik.bluetoothhid`, on F-Droid, IzzyOnDroid and Google Play): GPL-3.0, 291 stars, last push 2026-11-11, latest release v2.3.2 on 2026-09-13. — [repo](https://github.com/Fabi019/hid-barcode-scanner), [releases](https://github.com/Fabi019/hid-barcode-scanner/releases), [F-Droid](https://f-droid.org/packages/dev.fabik.bluetoothhid)
  - Decoding uses zxing-cpp and needs no internet. The app uses "the BluetoothHID API available on devices running Android 9 or greater", so the PC needs no extra software. Features include history export (text/JSON/CSV), multiple keyboard layouts, extra keys (\n, \t), a template engine with modifier combos, regex filtering with capture groups, a JavaScript engine for custom logic, auto-connect and auto-send. — [README](https://github.com/Fabi019/hid-barcode-scanner)
  - Code structure under `app/src/main/java/dev/fabik/bluetoothhid/bt/`: `BluetoothController.kt`, `BluetoothService.kt`, `Descriptor.kt`, `KeyTranslator.kt`, `KeyboardSender.kt`, `RfcommController.kt`, `ExternalController.kt`. There is also `ui/CustomQosOptions.kt`, which suggests user-tunable HID QoS settings. — [repo tree](https://github.com/Fabi019/hid-barcode-scanner/tree/main/app/src/main/java/dev/fabik/bluetoothhid)
  - HID descriptor (`Descriptor.kt`): Usage Page Generic Desktop / Usage Keyboard, application collection, `REPORT_ID` = 0x08. It has 8 modifier bits (usages 0xE0–0xE7), an 8-bit LED output report (Num Lock … Kana + 3), a reserved constant byte, and key array bytes with logical max 101 (0x65). It imports `BluetoothHidDeviceAppQosSettings` and `BluetoothHidDeviceAppSdpSettings`. — [Descriptor.kt](https://github.com/Fabi019/hid-barcode-scanner/blob/main/app/src/main/java/dev/fabik/bluetoothhid/bt/Descriptor.kt)
  - Pacing (`KeyboardSender.kt`): `sendProcessedString(..., sendDelay: Long, ...)` sends each key with `sendKey(key, sendDelay / 2)`, which is a press, a delay, then a release report, and then `delay(sendDelay / 2)` between keys. A code comment notes that `BluetoothHidDevice.sendReport` is fire-and-forget. The sender "always release[s] all keys at the end, even if cancelled". Backspaces use a fixed 10 ms delay. — [KeyboardSender.kt](https://github.com/Fabi019/hid-barcode-scanner/blob/main/app/src/main/java/dev/fabik/bluetoothhid/bt/KeyboardSender.kt)
  - Layouts (`KeyTranslator.kt`): keymaps load from `assets/keymaps/*.layout`, with a user-editable `custom.layout`. — [KeyTranslator.kt](https://github.com/Fabi019/hid-barcode-scanner/blob/main/app/src/main/java/dev/fabik/bluetoothhid/bt/KeyTranslator.kt). Support for accent/dead keys was added through issue #532 (closed 2026-05-09). — [#532](https://github.com/Fabi019/hid-barcode-scanner/issues/532). "Handy keyboard-layout tools" is still open as #438. — [#438](https://github.com/Fabi019/hid-barcode-scanner/issues/438). Layout files are minified at build time (PR #537). — [#537](https://github.com/Fabi019/hid-barcode-scanner/pull/537)
  - Other transports: RFCOMM mode (PR #436, 2025-09) and TCP client/server modes (PR #544, closed 2026-06-07). PR #539 fixed Bluetooth reconnect and added Cancel & Undo send. — [#436](https://github.com/Fabi019/hid-barcode-scanner/pull/436), [#544](https://github.com/Fabi019/hid-barcode-scanner/pull/544), [#539](https://github.com/Fabi019/hid-barcode-scanner/pull/539)
  - Known issues:
    - **Windows stuck keys (#564, closed 2026-08-18):** after the first scan, Enter, or on another phone the last character, "keeps repeating continuously" until the laptop is force-restarted. The reporter suspected key-release handling. — [#564](https://github.com/Fabi019/hid-barcode-scanner/issues/564)
    - **HID proxy blocked by an Android update (#553, closed 2026-07-16):** a user reports that "the Android update sometime in April now prevents the access to the HID Bluetooth Keyboard Proxy from third party apps". The maintainer recommends the `com.rdapps.bluetoothhidtester` app to check whether the OS exposes the profile. — [#553](https://github.com/Fabi019/hid-barcode-scanner/issues/553)
    - **Conflict with another HID app (#513):** connected, but no text arrived on the target until "Bluetooth Keyboard & Mouse Pro" was uninstalled. — [#513](https://github.com/Fabi019/hid-barcode-scanner/issues/513)
    - **Samsung:** #571, Galaxy A23 black scanning screen (closed 2026-09-02). — [#571](https://github.com/Fabi019/hid-barcode-scanner/issues/571). #391, could not scan the IMEI on a Galaxy S24 Ultra box (closed 2025-05-08). — [#391](https://github.com/Fabi019/hid-barcode-scanner/issues/391)
- **arnav-kr/Bluke**: AGPL-3.0, 173 stars, last push 2026-10-08. A Kotlin app that turns an Android phone into a Bluetooth HID keyboard, touchpad, gamepad and media remote with no host software. Its typing layouts are QWERTY, AZERTY, QWERTZ, Dvorak, Colemak and Russian ЙЦУКЕН, with Shift+Space cycling. It is not a barcode scanner. — [README](https://github.com/arnav-kr/Bluke)
- **jqssun/android-bt-remote** ("Remote"): GPL-3.0, 135 stars, last push 2026-06-20. A Bluetooth Classic HID keyboard, mouse and trackpad. It is built on Atharok's BT Remote (GitLab), kept as a submodule with patches, registers with `BluetoothHidDevice`, and needs Android 9+ on the phone. Claimed hosts include Windows XP SP2+, macOS 10.3+, iOS 13+, Linux 2.6+ and ChromeOS. A sibling repo, jqssun/darwin-bt-remote (AGPL-3.0, 83 stars), targets Apple devices. — [README](https://github.com/jqssun/android-bt-remote), [GitLab upstream](https://gitlab.com/Atharok/BtRemote)

### Inferences
- GPL-3.0 and AGPL-3.0 are not compatible with keeping SweepScan under MIT/Apache if code is copied. Reimplement from the HID spec and Android docs. The descriptor layout is standard and is not itself copyrightable expression in any meaningful way, but write your own.
- The patterns worth reusing as ideas:
  - always send an explicit all-keys-released report at the end, and after any cancel or error, to guard against the stuck-key failure in #564;
  - set inter-key delay and press-hold time as user settings;
  - use per-host layout files;
  - add a pre-flight check that the OS exposes the HID Device profile, because of #553;
  - warn when another HID app holds the profile, because of #513.

### Gaps
- I could not confirm that the package id `io.github.jqssun.btremote` belongs to jqssun/android-bt-remote. The README excerpt I read did not show it.
- I found no issue specifically about Samsung One UI breaking HID pairing or typing on an S23 Ultra. The Samsung issues found concern the camera or scanning.
- I did not read the default `sendDelay` value or the QoS defaults in Fabi019's Settings.

## 3. Open-source inventory and stock-counting apps that use the phone camera

### Takeaway
The "socketmobile/stockcount-android" repo no longer exists under the socketmobile org. A copy (UnklAdM/stockcount-android) shows it was built for hardware scanners, not the camera, and it exports a quantity-annotated .txt. The other inventory companions (InvenTree, Grocy Android, Basil, Hasr) are actively maintained. As far as I saw, they scan one item at a time.

### Cited Findings
- **socketmobile/stockcount-android**: the GitHub API returned "Not Found", and the socketmobile org lists no stock or count repo. A copy exists at **UnklAdM/stockcount-android** (Apache-2.0, 1 star, last push 2019-08-21). Its README says it "contains the source code of the Stock Count app by Socket Mobile for Android" and describes "rapid fire" multi-SKU scanning. Scans go to an editable text file "with quantity information automatically added to each scan", shareable as .txt. It notes "scanning barcode with built-in camera of the device is not availalbe yet"; any HID-mode scanner works. — [UnklAdM/stockcount-android](https://github.com/UnklAdM/stockcount-android)
- **inventree/inventree-app**: MIT, 144 stars, last push 2026-11-13. The official InvenTree mobile app. — [repo](https://github.com/inventree/inventree-app)
- **patzly/grocy-android**: GPL-3.0, 1,212 stars, last push 2026-08-25. A Grocy companion app. — [repo](https://github.com/patzly/grocy-android)
- **sabourinj/Basil**: MIT, 10 stars, last push 2026-11-12. A Grocy front-end "designed for Android devices with native barcode scanning hardware". — [repo](https://github.com/sabourinj/Basil)
- **mohammedsafaaxp/Hasr**: GPL-3.0, 6 stars, created 2026-10-27, last push 2026-11-12. "Receive goods and count stock from a phone, on top of ERPNext", where scanned cases convert to units. — [repo](https://github.com/mohammedsafaaxp/Hasr)
- Smaller apps: yuriycto/AcumaticaInventoryScanner (MIT, 12 stars, last push 2026-01-23) shows per-warehouse stock from Acumatica. Varadha9/Stockify (MIT, 6 stars, last push 2026-06-07) is an offline-first retail inventory app. EduSB1/Coletor-Produtividade (MIT, last push 2026-10-24) is a warehouse data-collection app with stock location counts. — [Acumatica](https://github.com/yuriycto/AcumaticaInventoryScanner), [Stockify](https://github.com/Varadha9/Stockify), [Coletor](https://github.com/EduSB1/Coletor-Produtividade)
- Snipe-IT: `snipe/snipe-it` returns "Moved Permanently" from the API, and I did not follow the redirect. — [api](https://api.github.com/repos/snipe/snipe-it)
- QR-GEN's receiving workflow (PR #61) has expected quantities, unexpected products, explicit confirmations, corrections with history and "stable exports", and documents an export contract. — [issue #6](https://github.com/Rockyljewell/QR-GEN/issues/6), [RECEIVING-WORKFLOW.md](https://github.com/Rockyljewell/QR-GEN/blob/main/docs/RECEIVING-WORKFLOW.md)

### Inferences
- Stock Count's "scan → line with qty → editable text file → share" is a simple, ERP-agnostic fallback export that SweepScan could offer next to HID typing, for example when the Bluetooth connection fails.
- None of these apps solves "many identical boxes in one camera view". They assume one scan per item, from hardware scanners or single camera scans.

### Gaps
- I did not inspect how InvenTree, Grocy Android or Hasr implement counting and export internally. There was no time to read their source.
- I found no "parts-stock" project. The search returned unrelated results.

## 4. Verification of the earlier AI-report claims

### Takeaway
- **Claim 1 (QR-GEN):** #32 is verified; #6 is not a duplicate-grouping bug report. "QRGen/QR-GEN" is Rockyljewell/QR-GEN. Its issue #32 is real and matches the claim, including the 3.1.6 recheck. Issue #6 is a roadmap; it does say the defaults aggregate by payload.
- **Claim 2 (Quagga2):** verified. The `decoder.multiple` behaviour matches the README, and related mobile-resolution and multi-label issues exist.
- **Claim 3 (capacitor-mlkit #96):** verified, with a nuance. The issue exists and contains a temporal vote-count fix. The "spatial" part is only ROI filtering by corner points, not spatial voting. The maintainer's vote-system PR #129 was closed without merging.

### Cited Findings
**Claim 1: QR-GEN**
- The project is **Rockyljewell/QR-GEN**, branded "QRGEN" in its README. It is Apache-2.0, has 0 stars, was created 2026-09-26, and was last pushed 2026-11-09. It is not kenglxn/QRGen (a Java QR generator with 1,506 stars) and not any other "QRGen" generator repo. — [repo](https://github.com/Rockyljewell/QR-GEN), [kenglxn/QRGen](https://github.com/kenglxn/QRGen)
- **Issue #32, "EAN13 reader returns a valid but wrong payload on a derived photo": verified.** It is open, created 2026-10-11 and updated 2026-11-08. The body says "The underlying reader can return checksum-valid, wrong EAN-13 values", reproduced in WASM 3.1.5/3.1.6 and in QR-GEN. — [#32](https://github.com/Rockyljewell/QR-GEN/issues/32)
  - The 2026-11-08 "released 3.1.6 recheck" used engine 3.1.6, Node 24.22.1, EAN13-only, `tryHarder:true`, `tryInvert:false` and full-frame PNGs:

    | Input | maxResults | Result |
    | --- | --- | --- |
    | Saved Chromium frame (640×480) | 1 | wrong **0798884816632** |
    | Locally retained derived frame | 255 | wrong **7034759003930** plus correct **5030159003930** |
    | Original positive control (480×360) | 1 | correct **9780804816632** |

    Both wrong values pass the EAN-13 checksum, and "The issue remains open." — [#32](https://github.com/Rockyljewell/QR-GEN/issues/32)
  - Context: the source images are ZXing blackbox test images (`ean13-2/07.png`, `/1/35`). The wrong reads appear after a 640×480 limited-range grayscale camera conversion. The author says these are "not independent samples, a population error rate, a new browser/phone test". — [#32](https://github.com/Rockyljewell/QR-GEN/issues/32)
  - A C++ row trace in the comments attributes the wrong read to "equal-best digit patterns in the wrong supporting rows" (rows y209 and y207). A candidate "complete-word ambiguity" patch only partly mitigates it: it suppresses the wrong value rather than recovering the correct one, and `/1/35` stays wrong. — [#32 comments](https://github.com/Rockyljewell/QR-GEN/issues/32), [PR #41](https://github.com/Rockyljewell/QR-GEN/pull/41), [PR #48](https://github.com/Rockyljewell/QR-GEN/pull/48)
  - Related: issue #93 is about zxing-wasm 3.1.6 fixing native result-vector cleanup, not the wrong EAN reads. — [#93](https://github.com/Rockyljewell/QR-GEN/issues/93)
- **Issue #6: mischaracterized.** Its real title is "Retail AR roadmap: instance counting, guided workflows and matched Scandit validation" (open, created 2026-10-11). It is not a bug report about two physical labels being grouped. It does say the Python, React Native and Flutter helper defaults "still aggregate by symbology and payload", that spatial observation is opt-in, and that "Scan-event duplicate filtering remains payload-based". So the claim's substance, that identical payloads get grouped by default, has partial support, but the framing as a reported bug is wrong. — [#6](https://github.com/Rockyljewell/QR-GEN/issues/6)

**Claim 2: Quagga2**
- README: "The `multiple` property tells the decoder if it should continue decoding after finding a valid barcode. If multiple is set to `true`, the results will be returned as an array of result objects. Each object in the array will have a `box`, and may have a `codeResult` depending on the success of decoding the individual box." The default is `multiple: false`. — [README](https://github.com/ericblade/quagga2/blob/master/README.md)
- #308, "Handling multiple barcodes in a single image" (2021-01-07): the maintainer points to `multiple: true`. The reporter found that the `boxes` coordinates are pixels of the internally resized image ("I didn't realize Quagga resizes the image before decoding"). — [#308](https://github.com/ericblade/quagga2/issues/308)
- #420, "Decoder Multiple Property" (2022-05-09): the maintainer says "Multiple is used to locate and return the results of multiple barcodes within the input image" and "A single barcode object will not be decoded by multiple readers, the first one in the array that returns a positive result will be used." — [#420](https://github.com/ericblade/quagga2/issues/420)
- #290, "Unable to scan barcode with low resolutions using mobile device" (Code 128, created 2020, closed): the maintainer notes "Since your image has multiple barcodes, which could easily confuse the locator", and suggests turning the locator off, using `area`, and raising the resolution. Only Dynamsoft's demo decoded the sample image. — [#290](https://github.com/ericblade/quagga2/issues/290)
- Other relevant items:
  - PR #605, "Fix double-offset of result.box when area is configured" (closed 2025-11-28), means `box` coordinates were wrong with `area` before that fix. — [#605](https://github.com/ericblade/quagga2/pull/605)
  - Open locator PRs #700 and #701 (2026-10-22) — [#700](https://github.com/ericblade/quagga2/pull/700), [#701](https://github.com/ericblade/quagga2/pull/701)
  - Old quaggaJS #164, "capturing multiple barcodes are not consistent" (open since 2017) — [serratus/quaggaJS#164](https://github.com/serratus/quaggaJS/issues/164)

**Claim 3: capawesome-team/capacitor-mlkit #96**
- **Verified that it exists:** "bug(barcode-scanning): multiple results while scanning same barcodes", created 2023-11-11, 15 comments, closed. The reporter scanned 3 EAN-13 labels and got 9 distinct values. For example, besides the correct `4058172310232` it returned `9061177310232`, `3051877310232` and `4058172310102`. — [#96](https://github.com/capawesome-team/capacitor-mlkit/issues/96)
- **Temporal voting fix (verified):** user thexii (2023-11-22) collects every `barcodeScanned` result into `{barcode, votes}` and counts repeats. "Mistakes will have no more 1-2 votes", and in their case the threshold is "3-5 votes". — [comment](https://github.com/capawesome-team/capacitor-mlkit/issues/96#issuecomment-1822610632). Another user accepts a code only after "the same code has been scanned 4 times in a row". — [#96](https://github.com/capawesome-team/capacitor-mlkit/issues/96)
- **Spatial part (partial):** thexii uses the returned corner points to discard codes outside a scan area. They note that long codes partly in view were read as wrong full values, which is why they moved to voting. — [comment](https://github.com/capawesome-team/capacitor-mlkit/issues/96#issuecomment-1948857243). The maintainer opened #130 for a scan-area restriction and said the scan area "should ideally not reach the edges of the screen". — [#96](https://github.com/capawesome-team/capacitor-mlkit/issues/96)
- **Maintainer response:** robingenz (2024-02-16) could not reproduce the problem, said it is ML Kit behaviour rather than a plugin bug, and closed the issue. He also opened PR #129, "feat(barcode-scanning): vote system", noting that `startScan` doesn't know a valid area, so all barcodes enter the vote. — [comment](https://github.com/capawesome-team/capacitor-mlkit/issues/96#issuecomment-1948635962). **PR #129 was closed without merge on 2024-10-08** (`merged_at` null). — [PR #129](https://github.com/capawesome-team/capacitor-mlkit/pull/129)
- Other users in the thread reported wrong Code 39 results, and Samsung devices that could not scan at all with the plugin; one team went back to the deprecated capacitor-community/barcode-scanner (ZXing/AVFoundation). — [#96](https://github.com/capawesome-team/capacitor-mlkit/issues/96)

### Inferences
- The #96 and QR-GEN #32 evidence together says that both ML Kit and zxing-cpp can emit checksum-valid wrong 1D values. For SweepScan this supports three rules:
  1. Commit a barcode value only after N agreeing reads on the *same track*. This is spatial-plus-temporal voting per track, which goes beyond #96's per-value votes.
  2. Reject reads whose corner points touch the frame edge, since partial codes produce wrong full values.
  3. Cross-check ML Kit against zxing-cpp before typing into the ERP.
- Code 128 has a mod-103 check and no fixed length, so its failure modes differ from EAN-13. Still, the "partially visible code" and "single-row misread" mechanisms carry over.
- Per-value voting as in #96 cannot count physical duplicates, because two identical boxes merge into one vote bucket. The votes must be attached to track IDs.

### Gaps
- I found no evidence that QR-GEN's #32 was reported upstream to zxing-cpp or zxing-wasm.
- I found no Quagga2 issue that specifically combines "mobile low resolution" with "multiple labels" other than #290.
- No other capacitor-mlkit issue proposing explicit *spatial* voting (votes per screen location) was found. Only #96 and #130 relate to it.
