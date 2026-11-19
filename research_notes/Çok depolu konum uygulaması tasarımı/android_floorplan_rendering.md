# Android floor-plan / rack-layout editor, viewer and indoor wayfinding (small stock room)

Scope: choosing an implementation for an existing Kotlin Android app (minSdk ~26, Material3 Views, Compose possible) that needs (a) a rack-layout editor on a grid, (b) a zoom/pan viewer with highlighted columns, a route from the entrance and a front (elevation) view of a row, (c) optional isometric/3D view. Research date: 2026-11-17. About 16 tool calls; several facts are from project docs only (no independent verification), and this is flagged where it applies.

## Q1. Compose Canvas vs custom View: gestures, zoom libraries, hit-testing, performance, accessibility, open-source examples

### Takeaway
Use Compose: a single `Canvas` (or `Modifier.drawBehind`) driven by your own world-to-screen transform state is the standard pattern. Gestures come from `detectTransformGestures`/`transformable`, or from a library: telephoto `Modifier.zoomable` (Apache-2.0, works on "canvas drawings", minSdk 21) or usuiat/Zoomable (`net.engawapg.lib:zoomable`, actively released in 2026). Hit-testing has to be done by hand in world coordinates. Accessibility for drawn racks needs invisible overlay composables or per-region semantics. I found no open-source Android floor-plan or seat-map *editor* to copy. MapCompose (Apache-2.0) is the closest ready-made *viewer* (markers, paths, rotation), but it is tile-based.

### Cited Findings
**Compose versions (late 2026)**
- An October 2026 snapshot of Google's Compose release page lists compose foundation/ui/runtime stable **1.12.1** (updated 21 Oct 2026), beta 1.13.0-beta02, and **Material3 stable 1.4.0** (1.5.0-beta02 in beta). An August 2026 snapshot listed 1.12.0 stable. I could not confirm the exact BOM name for Nov 2026; check the BOM mapping page. — [Compose releases](https://developer.android.com/jetpack/androidx/releases/compose?authuser=0)

**Built-in gestures**
- `detectTransformGestures` reports per-frame deltas: centroid, pan, zoom and rotation. With `panZoomLock=false`, all three are detected once touch slop is passed. — [composables.com detectTransformGestures](https://composables.com/docs/androidx.compose.foundation/foundation/functions/detectTransformGestures); [Android docs: Multitouch](https://developer.android.com/develop/ui/compose/touch-input/pointer-input/multi-touch)
- `Modifier.transformable` only detects gestures. You apply the resulting state yourself, usually through `graphicsLayer`. — [composables.com transformable](https://composables.com/foundation/transformable); [Android docs: Multitouch](https://developer.android.com/develop/ui/compose/touch-input/pointer-input/multi-touch)
- Modifier order matters. If `pointerInput` comes after `graphicsLayer`, the touch area is scaled and translated along with the content. Clickable children can swallow taps and block pinch. A common workaround is to run the transform detector in `PointerEventPass.Initial`. Filtering by pointer count avoids jumps, for example one finger drags a rack and two fingers pan/zoom. — [Stack Overflow mirror: natural pan & zoom](https://kaweah.freedombox.rocks/kiwix/content/stackoverflow.com_en_all_2023-11/questions/72922205/how-to-have-natural-pan-and-zoom-with-modifier-graphicslayer-pointerinput); [Stack Overflow mirror: zoomable layout with clickable children](https://kaweah.freedombox.rocks/kiwix/stackoverflow.com_en_all_2023-11/questions/76379459/how-to-create-a-zoomable-layout-that-holds-a-container-with-children-that-can-); [Canopas gestures part 2](https://canopas.com/gestures-in-jetpack-compose-all-you-need-to-know-part-2-61da0c2bab6f)

**telephoto (saket) `Modifier.zoomable`**
- Built for media, but it "can be used for anything such as text, canvas drawings, etc." Supports pinch, fling, double-tap zoom, single-finger zoom, haptics at limits, and nested scrolling. Applies transforms via `graphicsLayer`, or with `autoApplyTransformations = false` you read `state.contentTransformation` yourself, which is useful for keeping stroke widths and text crisp. `setContentLocation(ZoomableContentLocation…)` limits over-zoom and over-pan when the drawn content is smaller than the layout. — [telephoto zoomable docs](https://saket.github.io/telephoto/zoomable)
- Caveat for an editor: zoomable "consumes all tap gestures", so it is incompatible with `Modifier.clickable/combinedClickable`. You use its `onClick`/`onLongClick` instead. The docs do not show tap coordinates for those callbacks, and `onDoubleClick = { state, centroid -> }` exists. — [telephoto zoomable docs](https://saket.github.io/telephoto/zoomable)
- Releases: 0.19.0 is the latest (02 Apr; the page shows no year, presumably 2026), then 0.18.0 (04 Oct), 0.17.0 (15 Sep) and 0.16.0 (20 May). 0.16.0 added `SpatialOffset`/`SpatialRect` for converting between viewport and content coordinates, which is the hit-testing hook. 0.12.0 lowered minSdk to 21. Multiplatform (iOS/Wasm). — [telephoto releases](https://www.github.com/saket/telephoto/releases)

**usuiat/Zoomable (`net.engawapg.lib:zoomable`)**
- Compose Multiplatform `Modifier.zoomable` with pinch, double-tap and mouse-wheel zoom. Versions around 2.11.1 (22 Feb 2026) up to 2.13.0 appear on Maven; check GitHub for the latest. The repo is github.com/usuiat/Zoomable. — [klibs.io usuiat/Zoomable](https://klibs.io/project/usuiat/Zoomable); [API docs](https://usuiat.github.io/Zoomable/zoomable/net.engawapg.lib.zoomable/index.html)
- The author publishes a "zoomable scrollable row" sample. — [composables.com sample](https://composables.com/@usuiat/components/zoomable-scrollable-row)

**MapCompose (viewer candidate)**
- Apache-2.0 Compose library for tiled maps. Markers are arbitrary composables (draggable, anchored), `addPath` draws polylines, plus clustering, callouts, pinch/fling/rotation, and suspend `scrollTo`/`rotateTo` animations. Coordinates are normalized (0..1). Latest version 3.2.8. There is no tile-less mode; single-level maps still use a `TileStreamProvider`. About 283 stars and 535 commits. A multiplatform sibling, MapComposeMP 1.0.0, exists. — [MapCompose GitHub](https://github.com/p-lr/mapcompose)

**Open-source editors**
- No open-source Android/Compose seat-map or floor-plan *editor* turned up. The finds were pickers: yveskalume/seatpickerlayout (Compose seat picker), mutualmobile/SeatLayout (Java, Apache-2.0, last pushed 2022) and seatmap-canvas (web). Commercial SDKs exist (seats.io, SeatLayer). — [seatpickerlayout](https://awesome.ecosyste.ms/projects/github.com%2Fyveskalume%2Fseatpickerlayout); [SeatLayout](https://awesome.ecosyste.ms/projects/github.com%2Fmutualmobile%2Fseatlayout); [seatsio-android](https://jitpack.io/p/seatsio/seatsio-android); [plaan (web)](https://awesome.ecosyste.ms/projects/github.com%2Fffoodd%2Fplaan)

**Accessibility**
- Compose cannot infer semantics for canvas drawings, so you must add them. `Canvas(contentDescription=…)` exists, and `Modifier.semantics { onClick(label, action) }` can expose an action on the canvas. — [Android docs: key steps](https://developer.android.com/develop/ui/compose/accessibility/key-steps); [Make composables more accessible](https://developer.android.com/guide/topics/ui/accessibility/composables); [freeCodeCamp tutorial](https://www.freecodecamp.org/news/accessibility-in-jetpack-compose-comprehensive-tutorial/)
- Community advice (not official): Compose has no arbitrary virtual semantics nodes. Place non-drawing composables over each drawn box so TalkBack can focus them. — [Kotlin Slack thread](https://slack-chats.kotlinlang.org/t/9711743/regarding-accessibility-if-i-for-instance-draw-3-boxes-using?t=v2)

### Inferences
- Recommended architecture: one `Canvas` in world units (metres or grid cells) under a `Matrix`/(scale, offset) state. Draw all ~200 cells with `drawRect`/`drawText` (via `TextMeasurer`, cached). Hit-test by inverting the transform (`world = (screen - offset) / scale`) and testing rects. Even a linear scan over 200 rects is microseconds. 200 rect draws per frame is trivial for Compose/Skia. This is my engineering judgement; I found no benchmark.
- For the **editor**, raw `pointerInput` with your own state machine (tap-select / drag-move / handle-resize / two-finger pan-zoom) is safer than telephoto, because telephoto eats taps and is tuned for media. For the **viewer**, telephoto or usuiat `Modifier.zoomable` gives polished fling, double-tap and limits for almost no work.
- MapCompose fits only if you render the plan to a bitmap/tile first. Its marker-as-composable and path APIs are attractive, but for a 10x20 m room a hand-written Canvas is simpler.
- Custom View alternative (background knowledge, not fetched this session): `ScaleGestureDetector` + `GestureDetector` + `Matrix` on a `View.onDraw`, with `ExploreByTouchHelper` for virtual accessibility nodes. Mature, but more boilerplate. Since Compose interop (`ComposeView` inside a Views/Material3 activity) is easy, a Compose island for the map screen is the pragmatic choice.
- Effort estimate (inference): a viewer with pan/zoom, highlighting and tap-to-select takes about 2–4 days. An editor with snap, move, resize, rotate-90, properties sheet and undo takes about 1.5–3 weeks for one developer.

### Gaps
- No exact Compose BOM version for Nov 2026 was confirmed; only the Oct 2026 artifact versions above.
- telephoto release years are not printed on the releases page, and its onClick coordinate API is undocumented in the page read.
- No measured performance numbers for Compose Canvas with hundreds of shapes were found.

## Q2. Grid snapping and editing UX in mobile floor-plan / seat-map apps

### Takeaway
Little is publicly documented. magicplan's manual mode uses tapping corners on a background grid "to estimate", then exact numeric dimension edits. That is the useful pattern: coarse touch placement plus snap, then precise values in a form.

### Cited Findings
- magicplan Android: manual drawing is for "custom layouts, irregularly shaped rooms, and precise control over dimensions". — [magicplan help: Android floor plans](https://help.magicplan.app/creating-floor-plans-in-magicplan-on-your-android-device)
- "Define corners" workflow: tap corner locations, use the background grid to estimate wall length and angle, then adjust dimensions to the correct measurements. — [magicplan help: define corners](https://help.magicplan.app/create-a-room-with-the-define-corners-feature)
- magicplan markets finger or stylus sketching "as if you were drawing". — [magicplan mobile sketching](https://magicplan.app:443/mobile-sketching-solution)
- A competitor (RoomSketcher) says magicplan editing on a phone "can feel cramped". This is a biased source. — [RoomSketcher blog](https://www.roomsketcher.com/blog/is-roomsketcher-better-than-magicplan/)
- Warehouse route-planning guidance: the map must include each aisle, cross-aisle, obstruction, one-way path and hazard area. — [Cleverence pick routes](https://cleverence.com/articles/3pl-business/plan-efficient-pick-routes-for-order-pickers-6982)

### Inferences
- Concrete UX for SweepScan's editor, derived from the above and general practice:
  - Grid unit = 0.5 m or one rack-column width. Snap the rack origin and size to integer cells while dragging, and show the ghost outline at the snapped position.
  - Corner/edge handles only on the selected rack, with handles of at least 48 dp touch target.
  - Rotation is a "rotate 90°" button, not a gesture.
  - A bottom sheet holds the numeric fields: row name, column count, numbering direction (L→R / R→L / serpentine), and levels.
  - "Duplicate rack" plus auto-increment of the row name covers most of the work in a stock room with parallel racks.
  - Entrance is a special edge marker. Corridors are derived (see Q4) rather than drawn, with an optional "blocked" paint tool.
  - Undo stack: keep immutable layout snapshots.
- A "generate row of racks" wizard (N racks, spacing, aisle width) likely beats free-form drawing for typical shoe stock rooms.

### Gaps
- I could not confirm whether magicplan, Floor Plan Creator or seat-map builders snap to grid during touch drags, or how they handle handle sizes and rotation. No UX write-ups were found.

## Q3. Isometric 2.5D on plain Canvas vs real 3D (SceneView/Filament)

### Takeaway
For rack boxes, isometric projection on 2D Canvas is cheap (a few dozen lines, painter's algorithm by x+y depth) and there is now a Compose library (jayteealao/Isometric, minSdk 24). Real 3D via SceneView (Filament) is Compose-native and well-maintained (4.x in 2026) but adds about 3–5 MB and much more effort, with little extra value for a 2D stock room. Treat 3D as an optional portfolio extra.

### Cited Findings
- **jayteealao/Isometric** (`io.github.jayteealao:isometric-compose:1.1.0`): Compose library for interactive isometric scenes that handles projection, depth sorting and rendering. Requirements are minSdk 24, Kotlin 1.9+ and Compose 1.5+. Modules: `isometric-core` (pure Kotlin/JVM), `isometric-compose`, and `isometric-android-view`. It has its own `IsoColor`. — [Isometric docs](https://jayteealao.github.io/Isometric/); [installation](https://jayteealao.github.io/Isometric/getting-started/installation/)
- Its depth is computed as `x + y − 2z`, where higher means farther and drawn first. Sorting is on by default (`RenderOptions.enableDepthSorting`), and there is a known sort issue with complex shapes (Knot). Shapes take `onClick`, and the scene has `GestureConfig.onTap`. Docs last updated Jul 18, 2026. Licence and performance limits are not stated on the FAQ page. — [Isometric FAQ](https://jayteealao.github.io/Isometric/faq/)
- Its migration guide refers to the older View library `io.fabianterhorst:isometric` as the pre-Compose version. — [View→Compose migration](https://jayteealao.github.io/Isometric/migration/view-to-compose/)
- **SceneView**: 3.0 was the Compose rewrite ("3D is just Compose UI"), with a `Scene` composable plus `ModelNode`/`rememberModelInstance`. It uses Google Filament on Android, plus ARCore for AR. klibs lists 4.x releases through 4.17.0 (31 May 2026), and its docs mention up to v4.31.0. — [SceneView changelog](https://sceneview.github.io/changelog); [klibs sceneview-core](https://klibs.io/package/io.github.sceneview/sceneview-core); [GitHub](https://github.com/sceneview/sceneview)
- SceneView size: the FAQ says about 5 MB (native Filament + Kotlin), and AR adds about 1 MB. The homepage and comparison table say about 3 MB. These are project self-reports only. Its llms.txt lists Filament 1.70.0. — [SceneView FAQ](https://sceneview.github.io/faq); [SceneView comparison](https://sceneview.github.io/comparison); [llms.txt](https://sceneview.github.io/llms.txt)
- SceneView publishes a 3D-in-Compose codelab. — [codelab 3D compose](https://sceneview.github.io/codelabs/codelab-3d-compose)

### Inferences
- Hand-rolled isometric math (standard, background knowledge): with 2:1 dimetric, `sx = (x - y) * cos30 * s`, `sy = (x + y) * sin30 * s - z * s`. Draw each rack as 3 visible faces (top, left, right) with shaded colours. For axis-aligned non-overlapping boxes on a floor, sorting by `x + y` (or by the far corner) works with the painter's algorithm. Overlap cases are absent in a rack layout. Effort: about 1–2 days including a highlighted-column overlay. Hit-testing uses the inverse projection on the floor plane (z=0), or tests face polygons in reverse draw order.
- **The front (elevation) view of a row** needs no 3D at all. It is a 2D grid of columns × levels drawn on Canvas. This is probably the most useful "3D-like" feature for a picker.
- Real 3D effort (inference): procedurally building box meshes or loading a glb rack model, plus camera controls, takes about 1–2 weeks. Add APK growth (+3–5 MB) and GPU/battery cost on a cheap stock-room phone. Value is mostly visual.

### Gaps
- No licence, star count or performance data for jayteealao/Isometric was retrieved, so check its GitHub before adopting.
- No independent APK-size measurement of SceneView/Filament exists; verify with APK Analyzer.

## Q4. Indoor wayfinding in a small room: corridor graph vs grid A*, deriving walkable grid, smoothing, route drawing

### Takeaway
For a stock room of a few hundred m², rasterize rack rectangles into an occupancy grid (cell ≈ 0.25–0.5 m) and run A* (or plain BFS/Dijkstra). It is trivially fast and needs no hand-drawn corridors. Then simplify the path to corner waypoints, or use line-of-sight smoothing / Theta*. Red Blob Games recommends fewer nodes (waypoints or navmesh) for speed, but at this size speed does not matter. Use tie-breaking so routes look straight.

### Cited Findings
- Red Blob Games: grids are graphs; "the fewer nodes A* has to process, the faster it will run". Alternatives are waypoints at "exterior corners", navigation meshes, and hierarchical/quadtree maps. "movement can be on grid even if your map representation is not". Straightened paths "aren't guaranteed to be the shortest", and Theta* gives any-angle shortest paths. — [Red Blob Games: grid algorithms](https://www.redblobgames.com/pathfinding/grids/algorithms.html)
- Heuristics (Amit Patel): 4-way uses Manhattan `D*(dx+dy)`. 8-way uses diagonal distance `D*(dx+dy) + (D2-2*D)*min(dx,dy)`, which is octile when D2=√2. — [Amit's A* Heuristics](http://theory.stanford.edu/~amitp/GameProgramming/Heuristics.html)
- Tie-breaking for nicer paths:
  - `heuristic *= (1.0 + p)` with p < min step cost / max path length, e.g. 1/1000.
  - Cross-product tie-breaker `heuristic += abs(dx1*dy2 - dx2*dy1)*0.001`, which prefers the straight start-goal line.
  - Penalize direction changes to minimize turns.
  — [Amit's A* Heuristics](http://theory.stanford.edu/~amitp/GameProgramming/Heuristics.html)
- Warehouse picking routing families: **S-shape/serpentine** (traverse each aisle containing a pick fully, alternating direction), **return** routing, and **largest-gap** (skip aisles without picks), plus hybrids. — [BizAutomation S-shape](https://bizautomation.com/wms-software/s-shape-serpentine-routing); [Cleverence](https://cleverence.com/articles/3pl-business/plan-efficient-pick-routes-for-order-pickers-6982); [Medium routing simulation](https://medium.com/@beware-sim/optimizing-order-fulfillment-a-dive-into-warehouse-routing-strategies-via-simulation-part-i-4fe3302530f1)
- Georgia Tech ISyE has a web tool that visualizes warehouse travel as a heat map, including non-rectangular layouts. — [GT pick-path visualizer](https://www2.isye.gatech.edu/~jjb/wh/apps/pickpath/index.html)
- Route4Me builds a warehouse "road network" from the floor map and routes on it. BizAutomation shows step-by-step directed navigation on handheld scanners. — [Route4Me](https://route4me.com/platform/marketplace/labs/pick-and-pack-optimization); [BizAutomation](https://bizautomation.com/wms-software/s-shape-serpentine-routing)

### Inferences
- **Deriving the walkable grid:**
  1. Set the bounding room rect at resolution r.
  2. Mark cells covered by any rack rectangle as blocked. Inflate racks by a clearance, e.g. 0.2 m, so routes do not hug shelves.
  3. Mark user-painted "no-go" cells as blocked.
  4. The entrance becomes a start cell.
  5. Each column's **pick point** is the free cell directly in front of the column's face, on the aisle side. Compute it from rack orientation and the numbering direction.
- Algorithm: 4-way A* with Manhattan plus a turn penalty gives Manhattan-style aisle routes that look like how people walk in aisles. 8-way with octile, followed by line-of-sight smoothing (drop intermediate points while a Bresenham/supercover line between kept points stays in free cells), gives natural diagonal shortcuts in open areas. Either runs in < 1 ms for a 40×80 grid.
- Multi-pick ordering (several columns): with ~10 stops, a nearest-neighbour heuristic plus 2-opt over A* distance matrices is enough. S-shape is an alternative if the room has parallel aisles.
- Drawing the route: a polyline with rounded joins (`Stroke(cap=Round, join=Round)`), the start marker at the entrance, a pulsing target column, and arrowheads or an animated dash phase (`PathEffect.dashPathEffect` with an animated phase) to show direction. Auto-fit the camera to the route bounds. This reflects common practice; I found no source specific to an indoor-nav app.
- A corridor graph (hand-drawn nodes/edges) only makes sense if the user wants to force specific walkways. The grid approach removes an editing step, which matters for a single-developer app.

### Gaps
- No source showed how commercial indoor-navigation or WMS apps visually style routes (screenshots or design docs were not found).
- Red Blob's dedicated implementation and smoothing pages were not fetched this session; the any-angle (Theta*) note above comes from the grid-algorithms page.

## Q5. Photo of a hand sketch → layout: automatic vs trace-over

### Takeaway
Fully automatic conversion of a *hand-drawn* sketch is not reliable with classic OpenCV. Published accuracy figures are for printed plans, and learned methods are recommended for robustness. The practical pattern is a "photo as a semi-transparent, perspective-corrected background, user places snapped racks on top". At most, add an optional OpenCV "suggest rectangles" assist.

### Cited Findings
- A contour-based corner-detection paper reports 95% recognition accuracy on 90 **printed** floor plans. It notes OpenCV contour extraction is a standard method. — [DFKI GREC paper](https://www.dfki.de/fileadmin/user_upload/import/7682_lncs-grec.pdf)
- Stanford CS231A "Sketch 2 Graybox": Canny, then `cv2.findContours`, then Douglas-Peucker (ε = 0.05 × perimeter), picking the largest 4-point contour. Input was blue-pen sketches. No accuracy figure was reported. — [Sketch 2 Graybox paper](https://web.stanford.edu/class/cs231a/prev_projects_2022/Sketch_2_Graybox_Final_Paper.pdf)
- ECCV 2020: technical drawings are typically vectorized "by hand" from scans or photos, and degradation is a key problem. — [Deep Vectorization of Technical Drawings](https://www.ecva.net/papers/eccv_2020/papers_ECCV/papers/123580579.pdf)
- ACCV 2022 blueprint vectorization argues region detection is more robust than corner or edge detection, which are "less robust". — [Vectorizing Building Blueprints](https://openaccess.thecvf.com/content/ACCV2022/papers/Song_Vectorizing_Building_Blueprints_ACCV_2022_paper.pdf)
- Raster-to-Vector (ICCV 2017) is the commonly cited deep-learning baseline for floor-plan vectorization. — [FloorplanTransformation repo](https://awesome.ecosyste.ms/projects/github.com%2Fart-programmer%2FFloorplanTransformation); [R2V write-up](https://besthub.dev/articles/raster-to-vector-floorplan-reconstruction-r2v-for-standardized-housing-layouts-9f701e057eb7)
- SketchOpt prototyped a vectorizer for photos of hand-drawn plans, with no metrics given. — [SketchOpt arXiv](https://arxiv.org/pdf/2009.00261)

### Inferences
- Trace-over implementation:
  - The user takes a photo and drags 4 corner handles to the room corners. Apply a homography (Android `Matrix.setPolyToPoly`, or OpenCV `getPerspectiveTransform`) to rectify it.
  - The user enters the real room width/length to set scale.
  - Show the photo as a background layer at about 40% alpha under the grid, then place and snap racks.
  - Effort is about 1–2 days with no OpenCV dependency, since `Matrix.setPolyToPoly` supports 4-point perspective.
- An optional assist would run adaptive threshold → `findContours` → `approxPolyDP` → keep convex quads of plausible size, and offer them as "suggested racks" the user confirms. OpenCV Android adds significant APK size; I did not measure it this session. ML Kit and the app's existing CV pipeline are irrelevant here.
- Since the stock room is a set of parallel rectangles, a "rows wizard" (count, length, aisle width) may be faster than either approach.

### Gaps
- No benchmark of OpenCV on hand-drawn floor sketches was found. Accuracy would have to be measured on the user's own sketches.
- OpenCV Android SDK size for 2026 was not checked.

## Q6. Storing layouts: JSON schema and versioning

### Takeaway
I found no standard schema for small rack layouts. I found nothing sourced specifically on this, so the schema below is design inference. Store in metres, or integer grid units with an explicit cell size. Racks are rectangles with origin, size and a rotation in {0, 90, 180, 270}. Rows/columns/levels are metadata, and the walkable grid and corridors are derived rather than stored. Add a `schemaVersion` with explicit migrations.

### Cited Findings
- MapCompose uses normalized (0..1) coordinates for markers and paths, an example of a resolution-independent coordinate choice. — [MapCompose GitHub](https://github.com/p-lr/mapcompose)
- Route-planning guidance lists the map entities a layout must carry: aisles, cross-aisles, obstructions, one-way paths and hazard areas. — [Cleverence pick routes](https://cleverence.com/articles/3pl-business/plan-efficient-pick-routes-for-order-pickers-6982)

### Inferences
- Suggested schema (kotlinx.serialization-friendly):
```json
{
  "schemaVersion": 1,
  "id": "uuid", "name": "Depo A", "updatedAt": "2026-11-17T10:00:00Z",
  "units": "m", "gridCell": 0.5,
  "room": { "w": 12.0, "h": 20.0, "outline": null },
  "background": { "imageUri": "...", "homography": [9 floats], "alpha": 0.4 },
  "entrances": [ { "id": "E1", "x": 0.0, "y": 9.5, "w": 1.0, "side": "W" } ],
  "racks": [ {
      "id": "uuid", "row": "A", "x": 2.0, "y": 1.0, "w": 6.0, "d": 0.6, "rot": 0,
      "face": "S",            // which long side is the pick face (or "both")
      "columns": 12, "colStart": 1, "numbering": "LTR",   // LTR | RTL | SERPENTINE
      "levels": 5, "columnWidths": null,
      "qrPrefix": "A-"        // ties to existing QR column labels
  } ],
  "obstacles": [ { "x": 10, "y": 0, "w": 2, "h": 2, "label": "Kasa" } ],
  "walkOverrides": { "blockedCells": [[3,4]], "preferredCells": [] }
}
```
- Keep **derived data out of the file**: the occupancy grid, pick points, cell IDs like "A-07-3" and routes. Compute them on load so editing cannot desync them.
- Versioning:
  - Bump the integer `schemaVersion` on breaking changes.
  - Write a chain of `migrate(v)→v+1` functions over a `JsonObject` before decoding.
  - Use kotlinx.serialization with `ignoreUnknownKeys = true` and defaults for new fields, which gives forward/backward tolerance.
  - Store in Room as a JSON text column, or as files in app storage, with export/share as `.json` for backup.
- Prefer metres with a fixed `gridCell`, or integer cells with `gridCell` in metres, over pixels, so the same file drives the plan view, the elevation view and the isometric view.

### Gaps
- No published standard or open schema for rack/warehouse layouts (e.g. an IndoorGML or IMDF subset suited to stock rooms) was researched in this session. IMDF/IndoorGML could be checked if interoperability ever matters.
