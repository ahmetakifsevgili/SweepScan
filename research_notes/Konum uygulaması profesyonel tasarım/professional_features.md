# Professional features for a small stock-room location app (SweepScan) — value vs effort

Context checked in the codebase (Nov 2026, branch v0.3): ~8.4k lines Kotlin. Room DB (`location/LocationDb.kt`) has `column_scan` (PK depot,row,col; scannedAt, boxes), `sighting` (per barcode per column, seenAt, firstOrder), `barcode_model` (barcode -> model/color/size, source, updatedAt). **A re-sweep currently DELETEs the column's sightings and its column_scan row before inserting the new snapshot** (`LocationDb.kt` lines ~77-97), so no history survives. `RouteFinder.kt` is Dijkstra from the entrance to one column over a corridor graph. `hid/HidKeyboard.kt` already makes the phone a Bluetooth HID keyboard. These facts drive several effort estimates below.

## 1. Setup / onboarding (layout wizards, floor plans, room scanning, rack builders, naming)

### Takeaway
Pro WMS products set up locations as a **hierarchy + naming template + bulk generator**, not by drawing. Phone room-scanning (RoomPlan-like) does not exist as an Android API. For three small rooms, a "rack builder" wizard (row count, columns, shelves, naming pattern, live preview, then print labels) beats AR/LiDAR or photo-tracing on value per effort.

### Cited Findings
- Zoho Inventory: each warehouse starts with a receiving and a packing zone; you add zones, and each zone can have up to **5 levels**, with the lowest level as the storage bin. Bins must be enabled before a warehouse has transactions and can't be disabled afterwards. Bins are on the Premium plan (2,000/warehouse) and Enterprise (5,000) — [Zoho community: Introducing Bin Locations](https://help.zoho.com/portal/en/community/topic/introducing-bin-locations?page=1)
- inFlow recommends "street-style" naming **A-06-C (Aisle-Shelf-Bin), separated by dashes** ("A-06-C is correct, A06C is not"). With that convention inFlow **auto-suggests an optimized picking route** — [inFlow blog: street-style routing](https://www.inflowinventory.com/blog/optimize-your-warehouse-fulfillment-process-with-street-style-routing)
- inFlow: locations should be buildings, sublocations the shelves/aisles/bins inside one building. Sublocations are edited one at a time (+/- buttons), with no bulk grid generator documented — [inFlow support: tracking aisle/bin numbers](https://www.inflowinventory.com/support/cloud/can-i-track-specific-locations-like-aisle-numbers-bin-numbers-etc)
- Coresense WMS has sub-location *templates*: "create a single aisle or multiple aisles in a single click", with shelf and bin locations created automatically — [Coresense: Warehouse Sub-Location Templates](https://help.coresense.com/7.4/Inventory/Warehouse_Management/Warehouse_Sub-Location_Templates.htm)
- Infor and Oracle document bulk "create multiple locations" tools from a pattern — [Infor TWL: Creating multiple locations](https://docs.infor.com/csdis/2026.x/en-us/useradminlib_csd_cloud/twlsag/yvl1597331355633.html); [Oracle WLOC](https://docs.oracle.com/cd/E62114_01/oroms/pdf/5/cws_help/WLOC.htm)
- Sortly (SMB-focused) models locations as **folders and subfolders** (e.g. a folder per warehouse location, subfolders per shelf), with access limits per folder — [Sortly business inventory app](https://sortly.com/business-inventory-app/)
- Odoo 18: storage locations are enabled through Multi-Step Routes; *storage categories* cap a location's capacity — [Odoo 18 docs: Putaway rules](https://www.odoo.com/documentation/18.0/applications/inventory_and_mrp/inventory/shipping_receiving/daily_operations/putaway.html)
- RoomPlan is a Swift API that needs the **LiDAR scanner** on iPhone/iPad — [Apple: RoomPlan](https://developer.apple.com/augmented-reality/roomplan). An open ARCore GitHub feature request says Android has **no high-level room-scanning API** returning walls/doors/windows — [arcore-android-sdk issue #1772](https://github.com/google-ar/arcore-android-sdk/issues/1772)
- ARCore Depth API has been GA since ARCore 1.18. It estimates depth from camera motion, most accurate at roughly 0.5–5 m; ToF sensors cut scanning time and improve plane detection, but few Android phones have them — [Google Developers Blog: ARCore Depth API](https://developers.googleblog.com/a-new-wave-of-ar-realism-with-the-arcore-depth-api/); [SimplyWise: which Android phones scan rooms](https://www.simplywise.com/blog/which-android-phones-scan-rooms/)
- magicplan's Android app adds rooms by non-scanning methods (square room, defined corners, importing a plan), because its auto-scan needs LiDAR on iOS — [magicplan help: scan a room (Android)](https://help.magicplan.app/scan-a-room-with-the-camera-of-your-mobile-device-android)

### Inferences
- **Rack builder wizard** (value high, effort ~3–5 days). Steps: depot -> rows (count, letters A..I) -> per row: columns, shelves (4), which side faces the corridor -> naming pattern with live preview (`D1-A-03`, shelf optional) -> optional measured lengths (row length, corridor width in metres) -> generate plan + label PDF. The current dialogs in `LocationActivity`/`SiteLayout` already hold the data, so this is mostly a guided flow over them. Portfolio value: it shows onboarding UX thinking. Risk: low.
- Naming scheme: follow inFlow's convention — fixed-width numbers (03 not 3), dashes, coarse to fine (Depot-Row-Column[-Shelf]). The QR payload should hold this canonical code so that renaming a row never breaks printed labels: store a stable internal ID and keep the display name separate.
- **Metric plan from measurements** (value medium, effort 2–3 days): enter row length and corridor widths, and the plan and route lengths become real metres ("about 14 m"). This makes route/ETA credible.
- **Photo/sketch import + trace** (value low-medium, effort 4–7 days: image as background layer, pinch-zoom, scale calibration with two points). Worth it only if the hand-drawn plan doesn't match reality.
- **AR/Depth room scanning**: value low for 3 known rooms, effort weeks, high risk (no Android API, device support varies). Skip. A short "AR-ready" note in a README or portfolio is enough.

### Gaps
- Didn't verify the exact UI of Logiwa, ShipHero or NetSuite WMS bin-creation screens (vendor docs behind logins/marketing). No usability study found comparing wizard-based and drawing-based setup.

## 2. Data trust (staleness, confidence, history, re-sweep diff, undo, conflicts, variance)

### Takeaway
Inventory records are wrong far more often than people assume, so the features that make data *trustworthy* are the most "professional" ones: age per location, a variance/diff screen after a re-count, approve-or-recount, and an audit trail. In SweepScan this needs one schema change first: keep snapshot history instead of deleting it.

### Cited Findings
- DeHoratius & Raman (Management Science 54(4), 2008): of ~370,000 inventory records in 37 stores of one retailer, **65% were inaccurate**. Auditing practices reduce inaccuracy; complexity makes it worse. 26.4% of the variance lies between product categories vs 2.7% between stores — [EconPapers record](https://econpapers.repec.org/RePEc:inm:ormnsc:v:54:y:2008:i:4:p:627-641); [Chicago Booth Review: First Measure, Then Manage](https://www.chicagobooth.edu/review/first-measure-then-manage)
- NetSuite WMS: counts sit in "Complete/Pending Approval"; a supervisor **approves (final) or rejects (sends back for recount)**, and the Inventory Adjustments sublist shows each positive/negative adjustment — [NetSuite help: Approving or Rejecting Inventory Counts](https://oracle.hydrogen.sagittarius.connect.product.adaptavist.com/en/cloud/saas/netsuite/ns-online-help/section_155993310131.html)
- Datascope WMS: a supervisor reviews quantity and value variances on a Cycle Count Approval screen before posting — [Datascope: Cycle Count Approval](https://datascopewms.atlassian.net/wiki/x/GwBVB); [Datascope: QTY and value variances](https://datascopewms.atlassian.net/wiki/x/AQDhEg)
- HotWax (retail OMS) has a "Pending review" counts screen to accept or reject counted vs system quantities — [HotWax docs: Review Counts](https://docs.hotwax.co/documents/retail-operations/inventory/cycle-count/pending-review-count)
- Oracle EBS has a long-standing approve / recount / reject flow for cycle count adjustments — [Oracle: Approving Cycle Count Adjustments](https://docs.oracle.com/cd/A60725_05/html/comnls/us/inv/ccadj01.htm)
- Vendor guidance (examples, not standards): variance over a threshold (e.g. >5% or >$50) triggers a blind recount by a second person; within tolerance it auto-approves. KPIs are accuracy %, variance rate and recount ratio, and one aggregate % can hide a chronically bad bin — [Cleverence: WMS cycle count](https://www.cleverence.com/amp/articles/business-blogs/wms-cycle-count-4072/); [Cleverence: how to cycle count](https://www.cleverence.com/articles/business-blogs/how-to-cycle-count-4827/)
- Common ABC cadence: A items monthly, B quarterly, C annually; some sources use weekly/monthly/quarterly — [TechTarget: advantages of cycle counting](https://www.techtarget.com/enterprise-software/answer/What-are-the-advantages-of-cycle-counting?amp=1); [Racklify: scheduling cycle counts](https://racklify.com/encyclopedia/scheduling-cycle-counts-abc-pareto-and-practical-cadences-for-busy-warehouses/)
- Sortly sells "who did what, and when" activity history plus an Activity Report of changes to items, folders and tags. Activity history is even in its free plan — [Sortly](https://sortly.com/business-inventory-app/); [Software Advice: Sortly Pro](https://www.softwareadvice.com/inventory-management/sortly-pro-profile/)
- Firestore offline sync: multiple offline changes to the same document resolve **last write wins** — [Firebase docs: Access data offline](https://firebase.google.com/docs/firestore/manage-data/enable-offline?hl=en)

### Inferences
- **Snapshot history (prerequisite, effort 1–2 days)**: add a `sweep` table (id, depot,row,col, startedAt, endedAt, boxes, deviceId/user, appVersion) and make `sighting` reference sweepId. Stop deleting. "Current" becomes the latest sweep per column (a view or query). This unlocks history, diff, undo and staleness. Room migration risk: low-medium (test with a migration test).
- **Re-sweep diff screen** (value very high, effort 2–3 days after history): after sweeping a column, show **New / Gone / Count changed / Moved-from (seen elsewhere last time)** against the previous sweep, then [Accept] [Re-sweep] [Discard]. This is the WMS "variance review + approve/recount" pattern adapted to one user. It is also the strongest portfolio screenshot. The "moved" check is cheap: join the gone barcodes in column X with the new barcodes in column Y within the same day.
- **Undo** (effort 0.5 day after history): "Undo last sweep" = mark the sweep discarded (soft delete). A Snackbar with Undo for 10 s after save, plus discard in the diff screen.
- **Staleness/age** (value high, effort 1 day): colour columns on the plan by age of last sweep (e.g. <3 days fresh, <14 days ageing, older stale, never = hatched). Show "seen 5 days ago in A-03" in search results. Shoe stock turns fast, so the age thresholds should be settings.
- **Confidence indicator** (effort 1–2 days): per sighting, record the read source (barcode read directly vs OCR-inferred model vs counted box without a read) and the share of boxes with a read. Show "12 boxes, 10 identified". This is honest about the camera's limits, which matters because the counting is CV-based.
- **Per-location timeline** ("what was here last week", effort 1 day after history): tap a column, see the list of sweeps, tap one to view that snapshot. The "Item history" equivalent: every column a barcode was seen in, with dates.
- **Conflicts**: with a single phone there are none yet. Once shared, use **append-only sweep records** (each sweep is a new doc, never edited), so last-write-wins on a shared "current" doc can't silently erase a colleague's sweep. "Current" is computed as newest sweep per column.

### Gaps
- No published benchmark found for acceptable variance thresholds in small retail stock rooms. No source quantifies how much a diff screen improves accuracy (it is inferred from the WMS approval pattern).

## 3. Find workflows (AR find, pick lists, multi-stop routes, move/replenish, putaway)

### Takeaway
"Find in camera" (highlight the wanted box live) is the signature feature of Scandit MatrixScan Find, and it can be rebuilt on the existing free ML Kit + tracker pipeline. Multi-model pick lists with a route over several columns are cheap here, because the graph is tiny and brute force or nearest-neighbour + 2-opt is optimal or near-optimal.

### Cited Findings
- Scandit MatrixScan Find: a pre-built AR UI that highlights matching items with coloured dots and, when paused, a carousel of searched items with checkmarks for those found. It is built on the "Barcode Find" mode and needs a license with the **MatrixScan AR add-on** — [Scandit docs: MatrixScan Find Android get started](https://docs.scandit.com/sdks/android/matrixscan-find/get-started/); [Scandit product page](https://scandit.com/products/matrixscan-find)
- Dynamsoft Barcode Reader: Android has single and multiple-barcode modes. Pricing is an annual subscription "from $1,499/year", metered per scan (2 barcodes in one frame = 2 scans). No AR find feature found — [Dynamsoft pricing](https://www.dynamsoft.com/barcode-reader/pricing/); [Dynamsoft license types](https://www.dynamsoft.com/license-server/docs/about-v2.2.19/licenseTypes.html)
- ML Kit barcode scanning runs on-device, without a network, and returns a bounding box per barcode, which can be drawn as an overlay on a CameraX preview. The coordinates must be mapped to the preview — [ML Kit: Scan barcodes on Android](https://developers.google.com/ml-kit/vision/barcode-scanning/android?hl=tr); [ProAndroidDev: ML Kit + CameraX scanner](https://proandroiddev.com/building-barcode-qr-code-scanner-for-android-using-google-ml-kit-and-camerax-220b2852589e)
- Picker routing: S-shape traverses every aisle with an item; largest-gap enters an aisle only up to its largest gap. S-shape is good when there are many items per aisle, largest-gap when there are few. "Combined" uses a small DP per block. Ratliff & Rosenthal (1983) solved optimal routing for the basic parallel-aisle layout as a solvable TSP case — [Roodbergen: routing order pickers](https://www.roodbergen.com/warehouse/background.php); [ERIM: short explanation on routing strategies](https://www.erim.eur.nl/material-handling-forum/research-education/tools/calc-order-picking-time/what-to-do/short-explanation-on-routing-strategies)
- Roodbergen & de Koster (2001) benchmarked heuristics against branch-and-bound optimal routes; "combined+" was best in most cases — [Scribd copy of IJPR 2001](https://www.scribd.com/document/465893172/IJPR2001-pdf)
- Odoo putaway rules are checked in priority order (product/category/package). The recommended default for an empty rule is **"Last used" sublocation**, so Odoo suggests where the product was stored before. Storage categories cap capacity — [Odoo 18 docs: Putaway rules](https://www.odoo.com/documentation/18.0/applications/inventory_and_mrp/inventory/shipping_receiving/daily_operations/putaway.html)

### Inferences
- **Find in camera** (value very high in a dense shoe wall, effort 3–5 days). The app already tracks barcodes per frame (`tracking/BarcodeTracker.kt`) and draws overlays (`ui/OverlayView.kt`). Add a "target set" and draw a big green box and pulse/vibrate on match, dimming the others. Start the camera from search results ("Find 41-size 2345 here"). For boxes whose barcode is too small to read at distance, fall back to OCR'd model text (already present). Risk: medium (reading distance; mitigated by the existing auto-zoom). A free alternative to Scandit's licensed AR add-on, and a strong demo video.
- **Pick list / multi-find** (value high: a customer wants 3 sizes or models, or a restock list; effort 2–3 days). Choose several models -> resolve each to its best column (freshest sighting) -> order stops -> show the route on the plan with numbered stops and a checklist. Find-in-camera ticks items off. With ≤10 stops, **brute-force permutations (10! ≈ 3.6M is too many; use DP over subsets O(n²2ⁿ) for n≤12, or nearest-neighbour + 2-opt)** over shortest-path distances that the existing Dijkstra already computes. Effort is low because `RouteFinder` exists.
- **Move/replenish flow** (value medium, effort 1–2 days): "Moved boxes" = scan the source column QR, scan the boxes, scan the destination QR. Partial update without a full re-sweep, written as a move event in the history.
- **Putaway suggestion** (value medium-low for 3 rooms, effort 1–2 days): Odoo-style "last used location for this model" and "column with the most free space" (from box counts vs typical capacity). Easy to fake badly; ship only after capacity data is real.

### Gaps
- No public, current Scandit list price was found (sales-quoted). No independent measurement of MatrixScan Find's time savings (only vendor claims).

## 4. Insight (dashboards, coverage, heatmaps)

### Takeaway
A one-screen "health" dashboard turns stored sweep data into management value at low cost: coverage, oldest columns, models split across several places, empty columns, and accuracy trend from diffs. Heatmaps on the existing PlanView are the visual hook.

### Cited Findings
- Accuracy KPIs used in cycle-count programs: inventory accuracy %, variance rate, recount ratio. Per-bin breakdown matters because aggregates hide chronic problem bins — [Cleverence: how to cycle count](https://www.cleverence.com/articles/business-blogs/how-to-cycle-count-4827/)
- Most variance in record inaccuracy sits between product categories (26.4%) rather than stores (2.7%), which argues for per-category/per-location breakdowns over single totals — [EconPapers: DeHoratius & Raman 2008](https://econpapers.repec.org/RePEc:inm:ormnsc:v:54:y:2008:i:4:p:627-641)
- Sortly ships inventory summary and activity/user-action reports even for SMB users — [Software Advice: Sortly Pro](https://www.softwareadvice.com/inventory-management/sortly-pro-profile/)

### Inferences
- **Dashboard cards** (effort 2 days, all SQL over existing tables): % columns swept in last 7 days per depot; 10 oldest columns ("sweep these next" = a location-based cycle-count queue, ABC-style cadence by column); models seen in ≥2 columns (consolidation candidates); empty/near-empty columns; unknown barcodes (no model learned); diff-based "changes per sweep" trend.
- **Heatmap modes on PlanView** (effort 1–2 days): toggle colour by *age*, *box count/fill*, *change rate*, *search frequency* (log searches locally, which also feeds slotting: fast movers go near the entrance).
- "Sweep next" queue is the professional form of staleness: it turns a passive colour into a daily task.

### Gaps
- Found no shoe-retail-specific slotting study. Fast-movers-near-entrance is general warehousing practice, not sourced here.

## 5. Team (sharing, roles, who scanned what)

### Takeaway
With 2–5 coworkers and no IT, Firestore with offline persistence plus anonymous/Google sign-in is the realistic backend, and the free tier comfortably covers this load. Store sweeps as append-only documents to avoid last-write-wins data loss. A read-only web view or Google Sheet export is a cheap win for the manager.

### Cited Findings
- Firestore on Android has offline persistence **enabled by default** and syncs local changes when back online; same-document conflicts are **last write wins** — [Firebase docs: Access data offline](https://firebase.google.com/docs/firestore/manage-data/enable-offline?hl=en)
- Spark (free) plan: Firestore **50K reads/day, 20K writes/day, 20K deletes/day, 1 GiB stored**; excess is charged per day on Blaze — [Firebase pricing plans](https://firebase.google.com/docs/projects/billing/firebase-pricing-plans). Third-party Blaze per-read prices conflict ($0.06 vs $0.18 per 100K) — [Lido](https://lido.app/firebase/firebase-pricing) vs [ToolRadar](https://toolradar.com/tools/firebase-firestore/pricing). Check the official page.
- Sortly limits access per folder per team member, and its activity log answers "who did what, and when" — [Sortly](https://sortly.com/business-inventory-app/)

### Inferences
- Load estimate: ~60 columns × a few sweeps/day × ~30 sightings ≈ a few thousand writes/day if each sighting is a doc. Better: **one doc per sweep with the sightings array embedded** (well under 1 MiB) gives tens of writes/day. Far below free quotas.
- Plan: Room stays the source of truth on-device. A sync worker pushes `sweep` docs and pulls others'. Each sweep carries `userName`/`deviceId`. Roles: just "editor" vs "viewer" via Firestore security rules. Effort 4–6 days including rules and tests; risk medium (sync bugs, auth). It shows backend/offline-first skills in a portfolio.
- Cheaper interim (effort 1 day): share-intent CSV/JSON export plus import on a second phone, or a scheduled Google Sheets push. Read-only web view (Firebase Hosting page reading Firestore) is +2 days.
- Show "swept by Ali, 2 days ago" in search and diff screens. It builds accountability, and it is the feature colleagues notice first.

### Gaps
- Didn't verify current Firebase Auth anonymous-user limits or the latest Firestore pricing page numbers beyond the docs snippet above.

## 6. Hardware (Bluetooth ring/handheld scanners, label printers)

### Takeaway
Support a Bluetooth scanner in HID (keyboard) mode first: zero SDK, it works with any field, and it helps when phone-camera reads fail. Keep A4 PDF labels (already done) and treat direct label-printer SDKs as optional.

### Cited Findings
- HID mode = keyboard emulation, typing one character at a time into the focused field. SPP (serial) sends the whole barcode at once and is faster, but needs app code to read the stream — [CodeREADr: Bluetooth barcode scanners](https://www.codereadr.com/bluetooth-barcode-scanner/)
- Ring scanners (e.g. Saveo) offer BLE, HID and SPP. Handheld Group suggests its SDK over keyboard-wedge mode for the RS60 ring on non-Handheld devices — [Orca Scan: Saveo ring setup](https://orcascan.com/guides/saveo-scan-ring-orca-scan-setup-b2b22faa); [Handheld: RS60 on non-Handheld devices](https://www.handheldgroup.com/?p=63069)
- Socket Mobile scanners use a Companion App for Application Mode (SPP) on Android; Android 8+ can auto-connect in SPP — [Socket Mobile S700 manual](https://www.manualslib.com/manual/1801405/Socket-S700.html?page=4)
- Zebra Link-OS Multiplatform SDK: a Java library to discover, connect and print to Zebra label printers over Bluetooth on Android (ZPL/CPCL) — [Zebra Link-OS SDK](https://techdocs.zebra.com/link-os/3-00/labelindex); [Zebra app note: Android Studio](https://www.zebra.com/content/dam/support-dam/en/documentation/unrestricted/application-notes/getting-started-android-development-link-os-sdk-android-studio-app-note.pdf)
- Brother Print SDK for Android integrates Brother label/mobile printers and is distributed via Brother's developer program — [Brother Print SDK](https://www.brother.com.ph/en/solutions/brother-print-sdk-for-android-and-iphone-ipad)

### Inferences
- **HID scanner input** (effort 0.5–1 day): capture `KeyEvent`s in the Location/Search activities (a fast keystroke burst ending in Enter = a scan), routing column QR -> select column and product barcode -> search/move. Test with any cheap Bluetooth scanner (about $20–40). The codebase already knows the HID protocol from the other side (`hid/HidKeyboard.kt`).
- Label printers: A4 sheets with QR on sticker paper are fine for ~60 columns (a one-off job). A Brother/Zebra SDK is 2–3 days, needs hardware, and helps only if labels are reprinted often. Low priority.
- Laminated or acrylic label holders matter more than the printer (shoe-room dust and handling). This is a practical note rather than a sourced one.

### Gaps
- No Android SDK details were verified for specific budget ring scanners (Eyoyo, Netum). Their docs are mostly PDF manuals with config barcodes.

## 7. Ranked shortlist (usefulness × portfolio impression ÷ effort)

### Takeaway
Do history first (it unlocks most of the rest), then the diff screen, staleness, and find-in-camera. Team sync is the biggest portfolio item but should come after the single-user data model is append-only.

### Cited Findings
- Inaccuracy is common (65% of records in the DeHoratius & Raman study) and auditing reduces it, which supports prioritising re-count/diff/staleness features — [EconPapers](https://econpapers.repec.org/RePEc:inm:ormnsc:v:54:y:2008:i:4:p:627-641)
- Variance review + approve/recount is the standard pro-WMS pattern — [NetSuite help](https://oracle.hydrogen.sagittarius.connect.product.adaptavist.com/en/cloud/saas/netsuite/ns-online-help/section_155993310131.html)
- AR find is a premium, licensed feature in Scandit (MatrixScan AR add-on) — [Scandit docs](https://docs.scandit.com/sdks/android/matrixscan-find/get-started/)

### Inferences
Ranked (effort = one developer, this codebase):
1. **Append-only sweep history + Room migration** — 1–2 d, risk low-med. Foundation for 2, 3, 6, 8, 9. Fixes the current delete-on-resweep data loss.
2. **Re-sweep diff screen (New / Gone / Changed / Moved) with Accept / Re-sweep / Undo** — 2–3 d. Highest trust value and the best portfolio screenshot.
3. **Staleness on plan + "last seen X days ago" in search + "sweep next" queue** — 1–2 d. Cheap, visible daily value.
4. **Find in camera (AR highlight of target barcodes/models)** — 3–5 d, risk medium. Free rebuild of a licensed Scandit feature on ML Kit; great demo video.
5. **Pick list with multi-stop route (DP/2-opt over Dijkstra distances) + checklist** — 2–3 d. Reuses `RouteFinder`.
6. **Health dashboard + heatmap toggles (age / fill / change rate / search frequency)** — 2–3 d, pure SQL + PlanView colouring.
7. **Rack-builder setup wizard with naming template preview and measured metres** — 3–5 d. Professional onboarding; makes the app reusable for other shops (portfolio "generalisable" story).
8. **Confidence per column (identified vs counted boxes, read source)** — 1–2 d. Honest CV, differentiates the app from manual tools.
9. **Firestore sync of sweep docs, user name per sweep, viewer/editor rules (offline-first)** — 4–6 d, risk medium. Biggest team and portfolio upgrade; do it after 1.
10. **Bluetooth HID scanner support (keystroke-burst capture)** — 0.5–1 d. Small but makes field use robust when camera reads fail.

Explicitly deprioritised: AR/LiDAR room scanning (no Android API, weeks of work), photo-trace floor plans, putaway engine, direct label-printer SDKs.

### Gaps
- Effort figures are estimates from code size/structure (8.4k LOC, Room, existing tracker/route/plan classes), not measured. Real-world value ranking hasn't been validated with the coworkers. The Tuesday field test is a good place to ask which of 2–5 they would use.
