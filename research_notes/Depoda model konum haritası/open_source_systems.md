# Open-source inventory/location systems and camera-sweep shelf mapping (for SweepScan)

Context: one shoe-store stock room, ~500-600 boxes/week, products are model+colour+size variants, identical boxes share one barcode. Question: adopt an existing system, or build location mapping into SweepScan (Android).

Activity data (stars, licence, archive flag, last push, latest release) was pulled from the GitHub REST API (`gh api repos/...`) on 2026-11-16. Star counts change daily; treat them as approximate.

## 1. Existing inventory/warehouse systems: licence, activity, location model, QR/scan-into-location, variants, self-hosting

### Takeaway
InvenTree (MIT, very active, has an official Android app with "Scan Items Into Location") and ERPNext (GPL-3.0, warehouse tree down to shelf/bin level plus true attribute variants such as size × colour with automatic combination generation) are the only two that cover both location trees and variants well. HomeBox is easy to host but is a home-inventory tool (no stock variants). Odoo's built-in Barcode app is Enterprise-only. Snipe-IT and Grocy do not fit a shoe-box stock room. None of them understands "N identical boxes with one barcode at different shelf positions" without a per-box label or quantity-per-location bookkeeping.

### Cited Findings

**Activity / licence table (GitHub API, 2026-11-16)**

| Project | Repo | Licence (GitHub SPDX) | Stars | Archived | Latest release |
|---|---|---|---|---|---|
| InvenTree | inventree/InvenTree | MIT | ~7,870 | no | 1.7.2 (2026-11-12) |
| InvenTree Android/iOS app | inventree/inventree-app | MIT | ~143 | no | 1.1.0 (2026-11-16) |
| HomeBox (maintained fork) | sysadminsmedia/homebox | AGPL-3.0 | ~7,890 | no | v0.27.1 (2026-10-23) |
| HomeBox (original) | hay-kot/homebox | AGPL-3.0 | ~3,000 | **yes (archived)** | v0.10.3 (2024-01-04), last push 2024-06-11 |
| Odoo | odoo/odoo | NOASSERTION on GitHub (Community is LGPL-3 per Odoo; not verified here) | ~55,800 | no | no GitHub releases (versioned branches) |
| ERPNext | frappe/erpnext | GPL-3.0 | ~41,350 | no | v16.55.0 (2026-11-12) |
| OpenBoxes | openboxes/openboxes | EPL-1.0 | ~930 | no | v0.9.9 (2026-10-23) |
| Snipe-IT | grokability/snipe-it | AGPL-3.0 | ~15,190 | no | v8.8.3 (2026-10-22) |
| Grocy | grocy/grocy | MIT | ~9,690 | no | v4.7.1 (2026-09-04) |
| Part-DB | Part-DB/Part-DB-server | AGPL-3.0 | ~1,850 | no | v2.20.6 (2026-11-14) |
| Shelf.nu | Shelf-nu/shelf.nu | NOASSERTION (custom) | ~3,060 | no | shelf@2.5.3 (2026-11-13) |
| PartKeepr | partkeepr/PartKeepr | GPL-3.0 | ~1,530 | **yes (archived)** | 1.4.0 (2018) |
| "ezinventory" | no canonical project found; matches are tiny repos (e.g. hpapajani/EzInventory, MIT, 2 stars, last push 2023-02; andude2/EZInventory, no licence, 3 stars) | — | — | — | — |

Sources: [InvenTree](https://github.com/inventree/InvenTree), [inventree-app](https://github.com/inventree/inventree-app), [sysadminsmedia/homebox](https://github.com/sysadminsmedia/homebox), [hay-kot/homebox](https://github.com/hay-kot/homebox), [odoo/odoo](https://github.com/odoo/odoo), [frappe/erpnext](https://github.com/frappe/erpnext), [openboxes](https://github.com/openboxes/openboxes), [snipe-it](https://github.com/grokability/snipe-it), [grocy](https://github.com/grocy/grocy), [Part-DB](https://github.com/Part-DB/Part-DB-server), [shelf.nu](https://github.com/Shelf-nu/shelf.nu), [PartKeepr](https://github.com/partkeepr/PartKeepr), [hpapajani/EzInventory](https://github.com/hpapajani/EzInventory), [andude2/EZInventory](https://github.com/andude2/EZInventory).

**InvenTree**
- Locations are a tree: "Locations are arranged in a cascading manner and each location may contain multiple sub-locations, or stock, or both"; stock items link to a location. — [InvenTree docs: Stock](https://docs.inventree.org/en/latest/stock/)
- "Structural" locations only organise the hierarchy (e.g. a shelf that contains sub-locations) and cannot hold stock directly; "location types" set icons and may later carry drawer dimensions. — [InvenTree docs: Stock](https://docs.inventree.org/en/latest/stock/)
- The mobile app has "Scan Items Into Location" on a location page (scan stock items, which must already exist in the database, to move them there) and the reverse "Scan Into Location" on a stock-item page. — [InvenTree app barcode docs](https://docs.inventree.org/en/stable/app/barcode/)
- "Assign Barcode" links any existing (e.g. third-party / manufacturer) barcode to a location, stock item or part; a barcode already in use cannot be reassigned this way. — [InvenTree app barcode docs](https://docs.inventree.org/en/stable/app/barcode/)
- The app takes camera input or keyboard-wedge scanners (Bluetooth or built-in), which must append Enter. — [InvenTree app barcode docs](https://docs.inventree.org/en/stable/app/barcode/)
- Variants: "template parts" with variants created one at a time via a Duplicate Part form; the docs mention colour as an example option, but describe no parameter-driven or automatic variant generation. Template stock totals include all variants. — [InvenTree docs: Templates](https://docs.inventree.org/en/stable/part/template/)

**HomeBox (sysadminsmedia fork)**
- Written in Go; "idle memory usage is less than 50MB for the whole container"; items are organised into categories, locations and tags with custom fields; Docker run/compose deployment. — [HomeBox README](https://github.com/sysadminsmedia/homebox)
- Open issues on its QR features: camera fails to initialise on mobile for QR scanning until switching front/back sensors (#1290), label generator QR codes failing on underpowered servers (#1312), long names breaking label layout (#1323), asset-ID duplication across collections (#1344), QR scan of a lost item when not signed in (#13). — [#1290](https://github.com/sysadminsmedia/homebox/issues/1290), [#1312](https://github.com/sysadminsmedia/homebox/issues/1312), [#1323](https://github.com/sysadminsmedia/homebox/issues/1323), [#1344](https://github.com/sysadminsmedia/homebox/issues/1344), [#13](https://github.com/sysadminsmedia/homebox/issues/13)
- The original hay-kot/homebox is archived (last push 2024-06-11). — [hay-kot/homebox](https://github.com/hay-kot/homebox)

**Odoo**
- The built-in Barcode app is described by secondary sources as Enterprise-only; Community users rely on third-party modules (e.g. "Do Stock Barcode" for Odoo 19 Community, "Barcode Warehouse Ops", which says Community "ships no barcode app"). I could not read the official editions table (checkmarks did not render); it lists "Barcode" as a sub-item of Inventory. — [Cleverence comparison](https://www.cleverence.com/articles/business-automation/warehouse15-vs-odoo-barcode-app/), [Do Stock Barcode (apps.odoo.com)](https://apps.odoo.com/apps/modules/19.0/do_stock_barcode), [Barcode Warehouse Ops](https://apps.odoo.com/apps/modules/19.0/barcode_warehouse_ops), [Odoo editions](https://www.odoo.com/page/editions)
- Location barcodes are assigned under Inventory configuration (Enterprise book). — [Cybrosys Odoo 19 Enterprise book: Barcode](https://www.cybrosys.com/odoo/odoo-books/v19/barcode/)
- Storage locations are enabled with Multi-Step Routes; putaway rules route by product, product category or package type, in priority order; storage categories cap a location's capacity. — [Odoo 18 docs: Putaway rules](https://www.odoo.com/documentation/18.0/applications/inventory_and_mrp/inventory/shipping_receiving/daily_operations/putaway.html)
- Odoo supports product variants with per-variant stock. — [Odoo inventory features](https://odoo.com/app/inventory-features)
- OCA's `stock_putaway_product` adds a fixed per-product location putaway strategy (community add-on). — [OCA stock-logistics-warehouse](https://github.com/OCA/stock-logistics-warehouse/blob/8b65a84f689d3bf12746c3a1652585f6075b4ff9/stock_putaway_product/README.rst)

**ERPNext / Frappe**
- Warehouses form a tree ("Is Group" warehouses hold sub-warehouses); the docs say "You can create a sub-Warehouse which could be a shelf", with an example hierarchy Warehouse > Room > Row > Shelf > Bin. There is no separate physical-bin object: every shelf/bin is a warehouse, and stock balance is kept per item × warehouse. — [ERPNext docs: Warehouse](https://docs.frappe.io/erpnext/user/manual/en/warehouse)
- Item variants: template item with "Has Variants" plus an attribute table; selecting e.g. 2 colours × 3 sizes auto-creates all 6 variants; template changes sync to variants; transactions are not allowed on the template. — [ERPNext docs: Item Variants](https://docs.frappe.io/erpnext/user/manual/en/item-variants)
- Putaway rules are per item × warehouse with capacity and priority ("Priority will be considered first. Followed by free space"), applied in Purchase Receipts and Stock Entries. — [ERPNext docs: Putaway Rule](https://docs.frappe.io/erpnext/user/manual/en/putaway-rule)

**OpenBoxes**
- Has a Putaway API (get putaway candidates for a location, create/update/split putaway items, mark COMPLETE). — [OpenBoxes Putaway API](https://openboxes.readthedocs.io/en/stable/api-guide/putaway/)

**Grocy**
- Barcode fields accept USB/BT scanners (recommended prefix char + TAB) or browser camera scanning via ZXing (requires HTTPS); external barcode lookup plugin defaults to Open Food Facts. — [Grocy README](https://github.com/grocy/grocy)

### Inferences
- For a single shoe stock room, ERPNext's data model fits best on paper (true size × colour attribute variants, shelf-level child warehouses), but it is a full ERP (accounting ledgers per warehouse) and heavy to self-host for one shop. InvenTree is lighter and has the best ready-made Android "scan location then scan items" flow, but its variants are hand-created one by one, which is painful for shoe size runs.
- Every system models a location as a node holding quantities of a product. Because identical boxes share one barcode, scanning a box tells the system only "one more unit of variant X is here"; it cannot say which physical box moved. That works for "where is model X size 42?" lookups as long as counts are kept, but stale locations build up if removals aren't scanned.
- Since SweepScan already reads labels and knows the product list (from DİA), the smallest build is: a location QR (shelf/row/column) plus an item→location table with timestamps inside SweepScan. That is less effort than syncing to a separate server. Adopting InvenTree or ERPNext mainly makes sense if the shop wants a server-side system of record.
- Grocy (food/household), Snipe-IT (serialised IT assets) and Part-DB (electronic parts) are poor fits for size-run footwear stock.

### Gaps
- Snipe-IT's location model and Grocy's location model were not checked in docs this session; only repo metadata was verified.
- OpenBoxes bin-location UI and mobile scanning were not found in docs.
- InvenTree's exact location QR label format, its bulk import/REST API details, and its self-host hardware needs were not fetched (docs.inventree.org stock-location subpage returned 404).
- HomeBox quick-start (homebox.software) returned 403; I did not confirm item quantities or CSV import from its docs.
- Odoo Barcode Enterprise-only status rests on third-party sources; the official editions table didn't render.
- ERPNext barcode-scan-into-warehouse UI and self-hosting resource needs were not checked.

## 2. Projects/papers that build item-to-position maps from camera sweeps or shelf images

### Takeaway
Open-source shelf-image work is mostly retail planogram compliance: detect products (often trained on SKU-110K), group detections into shelf rows (clustering, or line detection), then match them to slots (grid / Hungarian assignment). Code quality is mostly thesis or portfolio level. The SKU-110K dataset is restricted to academic, non-commercial use, so it can't legally back a commercial SweepScan feature.

### Cited Findings
- **SKU-110K (eg4000/SKU110K_CVPR19)**: ~851 stars, no licence file on GitHub, last push 2023-03; README says "The dataset is provided for the exclusive use by the recipient and solely for academic and non-commercial purposes." — [SKU110K_CVPR19](https://github.com/eg4000/SKU110K_CVPR19)
- **Media-Smart/SKU110K-DenseDet**: dense-scene detector for SKU-110K, Apache-2.0, ~113 stars, last push 2021. — [SKU110K-DenseDet](https://github.com/Media-Smart/SKU110K-DenseDet)
- **laitalaj/cvpce** (MIT, ~11 stars, 2021): computer-vision planogram compliance evaluation, the code for a University of Helsinki master's thesis; CUDA-only; uses SKU-110K for proposal generation, GP-180 for classification and planograms; pre-trained weights in releases. — [cvpce](https://github.com/laitalaj/cvpce), [thesis](http://urn.fi/URN:NBN:fi:hulib-202106092585)
- **Alijanloo/Retail-Shelf-Monitoring** (MIT, ~17 stars, last push 2025-11): CCTV keyframes → shelf detection → image alignment (ORB/SIFT + RANSAC homography) → YOLOv12-M (fine-tuned on SKU-110k) → SKU recognition (MobileNetV3 embeddings + FAISS) → **"Automated DBSCAN-based clustering of shelf items"** for grid generation → SORT tracking → temporal consensus for alerts. — [Retail-Shelf-Monitoring](https://github.com/Alijanloo/Retail-Shelf-Monitoring)
- **findsamirks-commits/Shelf-Vision** (no licence, ~1 star, 2026): YOLOv8 detections are matched to planogram slots by "spatial IoU + Hungarian match" (`linear_sum_assignment`); planograms are JSON with tier definitions and normalised boxes. — [Shelf-Vision](https://github.com/findsamirks-commits/Shelf-Vision)
- **Shelf Management (Expert Systems with Applications, 2024)**: detects products, finds shelf rows as semantic lines with a Deep Hough Transform (reported F1 97%), recognises products with MobileNetV3, then assigns products to rows/positions; introduces the SHARD (row) and SHAPE (product) datasets; code/datasets said to be on GitHub. Full text was not readable here (403); details come from the search summary and the open PDF. — [univpm PDF](https://iris.univpm.it/retrieve/82ff3f72-911d-492f-9726-bac1fe1b9c41/1-s2.0-S0957417424015021-main.pdf)
- A patent on tracking store slots uses shelf-tag barcodes and prices to match slots to planogram entries. Another smart-shelf patent checks for exactly one price tag between adjacent dividers. — [US 11126962](https://image-ppubs.uspto.gov/dirsearch-public/print/downloadPdf/11126962), [US 11423354](https://image-ppubs.uspto.gov/dirsearch-public/print/downloadPdf/11423354)
- Ultralytics has a YOLO26 planogram-compliance write-up (detection + gaps + shelf grouping), mirrored by small PoCs such as luis-codex/planogram-compliance (no licence). — [Ultralytics blog](https://www.ultralytics.com/blog/using-ultralytics-yolo26-for-planogram-compliance-detection), [luis-codex/planogram-compliance](https://github.com/luis-codex/planogram-compliance)

### Inferences
- The position-assignment recipes in the literature map directly onto shoe-box stacks: (a) cluster detection centres into rows/columns (DBSCAN on y then x, as Retail-Shelf-Monitoring does); (b) detect shelf lines/edges (Deep Hough) and assign boxes to the line above or below them; (c) anchor positions to shelf-edge tags (the patents). For SweepScan, option (c) is location QR stickers on shelf edges or uprights: decode them in the same frames as the box labels and assign each label to the nearest location QR in image space. This is cheaper and more robust than learning shelf geometry.
- Shoe boxes have readable end-labels (model/colour/size text + barcode), so the "SKU recognition" step that retail projects solve with embeddings is already solved by SweepScan's barcode+OCR pipeline. Only the geometry and row/column step is needed.
- Most repos are MIT and borrowable, but they are small and poorly maintained. Reuse the ideas (clustering, Hungarian matching against expected slots), not the code. Avoid SKU-110K-trained weights in a commercial product because of the dataset's licence.

### Gaps
- No open-source project was found that builds a map from a handheld phone sweep (as opposed to fixed CCTV or a single photo). Shelf-scanning robots (Simbe, Bossa Nova, Zebra) are proprietary, and I didn't find open code for them.
- The GitHub link and licence for the Shelf Management (ESWA 2024) code/datasets weren't verified.
- arXiv-specific planogram papers weren't retrieved in this session.

## 3. Small "where is my item" apps combining location QR + item barcode scanning, and lessons from their issues

### Takeaway
No popular standalone open-source "scan shelf QR, then scan items" put-away app was found. That flow lives inside InvenTree's app ("Scan Items Into Location"), HomeBox's location QR labels, and commercial or Odoo-module scanners. Issue trackers mostly show practical failures: camera initialisation, label rendering, ID collisions, and items that must exist before they can be scanned.

### Cited Findings
- InvenTree's put-away flow: open a location, "Scan Items Into Location", scan items, and each must already exist in the database. Unknown barcodes can only be linked with "Assign Barcode". — [InvenTree app barcode docs](https://docs.inventree.org/en/stable/app/barcode/)
- The common commercial pattern: scan a product, the app proposes a default location, scan a location barcode to override. Some handhelds block scanning an item into a bin not meant for it; others scan the container once, then the destination shelf. — summarised from search results incl. [Ventor putaway with scanner](https://ventor.tech/mobile/processing-putaway-using-a-barcode-scanner), [Cleverence receiving/put-away](https://www.cleverence.com/inventory/features/receiving-put-away/), [Odoo forum: putaway to location via barcode](https://www.odoo.com/hi_IN/forum/help-1/warehouse-putaway-to-location-using-barcode-scanner-71091)
- HomeBox QR issue examples: mobile camera initialisation failure (#1290), QR label generation failing on weak servers (#1312), long names breaking labels (#1323), duplicated asset IDs (#1344). — [HomeBox issues](https://github.com/sysadminsmedia/homebox/issues?q=QR)
- Grocy's maintainer recommends a USB laser scanner over the phone camera ("cheap and work... better, faster, under any lighting condition and from any angle"). — [Grocy README](https://github.com/grocy/grocy)
- GitHub repo searches for "where is my stuff qr location" and "stockroom shelf location barcode scanner app" returned no results. — GitHub search (gh CLI), 2026-11-16

### Inferences
- Stale locations: every system records a location only when someone scans a move. A sweep-based approach (re-observing the whole shelf) can refresh all positions at once and mark items not seen in their last-known location as "unconfirmed". That is a real advantage over put-away-only flows.
- Mis-scans with identical barcodes: since boxes sharing one barcode are interchangeable, store counts per (variant, location) with a last-seen time rather than per-box identity, unless SweepScan's re-identification is trusted.
- Labels: use short location codes (e.g. "R3-C5") in large QR to avoid HomeBox-style label layout and readability problems. Keep location IDs globally unique.

### Gaps
- I found no issue threads that explicitly discuss "stale location" or "mis-scan into wrong bin" in InvenTree/HomeBox, because the searches returned few relevant hits. These lessons are inferred, not documented.
