# Map and "where is it" UI/UX design for a small stock-room location app

Scope: 2D plan view of three small stock rooms (racks split into columns, a highlighted column, a route from the entrance, a front/elevation view of a rack row, search plus result list). Target: Kotlin Android, Views (custom Canvas PlanView), Material 3 dark theme, used in a dim basement by shop staff. Research date: 2026-11-17. About 20 tool calls; several official pages (m3.material.io, Apple IMDF register) render with JavaScript and could not be read directly, so some Material facts come from the Material Components for Android docs on GitHub instead.

## 1. Visual language of professional indoor maps (Apple, Google, Mapbox, Mappedin, retail apps)

### Takeaway
Professional indoor maps use a quiet, low-detail base that recedes, and spend strong colour on only one or two things: the selected destination and the walking path. Selection gets an outline plus a colour change, not colour alone. Detail is added only as the user zooms in, and the map uses the app's own style rather than copying Apple or Google Maps.

### Cited Findings
- Apple HIG offers two map emphasis styles. The default style is fully saturated. The "muted" style is a desaturated map, recommended when you have a lot of information-rich content that should stand out against the map — [Apple HIG: Maps](https://developer.apple.com/design/human-interface-guidelines/maps)
- Apple HIG: "Clearly identify elements that people select," using "distinct styling like an outline and color variation" — [Apple HIG: Maps](https://developer.apple.com/design/human-interface-guidelines/maps)
- Apple HIG (indoor maps): "Adjust map detail based on the zoom level." Show large areas such as rooms at every zoom level, then add finer features and labels as the user zooms in. Example: an airport shows terminals and gates when zoomed out, and stores and restrooms when zoomed in — [Apple HIG: Maps](https://developer.apple.com/design/human-interface-guidelines/maps)
- Apple HIG: "Use distinctive styling to differentiate the features of your map"; colour together with icons helps distinguish area types — [Apple HIG: Maps](https://developer.apple.com/design/human-interface-guidelines/maps)
- Apple HIG: offer a floor picker for multi-level venues and "keep floor numbers concise". A list of floor numbers is usually enough, rather than floor names — [Apple HIG: Maps](https://developer.apple.com/design/human-interface-guidelines/maps)
- Apple HIG: include surrounding areas for context, but if they are not interactive, "use dimming and a distinct color to make them appear supplemental" — [Apple HIG: Maps](https://developer.apple.com/design/human-interface-guidelines/maps)
- Apple HIG: "Limit scrolling outside of your venue"; keep at least part of the indoor map on screen at all times — [Apple HIG: Maps](https://developer.apple.com/design/human-interface-guidelines/maps)
- Apple HIG: "Design an indoor map that feels like a natural extension of your app. Don't try to replicate the appearance of Apple Maps." Area overlays, icons and text should match the app's style. The example screenshot tints the map elements green to match the app UI and uses custom icons for gates — [Apple HIG: Maps](https://developer.apple.com/design/human-interface-guidelines/maps)
- Apple HIG: ensure enough contrast between custom controls and the map. A thin stroke or a light drop shadow helps controls stand out — [Apple HIG: Maps](https://developer.apple.com/design/human-interface-guidelines/maps)
- Apple HIG: annotation (pin) text strings should be kept to two or three characters for readability, and the default pin tint can be changed to the app's colour — [Apple HIG: Maps](https://developer.apple.com/design/human-interface-guidelines/maps)
- Apple HIG: cluster overlapping points of interest into one pin showing a count. Clusters expand as the user zooms in — [Apple HIG: Maps](https://developer.apple.com/design/human-interface-guidelines/maps)
- Apple HIG (place cards): "Keep the location on your map visible when displaying a place card." Offset the card so it points to the selected location — [Apple HIG: Maps](https://developer.apple.com/design/human-interface-guidelines/maps)
- IMDF (Apple's Indoor Mapping Data Format) became an OGC Community Standard, v1.0.0 (OGC 20-094). Units carry a `category` (for example `storage`, `room`, `restroom`, `elevator`). Occupants have no geometry of their own and are placed through an anchor or display point. In practice, renderers style units by category — [OGC IMDF](https://docs.ogc.org/cs/20-094/); [Apple IMDF](https://register.apple.com/resources/imdf/); MapLibre + IMDF talk: [Speaker Deck](https://speakerdeck.com/kyotonagoya/implementing-interactive-indoor-maps-with-maplibre-and-imdf)
- Mappedin design team: "highlighting the walking paths" is called "the most important part of wayfinding." Emphasise the areas people need and push less relevant areas into the background. Use contrast to build hierarchy so floors and paths stand out. Too much detail or too little makes the map hard to read — [Mappedin: Designing indoor maps for different use cases](https://www.mappedin.com/resources/blog/designing-indoor-maps-for-different-use-cases/)
- Mappedin: important features are chosen to be distinguishable for different types of colour blindness. Maps target AODA/ADA guidelines. Hospitals use calm palettes (light blues, soft greys), while malls use brighter colours — [Mappedin blog](https://www.mappedin.com/resources/blog/designing-indoor-maps-for-different-use-cases/)
- Mappedin SDK has a "travelled" path mode: the full start-to-end path stays visible, and the part already walked is recoloured to show progress — [Mappedin web SDK v6 wayfinding guide](https://developer.mappedin.com/web/v6/guides/wayfinding/)
- Mapbox Navigation SDK draws a route in stacked layers: a casing layer at the bottom, the main route line above it, and traffic on top. The casing is the widest layer, which "gives the appearance of a border" around the route. There is a "vanishing route line" option for the travelled part — [Mapbox Android Navigation: Route line](https://docs.mapbox.com/android/navigation/guides/ui-components/route-line); [Mapbox iOS route line styling](https://docs.mapbox.com/ios/navigation/v2/examples/route-lines-styling/)
- Home Depot app: find any item's "exact aisle and bay location" with one tap. Store Mode shows aisle and bay on a 3D store map with directions — [Home Depot mobile app](https://www.homedepot.com/c/mobile-app); [Home Depot: find everything in store](https://www.homedepot.com/c/ai/find-everything-you-need-inside-the-home-depot-store/9ba683603be9fa5395fab901c3a65572); [Home Depot corporate news](https://corporate.homedepot.com/news/company/home-depot-named-omnichannel-retailer-year)
- UX Magazine's case study on Home Depot says Store Mode tells users whether a product is in stock and where it is by aisle and bay, and argues that the app UX should change once the user is inside the store — [UX Magazine: Designing for place at The Home Depot](https://uxmag.com/articles/designing-for-place-at-the-home-depot)
- A Lowe's App Store user review says aisle/bay lookup usually works but not always, praises an older map pin for the item location, and asks for a way to report a moved or missing item — [Lowe's on the App Store](https://apps.apple.com/app/id457954781) (one user's opinion, not a design source)

### Inferences
- For SweepScan: the base plan (walls, floor, racks) should be desaturated and low in contrast against itself. Only the target column (amber) and the route (blue) should be saturated. This is Apple's "muted emphasis" idea applied to a Canvas.
- The text-first answer ("Depo 2 · Sıra 4 · Kolon C") is the primary answer, like Home Depot's "aisle X, bay Y". The map is a confirmation and orientation aid, not the main result.
- Use a route with casing: a dark 2–3dp outline under a 4–5dp coloured line. This reads well on both the floor colour and rack colour. Draw it as a solid line; a dashed line can look like a boundary or a disabled state. A dashed line only makes sense for an "optional/approximate" segment.
- Selection = fill colour + thicker outline + label (three cues). This meets Apple's outline-plus-colour rule and WCAG 1.4.1 (see section 3).
- Three depots are similar to "floors": use a compact selector with short labels (D1 / D2 / D3, or short depot names) rather than long names.
- A "report moved/missing" action on the result card, as the Lowe's user asked for, fits SweepScan's data-quality loop. This is an inference from a single review.

### Gaps
- Could not read Apple's IMDF style/register pages (rendered with JavaScript), so there are no official IMDF colour values per unit category.
- No official published style specs (hex values, line widths) were found for Google Maps indoor, IKEA, Walmart, Target or Lowe's store maps. These apps are proprietary, and no reliable teardown was found.
- Pointr and Esri ArcGIS Indoors design guidance was not reached within the tool budget.

## 2. Warehouse/WMS-specific visualisations (rack/bin maps, heatmaps, elevation views)

### Takeaway
WMS visualisers follow a common pattern: a hierarchy (aisle > rack > bay > level > bin) drawn as a plan or 3D layout. Search or an order highlights the matching bins. Tapping a bin opens a side panel or drawer with its contents. A three-step traffic-light heatmap shows fill, and there is a toggle to hide empty levels. Rack-face (elevation) views split into levels and bins are common in Sellercloud-type tools.

### Cited Findings
- Odoo 18 "Smart Warehouse Visualizer" (third-party, LGPL-3): a WebGL 3D layout. In View Mode each bin is colour-coded by live occupancy: green <50% "Healthy", amber 50–80% "Monitor", red >80% "Critical". Product search with autocomplete covers products and locations, highlights every bin holding the product and shows a quantity breakdown. Clicking a bin opens a slide-over panel (SKU, lot/serial, expiry, on-hand, reserved, available). A picking-order mode highlights all bins in a transfer, with progress bars — [Odoo Apps: warehouse_visualizer 18.0](https://apps.odoo.com/apps/modules/18.0/warehouse_visualizer) (vendor listing, not independently verified)
- Other Odoo 3D views: Cybrosys "Stock 3D View" (double-click a location to see its stock); Mindscript (Odoo 19) draws each location as a rack sized from its dimensions and fills it by on-hand stock — [Odoo Apps: stock_3d_view 18.0](https://apps.odoo.com/apps/modules/18.0/stock_3d_view); [Odoo Apps: mindscript_stock_3d_view 19.0](https://apps.odoo.com/apps/modules/19.0/mindscript_stock_3d_view)
- Infor WMS visual travel/locations: each location is drawn as a 3D box from its X/Y front-centre point, plus length, width, height and level, with an orientation (0–270°). Travel sections are coloured with per-section colours drawn on the floor. Hovering highlights a location; clicking selects it. A Section menu filters which locations are shown — [Infor WMS: Visual Travel Audit overview](https://docs.infor.com/wms/2026.x/en-us/wmsolh/visualtravel_loc.html); [Infor WMS docs](https://docs.infor.com/wms/2026.x/en-us/useradminlist/wms_vtw/hcc1641390759661.html)
- Sellercloud "Visualize" module: hierarchy aisle > rack > bay > level > bin. Users switch between rack levels, can "Hide Levels Without Bins", and selecting a bin opens a slide-out panel with its contents — [Sellercloud Visualize module workflow](https://lms.sellercloud.com/?p=12420)
- SKUsavvy: an interactive 3D display of every bin, showing filled vs. empty bins, with a layout builder that switches between racking levels — [SKUsavvy: visual inventory management](https://skusavvy.com/visual-inventory-management-system); [SKUsavvy: warehouse mapping software](https://skusavvy.com/warehouse-mapping-software)
- Mecalux Easy WMS: a heatmap over the warehouse layout with filters to check utilisation by operation type (used for slotting) — [Mecalux: heatmap slotting](https://www.mecalux.com/software/updates/heatmap-slotting)
- Hopstack storage management: a hierarchy tree with expand/collapse-all and search across the tree — [Hopstack help](https://help.hopstack.io/home/enhanced-storage-management)
- Oracle JD Edwards defines a "level of detail" for locations, a hierarchical location code used for drill-down — [Oracle JDE: Level of detail for locations](https://docs.oracle.com/en/applications/jd-edwards/supply-chain-manufacturing/9.2/eoawm/level-of-detail-for-locations.html)
- Mecalux rack-label article: rack labels "play a central role" in organising warehouses (labels on racks and levels match the system's location codes) — [Mecalux: rack labels](https://www.mecalux.com/blog/rack-labels-warehouse)
- WCAG notes that heat maps representing measurements count as "essential" presentation and may be exempt from the 3:1 non-text contrast rule, but colour alone still should not carry the meaning (1.4.1) — [W3C Understanding 1.4.11 Non-text Contrast](https://www.w3.org/WAI/WCAG22/Understanding/non-text-contrast.html)

### Inferences
- SweepScan's elevation (front) view should follow the WMS rack-face grid: columns along X, levels along Y, with the target cell filled amber and its row and column headers also highlighted (a crosshair effect). Then "which column, which level" can be read from the axis labels without counting cells.
- Make labels on the screen match the physical QR column labels exactly (same code text, same order left to right as seen when standing in the corridor). The Mecalux rack-label article and the WMS hierarchy both point to one shared location code between the physical label and the UI.
- A fill or age heatmap is an optional later layer. If added, use a three-step sequential scale with a legend and numeric text in the drawer, rather than a rainbow. Keep it off by default so it does not compete with the amber "target" highlight. Amber means "target" in SweepScan, so do not reuse amber as a heatmap step; that is a conflict to avoid.
- The pattern "tap cell → drawer with SKU / quantity / last-seen" is standard in WMS UIs. On a phone this is a bottom sheet (section 3), not a side panel.
- Hover does not exist on touch screens. Replace it with a pressed-state flash plus the selection outline.

### Gaps
- No public documentation with screenshots was found for Manhattan, Zebra/SOTI, Logiwa, ShipHero or Sortly rack visualisations within the budget. Sortly is folder-based and not map-based as far as known, but this was not verified.
- No colour or hex specifications for rack-face views were found in any WMS documentation.

## 3. Interaction patterns (search, result cards, bottom sheet, zoom-to-result, empty/error states, accessibility, dark palettes)

### Takeaway
The proven mobile pattern is a map on top with a persistent, draggable standard bottom sheet (peek > half > full). Search with suggestions lives at the top or in the sheet. Selecting a result fits the map to the target while keeping it visible above the sheet. Accessibility needs 4.5:1 for text, 3:1 for map shapes and state indicators, 48dp touch targets, and selection that is not shown by hue alone.

### Cited Findings
- Google Maps' place sheet has detents (collapsed/peek, half, full, hidden). It shows progressively more content at each level and keeps a sliver of map visible when fully expanded. Tapping a place opens it half-expanded; tapping the map hides it — [ProAndroidDev: Google Maps-style bottom sheet in Compose](https://proandroiddev.com/building-a-google-maps-style-bottom-sheet-with-jetpack-compose-eccc1f3cf578); [Kotlin Slack thread on Maps sheet states](https://slack-chats.kotlinlang.org/t/5087869/in-google-maps-the-bottom-sheet-has-4-distinct-states-and-th) (secondary sources)
- Map padding must be adjusted as the sheet moves so the sheet does not cover the target or the map controls — [Turo Engineering: adjusting Compose Google Map while bottom sheet moves](https://medium.com/turo-engineering/adjusting-compose-google-map-while-bottom-sheet-moves-4a7465305137)
- NN/g: bottom sheets usually start non-modal in their minimized state and can become modal when expanded — [NN/g: Bottom sheets (mirror)](https://hn.jakechampion.name/reader/https://www.nngroup.com/articles/bottom-sheet)
- Google Maps' 2025 redesign extended sheets to more tabs. Press coverage noted that persistent panels can make the UI feel crowded — [9to5Google: Maps redesign sheets](https://9to5google.com/2025/01/21/google-maps-redesign-sheets/)
- Apple HIG: "Help people find places in your map": combine search with category filters (a mall search might filter by store type) — [Apple HIG: Maps](https://developer.apple.com/design/human-interface-guidelines/maps)
- Material SearchView is a container: put search history (on first expand), suggestions (while typing) or results (after submit) inside it, for example in a RecyclerView. Tapping the SearchBar opens the SearchView with built-in expand/collapse animation when both are in a CoordinatorLayout. Predictive back works automatically. `autoShowKeyboard` defaults to true. Recommended `windowSoftInputMode` is `adjustNothing` — [MDC Android: Search](https://github.com/material-components/material-components-android/blob/master/docs/components/Search.md)
- WCAG 2.2 SC 1.4.11: meaningful graphical objects and visual state indicators (selected, focused) need ≥3:1 against adjacent colours. Ratios are thresholds, so 2.999:1 fails. Changing only the hue to show state is a 1.4.1 (Use of Color) problem — [W3C Understanding 1.4.11](https://www.w3.org/WAI/WCAG22/Understanding/non-text-contrast.html)
- Android accessibility guidance: text contrast ≥4.5:1 for text under 18pt (or bold under 14pt), ≥3:1 for larger text, and 3:1 between surfaces and non-text elements such as icons. Touch targets are ≥48dp; padding plus minWidth/minHeight should total at least 48dp, so a visible element can be smaller — [Android Developers: Accessibility foundations](https://developer.android.com/design/ui/mobile/guides/foundations/accessibility); [Android Developers: Make apps more accessible](https://developer.android.com/guide/topics/ui/accessibility/apps)
- Okabe-Ito (Wong) colour-blind-safe palette: orange #E69F00, sky blue #56B4E9, bluish green #009E73, yellow #F0E442, blue #0072B2, vermillion #D55E00, reddish purple #CC79A7, black #000000. The key rule is to encode information in more than colour (shape, weight, label) — [Okabe-Ito hex reference](https://conceptviz.app/blog/okabe-ito-palette-hex-codes-complete-reference); [ggokabeito manual](https://malcolmbarrett.r-universe.dev/ggokabeito/doc/manual.html)
- Material dark theme (M2 guidance, still the basis of M3 dark): recommended base surface #121212, not pure black. Elevation is shown with a lighter overlay (about 5% at 1dp up to about 16% at the highest level). Use desaturated colours on dark surfaces to reduce eye strain and visual vibration — [Material Design: Dark theme](https://m2.material.io/design/color/dark-theme); [Google Design: Material dark theme](https://design.google/library/material-design-dark-theme)

### Inferences
- SweepScan layout: PlanView fills the top area. Under it is a standard (non-modal) BottomSheet with three detents:
  - Peek (~120–160dp): the one-line answer plus a primary action ("Route" / "Print label").
  - Half: results list and the elevation view.
  - Full: details.
  Keep the target column inside the visible map area above the sheet by padding the zoom-to-fit rectangle by the sheet's current top.
- Zoom-to-result: animate the PlanView matrix (scale plus translate) over about 250–350ms with ease-out (FastOutSlowIn). Fit a rectangle around entrance → target, so the user sees the route and destination together. Then briefly pulse the target (one or two scale or alpha pulses, then stop). This is an inference from common map behaviour; no source gives exact timings.
- Search-as-you-type: debounce about 150–250ms. Show matching locations and products with the matched characters bolded. Show recent searches when the field is empty. Each result row shows the depot chip + location code + item count. If the app later knows "where I am" (for example the last scanned QR), sort by nearest first and show "nearest" on the top card.
- Empty states need explicit copy and an action: "No location for 'XYZ' — scan a label / check spelling / show all depots". Errors and offline: the app is local-first, so show a non-blocking Snackbar only when sync or printing fails. Never blank the map.
- Since there is no positioning, the "you are here" marker is the entrance (door icon plus the label "Giriş"). It is drawn as the route origin with a distinct shape (circle or door glyph) so it differs from the target (filled rectangle plus pin) by shape, not only colour.
- For a dim basement: a dark UI reduces glare, but text must stay at least 4.5:1, and the target and route should be well above 3:1 (aim for 5:1 or more) because phone brightness may be low. Make map labels a few sp larger than usual (14–16sp minimum on the map), since staff glance at the phone while holding items.

### Gaps
- No primary source found for exact zoom-to-result animation durations in Google Maps or Apple Maps. The values above are typical motion-system durations (see section 4), not map-specific specs.
- The NN/g article was read through a mirror, not nngroup.com directly.

## 4. Material 3 guidance relevant here (bottom sheets, search, chips, segmented buttons, app bar, typography, colour roles, motion)

### Takeaway
In Views (MDC 1.8+), the needed parts already exist: a standard BottomSheetBehavior with BottomSheetDragHandleView, SearchBar + SearchView, ChipGroup or MaterialButtonToggleGroup for depot selection, and theme colour roles (surfaceContainer levels, primary/tertiary containers, outline/outlineVariant). Use these roles instead of hard-coded colours wherever possible, and keep map-specific colours as separate tokens.

### Cited Findings
- Standard bottom sheet: no scrim, coexists with the main UI, attached with `BottomSheetBehavior` to a direct child of `CoordinatorLayout`; `behavior_hideable` defaults to false. Modal: `BottomSheetDialogFragment`, scrim, dismissable — [MDC Android: BottomSheet](https://github.com/material-components/material-components-android/blob/master/docs/components/BottomSheet.md)
- Bottom sheet defaults:
  - `behavior_peekHeight` = auto; `behavior_fitToContents` = true; `behavior_halfExpandedRatio` = 0.5.
  - `STATE_HALF_EXPANDED` is used only when `fitToContents` = false.
  - `android:maxWidth` = 640dp; elevation 1dp.
  - Shape `shapeAppearanceCornerExtraLarge`; background `colorSurfaceContainerLow`.
  - The peek height should be tall enough to signal there is more content.
  — [MDC Android: BottomSheet](https://github.com/material-components/material-components-android/blob/master/docs/components/BottomSheet.md)
- `BottomSheetDragHandleView`: default minimum size 48×48dp. TalkBack, Voice Access and Switch Access can expand or collapse the sheet through it; tap cycles expanded/collapsed — [MDC Android: BottomSheet](https://github.com/material-components/material-components-android/blob/master/docs/components/BottomSheet.md)
- Material 3 also defines a standard (non-modal) sheet usable alongside other content, such as a map. Compose Material3 historically shipped only the modal sheet plus `BottomSheetScaffold`, so Maps-style multi-detent sheets in Compose need custom anchored dragging — [Material: Sheets: bottom](https://material.io/components/sheets-bottom); [Composables: bottom sheets in Compose](https://composables.com/blog/bottomsheet) (2023, may be outdated)
- SearchBar defaults: background `colorSurfaceContainerHigh`, `liftOnScrollColor` `colorSurfaceContainerHighest`. SearchBar extends Toolbar (navigation icon plus menu). Give the SearchBar and SearchView the same hint and set contentDescription. SearchView makes siblings unimportant for accessibility while it is open — [MDC Android: Search](https://github.com/material-components/material-components-android/blob/master/docs/components/Search.md)
- M3 dark colour roles (tonal references):
  - primary = primary80; primaryContainer = primary30 (paired with onPrimaryContainer = primary90).
  - tertiary = tertiary80; tertiaryContainer = tertiary30.
  - surfaceContainerLowest/Low/–/High/Highest = neutral4/10/12/17/22.
  - outline = neutral-variant60; outlineVariant = neutral-variant30.
  - Always pair a container with its "on" colour.
  — [MDC Android: Color theming](https://github.com/material-components/material-components-android/blob/master/docs/theming/Color.md)
- M3 baseline dark primary is #D0BCFF (purple). Sources disagree on whether the baseline dark `surface` is #141218 or #1C1B1F (#141218 is confirmed as surface-dim) — [Material colour reference (third-party)](https://abacktools.com/tools/data/dev-utilities/material-design-color-reference); [Compose ColorScheme reference](https://developer.android.com/reference/kotlin/androidx/compose/material3/ColorScheme)
- Material dark theme: `elevationOverlayEnabled` is true in dark themes — [Material Design: Dark theme](https://m2.material.io/design/color/dark-theme); [MDC Android dark theme](https://material.io/develop/android/theming/dark)

### Inferences
- Depot switch: there are three fixed, mutually exclusive options, so a single-select `MaterialButtonToggleGroup` (segmented button) is semantically right for "which depot is the map showing". FilterChips fit better if search results can be filtered across several depots at once. Using both is fine: a segmented control above the map, and chips inside the search results.
- Top bar: replace a plain app bar with a SearchBar (hint: "Ürün veya konum ara"). This puts search in the most prominent place, as Google Maps does.
- Typography (M3 type scale tokens):
  - titleLarge (22sp) for the location code in the sheet header, for example "D2 · 04-C".
  - bodyMedium (14sp) for secondary lines.
  - labelLarge (14sp, medium) for chips and buttons.
  - labelMedium (12sp) only for de-emphasised axis labels.
  - The location code in the result card should be the biggest, most legible text on screen; consider a tabular/monospace face for codes so columns line up.
  - The sp values are well-known M3 defaults; the official m3.material.io page could not be fetched to re-confirm them.
- Colour roles mapping:
  - Sheet: `colorSurfaceContainerLow`. Result cards: `colorSurfaceContainer` / `High`.
  - Target highlight in UI chrome (chip, card accent): `tertiaryContainer`/`onTertiaryContainer` if the theme's tertiary is set to amber. Route: `primary` if primary is set to the route blue.
  - Keep map-specific tokens (floor, rack, wall) in a small `MapPalette` object, so the Canvas and the Views share one source of truth.
- Motion: use standard M3 easing (emphasized decelerate for things entering, about 300–400ms; standard about 200–300ms for state changes). Avoid continuous animation (no endlessly pulsing marker) because it costs battery and distracts. The M3 motion page was not fetched; durations are from general M3 knowledge and should be treated as approximate.

### Gaps
- m3.material.io pages (bottom sheet guidelines, colour roles, type scale, motion tokens, segmented buttons) render with JavaScript and could not be fetched. Values above come from MDC-Android docs or are flagged as general knowledge.
- The exact current M3 baseline dark `surface` hex is unresolved (#141218 vs #1C1B1F).

## 5. Concrete recommendations to make the existing screen look professional

### Takeaway
Restructure to search bar → depot segmented control → full-width plan → standard bottom sheet (answer + elevation view + results). Mute the base map (dark neutral floor, slightly lighter racks with a visible outline, mid-grey walls). Reserve amber for the target only and a casing-backed blue for the route. Remove dashed lines, loud grid lines and card clutter. The palette below was checked against WCAG ratios.

### Cited Findings
- Contrast ratios below were computed with the WCAG relative-luminance formula; thresholds are 4.5:1 for small text, 3:1 for graphics and state — [W3C Understanding 1.4.11](https://www.w3.org/WAI/WCAG22/Understanding/non-text-contrast.html); [Android accessibility](https://developer.android.com/design/ui/mobile/guides/foundations/accessibility)

  | Role | Hex | Measured against | Ratio | Passes |
  |---|---|---|---|---|
  | Primary text | #E6E8EB | floor #1A1D21 / rack #2E353D | 13.8 / 10.1 | text 4.5 |
  | Secondary text | #A9B0B8 | floor / rack | 7.7 / 5.7 | text 4.5 |
  | Target highlight (amber) | #FFB300 | rack #2E353D / floor | 6.9 / 9.4 | graphic 3.0 |
  | Text on amber | #241A00 | amber #FFB300 | 9.6 | text 4.5 |
  | White text on amber | #FFFFFF | amber | 1.8 | FAILS, do not use |
  | Route (Okabe-Ito sky blue) | #56B4E9 | floor / rack / canvas #0E1013 | 7.3 / 5.4 / 8.3 | graphic 3.0 |
  | Wall line | #8B95A1 | floor | 5.6 | graphic 3.0 |
  | Rack outline | #7A8591 | floor / rack fill | 4.5 / 3.3 | graphic 3.0 |
  | Rack fill alone | #2E353D | floor | 1.36 | fails, so needs the outline |
  | Column divider | #4A535E | rack fill | 1.6 | decorative only, OK |
  | Entrance marker (green) | #3DDC97 | floor | 9.6 | graphic 3.0 |
  | Okabe-Ito bluish green | #009E73 | floor | 4.9 | graphic 3.0 |
  | Amber vs route blue | #FFB300 vs #56B4E9 | each other | 1.29 | luminance-close; separated by hue (orange/blue is CVD-safe) and by shape |

- Okabe-Ito orange/sky-blue are designed to stay distinguishable under common colour-vision deficiencies. SweepScan's current amber/cyan pair is close to these — [Okabe-Ito reference](https://conceptviz.app/blog/okabe-ito-palette-hex-codes-complete-reference)
- Apple: use a muted base map when you have information-rich overlays; mark the selection with an outline plus a colour change — [Apple HIG: Maps](https://developer.apple.com/design/human-interface-guidelines/maps)
- Mapbox: route casing is wider than the line to give a border effect — [Mapbox Route line](https://docs.mapbox.com/android/navigation/guides/ui-components/route-line)
- Material dark: avoid pure black, use about #121212-level surfaces, and desaturate — [Material Design: Dark theme](https://m2.material.io/design/color/dark-theme)

### Inferences
**Layout (top → bottom)**
1. `SearchBar` (hint "Ürün / konum ara") + `SearchView` for suggestions and recent searches.
2. Depot `MaterialButtonToggleGroup` (D1 | D2 | D3), 40–48dp tall, single selection required.
3. PlanView fills the remaining height. Overlay a small "fit/recenter" FAB (48dp) at the bottom-right above the sheet peek. No permanent legend; at most a one-line legend chip ("■ hedef  ━ rota  ● giriş") that can be dismissed.
4. Standard bottom sheet with a drag handle:
   - Peek: the location code in large type + depot + "N ürün" + buttons [Rota] [Etiket yazdır].
   - Half: the elevation (front) view of that rack row with the target cell, then other matches.
   - Full: item details and history.
   - Tapping a rack or column on the plan updates the sheet (tap-to-inspect).

**PlanView styling**
- Canvas/outside #0E1013. Floor/corridors #1A1D21.
- Walls: 2–3dp stroke #8B95A1, square joins, no grid lines on the floor. Remove the background grid, or keep it at under 1.3:1 contrast and only while editing the layout.
- Racks: fill #2E353D, 1.5dp outline #7A8591, corner radius 2–3dp. Column dividers are hairlines #4A535E (decorative).
- Column labels (A, B, C… or numbers) appear inside slots only when the zoom makes them at least 11–12sp; otherwise hide them (zoom-based level of detail, Apple HIG).
- Row-number badges in corridors: small pill (`surfaceContainerHigh`-like #2A2F35 fill, #E6E8EB text, 12–14sp medium) placed at the aisle mouth nearest the entrance. Use one badge per row, not repeated.
- Target column: fill #FFB300, 2.5dp outline #FFE08A (or a dark 2dp inner stroke so it stays crisp), label in #241A00. Also draw a small pin or chevron above the rack in amber with the code text. This gives colour + outline + label, three cues.
- Other matches (secondary results): amber outline only, no fill, or 40% amber fill. Non-target racks in the active row stay neutral. Optionally dim racks in other rows by about 15% (Apple "supplemental" dimming) when a target is selected.
- Route: 5dp line #56B4E9 over a 8dp casing #0E1013, round caps and joins, corners filleted (radius about 6dp). Start with the entrance marker (green #3DDC97 circle or door glyph, label "Giriş"). End with an arrowhead or dot right at the column face, not in the middle of the rack. Draw it solid; drop the dashed variant, or use dashes only for "approximate" segments.
- Entrance: always visible, and drawn on top so it cannot be hidden by a rack.

**Elevation (front) view**
- Grid of columns × levels, with the same rack fill and outline as the plan. Column header row on top and level numbers on the left, in labelMedium #A9B0B8.
- The target cell is amber with dark text. Its column header and level number change to amber-on-dark (crosshair cue).
- Draw a floor line under level 1 and number levels from the floor up, the way people count physically. Make that order clear with a small "↑ Kat" axis label.

**What to remove or reduce**
- Dashed or solid duplicate route styles. Use one route style.
- Background grid. Heavy card shadows and borders on results (M3 dark uses tonal surfaces, not shadows). Saturated colour on non-interactive elements. Multiple competing badges. Any white text on amber.
- Pure-black (#000000) backgrounds next to bright amber, which causes halation and glare in the dark. Use #0E1013–#1A1D21.

**Interaction details**
- After a search selection: switch depot if needed. Animate fit-to-bounds (entrance + target) over about 300ms. Open the sheet at peek. Pulse the target once.
- Double-tap to zoom, pinch-zoom with min/max scale clamped, and pan clamped so part of the plan always stays visible (Apple: "limit scrolling outside your venue").
- All tappable things (racks, chips, FAB, sheet handle) need at least 48dp hit areas, even if drawn smaller. Expand Canvas hit-testing rectangles with padding.
- TalkBack: give PlanView a contentDescription summary such as "Depo 2, hedef sıra 4 kolon C, girişten 12 metre", or make the sheet text carry it. Implement `ExploreByTouchHelper` for virtual rack nodes if time allows.

### Gaps
- The proposed hex values are a designed palette based on cited principles, not values copied from any shipped indoor-map product. They should be checked on the actual phone in the basement at low brightness. The measured ratios assume sRGB and an ideal screen.
- No source gives an exact distance-based "nearest" ranking for shelf-level indoor search without positioning; ordering by the last-scanned location is an inference.
