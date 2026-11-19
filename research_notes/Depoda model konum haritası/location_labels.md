# Location labelling for a small shoe stock room (QR location labels read by a phone camera at 0.5-1.5 m)

Scope: how to design, size, place and print row/column location labels for floor-stacked shoe-box columns along a corridor, read by an Android phone with Google ML Kit barcode scanning while sweeping. Research done 2026-11-16. Most sizing and placement sources are vendor or commercial blogs, not standards bodies; flagged where relevant.

## 1. Location code schemes and granularity when stock moves often

### Takeaway
The industry hierarchy is Zone-Aisle-Bay-Level-Position, with fixed-width, zero-padded numeric fields, dashes as separators, and letters reserved for zones. Floor/bulk storage commonly drops the level and position fields and addresses only zone + lane/row. For a stock room where box stacks are reshuffled often, a column-level address (e.g. row + column, no level/position) is the granularity that matches practice for floor stacking.

### Cited Findings
- Standard hierarchy: Zone-Aisle-Bay-Level-Position, e.g. `D-07-23-3-2`; addresses encode the physical spot, not the product, so the address does not change when SKUs move — [Camcode: location numbering system](https://www.camcode.com/blog/how-to-create-a-foolproof-warehouse-location-numbering-system)
- Field rules: pad numerics with leading zeros and keep each field the same width everywhere so codes sort and parse consistently; two-digit aisle and bay; one-digit level; one-digit position for pallet rack, two for shelving/bins; leave gaps in sequences so new racks can be added without renumbering — [Camcode](https://www.camcode.com/blog/how-to-create-a-foolproof-warehouse-location-numbering-system)
- Separators: dash or period; Camcode says dashes read fastest on a label, and separators should stay on the printed label even if the system strips them — [Camcode](https://www.camcode.com/blog/how-to-create-a-foolproof-warehouse-location-numbering-system)
- Avoid letters O and I in letter fields (confused with 0 and 1); use numerals for aisle/bay/level/position, reserve letters for zones, ideally five or fewer — [Camcode](https://www.camcode.com/blog/how-to-create-a-foolproof-warehouse-location-numbering-system)
- Floor/bulk locations can drop level and position fields (addressed as zone + lane number only); mark floor lanes with floor labels at the head of each lane; give staging/returns their own zone codes — [Camcode](https://www.camcode.com/blog/how-to-create-a-foolproof-warehouse-location-numbering-system)
- Example bulk-floor code `ZB-F03-X05` (zone bulk, floor row 3, position 5), and suggestion to use `L00` for floor level — [Cleverence: rack numbering system](https://www.cleverence.com/amp/articles/for-business/warehouse-rack-numbering-system-4827/) (via search summary)
- Levels numbered bottom to top (floor = 01); positions numbered left to right as you face the section; back-to-back faces need a side element (L/R) so each face is unique; serpentine numbering lets a walker move through aisles in a continuous loop — [Camcode: how to label a warehouse rack](https://camcode.com/blog/how-to-label-a-warehouse-rack/); [Cleverence: aisle numbering](https://www.cleverence.com/articles/for-business/warehouse-aisle-numbering-4827/); [MMH: numbering schemes](https://www.mmh.com/article/other_voices_tips_for_effective_warehouse_numbering_schemes/Best_Practices)
- Some guides recommend not starting at 01 near the dock (e.g. start at 10/11) to leave room for expansion; others use odd/even bays on opposite aisle sides — [MMH: location numbering](https://www.mhlnews.com/archive/location-numbering-your-warehouse); [MMH numbering schemes](https://www.mmh.com/article/other_voices_tips_for_effective_warehouse_numbering_schemes/Best_Practices)
- Verification step: load the full address list into the system before applying labels, then scan every label; a code that scans but does not resolve means a label in the wrong place — [Camcode](https://www.camcode.com/blog/how-to-create-a-foolproof-warehouse-location-numbering-system)
- Range-based generation (generate bin labels from ranges, not hand-typed lists) is a recommended way to avoid typos — [Cleverence label blog: bin location labels from ranges](https://www.cleverence.com/label/blog/bin-location-labels-from-ranges-not-a-list)

### Inferences
- For a corridor of floor-stacked shoe-box columns that get reshuffled, the stable physical unit is the column footprint on the floor (row/side + column number), not the box height. A code like `LOC:A-03` (side/row A, column 03) or `LOC:R1-C03` mirrors the "bulk lane = zone + lane" practice; level within the stack is better derived by the app (vision/order of reads) than printed.
- Two sides of the corridor should get distinct letters (like the back-to-back L/R advice), and columns should be numbered in walking order (serpentine-like) so a single sweep reads them in increasing order, which also lets the app sanity-check sequence (e.g. A-03 must come between A-02 and A-04).
- Leave numbering gaps (e.g. columns 01..20 printed, spare 21..30 kept in the PDF) because adding a column later should not force renumbering.

### Gaps
- No source specific to small retail (shoe) stock rooms was found; all guidance is warehouse/WMS-vendor guidance adapted down.
- No GS1 location-labelling guideline was fetched in this session (GS1 GLN is for legal/physical locations at organisation level, not shelf columns; not verified here).

## 2. QR vs Code 128 vs Data Matrix; error correction; distinguishing location codes from product barcodes

### Takeaway
For phone-camera reading of short location codes, a regular (not Micro) QR Code at error level M or Q, in alphanumeric mode, with a fixed prefix such as `LOC:` is the pragmatic choice. Data Matrix in ML Kit has a center-of-image restriction (only one per frame), which makes it a poor fit for sweeping; Code 128 is the classic warehouse location symbology but is 1D and gets long for distance reading. A text prefix is the established WMS way to classify location scans versus product scans.

### Cited Findings
- ML Kit supports Code 128, Code 39, Code 93, Codabar, EAN-13, EAN-8, ITF, UPC-A, UPC-E, QR Code, PDF417, Aztec and Data Matrix (Micro QR is not in the list) — [Google ML Kit barcode scanning (Android)](https://developers.google.com/ml-kit/vision/barcode-scanning/android)
- ML Kit: Data Matrix codes must intersect the center point of the image, so only one Data Matrix code can be recognized per image — [ML Kit Android docs](https://developers.google.com/ml-kit/vision/barcode-scanning/android)
- ML Kit: restricting detection to specific formats (`setBarcodeFormats`) speeds up the detector — [ML Kit Android docs](https://developers.google.com/ml-kit/vision/barcode-scanning/android)
- Code 128 is described as a safe, compact choice for 6-12 character location codes on paper shelf labels; QR is more widely supported by phone camera apps; Data Matrix suits very small/damage-prone parts but "often requires a specialized scanning app" on phones — [Cleverence: barcodes vs QR in warehousing](https://www.cleverence.com/articles/business-blogs/barcode-vs-qr-warehouse-4729/); [Cleverence: barcodes for inventory](https://www.cleverence.com/articles/for-business/barcodes-for-inventory-4827/) (vendor blogs)
- Cited accuracy figures (Ohio University study as quoted by Camcode): manual keying ~1 error per 300 keystrokes; Code 128 ~1 misread per 2.8 million scans; UPC ~1 per 394,000; Data Matrix ~1 per 10.5 million — [Camcode: barcode scanning distance](https://www.camcode.com/blog/bar-code-scanning-distance/) (secondary citation, original study not checked)
- QR error correction levels recover roughly 7% (L), 15% (M), 25% (Q), 30% (H) of codewords; higher levels reduce capacity, which on a small label forces a bigger version/denser code; M or Q is typical for small labels, Q for industrial/handled labels — [QR error correction levels explained](https://qrs-dda.pages.dev/blog/error-correction-levels-l-m-q-h-explained); [Seagull Scientific help](https://help.seagullscientific.com/2016/en/Content/ErrorCorrection_QRCode.html) (commercial/secondary sources)
- Denso Wave: Level M is the "standard" level; Version 2 at Level M holds 38 alphanumeric characters; Version 1 = 21x21 modules, Version 3 = 29x29 modules (each version adds 4 modules per side) — [qrcode.com: how to create](https://www.qrcode.com/en/howto/code.html); [qrcode.com: versions](https://www.qrcode.com/en/about/version.html)
- WMS prefix practice: Infor location barcodes use a fixed prefix (`LL:` + company + location, Code128A), with a separate `BL:` prefix for bins; Oracle WMS classifies scans by "Prefix Text" in preprocessing rules; IBM instead binds each scan field to a barcode type "Location" — [Infor: label field prefixing](https://docs.infor.com/mscm/2024.x/en-us/mscmolh/mscmag/xup1537939885013.html); [Infor: barcode format definitions](https://docs.infor.com/ifsm/2026.x/en-us/ifsmolh/ifsmmscmag/fsk1727429600924.html); [Oracle WMS barcode preprocessing rules](https://docs.oracle.com/en/cloud/saas/warehouse-management/26b/owmol/barcode-preprocessing-rules.html); [IBM: defining barcode types](https://www.ibm.com/docs/en/SSGTJF/configuration/c_WMS_DefiningBarCodeTypes.html)
- Retail product barcodes (EAN/UPC) are numeric: country/company prefix, product number, check digit, with no location prefix — [Oracle WMS multi-field barcodes](https://docs.oracle.com/en/cloud/saas/warehouse-management/24c/owmld/working-with-multi-field-barcode-classes-application-identifiers.html) (via search summary)

### Inferences
- Use QR for location labels and keep product reading on whatever the box labels already carry (EAN-13 / Code 128 / OCR). Because product barcodes are purely numeric, a location payload starting with letters and a colon (`LOC:A-03`) can never collide with an EAN/UPC, and the app can reject any QR whose text does not match a strict regex like `^LOC:[A-HJ-NP-Z]-\d{2}$`. Adding a version/site tag (e.g. `LOC1:` or `SS:LOC:`) leaves room for future schema changes.
- Keep the payload in QR alphanumeric mode: per ISO/IEC 18004 the alphanumeric set is 0-9, A-Z (uppercase only), space and `$ % * + - . / :`, so `LOC:A-03` (8 chars) encodes compactly. Lowercase letters or `_` would force byte mode and a larger code. (Character set from general knowledge of the standard; not re-fetched this session.)
- With 8-10 alphanumeric characters, a Version 1 (21x21) QR fits even at a high level (standard Version 1 capacities are 25/20/16/10 alphanumeric chars for L/M/Q/H; from the ISO table, not verified in this session). Version 1 = largest modules for a given printed size, which is what matters at 1.5 m. So choose the highest EC level that still fits Version 1 (Q, or H if the payload is <=10 chars), and pin `version=1` so the generator cannot silently grow it.
- Avoid Data Matrix for sweeping (center-only restriction) and avoid Micro QR (not supported by ML Kit). Code 128 for an 8-character code at a readable bar width becomes a long strip; it is also harder to read when the phone is tilted. A human-readable text line under the QR (big font, e.g. "A-03") is standard practice and doubles as an OCR/visual fallback.

### Gaps
- No independent benchmark found comparing ML Kit read rates of QR vs Code 128 vs Data Matrix at 0.5-1.5 m.
- No official ISO/IEC 18004 capacity table fetched this session; capacities above are from the standard as commonly reproduced.

## 3. Printed size, module size and quiet zone for 0.5-1.5 m with a phone camera

### Takeaway
ML Kit's hard floor is 2 camera pixels per module; QR needs a 4-module quiet zone. The widely repeated rule of thumb is code width ≈ scan distance / 10 (so ~15 cm at 1.5 m, ~5 cm at 0.5 m), a commercial heuristic for front-on scanning in good light. Pixel math for a 1080p analysis stream suggests a Version 1 QR of roughly 7-10 cm is near the theoretical limit at 1.5 m, so 10-15 cm codes (or ML Kit zoom) give a safety margin for motion blur.

### Cited Findings
- ML Kit input guidelines: the smallest meaningful unit should be at least 2 pixels wide, and for 2D codes 2 pixels tall; denser formats need more pixels (EAN-13 at least ~190 px wide; PDF417 example ~1156 px wide) — [ML Kit Android docs](https://developers.google.com/ml-kit/vision/barcode-scanning/android)
- ML Kit: for typical apps use higher resolution such as 1280x720 or 1920x1080, which lets barcodes be scanned from farther away; lower resolution only if the barcode fills most of the image; poor focus reduces accuracy — [ML Kit Android docs](https://developers.google.com/ml-kit/vision/barcode-scanning/android)
- ML Kit zoom suggestion / auto-zoom (bundled 17.2.0+, unbundled 18.3.0+): when no barcode can be decoded because they are too far away, the app is notified with a recommended zoom ratio; set `setMaxSupportedZoomRatio()` to the camera's real maximum; `enableAllPotentialBarcodes()` returns undecodable candidate barcodes to guide zooming — [ML Kit Android docs](https://developers.google.com/ml-kit/vision/barcode-scanning/android)
- QR requires a four-module-wide quiet zone on all sides with nothing printed in it; Denso's example: 400 dpi printer with 4 dots/module = 0.254 mm/module — [qrcode.com: how to create](https://www.qrcode.com/en/howto/code.html)
- 10:1 rule (code width ≈ distance/10) is a widely repeated rule of thumb for a well-lit, black-and-white code scanned front-on; not a formal standard; some sources say add 25-50% for glare/moving viewers; minimums of ~2-2.5 cm at 20-30 cm — [Uniqode: sizing QR codes](https://www.uniqode.com/blog/qr-code-best-practices/how-to-perfectly-size-your-qr-codes); [Scanova: scanning distance](https://scanova.io/blog/qr-code-scanning-distance/); [QRStuff: what size should a printed QR be](https://www.qrstuff.com/blog/?p=69); [Linkbreakers size benchmarks](https://linkbreakers.com/help/article/qr-code-size-and-print-dimension-benchmarks)
- Module size in pixels (not "distance") is the real limiting metric for an SDK; a smartphone's ability to decode small codes is mainly driven by camera resolution, autofocus and exposure — [Dynamsoft: distance vs module size](https://www.dynamsoft.com/codepool/ios-barcode-scanner-distance-measure.html); [Scandit: tiny barcodes](https://www.scandit.com/blog/scanning-tiny-barcodes/)
- Scandit reports reading 8 common barcodes from an average 77 cm in a calibrated test and up to 240 cm for SDK 5.0 (vendor marketing figures) — [Scandit SDK 5.0](https://www.scandit.com/scandit-barcode-scandit-barcode-scanner-sdk-5-0-introducing-next-generation-barcode-scanning/)
- Label size should be driven mainly by scanning distance and how much information is printed; farther labels should be larger — [Camcode: scanning distance](https://www.camcode.com/blog/bar-code-scanning-distance/)

### Inferences (own calculation, to be verified by printing test labels)
- Assume a typical main camera ~69° horizontal FOV (26 mm-equivalent lens). Field width at distance d ≈ 1.39 × d: ~0.7 m at 0.5 m, ~2.1 m at 1.5 m.
- At 1920 px analysis width and 1.5 m: ~0.92 px/mm, so ML Kit's 2 px/module floor needs modules ≥ ~2.2 mm. A Version 1 QR (21 modules) is then ≥ ~46 mm, ~63 mm including the 4-module quiet zone. At 1280 px width the floor becomes ~3.3 mm/module (~69 mm code). If the analysis stream is 640x480 (a common low default for analysis use cases), the floor is ~6.5 mm/module (~137 mm code).
- The 2 px floor is for a sharp, frontal image; for sweeping (motion blur, tilt, rolling shutter) plan 3-4 px/module. That gives ~3.3-4.4 mm modules at 1080p and 1.5 m, i.e. a Version 1 code of ~70-90 mm (plus quiet zone). This agrees reasonably with the 10:1 rule (150 mm at 1.5 m) as a conservative upper bound.
- Practical recommendation: Version 1 QR printed at ~90-100 mm side (≈4.5 mm modules) with an ~18 mm quiet zone fits within an A4 quarter or a 105x148.5 mm (A6, 4-per-A4) label and should read across 0.5-1.5 m at 1080p; labels meant only for ≤0.8 m can be ~50-60 mm. The app's existing ML Kit auto-zoom further extends range.
- Use the highest analysis resolution the pipeline tolerates (ML Kit recommends 1280x720 or 1920x1080) and restrict formats to QR (+ product formats) for speed.

### Gaps
- No primary/official pixels-per-module guidance for QR specifically beyond ML Kit's general 2 px rule.
- No measured data for ML Kit read range vs printed module size at sweeping speed; the user's own test (print 3 sizes, sweep at 0.5/1.0/1.5 m) is the only reliable answer.
- The QRStuff distance/size table could not be fetched (only index page retrieved).

## 4. Placement: floor vs shelf-edge vs hanging vs wall; stacked floor columns; angle and glare

### Takeaway
For floor/bulk storage the standard practice is floor labels at the head of each lane, usually paired with overhead or wall/upright signs because floor labels get covered, scuffed and are read at short range. For shoe-box columns stacked on the floor, a label on the wall or a post behind/above each column (at a height not hidden by the stack) plus an optional floor label at the column front matches this practice. Matte surfaces and avoiding head-on lighting reflection reduce glare failures.

### Cited Findings
- Floor labels: durable floor-mounted labels for pallet positions, bulk locations or staging; suited where many positions sit on the floor rather than in racks; durability against foot traffic, trucks and pallets is the main design concern — [Racklify: floor location label vs rack label](https://racklify.com/encyclopedia/floor-location-label-vs-rack-label-choosing-the-right-warehouse-marking/); [Racklify: installing floor labels](https://racklify.com/encyclopedia/how-to-install-and-maintain-floor-location-labels-for-pallet-positions/)
- Bulk floor markings should be paired with overhead barcode signs for tracking; overhead signs recommended for bulk inventory — [MMH: bulk location warehouse signs](https://www.mmh.com/article/other_voices_understanding_your_options_for_bulk_location_warehouse_signs); [MMH: why mark warehouse floors](https://www.mmh.com/article/why_you_should_mark_your_warehouse_floors/news)
- Camcode: floor and pallet labels usually work with short-range scanners (~5-8 ft listed for floor labels); hanging signs and high rack labels need long-range scanning (vendor figures) — [Camcode floor labels](https://www.camcode.com/warehouse-floor-labels.html); [Camcode warehouse labels sheet](https://camcode.com/wp-content/uploads/2026/04/Warehouse-Labels-2017-Letter.pdf)
- Rack practice: beam labels on the front of the beam for levels 1-2; multi-level totem label on the upright for higher levels; bin labels on the shelf lip; aisle signs at both ends of every aisle; hanging labels for overhead positions — [Camcode](https://www.camcode.com/blog/how-to-create-a-foolproof-warehouse-location-numbering-system)
- Glare: smooth glossy surfaces reflect illumination directionally back to the camera and can saturate an imager; a reader held perpendicular gets specular reflection causing misreads; matte surfaces diffuse light and read better — [Keyence: troubleshooting read errors](https://www.keyence.com/ss/products/auto_id/codereader/code_verification/faq-read-error.jsp); [TechNetExperts: angular scan glossy barcodes](https://www.technetexperts.com/angular-scan-glossy-barcodes/); [Avery UK: matt vs gloss labels](https://www.avery.co.uk/blog/tips/matt-labels-or-gloss-labels)
- Sources conflict on layering (gloss over matte vs matte over gloss) — testing advised — [search summary of PakFactory / Smith Corona / mobile-inventory blog](https://pakfactory.com/blog/matte-vs-gloss-lamination/)
- UPM Raflatac launched an "extra-matte" label face specifically for reliable barcode scanning — [Labels & Labeling](https://www.labelsandlabeling.com/news/new-products/upm-raflatac-adds-extra-matte-label-face-reliable-barcode-scanning)

### Inferences
- In a corridor where boxes are stacked in floor columns, a floor label in front of a column is hidden as soon as a box is placed there or a person stands on it, and is viewed at a steep angle by a phone held at chest height. The more robust primary marker is a wall/post/shelf-back label at about 1.0-1.6 m height (above or beside the top of typical stacks, or at a fixed height on the wall behind each column), facing the corridor so the camera sees it near-frontally while sweeping.
- If columns stand away from walls, a label on the column's divider/upright or a small hanging tag at eye level fills the same role. Floor labels can be a secondary/backup marker for re-setting columns after reshuffles.
- Place each location label at a consistent height and consistent position relative to its column (e.g. top-left corner) so the app can associate boxes with the nearest label in the frame; when two labels are visible at once, consistent geometry lets the app pick "the label above/left of this stack".
- Tilt the phone slightly off-perpendicular (or mount labels slightly angled) if overhead lights reflect; matte laminate or no laminate at all is safer than glossy pouch lamination.

### Gaps
- No source specifically on shoe-box column stacking or small retail back rooms.
- No quantitative data on maximum readable tilt angle for ML Kit QR decoding.

## 5. Durability, materials and cost

### Takeaway
Warehouses use polyester or floor-grade labels; for a small indoor stock room, paper labels from an office laser printer are adequate on walls/posts, and floor labels need a protective cover (laminate or clear floor tape). Matte protection is preferred over glossy for cameras.

### Cited Findings
- Material choices: polyester labels for ambient environments; cold-storage labels for freezers; retroreflective labels for long-range scanning; hanging labels for overhead positions — [Camcode](https://www.camcode.com/blog/how-to-create-a-foolproof-warehouse-location-numbering-system)
- Floor labels must resist abrasion from foot traffic, lift trucks and pallets; vendors stress abrasion resistance and installation ease — [WarehouseIQ: racking labels](https://Store.warehouseiq.com/blogs/warehouse-solutions/warehouse-racking-labels-where-to-buy-how-to-use); [ID Label: common warehouse label types (PDF)](https://www.idlabelinc.com/hubfs/48722289/Common-Types-of-WH-Labels.pdf?hsLang=en)
- Camcode's cost framing for a small barcode system: roughly $1,000-$10,000 total; industrial label printer ~$300-$3,000 (not needed for office-printer approach) — [Camcode: scanning distance](https://www.camcode.com/blog/bar-code-scanning-distance/)
- Turkish availability and price: Tanex TW-2037 A4 laser label sheet, 70 x 37.125 mm, 24 labels per sheet, 100 sheets, listed at ~374 TL on n11 (date of price not shown); Tanex TW-2736 70x36 mm 100 sheets ~409-470 TL on Idefix — [n11 Tanex](https://www.n11.com/arama?m=Tanex); [Idefix Tanex](https://www.idefix.com/marka/tanex-20039695); [Akakçe Tanex etiket](https://www.akakce.com/etiket/tanex.html)

### Inferences
- A 70x37 mm cell (Tanex TW-2037, 24-up) is too short for a ~90 mm code; for 1-1.5 m reading use larger formats: full-sheet A4 sticker cut to size, 2-up (210x148.5), 4-up (105x148.5, A6) or 8-up (105x74 mm, fits a ~60 mm code) sheets — common Tanex/Avery-style formats in Turkey (check exact Tanex model numbers before buying; not verified here).
- At ~374 TL per 100 sheets, a 4-up A4 label costs ~0.9 TL each; even 100 locations cost well under 100 TL in sheets. Cold laminating with matte self-adhesive laminate or covering with clear matte packing tape adds durability for floor/handled labels.
- Plain black on white, printed at the printer's native resolution (600 dpi laser), no toner-saving mode; laser toner is more smudge/water resistant than inkjet.

### Gaps
- No verified source for Turkish-market matte laminating film or floor-marking tape prices.
- Exact Tanex 4-up/8-up model numbers and prices not retrieved.

## 6. Printing tools: segno, qrcode, reportlab, label-sheet generators

### Takeaway
Python `segno` (pure Python, supports SVG/PDF with float scale and explicit border) plus `reportlab` or `pylabels` for sheet layout is a low-effort, fully offline way to print A4 location sheets. Force regular QR (`micro=False`) because segno may otherwise emit Micro QR, which ML Kit cannot read.

### Cited Findings
- `segno.make()`: `error` default None → Level L (with `boost_error=True` default, `error` is a minimum and the level may rise without changing version); `micro` default None means a Micro QR "may be generated if applicable", `micro=False` forces regular QR; `version` default picks smallest; `mode` auto (alphanumeric supported for QR 1-40 and Micro M2-M4) — [segno API docs](https://segno.readthedocs.io/en/latest/api.html)
- `QRCode.save()`: `scale` = module size (pixels for PNG; float allowed for SVG/PDF); `border` = quiet zone in modules, default 4 for QR and 2 for Micro; SVG accepts `unit='mm'`; PNG accepts `dpi` metadata — [segno API docs](https://segno.readthedocs.io/en/latest/api.html)
- `pylabels` / `pylabels2`: Python library that uses ReportLab to produce PDFs of label sheets; user defines a `Specification` (sheet size, columns, rows, label size, margins, corner radius) and a drawing callback; README demo prints on Avery 5160 — [pylabels2 on PyPI](https://pypi.org/project/pylabels2); [pylabels on PyPI](https://pypi.org/project/pylabels)
- `qr-label-generator` (Rust, mioCry/QRLabel_Generator) generates warehouse labels as A4 PDFs with layouts like 3x4, 2x5, 4x10 and shelf-style codes — [docs.rs qr-label-generator](https://docs.rs/crate/qr-label-generator/latest)
- Odoo module `stock_location_qr_print` prints warehouse location QR labels with Avery templates or custom dimensions (paid add-on, Odoo only) — [Odoo apps](https://apps.odoo.com/apps/modules/19.0/stock_location_qr_print)
- Free Google Workspace add-on "Free Bulk Barcode and QR Code Generator" prints from CSV on chosen Avery template — [Google Workspace Marketplace](https://workspace.google.com/marketplace/app/free_bulk_barcode_and_qr_code_generator/101625109020)
- Shelf.nu documents printing its QR codes on Avery sheets — [Shelf knowledge base](https://www.shelf.nu/knowledge-base/using-shelf-qr-codes-with-avery-label-sheets)

### Inferences
- Minimal script plan: for each location in a range (e.g. sides A/B × columns 01-30), `segno.make(f"LOC:{side}-{col:02d}", error='q', version=1, micro=False, mode='alphanumeric')`, draw into a ReportLab canvas at exact mm size (e.g. 90 mm code = 21 modules × ~4.29 mm, plus 4-module border), with large human-readable text "A-03" (≥ 20 mm tall) and an arrow showing which column it belongs to; layout 4-up or 2-up on A4 to match the bought sheets. Print at 100% scale ("Actual size", no "fit to page") and measure one printed code with a ruler.
- Generate from ranges in code (not hand-typed lists) and include a one-page test sheet with three sizes (50, 75, 100 mm) to calibrate on the actual phone before printing all labels.
- ZPL/Labelary is only relevant if a Zebra-type thermal printer is used; for an office laser printer it adds nothing.

### Gaps
- Did not find a widely used GitHub tool specifically for "location label sheets for floor stacks"; existing tools are WMS add-ons or generic label-sheet generators.
- Labelary/ZPL documentation was not reviewed (out of scope for office printers).
