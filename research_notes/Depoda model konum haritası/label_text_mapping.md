# Reading the shoe box label text to map a barcode to its model code

Context for the report writer: SweepScan already depends on `com.google.mlkit:barcode-scanning:17.3.0` and `com.google.mlkit:text-recognition:16.0.1` (bundled, Latin) — see `app/build.gradle.kts` lines 79/81. It also has `app/src/main/java/com/sweepscan/scan/OcrEngine.kt`, which today runs ML Kit OCR on an upright crop around a located-but-unread barcode (max side 1024 px) and accepts only an 8/13-digit number with a valid GS1 check digit. So the OCR plumbing (crop, rotation, blocking call on the analysis thread) already exists; the new work is reading the model/colour/size line and pairing it with the barcode.

Target label line example: `0159 2041 / SİYAH / 38` (model code, colour, EU size). Barcode: Code 128, 13 digits (sometimes 8).

## 1. ML Kit Text Recognition v2 (bundled, Latin): limits, resolution, latency, blur, Turkish

### Takeaway
ML Kit's documented guidance is "at least 16x16 px per character, gains stop above ~24x24 px", Turkish is an officially supported Latin-script language, and the bundled model exposes per-element/per-symbol confidence and bounding boxes that can drive geometry pairing and voting. Google publishes no accuracy numbers, no per-device latency figures and no character-confusion statistics, so those must be measured on the user's phone.

### Cited Findings
- Character size: "Ideally, each character should be at least 16x16 pixels"; characters larger than 24x24 px generally do not improve accuracy. — [ML Kit Text Recognition v2, Android](https://developers.google.com/ml-kit/vision/text-recognition/v2/android)
- Resolution examples: 640x480 is enough for a business card filling the frame width; a letter-sized page may need 720x1280. For real-time use, fill the frame with the text and use lower resolution while still meeting the per-character minimum; smaller images process faster. — [ML Kit Text Recognition v2, Android](https://developers.google.com/ml-kit/vision/text-recognition/v2/android)
- Focus/blur: poor focus reduces accuracy; the docs only advise asking the user to recapture. No quantitative motion-blur guidance is given. — [ML Kit Text Recognition v2, Android](https://developers.google.com/ml-kit/vision/text-recognition/v2/android)
- Versions/size: bundled Latin = `com.google.mlkit:text-recognition:16.0.1` (~4 MB per script per architecture, model available immediately); unbundled = `play-services-mlkit-text-recognition:19.0.2` (~260 KB, model downloaded via Play services, requests before download completes return no results). minSdk 23. — [ML Kit Text Recognition v2, Android](https://developers.google.com/ml-kit/vision/text-recognition/v2/android)
- Speed: "real-time on most devices" for the Latin library; other scripts slower. No millisecond figures published. — [ML Kit Text Recognition v2, Android](https://developers.google.com/ml-kit/vision/text-recognition/v2/android)
- Real-time tips: with CameraX keep `STRATEGY_KEEP_ONLY_LATEST`, drop frames while the detector is busy, close each `ImageProxy`, use YUV_420_888/NV21. — [ML Kit Text Recognition v2, Android](https://developers.google.com/ml-kit/vision/text-recognition/v2/android)
- Result hierarchy: Text → TextBlock → Line → Element (word) → Symbol (character); each level has text, corner points and bounding box. — [ML Kit Text Recognition v2, Android](https://developers.google.com/ml-kit/vision/text-recognition/v2/android)
- `Text.Element` API: `getConfidence()` 0.0–1.0 (returns 0 for the unbundled library on Play services older than 22.30); `getAngle()` rotation in degrees (−180..180, clockwise positive); `getCornerPoints()` 4 points clockwise from top-left (may be non-rectangular under perspective); `getRecognizedLanguage()` BCP-47 or "und"; `getSymbols()` not yet available in unbundled versions. — [Text.Element reference](https://developers.google.com/android/reference/com/google/mlkit/vision/text/Text.Element)
- Turkish (`tr`, Latn) is in the list of 31 supported Latin-script languages; the page gives no diacritic-specific guidance. — [ML Kit v2 supported languages](https://developers.google.com/ml-kit/vision/text-recognition/v2/languages)
- Latency anecdotes (not controlled, old): ~400–800 ms/frame at 640x480 on a Samsung phone (2020 Stack Overflow); one report of >4 s per frame with `MlKitAnalyzer` vs ~600 ms with a manual `ImageAnalysis.Analyzer`; a third-party iOS comparison (iPhone 12) reported ~0.05 s average for ML Kit. — summarised in search results pointing to [SO: mlkitanalyzer slow](https://kiwix.lema.org/stackoverflow.com_en_all_2023-11/questions/74109891/mlkitanalyzer-for-android-camerax-is-slow-for-text-recognition) and [Heartbeat iOS OCR comparison](https://heartbeat.comet.ml/comparing-on-device-text-recognition-ocr-sdks-24f8a0423461) (secondary/anecdotal; treat with caution).
- The most recent ML Kit release-notes listing seen showed MLKitTextRecognition (v2, iOS) 7.0.0 dated 2025-06-27 and an Android API-change entry dated 2026-04-02 whose content was not visible. — [ML Kit release notes](https://developers.google.com/ml-kit/release-notes) (snippet only; not verified in full)

### Inferences
- Label sizing: if the model line is printed ~3–4 mm tall, 16 px per character means the label must be resolved at roughly 4–5 px/mm. On a 1280x720/1920x1080 analysis frame this generally requires the box to be close or the camera zoomed; the app's existing auto-zoom (for barcodes) helps. Cropping the label region from the full-resolution analysis frame (as `OcrEngine` already does) is better than OCR on a downscaled whole frame.
- Since ML Kit gives a confidence per Element and per Symbol in the bundled build, a per-character confidence floor (e.g. reject a model code if any digit symbol is below ~0.5) is implementable; the exact threshold must be tuned on real labels — no published calibration exists.
- Expected confusions (general OCR behaviour, not ML Kit-documented): 0↔O/D, 1↔I/l/|, 5↔S, 8↔B, 2↔Z, 6↔G; "/" read as "l", "1", "|" or dropped; "İ" (dotted capital) read as "I", "Ş" as "S", "Ğ" as "G", "Ü/Ö" without dots. Because the model code is all digits and the colour comes from a small vocabulary, both are easy to correct by position-aware mapping and dictionary matching (see section 2).
- Motion blur: with no documented tolerance, the safe approach is to run OCR only on frames where the barcode was just decoded (a decode implies the label region was sharp enough) and to vote across several frames.
- Latency on Snapdragon 8 Gen 2: no source found; Element/Line-level OCR on a ~1000 px crop is plausibly tens of ms on that class of SoC, but this must be measured (the repo already has `OcrBenchmarkTest.kt` under androidTest, which is the right place to time it).

### Gaps
- No official accuracy figures, character-confusion tables, or Turkish-diacritic error rates for ML Kit v2 were found.
- No published latency for ML Kit text recognition on Snapdragon 8 Gen 2 / Galaxy S23-class devices.
- No documented motion-blur tolerance.

## 2. Robust parsing of "NNNN NNNN / COLOUR / SIZE" and pairing text with the right barcode

### Takeaway
Treat the label as a template with typed fields (8-digit model code, colour from a known vocabulary, EU size 17–50 with optional half), validate each field with a regex after position-aware character correction, and pair text to a barcode by geometry relative to the decoded barcode box. Commercial "label capture" SDKs (Scandit) use exactly this pattern: a label definition with a barcode field plus OCR text fields validated by regexes and disambiguated by anchor keywords.

### Cited Findings
- Scandit Smart Label Capture defines a label as a set of fields: custom barcode fields plus custom text fields whose OCR output is validated by `valueRegexes` (examples `\d{2}/\d{2}/\d{4}`, `[A-Z]{2}\d{6}`, barcode `^[0-9]{12,13}$`), with `anchorRegexes` (e.g. "exp", "pack") to tell look-alike fields apart, and fields that can be optional. — [Scandit Label Field Definition](https://docs.scandit.com/data-capture-sdk/ios/label-capture/api/label-field-definition.html); [Scandit Custom Text (Android)](https://docs.scandit.com/data-capture-sdk/android/label-capture/api/custom-text.html); [Scandit label definitions](https://docs.scandit.com/7.6.14/sdks/flutter/label-capture/label-definitions/)
- A Scandit patent ("Label capture using optical pattern recognition and barcode semantics") argues barcodes carry error checking whereas OCR has little or none, so decoded barcode content is used to verify OCR text, with approximate matching because OCR may misread characters; it also notes the system may not know whether accompanying text is above or below the barcode. — [US patent 12711794 (USPTO PDF)](https://image-ppubs.uspto.gov/dirsearch-public/print/downloadPdf/12711794) (described from search snippet; full text not read)
- Another patent uses the label itself as an anchor: detect the label, then decode its fields by OCR or barcode decoding. — search-result summary of the same USPTO family ([USPTO 12711794](https://image-ppubs.uspto.gov/dirsearch-public/print/downloadPdf/12711794)); not independently verified
- ML Kit gives bounding boxes and corner points for every Block/Line/Element, and an angle per Element, so geometry relative to a barcode box is directly available. — [Text.Element reference](https://developers.google.com/android/reference/com/google/mlkit/vision/text/Text.Element)

### Inferences (proposed design, not from a source)
- Normalisation before matching: `uppercase(Locale("tr"))`, collapse whitespace, unify separators `[/|\\]` and stray `l`/`I` between spaces to `/`.
- Position-aware correction only in digit slots: map `O,o,D,Q→0`, `I,l,|,i→1`, `S,s→5`, `B→8`, `Z→2`, `G→6` inside the 8-character model-code candidate; never apply it to the colour field.
- Model code regex (tolerant of a missing/extra space or a dash/dot): `(?<![0-9A-Z])([0-9OoDIl|SB]{4})[\s.\-]?([0-9OoDIl|SB]{4})(?![0-9A-Z])` → after correction must be exactly `\d{4} \d{4}`. Also accept `\d{8}` written without a space and normalise to `NNNN NNNN`. If the company's model codes are known to have a fixed prefix pattern (e.g. leading `0`), add that as a constraint.
- Must not match the barcode's own human-readable digits: reject any 8-digit candidate that is a substring of the decoded 13-digit barcode or of any GS1-check-digit-valid 8/13-digit number in view (the existing `OcrEngine` logic already identifies those).
- Size regex: `(?<!\d)(1[7-9]|[2-4]\d|50)(?:[.,](5)|½)?(?!\d)`; take the token after the last separator, or the last number on the line.
- Colour: letters `[A-ZÇĞİÖŞÜ]+(?:[ \-][A-ZÇĞİÖŞÜ]+)*` between separators; match against a vocabulary (SİYAH, BEYAZ, LACİVERT, KAHVERENGİ/KAHVE, TABA, BEJ, GRİ, KIRMIZI, BORDO, PEMBE, MAVİ, YEŞİL, HAKİ, VİZON, ...) with diacritic-folded Levenshtein ≤1–2 (fold İ→I, Ş→S, Ğ→G, Ü→U, Ö→O, Ç→C). Learn new colours only after repeated agreement. Store the canonical Turkish spelling.
- Whole-line pattern (after normalisation): `^(\d{4} \d{4})\s*/?\s*([A-ZÇĞİÖŞÜ][A-ZÇĞİÖŞÜ \-]*?)\s*/?\s*(\d{2}(?:[.,]5)?)$`, but parse fields independently too because ML Kit often splits one printed line into separate Lines/Blocks.
- Geometry pairing when several labels are in view: (1) build the barcode quad from ML Kit barcode `cornerPoints`; (2) rotate the crop so the barcode is horizontal (the existing `LumaCrop.upright` does this); (3) search a region defined in barcode-width units (e.g. up to ~1.5 barcode heights above, the digits strip below, and ±0.5 widths sideways — to be calibrated against real labels); (4) require the text Element angle to be within ~10–15° of the barcode angle; (5) assign each text candidate to the nearest barcode by distance in that normalised frame, and refuse to assign if the second-nearest barcode is nearly as close (ambiguity margin). (6) Bonus check: if the OCR in the same crop reads the barcode's human-readable digits and they equal the decoded value, the crop is confirmed to belong to that barcode (this is the "barcode semantics verify OCR" idea above).
- First field test should log raw OCR Lines with boxes relative to the barcode, so the region constants come from data rather than guesses.

### Gaps
- No published academic benchmark specific to shoe-box end labels or to the "barcode ↔ nearby text" association problem was read in full; evidence comes from vendor SDK docs and patent summaries.
- Exact layout of all suppliers' labels (where the model line sits relative to the barcode) is unknown and must be sampled in the stock room.

## 3. Learning a barcode → model mapping across frames (voting, thresholds, store once)

### Takeaway
Use the same "agree across N frames before trusting" rule ML Kit itself recommends for barcodes, plus field validation and cross-barcode consistency, then persist the mapping and stop running OCR for that barcode. No source gives specific thresholds; they must be chosen and tuned.

### Cited Findings
- ML Kit barcode docs: video frames may decode differently from one another, so wait until the same value is read across consecutive frames before trusting it. — [ML Kit Barcode Scanning, Android](https://developers.google.com/ml-kit/vision/barcode-scanning/android)
- Scandit's patent frames OCR as the weaker signal that should be verified against barcode content with approximate matching. — [USPTO 12711794](https://image-ppubs.uspto.gov/dirsearch-public/print/downloadPdf/12711794) (snippet-level)
- A 2022 paper on product verification uses OCR classification with Mondrian conformal prediction (i.e., calibrated per-class confidence to decide when to accept). — [Arias et al. 2022 (UMN)](https://www-users.cse.umn.edu/~gini/publications/papers/Arias2022aidbei.pdf) (title/abstract level only)
- A review of grocery label detection/recognition contrasts barcode-based and label(text)-based systems. — [MDPI Applied Sciences 13(5):2871](https://www2.mdpi.com/2076-3417/13/5/2871) (not read in full)

### Inferences (proposed algorithm)
- Per barcode, keep a small vote table: `modelCode → {count, distinctFrames, sumConfidence, lastSeen}`, same for colour and size. Only count reads from frames where the barcode itself was decoded in the same frame (so geometry is fresh) and only one vote per frame per barcode.
- Lock rule (starting point to tune): accept model code when ≥3 votes from ≥3 distinct frames, the winner has ≥2/3 of all votes for that barcode, and mean min-symbol-confidence ≥ ~0.6; if two different codes each reach 2 votes, mark "conflict" and require 2 more agreeing votes.
- Cross-checks that make the mapping very reliable for shoe stock: (a) all sizes of one model+colour share the model code and differ only in size, so a new barcode whose OCR gives a known model+colour with a not-yet-seen size is plausible; (b) two different barcodes must not map to the same (model, colour, size) — if they do, flag for review; (c) barcodes of one model are often consecutive or share a long prefix (internal numbering), which can be used as a soft prior but not as a rule.
- Persist the locked mapping (Room/SQLite table `barcode → model, colour, size, source=ocr|erp|manual, votes, firstSeen`). Once locked, never OCR that barcode again; allow manual correction in the UI, and let an ERP import override OCR-learned rows.
- Keep the raw winning crop (small JPEG) for disputed mappings so the user can confirm them later in the session viewer.

### Gaps
- No public source gives validated vote counts or confidence thresholds for barcode-text association; values above are engineering starting points.

## 4. Alternatives: ERP export (DİA) and deriving the model from the GTIN

### Takeaway
The most reliable mapping is the ERP's own stock card data: DİA's web service exposes stock card listing and variant listing methods (JSON, session-based). A model code cannot be derived from a GTIN-13 because GS1 numbers are deliberately non-significant; 868/869 only says GS1 Turkey issued the company prefix, and labels starting 2x/02 are restricted-circulation (internal) numbers with company-defined structure.

### Cited Findings
- DİA web service (v3, JSON, `/api/v3/scf/json`) has stock methods `scf_stokkart_listele`, `scf_stokkart_getir`, `scf_stokkart_varyant_listele`, `scf_stokkart_birimleri_listele/_getir`, `scf_stok_depo_miktarlari_listele`, `scf_stok_varyant_miktarlari_listele`, `scf_varyant_miktarlari_listele`, `scf_stokkart_detay_listele` ("resimler, varyantlar, fiyatkartlar, birimler"), plus `scf_stokkart_barkod_uret` (generates barcode numbers; `turu` = ADET, KG, PLU, MIKTARLI, EAN8, EAN13, RANDOM; uses credits/kontör) and `scf_karebarkod_listele`. Common parameters: `session_id`, `firma_kodu`, `donem_kodu`, `filters`, `sorts`, `params`, `limit`, `offset` (limit 0 = all records). — [DİA Doc: Stok-Hizmet web services](https://doc.dia.com.tr/doku.php?id=gelistirici:wsapi:stokhizmet); [DİA Doc: SCF overview](https://doc.dia.com.tr/doku.php?id=gelistirici:wsapi:scf)
- The fact that DİA can generate EAN8/EAN13/RANDOM barcodes (`scf_stokkart_barkod_uret`) indicates barcodes in DİA can be system-generated numbers attached to stock cards/units, consistent with these labels carrying internal numbers. — [DİA Doc: Stok-Hizmet](https://doc.dia.com.tr/doku.php?id=gelistirici:wsapi:stokhizmet)
- A third-party integrator says DİA API access may require an application/approval on the DİA side. — [Zunapro DİA integration page](https://www.zunapro.com/turkey/tr/entegrasyon/dia) (vendor marketing)
- GS1 prefixes 868–869 belong to GS1 Turkey; a prefix identifies the member organisation that issued the company prefix, not country of origin. — [GS1 prefixes](https://www.gs1.org/prefixes); [Wikipedia: List of GS1 country codes](https://en.wikipedia.org/wiki/List_of_GS1_country_codes)
- GTIN = GS1 company prefix (assigned by the member organisation) + item reference (assigned by the company) + check digit; the split varies with the company-prefix length. — [GS1 prefixes](https://www.gs1.org/prefixes); search summary of [GS1 General Specifications](https://ref.gs1.org/standards/genspecs/)
- GS1 rule: GTINs must be allocated uniquely and "SHOULD NOT contain any intelligence or parsable strings"; GS1 General Specifications have a section titled "Non-Significance"; embedding internal codes is discouraged. — [GSCN 16-476 GTIN Redundancy](https://www.gs1.org/docs/barcodes/GSCN_16_476_GTIN_Redundancy.pdf); [GS1 General Specifications ch. 4 (mirror)](https://cli.im/knowledge/compliance-labels/coding-systems/coding-systems/standards/group/gs1-general-specifications-standard-release26-chapter-04) (quoted via search snippets)
- Restricted Circulation Numbers: GS1 prefixes 02 and 20–29 can be used for numbers valid only within a company or region; for company-internal use the structure and management are the company's responsibility; RCNs shall only be encoded in EAN-8, EAN-13, UPC-A or UPC-E and not with Application Identifiers. — [GSCN 23-006 RCN](https://www.gs1.org/docs/barcodes/GSCN-23-006-RCN.pdf)
- ML Kit validates checksums for most formats but notes the checksum digit is not supported for ITF and Code 39. — [ML Kit Barcode Scanning, Android](https://developers.google.com/ml-kit/vision/barcode-scanning/android)

### Inferences
- These labels encode a 13/8-digit number in Code 128 rather than EAN-13. Under GS1 rules a GTIN in Code 128 would normally be GS1-128 with AI (01); a plain Code 128 with 13 digits strongly suggests an internal/ERP-generated number (possibly EAN-13-shaped with a valid check digit, which the app already checks). Either way, nothing in the number reliably encodes model/colour/size.
- Practical hierarchy: (1) DİA export (Excel export of stock cards with barcode, stock code, variant/colour/size) or the web service → bulk import into the app; (2) OCR learning for barcodes not in the import; (3) manual entry. Even a one-time Excel export removes the need for OCR on most boxes and gives ground truth for testing the OCR path.
- Weak heuristic only: if the company's internal numbering is sequential per model (common when generating barcodes in bulk for a size run), barcodes of the same model may share a long prefix; useful as a sanity prior, never as the mapping.

### Gaps
- Exact DİA field names for barcode, model, colour and size (`ScfStokkartModel`, variant model) and the filter syntax were not visible in the fetched parts of the docs; the DİA "Excel export" of stock cards was not documented in sources found.
- Turkish company-prefix lengths used by GS1 Turkey were not found.
- Whether this shop's barcodes are GS1-registered (868/869) or internal was not determined (needs a sample of real barcodes).

## 5. ML Kit barcode scanning 17.3.0 (QR + 1D together) and the cost of OCR alongside it

### Takeaway
One ML Kit `BarcodeScanner` can read QR and Code 128/EAN in the same pass; restricting formats speeds it up. Text recognition is a separate, heavier model, so it should run only for new/unmapped barcodes on a crop, never on every frame.

### Cited Findings
- Supported formats in one scanner: Code 128, Code 39, Code 93, Codabar, EAN-13, EAN-8, ITF, UPC-A, UPC-E, QR, PDF417, Aztec, Data Matrix; restrict with `BarcodeScannerOptions.Builder().setBarcodeFormats(...)`, which speeds up detection. Data Matrix must intersect the image centre (one per image). — [ML Kit Barcode Scanning, Android](https://developers.google.com/ml-kit/vision/barcode-scanning/android)
- Versions: bundled `com.google.mlkit:barcode-scanning:17.3.0` (~2.4 MB), unbundled `play-services-mlkit-barcode-scanning:18.3.1` (~200 KB). Auto-zoom (`setZoomSuggestionOptions`, `ZoomCallback`, `setMaxSupportedZoomRatio`) from bundled 17.2.0; `enableAllPotentialBarcodes()` from 17.1.0 returns detected-but-undecoded barcodes. — [ML Kit Barcode Scanning, Android](https://developers.google.com/ml-kit/vision/barcode-scanning/android)
- Input: smallest module ≥2 px (≥2 px tall for 2D); EAN-13 needs ≥190 px width; typical apps use 1280x720 or 1920x1080; stay ≤ ~2 MP for real time. — [ML Kit Barcode Scanning, Android](https://developers.google.com/ml-kit/vision/barcode-scanning/android)
- Third-party benchmark: ML Kit's default barcode scanner averaged 13.07 ms on an iPhone 13 (vendor-run, iOS, barcode not text). — [Dynamsoft deterministic benchmark](https://www.dynamsoft.com/blog/insights/deterministic-benchmark-barcode-sdk-ios/) (competitor-published; caution)

### Inferences
- Pipeline: barcode scan every analysis frame (formats limited to CODE_128, EAN_13, EAN_8, QR_CODE) → for each decoded barcode not yet in the mapping table and not locked, schedule at most one OCR call per barcode per K frames (or per ~200–300 ms), on the upright crop around that barcode, on a separate single-thread executor so barcode cadence is not blocked → stop OCR once the mapping locks. This bounds OCR to the "learning" phase of each new SKU.
- Reuse the existing `OcrEngine` crop path: extend `region()` to include the model-line area and return all Lines with geometry instead of only the GS1 number, so one OCR call serves both the "read printed digits" fallback and the model-code learning.
- If the text recognizer and barcode scanner are both bundled, the APK grows ~4 MB + ~2.4 MB per ABI (already the case in this repo).

### Gaps
- No measured cost of running ML Kit text recognition alongside barcode scanning per frame on Android; must be measured with the repo's `OcrBenchmarkTest`.
