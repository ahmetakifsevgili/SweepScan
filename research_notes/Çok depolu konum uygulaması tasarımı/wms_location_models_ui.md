# How inventory/WMS apps model warehouses and locations, and how they answer "where is this item"

Context: notes for a small Android app for a shoe shop with three stock rooms (a big depot with about 9 rows of wall racks plus 2 islands around a corridor, a small depot, and a shared-use depot). The app answers "where is model 0159 2000?" at model level (row + column), not full stock. Research date: 2026-11-17. Versions are noted where the sources show them.

## 1. Location data models: InvenTree, Odoo, ERPNext, Snipe-IT, Sortly, Zoho, inFlow, Fishbowl

### Takeaway
Almost every system uses one self-referencing tree of locations (a parent pointer plus a computed full path). The levels (warehouse, room, row, shelf, bin) are a naming convention, not fixed columns. Two ideas recur: a flag that marks a node as "container only, cannot hold stock" (InvenTree structural, Odoo view, ERPNext is_group), and a scannable barcode on each location. Stock is usually linked to a leaf location through a separate quantity record (quant, bin, stock item) per product or variant. The location itself never lists products.

### Cited Findings
**InvenTree (docs "latest", late 2026)**
- Stock locations are "arranged in a cascading manner". Each can contain sub-locations, stock items, or both. — [InvenTree docs: Stock](https://docs.inventree.org/en/latest/stock/)
- Optional **structural** flag: the location organizes the tree and holds no stock directly, for example a shelf or drawer whose items sit in bins below it. — [InvenTree docs: Stock](https://docs.inventree.org/en/latest/stock/)
- The model code refuses to make a location structural while stock items sit in it. — [InvenTree source mirror (models.py)](https://git.invenhost.com/inventree/InvenTree/src/commit/8972dcb50609ba77715e5b4cde2c6c73071be4c9/InvenTree/stock/models.py)
- Optional **external** flag: items there may not be available right away. The flag is not inherited by sub-locations. It was added around API v97 (2023). — [InvenTree docs: Stock](https://docs.inventree.org/en/latest/stock/); [InvenTree docs 1.3.x](https://docs.inventree.org/en/1.3.x/stock/)
- **StockLocationType** is a reusable type such as "Warehouse, room, shelf, drawer". Its main job is to give every location of that type the same icon (Tabler icons, format `ti:bookmark:outline`). A planned future use is drawer dimensions for a "find a matching, empty stock location" tool, which is not yet available. — [InvenTree docs: Stock](https://docs.inventree.org/en/latest/stock/)
- A Part links to locations only through its Stock Items, each of which has a Location field. A location does not list parts directly. — [InvenTree docs: Stock](https://docs.inventree.org/en/latest/stock/)
- Mobile app barcodes:
  - A global "Scan Barcode" opens whatever object the code is linked to, including a location.
  - "Assign Barcode" links a custom code to a location. The code must be unique in the database.
  - "Scan Items Into Location" works from the location page, and "Scan Into Location" works from an item page.
  - "Transfer Stock Location" moves a whole location under a new parent by scanning the target.
  - Scanning works with the camera or a keyboard-wedge scanner that sends Enter after each code.
  - [InvenTree docs: App barcodes](https://docs.inventree.org/en/latest/app/barcode/)

**Odoo (18.0 source; 19.0 is also current)**
- `stock.location` fields:
  - `name`, `complete_name` (stored full path)
  - `location_id` (Parent Location), `child_ids`, `parent_path` (`_parent_store`)
  - `usage` (Location Type)
  - `barcode`
  - `posx`/`posy`/`posz`, labelled "Corridor (X)", "Shelves (Y)", "Height (Z)", which are informational only
  - `removal_strategy_id` (FIFO/LIFO/closest/FEFO), `putaway_rule_ids`, `storage_category_id`
  - `quant_ids`, `warehouse_id` (computed), `cyclic_inventory_frequency`, `is_empty`
  - [odoo/odoo 18.0 stock_location.py](https://raw.githubusercontent.com/odoo/odoo/18.0/addons/stock/models/stock_location.py)
- `usage` values: `supplier`, `view` (a container that cannot hold products), `internal`, `customer`, `inventory` (loss), `production`, `transit`. — [odoo/odoo 18.0 stock_location.py](https://raw.githubusercontent.com/odoo/odoo/18.0/addons/stock/models/stock_location.py)
- `complete_name` is the parent's complete_name + "/" + name. A view location with no parent uses only its name. Barcodes are unique per company (`barcode_company_uniq`). A new location's barcode defaults to its complete_name, and name search also matches barcodes. — [odoo/odoo 18.0 stock_location.py](https://raw.githubusercontent.com/odoo/odoo/18.0/addons/stock/models/stock_location.py)
- Example path in the docs: `WH/Stock/Zone A/Refrigerator 1`. Sub-locations must be switched on first (Inventory > Configuration > Settings > Storage Locations). — [Odoo 18 docs: Locations](https://www.odoo.com/documentation/18.0/applications/inventory_and_mrp/inventory/warehouses_storage/inventory_management/use_locations.html)
- Putaway: `_get_putaway_strategy` filters rules by product, product category (including parent categories) and package type. It weighs the current and incoming quantities in child locations against the storage category limits. It falls back to the location itself, or to the first child if the location is a view. — [odoo/odoo 18.0 stock_location.py](https://raw.githubusercontent.com/odoo/odoo/18.0/addons/stock/models/stock_location.py)

**ERPNext / Frappe**
- Warehouse is a tree. "Is Group" lets a warehouse hold child warehouses. A non-group warehouse can be converted to a group. A Tree view shows the whole hierarchy. — [ERPNext docs: Warehouse](https://docs.frappe.io/erpnext/user/manual/en/warehouse)
- The docs suggest modelling detail as a deeper tree: "Warehouse > Room > Row > Shelf > Bin". — [Frappe forum, summarising docs](https://discuss.frappe.io/t/warehouse-hierarchy-tree-nested-set/5677)
- The **Bin** doctype is not a physical bin. It is a per item+warehouse ledger summary (actual qty, ordered qty, valuation). — [Frappe forum: Warehouse Bin System](https://discuss.frappe.io/t/warehouse-bin-system/31299); [Frappe forum: Aisle, row and bins](https://discuss.frappe.io/t/aisle-row-and-bins-in-warehouse/33702)
- One user with about 20,000 warehouse nodes (a leaf per bin) found the tree unusable for picking a delivery warehouse on sales orders. Users still ask for native aisle/bay/tier/bin support. — [Frappe forum: Warehouse Bin System](https://discuss.frappe.io/t/warehouse-bin-system/31299); [Frappe forum: Aisle/Bay/Tier/Bin](https://discuss.frappe.io/t/warehouse-bin-locations-aisle-bay-tier-bin/149483)

**Snipe-IT**
- Locations have an optional parent. Circular references are blocked. A location shows its direct children and all descendants, and locations can be searched and sorted by parent name. (Source is a third-party Mintlify mirror of the Snipe-IT docs.) — [Snipe-IT locations (mintlify mirror)](https://www.mintlify.com/grokability/snipe-it/features/locations)

**Sortly**
- "Locations = folders". Folders nest (for example a folder per warehouse with a subfolder per shelf), and items live inside them. "Move to Folder" can move part of an item's quantity. To keep the same item in several places you clone it, optionally linked to the same Sortly ID. — [Sortly: Product Overview](https://help.sortly.com/hc/en-us/articles/360036515592-Sortly-Product-Overview); [Sortly: Can an item be in multiple locations?](https://help.sortly.com/hc/en-us/articles/40242610430875-Can-an-Item-Be-in-Multiple-Locations); [Sortly developer docs: folders](https://developer.sortly.com/folders.md)
- "Item Variants" is listed as an item field, but the docs found do not say how variants relate to folders. — [Sortly: How to Add Items](https://help.sortly.com/hc/en-us/articles/360042658292)

**Zoho Inventory** (community announcement posts, not the official help pages)
- Each warehouse has zones; by default a Receiving zone and a Packing zone exist. Each zone can have up to 5 levels, and the lowest level is the bin.
- Bin locations are switched on per warehouse and cannot be switched on once the warehouse has transactions. Each item needs "Track Bin Location" ticked.
- Limits are 2,000 bins per warehouse on Premium and 5,000 on Enterprise, more with an add-on. Transfer orders show the source and destination bins.
- [Zoho community: Introducing Bin Locations](https://help.zoho.com/portal/en/community/topic/introducing-bin-locations?page=3)

**inFlow (Cloud)**
- "Locations should represent buildings, and sublocations should represent the shelves/aisles/bins within a specific building." Sublocations are a flat level under a location, on some plans only. A product can have its own default location, which overrides the system default. — [inFlow: multiple locations](https://inflowinventory.com/support/cloud/how-do-i-handle-inventory-in-multiple-locations); [inFlow: aisle/bin numbers](https://www.inflowinventory.com/support/cloud/can-i-track-specific-locations-like-aisle-numbers-bin-numbers-etc)
- inFlow On-Premise was sunset on 31 July 2024. — [inFlow On-Premise support](https://onpremise.inflowinventory.com/support/article/37438990/how-do-i-handle-inventory-in-multiple-locations/)

**Fishbowl**
- Two levels: a **Location Group** (warehouse, store or other large place) holds **Locations** (shipping, repair, inspection, storage, storefront and similar). Both can be imported and exported as CSV, and there is a REST API for location groups. — [Fishbowl blog: Location Group module](https://fishbowlinventory.com/blog/exploring-the-location-group-module); [Fishbowl: import/export locations](https://help.fishbowlinventory.com/advanced/s/importLocations.html)

### Inferences
- The model that transfers best to SweepScan is one self-referencing `Location(id, parent_id, name, code, kind, barcode, sort_order)` table:
  - `kind` is a small enum: depot / row / column.
  - A computed path such as `BIG/R03/C05` works like Odoo's complete_name.
  - A "container only" flag stops someone assigning a model to "Row 3" when they meant a column.
- Three levels (depot > row > column) are enough. Adding level or bin would repeat the ERPNext problem, a huge tree that slows data entry for little gain at model level.
- Variants: Odoo, InvenTree and ERPNext all attach quantity records (quant, stock item, bin) to a specific product or variant at a leaf location. For SweepScan's model-level question, a simple `ModelPlacement(model_code, location_id, note, updated_at)` link table, many-to-many, does the same job without quantities. A model can sit in two columns, like Sortly's multi-folder items.
- Zoho's "receiving zone" and Odoo's Input/Output are a useful idea: a pseudo-location such as "unsorted / just arrived" in each depot, for boxes not yet shelved.

### Gaps
- I did not verify current InvenTree StockLocation field names from source (for example `pathstring`, `location_type`, `custom_icon`, and whether a Part has a `default_location`). I also did not confirm how InvenTree Part templates and variants relate to stock locations.
- I did not open ERPNext's Warehouse doctype JSON for exact field names, beyond is_group and parent_warehouse being implied by the tree.
- I could not confirm whether Sortly has a nesting depth limit (one snippet looked garbled) or how its variants relate to folders.
- Snipe-IT details come from a mirror, not the official docs.

## 2. How "where is X" is shown: lists, codes, maps, 3D views and retail aisle finders

### Takeaway
Business inventory tools answer "where is X" with text: a breadcrumb path or location code, plus a list of the locations holding the item with a quantity each. Odoo Community has no floor-plan view; 3D views come only from paid or third-party modules. Visual maps are mainly a consumer retail feature (Home Depot, Lowe's, Walmart). They show a pin on the store map plus a short aisle/bay code, and that pairing (big code + small highlighted map) suits SweepScan best.

### Cited Findings
- **Sortly**:
  - The Items search shows each result's folder path above it (for example "All Items > Warehouse 1").
  - The Inventory Summary report has a Folder column that lists every folder holding the item, with the quantity in each.
  - [Sortly: View Item Locations](https://help.sortly.com/hc/en-us/articles/42092162271387-View-Item-Locations-in-Sortly)
- **Odoo native**: locations show as text paths (`WH/Stock/Zone A/...`). The `posx/posy/posz` fields exist but are informational only. — [odoo/odoo 18.0 stock_location.py](https://raw.githubusercontent.com/odoo/odoo/18.0/addons/stock/models/stock_location.py). A vendor says native Odoo has "only a flat text-based location tree" with no spatial view. This is marketing; ECOSIRE sells a build-to-order 3D module from $249. — [ECOSIRE Stock 3D View](https://ecosire.com/apps/odoo/stock-3d-view)
- **Odoo "Stock 3D View" by Cybrosys** (LGPL-3; versions 15.0 to 19.0; the 18.0.1.0.0 release is dated 5 June 2026):
  - You give each location a code, capacity, dimensions and an X/Y/Z position. A "3D" button opens a per-warehouse WebGL scene, with a dropdown to switch warehouses.
  - Blocks are see-through and coloured by load against capacity.
  - Opening one location highlights it inside the warehouse. Double-click or double-tap shows its current stock and lots.
  - Controls: left-drag rotates, right-drag moves, scroll zooms.
  - Recommended hardware is 16 GB+ RAM and a GPU with 4 GB.
  - [Odoo Apps: stock_3d_view 18.0](https://apps.odoo.com/apps/modules/18.0/stock_3d_view)
- **Odoo "Warehouse Visualizer"** (18.0.1.0.0): an interactive WebGL 3D floor plan with a side panel of demand progress bars and picked vs required quantities per product. — [Odoo Apps: warehouse_visualizer](https://apps.odoo.com/apps/modules/18.0/warehouse_visualizer)
- Odoo talks on 3D location views go back to Odoo Experience 2017 and 2020, so the idea is old but has never been part of core Odoo. — [OXP 2017 talk](https://www.odoo.com/it_IT/event/odoo-experience-2017-692/track/visualize-your-warehouse-locations-at-a-glance-back-to-a-3d-world-857); [OXP 2020 talk](https://www.odoo.com/event/odoo-experience-2020-2622/track/3d-representation-of-multi-warehouse-locations-2116)
- **Home Depot app**:
  - Product Locator: pick a store, search for a product, tap the map, and the app gives the aisle and bay with one tap, along with the store's stock level.
  - The Magic Apron AI assistant shows store maps with aisle and bay positions. It is reachable from Store Mode or by scanning QR codes in the store, and the app knows each store's layout.
  - The product page itself returned 403; these details come from search snippets plus the corporate release.
  - [Home Depot: Find everything in store](https://www.homedepot.com/c/ai/find-everything-you-need-inside-the-home-depot-store/9ba683603be9fa5395fab901c3a65572); [Home Depot corporate: Magic Apron expansion](https://corporate.homedepot.com/news/company/home-depot-expands-magic-apron-deliver-personalized-localized-store-guidance)
- **Lowe's**:
  - Product locator launched in 2013 on Point Inside's platform. The aisle and/or bay of every in-stock item shows as a pin on an indoor map of that store, and a bay is about a two-foot section of an aisle.
  - One later review says the app now lists aisle and bay as text and used to show a map pin, so the current map feature may vary.
  - [PR Newswire 2013](https://prnewswire.com/news-releases/lowes-introduces-product-locator-mobile-technology-to-make-shopping-easier-233605101.html); [Computerworld](https://www.computerworld.com/article/2486318/app-helps-lowe-s-shoppers-find-products-without-asking.html)
- **Walmart**: Store Map launched in 2018 with the layouts of all 4,700+ US stores. Every item is mapped to aisle and shelf area, a searched item is shown as a pin, and the map also marks landmarks (pharmacy, pickup, restrooms). — [Chain Store Age](https://chainstoreage.com/technology/discounter-turns-app-store-assistant); [Geospatial World case study](https://geospatialworld.net/prime/case-study/location-and-business-intelligence/walmart-uses-location-based-technologies-to-improve-in-store-navigation-for-shoppers-3/)
- Store maps can go stale after remodels or seasonal resets. — [Supermarket News NZ](https://supermarketnews.co.nz/global/international-news/store-maps-help-customers-find-products/)

### Inferences
- Answer layout for SweepScan, based on the retail pattern:
  1. **Big location code first**, for example "BÜYÜK DEPO · Sıra 3 · Kolon 5" (big depot, row 3, column 5) or `B-03-05`. Workers act on the code; the map only confirms it.
  2. Below it, a **small 2D top-down schematic** of that depot. Highlight the target row (wall-rack segment or island) and draw a pin or filled cell at the column. Use landmarks the staff know, such as the door, the corridor and the islands, the way Walmart marks the pharmacy.
  3. If the model is in several places, a short list grouped by depot, like Sortly's Folder column.
  4. Optionally, a rack front view of the row with the column number highlighted.
- Skip 3D. The Odoo 3D modules need desktop-class GPUs and position data for every location, which is far too heavy for one shop with no IT. A static vector schematic (a grid of rects in SVG or Compose Canvas) built once per depot is enough.
- The Home Depot "QR codes in store" pattern fits SweepScan's QR column labels well. Scanning a column label could open that column's contents (InvenTree's "Scan Barcode" opens the location page), so lookup works both ways: item to place, and place to items.

### Gaps
- I did not find documented UI for the Odoo Barcode app's location display (source and destination prompts during a transfer).
- I did not find details of Shopify POS's stock-location features, or of SaaS tools named ShelfMap or "Warehouse Map", in this pass.
- Screenshots or details of current (2026) Home Depot, Lowe's and Walmart map UIs (pin style, route lines) were not available. The Home Depot page was blocked (403) and the Lowe's and Walmart sources are from 2013 to 2018. None of the sources mentions turn-by-turn paths inside stores.
- I did not check whether InvenTree's web UI or app has any map or floor-plan feature. Its docs only describe the tree and barcodes.

## 3. Location code conventions: format, numbering direction, odd/even sides, small rooms

### Takeaway
The common practice is a fixed-width, separated, hierarchical code (Zone-Aisle-Bay-Level-Bin) with zero-padded parts. Levels are numbered from the floor up. Odd and even numbers usually go on opposite sides of an aisle, but sources disagree on which side is which. Serpentine numbering is favoured for picking routes, but it adds mental load without scanners. For small sites, start with zone + aisle + rack only, and always print the full code on every label.

### Cited Findings
- **Picqer**:
  - Separate the parts with dots or dashes (03.05 or 03-05), never 0305. Zero-pad every part. Skip the zone or shelf levels if unused.
  - Odd numbers on the right and even on the left, like house numbers, so a sorted list gives a sensible walk route. Number shelves from bottom to top. Number aisles, not individual rack rows.
  - Examples: `03.05` (single room, aisle 03, rack 05), `B.02.07` (zone B), `B.02.05.3.03` (zone, aisle, rack, shelf, bin), `A.05.12` (floor location).
  - Small warehouses: start partial with zones, aisles and racks (about one day of work), make aisle numbers visible from the walkway, and keep one unique product per location.
  - Always print the full code on the label ("A.05.12", not "12").
  - [Picqer blog: warehouse location numbers](https://picqer.com/en/blog/warehouse-location-numbers)
- Serpentine is "generally recommended" so pickers weave through aisles in one continuous flow. However, it adds mental load on paper because even/odd no longer maps simply to left/right; scanners and voice picking reduce that cost. — [ShipBob: location numbering](https://www.shipbob.com/blog/warehouse-location/); [Cleverence: aisle numbering](https://www.cleverence.com/articles/for-business/warehouse-aisle-numbering-4827/)
- Cleverence describes the reverse odd/even convention at some sites (even on the left, odd on the right, facing "north" or the dock doors), which conflicts with Picqer. If you switch to serpentine later, remap only new aisles or plan a weekend cutover with preprinted labels. — [Cleverence: aisle numbering](https://www.cleverence.com/articles/for-business/warehouse-aisle-numbering-4827/)
- Infor WMS: bays get odd or even numbers by aisle side, and the lowest level number is nearest the floor. — [Infor docs: Location assignments](https://docs.infor.com/csdis/2026.x/en-us/useradminlib_csd_cloud/twlsag/odn1549376315989.html)
- ShipHero: number shelves from the floor up, so higher shelves can be added later without relabelling. — [ShipHero help](https://software-help.shiphero.com/hc/en-us/articles/20747862305421)
- ChannelDock: in narrow one-way aisles, label the two facing sides odd and even to get a natural zig-zag pick path. — [ChannelDock: streamlining your warehouse](https://help.channeldock.com/en/articles/13750574-streamlining-your-warehouse)
- CamCode: use two digits for the aisle, bay and level fields so the scheme scales past 9. A cited example code is `A04-03-b-03` (zone A, aisle 04, rack 03, shelf b, bin 03). — [CamCode: how to label a warehouse rack](https://camcode.com/blog/how-to-label-a-warehouse-rack/)
- Odoo builds location barcodes from the 5-character warehouse code + suffix (for example `WHSTOCK`). A new location's barcode defaults to its full path. — [odoo/odoo 18.0 stock_warehouse.py](https://raw.githubusercontent.com/odoo/odoo/18.0/addons/stock/models/stock_warehouse.py); [stock_location.py](https://raw.githubusercontent.com/odoo/odoo/18.0/addons/stock/models/stock_location.py)

### Inferences
- Suggested SweepScan code is `<depot letter>-<row 2 digits>-<column 2 digits>`, for example `B-03-05` (Büyük, the big depot), `K-01-04` (Küçük, the small depot), `O-02-01` (Ortak, the shared depot). Dash separators, zero-padding and the full code on every QR label follow Picqer and CamCode.
- In the big depot the wall racks run around a corridor, so "odd left, even right" has no natural meaning. It is simpler to number rows 01 to 09 clockwise from the door, the same direction for every row, and give the islands their own row numbers (for example 10 and 11, or I1 and I2). Columns run left to right as you face the rack. This avoids the serpentine mental-load problem that Cleverence describes for paper or no-scanner use.
- Keep levels (shelf height) out of the code, since the app answers at column level. If needed later, add a suffix (`B-03-05-2`) instead of renumbering, following the floor-up advice that lets you extend without relabelling.
- Make sure the labels and the app's map agree on direction; an on-screen schematic with a "door" marker resolves any left/right ambiguity.

### Gaps
- I found no source specific to retail stock rooms or shoe stores (as opposed to warehouses) on numbering perimeter wall racks around a corridor. The recommendation above is inferred.
- The odd/even side convention is disputed (Picqer vs Cleverence); there is no single standard.

## 4. Multi-warehouse representation and transfers

### Takeaway
There are two patterns. In ERP-style systems (Odoo, Fishbowl, inFlow, Zoho) a warehouse or site is its own entity with an address and short code, its own location subtree, and transfers between sites, sometimes through a transit location. In tree-only systems (InvenTree, Snipe-IT, Sortly, ERPNext) a warehouse is just a top-level node, and a transfer is a move from one node to another. For three rooms in one shop with no accounting, top-level nodes are enough.

### Cited Findings
- **Odoo `stock.warehouse` fields**:
  - `name`; `code` (required, max 5 characters, unique per company, used for location barcodes and sequence prefixes).
  - `view_location_id` (the required parent view location of all the warehouse's locations, named after the code).
  - `lot_stock_id` ("Stock").
  - `resupply_wh_ids` (warehouses this one is resupplied from; routes are created automatically for each ticked warehouse).
  - [odoo/odoo 18.0 stock_warehouse.py](https://raw.githubusercontent.com/odoo/odoo/18.0/addons/stock/models/stock_warehouse.py)
- Odoo creates child locations automatically: Stock always; Input, Quality Control, Output and Packing Zone depending on how many receipt and delivery steps are set. Inter-warehouse resupply goes through the company's `internal_transit_location_id`, or an inter-company transit location between companies. — [odoo/odoo 18.0 stock_warehouse.py](https://raw.githubusercontent.com/odoo/odoo/18.0/addons/stock/models/stock_warehouse.py)
- In the Odoo docs a warehouse is "a physical space with an address". Required fields are Warehouse name, Short Name (≤5 characters, default `WH`), Address and Company. Stock moves between warehouses are stock moves or routes, and a "Resupply From" field is available. — [Odoo 18 docs: Warehouses](https://www.odoo.com/documentation/18.0/applications/inventory_and_mrp/inventory/warehouses_storage/inventory_management/warehouses.html)
- inFlow: locations are buildings and sublocations are shelves or aisles. Purchase-order lines can each be received into a different location. — [inFlow: multiple locations](https://inflowinventory.com/support/cloud/how-do-i-handle-inventory-in-multiple-locations)
- Fishbowl: Location Groups (warehouse or store) contain Locations. — [Fishbowl blog](https://fishbowlinventory.com/blog/exploring-the-location-group-module)
- Zoho: bins are per warehouse, and transfer orders record the source and destination bins. — [Zoho community: Bin Locations](https://help.zoho.com/portal/en/community/topic/introducing-bin-locations?page=3)
- InvenTree: "Transfer Stock Location" re-parents a whole location by scanning the target, and stock items are moved by scanning into a location. — [InvenTree docs: App barcodes](https://docs.inventree.org/en/latest/app/barcode/)
- Sortly: moving part of a quantity uses "Move to Folder". — [Sortly: Can an item be in multiple locations?](https://help.sortly.com/hc/en-us/articles/40242610430875-Can-an-Item-Be-in-Multiple-Locations)

### Inferences
- For SweepScan, the three depots can be top-level `Location` rows (kind = depot) with a short code and a display name. A separate Warehouse entity is unnecessary: there is no address, accounting or route logic, and the shop's DİA ERP stays the system of record for stock.
- A "transfer" in SweepScan is just re-pointing a model's placement. Log it as a move event (model, from, to, who, when), much like InvenTree's scan-into-location, so stale placements can be traced. No transit location is needed because the rooms are steps apart.
- The shared-use depot could carry an InvenTree-style "external" flag ("may not be immediately available or may be moved by others") and show a badge in results.

### Gaps
- I did not check how DİA (the shop's ERP) models depots and warehouses. That is outside this scope but relevant for any sync.

## 5. Open-source floor-plan and warehouse visualisation projects

### Takeaway
I found no mature, widely used open-source "rack map" component. The candidates are a small MIT Vue 3 grid-blueprint component (grid-plan), an AI layout generator (WDA), a structural-steel demo app (VIKTOR), and an LGPL-3 Odoo 3D module. For an Android app, drawing a simple per-depot grid natively is likely simpler than adopting any of them.

### Cited Findings
- **graphieros/grid-plan**: "A lightweight Vue3 component to create dynamic blueprints" for rooms, datacenters and racks.
  - Features: place, resize and delete typed blocks on a grid; a config object for size, colours and a 3D view; slots for icons, menu and inventory; read-only mode.
  - Built with Vue 3, Three.js (peer dependency) and TypeScript.
  - Licence MIT; 107 stars, 7 forks, 60 commits; no releases. I could not confirm the date of the last commit.
  - [GitHub: graphieros/grid-plan](https://github.com/graphieros/grid-plan)
- **shucheng-ai/WDA-main**: an AI tool that generates warehouse layout designs in 2D and 3D. It reads CAD drawings to find walls, pillars and doors. Licence not confirmed. — [GitHub: WDA-main](https://github.com/shucheng-ai/WDA-main)
- **viktor-platform/warehouse-configurator**: a VIKTOR sample app that generates a 3D warehouse structure, focused on the steel structure rather than racks. Last pushed in 2024. — [GitHub: warehouse-configurator](https://www.github.com/viktor-platform/warehouse-configurator); [ecosyste.ms entry](https://awesome.ecosyste.ms/projects/github.com%2Fviktor-platform%2Fwarehouse-configurator)
- **Cybrosys stock_3d_view** (Odoo addon, LGPL-3, about 30.7k lines of code, versions 15 to 19). See section 2. — [Odoo Apps](https://apps.odoo.com/apps/modules/18.0/stock_3d_view)
- **Sweet Home 3D** is an open-source interior design app (not on GitHub) that some people repurpose for basic warehouse layouts. This comes from a review aggregator of low-to-medium reliability. — [Gitnux: warehouse layout software](https://gitnux.org/best/warehouse-layout-software/)
- rackify.io (jbwod) is a server-rack (datacenter) planner, not a warehouse tool. — [GitHub: rackify.io](https://github.com/jbwod/rackify.io)

### Inferences
- Nothing found is an Android or Kotlin library. A good fit for SweepScan is a hand-built depot schematic, either one JSON per depot (rows as rectangles with x, y, w, h and a column count) drawn with Compose Canvas, or SVG. grid-plan's data shape (typed blocks on a grid with a read-only mode) is a reasonable model for the format even if its Vue code is not reused.
- The MIT licence (grid-plan) allows borrowing ideas or code. LGPL-3 Odoo modules can't simply be embedded in a closed Android app.

### Gaps
- I did not search GitHub directly (by stars or topic) for "warehouse-map", "floor-plan editor" or "indoor map Android" libraries. Recent activity and licences for WDA, and recency for grid-plan, are unverified.
- I did not research indoor-map SDKs (for example Mapwize/Situm or open-source indoor map renderers); they are probably overkill here.
