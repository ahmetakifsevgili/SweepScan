# Storage/sharing options for a stock-room "model -> row/column" map, and DİA ERP shelf-location support

## Q1. Practical ways to store and share the location map (phone DB + export, Google Sheets, Firebase, Supabase, InvenTree, static page)

### Takeaway
For one phone and one or two writers in a shop with no IT staff, the low-risk path is: keep the map in an on-phone database (Room/SQLite) as the source of truth, and publish a read-only copy for coworkers (a CSV/Excel share, or a push to one Google Sheet). Firestore free tier covers this data volume many times over and has offline sync built in, but it adds a Google Cloud project, auth and security rules to maintain. Supabase free projects pause after about 7 days of low activity, which suits a shop that may go a week without scanning badly. InvenTree is a full self-hosted inventory server and is too heavy for this.

### Cited Findings

**Google Sheets API (from the Android app directly)**
- Limits: 300 read requests and 300 write requests per minute per project, and 60 per minute per user per project. A batch request counts as one request, including its subrequests. Going over returns HTTP 429, and the request can be retried after a minute. — [Google Sheets API usage limits](https://developers.google.com/workspace/sheets/api/limits)
- Normal use costs nothing. The page says Google plans to start charging for usage over quota to a Cloud billing account later in 2026. — [Google Sheets API usage limits](https://developers.google.com/workspace/sheets/api/limits)
- Google recommends exponential backoff (min(2^n + random ms, max_backoff), with a max backoff of about 32-64 s) and requests of 2 MB or less. — [Google Sheets API usage limits](https://developers.google.com/workspace/sheets/api/limits)

**Google Apps Script (as a small web app or endpoint the phone posts to)**
- Consumer (gmail.com) quotas: 6 min runtime per execution, 90 min/day total trigger runtime, 20,000 URL Fetch calls/day, 30 simultaneous executions per user (1,000 per script). — [Apps Script quotas](https://developers.google.com/apps-script/guides/services/quotas)
- The quota page has no web-app-specific limits (see Gaps).

**Firebase Cloud Firestore**
- Free quota: 1 GiB stored, 50,000 document reads/day, 20,000 writes/day, 20,000 deletes/day, 10 GiB/month outbound transfer. Daily quotas reset around midnight Pacific time. Each project gets exactly one free database. — [Firestore quotas](https://firebase.google.com/docs/firestore/quotas)
- TTL deletes, point-in-time recovery, backups, restore and clone are not free and need billing turned on. — [Firestore quotas](https://firebase.google.com/docs/firestore/quotas)
- Offline: on Android and Apple, offline persistence is on by default. The default cache size is 100 MB. Writes made offline are sent automatically once the device reconnects. Conflicts are resolved as last write wins. — [Firestore: access data offline](https://firebase.google.com/docs/firestore/manage-data/enable-offline)

**Supabase (Postgres)**
- Free projects with low database activity over 7 days may be paused. A warning email arrives about a week before the pause. "A few user requests to the database each day" usually keeps a project active. Paid plans are never paused for inactivity. — [Supabase: project pausing](https://supabase.com/docs/guides/platform/free-project-pausing)
- A paused project can be restored from the Dashboard with its data within 1 year of the pause. The limit exists because backups are kept for a limited time. — [Supabase: project pausing](https://supabase.com/docs/guides/platform/free-project-pausing)
- Third-party 2026 summaries list the Free plan as 2 active projects, 500 MB database, 50,000 MAU and 5 GB egress, with no downloadable backups or PITR. These figures are not from the official pricing page. — [DEV Community: Supabase free tier limits in 2026](https://dev.to/mahdi_benrhouma_fe1c6005/supabase-free-tier-limits-in-2026-what-you-actually-get-and-when-you-will-hit-them-4mhe); [automationatlas.io](https://automationatlas.io/answers/supabase-free-tier-limits-2026/); official page to verify: [supabase.com/pricing](https://supabase.com/pricing)
- Sources disagree on what counts as "activity". The official docs say database activity, and one third-party article says incoming API requests. — [productcompass.pm](https://www.productcompass.pm/p/supabase-project-paused) vs [Supabase docs](https://supabase.com/docs/guides/platform/free-project-pausing)

**InvenTree (self-hosted open-source inventory)**
- Stock locations are hierarchical: "each location may contain multiple sub-locations, or stock, or both". "Structural" locations only organise other locations and hold no stock directly. — [InvenTree docs: Stock](https://docs.inventree.org/en/stable/stock/)
- An Android app (Play Store) offers "native barcode support" for stock-control actions. It talks to an InvenTree server through the InvenTree API. — [InvenTree docs: App](https://docs.inventree.org/en/stable/app/)

### Inferences
- **Data size.** The map has a few hundred to a few thousand rows (model code, row, column, timestamp, who). That is tiny next to every free tier listed. Even a full re-sync of 5,000 rows a day stays far under Firestore's 20k writes/day. With batching, a Sheets push is one request per sync.
- **Room/SQLite + CSV/Excel share (recommended baseline).** No cost, no account, works fully offline, and data never leaves the phone unless someone shares it. Coworkers get the file through WhatsApp, e-mail or Drive using the Android share sheet (FileProvider plus an ACTION_SEND intent). The downside is that coworkers see a snapshot that goes stale. I did not re-fetch the Room docs this session; Room is Android's standard SQLite abstraction: https://developer.android.com/training/data-storage/room
- **Google Sheets as the shared "read view".** Coworkers already know Sheets, and it can be shared view-only or published as a web page or CSV link, so they need no app. Writing from Android needs one of two setups:
  - OAuth with the shop's Google account (Sheets API scope, a Cloud project and an OAuth consent screen). This is the most setup.
  - An Apps Script web app deployed "execute as me", which the phone POSTs JSON to. This is the simplest: no Cloud project in the app, and the URL works as a shared secret. The trade-off is weak auth, since anyone with the URL can write. Use a token parameter.
  
  Offline behaviour has to be built: queue pending changes in Room and flush them when online with WorkManager. With one writer there are effectively no conflicts. With two, upsert keyed on model code plus last-write-wins by timestamp is enough.
- **Firestore.** It gives offline-first sync and last-write-wins for free, which fits one or two writers. Coworkers without the app need a viewer, though: a small hosted web page with Firebase Hosting and auth, or an export. The setup involves a Firebase project, `google-services.json` and security rules. Data sits on Google servers, with the location chosen at database creation.
- **Supabase.** The free-tier pause makes it a poor fit for a shop that might go more than a week without scanning. The phone would also need its own offline queue, since there is no built-in offline cache like Firestore's. Paying for Pro removes the pause, but no-cost was a stated preference.
- **InvenTree.** It needs a server (a PC or VPS running all the time) and someone to maintain it, which a shop without IT staff doesn't have. Its location hierarchy (stock room > row > column) does match the problem, if the shop ever grows into a real WMS.
- **Static web page.** The app could export an HTML or JSON file that a viewer opens offline, or upload to free static hosting. That is read-only and needs a re-export after each change, so it is about the same as the CSV-share option.
- **Privacy.** Room plus file share keeps data on the phone and in whatever chat app it is shared through. Sheets, Firestore and Supabase store it on US-headquartered cloud providers. A model-to-shelf map is not personal data, so KVKK exposure is low, as long as you don't store worker names or phone numbers beyond a first name or initials.

### Gaps
- I did not fetch Apps Script web-app limits (request rate, payload size, cold-start latency). The quotas page did not list them.
- I did not verify the official Supabase Free plan numbers on supabase.com/pricing. The figures above come from third-party 2026 summaries.
- I did not check whether a paused Supabase project is deleted after the 1-year restore window. The official page only says restore is possible within 1 year.
- I found no source on InvenTree install effort (Docker/Postgres) or whether the Android app is free. The fetched pages didn't cover either.
- I found no source on Firestore regional data residency options for Turkey or the EU.

## Q2. Does DİA ERP support shelf/bin locations (raf yeri), and can they be written through the Web Service API v3? What does DİA Mobil offer?

### Takeaway
Yes. DİA has a first-class **"Raf Yeri" (shelf location)** concept with its own API service ("DYS - Depo Yönetim Sistemi" in the SCF module). Shelf locations are cards per warehouse (`scf_rafyeri_*`), and stock is placed into them through **Raf Yeri Fişi** (shelf-location slips) whose lines carry stock card, unit, shelf, destination shelf and quantity. The stock card itself also has a free-text `rafyeri` field, a `rafyeritakibi` flag and a list of default shelf locations. All of these can be written through API v3 (`scf_stokkart_guncelle`, `scf_rafyeri_ekle`, `scf_rafyeri_fisi_ekle`). Whether the shop's DİA licence has the shelf-location module enabled is a separate question.

### Cited Findings
- The DİA WS API v3 docs have a "SCF - Depo Yönetim Sistemi (DYS)" page. Its endpoint is `https://SUNUCUKODU.ws.dia.com.tr/api/v3/scf/json`. — [DİA WS: DYS](https://doc.dia.com.tr/doku.php?id=gelistirici:wsapi:dys)
- Shelf-location methods: `scf_rafyeri_listele`, `scf_rafyeri_getir`, `scf_rafyeri_ekle`, `scf_rafyeri_guncelle`, `scf_rafyeri_sil`, `scf_rafyeri_takipli_depo_listele` (warehouses with shelf tracking), `scf_rafyeri_stok_miktar_listele` (stock quantities in a shelf location), `scf_rafyeri_stok_miktar_serilot_listele`. — [DİA WS: DYS](https://doc.dia.com.tr/doku.php?id=gelistirici:wsapi:dys)
- Shelf-location slip methods: `scf_rafyeri_fisi_listele/_getir/_ekle/_guncelle/_sil`, `scf_rafyeri_fisi_belge_kalemleri_listele`, `scf_rafyeri_fisi_islemler_listele`, and `scf_rafyeri_fisi_islem`. The `islem` values are `belgelerigetir`, `islemsil`, `serilotislemsil`, `barkodkontrol` (barcode check against documents), `serilotkontrol` and `fisikapat` (close slip). — [DİA WS: DYS](https://doc.dia.com.tr/doku.php?id=gelistirici:wsapi:dys)
- Every call takes `session_id`, `firma_kodu` and an optional `donem_kodu` (defaults to the current period). Add and update calls take a `kart` dict. List calls take `filters`, `sorts`, `limit` and `offset`. — [DİA WS: DYS](https://doc.dia.com.tr/doku.php?id=gelistirici:wsapi:dys)
- **ScfRafyeriModel** fields:

  | Field | Type | Meaning |
  |---|---|---|
  | `kod` | Unicode(256) | Code |
  | `aciklama` | Unicode(128) | Description |
  | `bolge` | Unicode(128) | Region/zone |
  | `_key_sis_depo` | | Warehouse link |
  | `en`, `boy` | | Width, length |
  | `maxdesi`, `maxnetagirlik` | | Max volumetric weight, max net weight |
  | `dolulukdurumu` | | Occupancy status |
  | `durum` | | Active/passive |
  | `_key_sis_seviyekodu` | | Authorisation level |

  It has no separate row/column/aisle fields, so row and column would have to be encoded in `kod` (for example "R03-C05") or in `bolge`. — [ScfRafyeriModel](https://doc.dia.com.tr/doku.php?id=gelistirici:models:scf_rafyeri_model)
- **ScfStokkartModel** location fields: `rafyeri` (Unicode(32), "Raf Yeri"), `rafyeritakibi` (Unicode(1), "Raf Yeri Takibi", default "f"), `_key_scf_rafyerleri` (Array(Integer64), "Öntanımlı Raf Yerleri", default shelf locations). Other fields: `stokkartkodu` Unicode(128), `aciklama` Unicode(200), `_key_scf_marka`, `ekalan1`-`ekalan10` (Unicode(256) extra fields), `_key_sis_ozelkod1`-`11`. There are about 140 fields in total. — [ScfStokkartModel](https://doc.dia.com.tr/doku.php?id=gelistirici:models:scf_stokkart_model)
- **ScfRafyeriFisiKalemiModel** (slip line) fields: `_key_scf_stokkart`, `_key_scf_stokkart_birimleri`, `_key_scf_rafyeri`, `_key_scf_rafyeri_dest` (destination shelf, meaning moves between shelves are supported), `_key_scf_palet`, `miktar`, plus optional links to invoice, waybill, order and delivery lines. Variants are handled in a sub-model, `ScfRafyeriFisiKalemiVaryantModel`. — [ScfRafyeriFisiKalemiModel](https://doc.dia.com.tr/doku.php?id=gelistirici:models:scf_rafyeri_fisi_kalemi_model); [DİA models: SCF index](https://doc.dia.com.tr/doku.php?id=gelistirici:models:scf)
- The SCF model index also lists `ScfDepoSayimiModel`, `ScfSayimfisiKalemiVaryantModel` (counting slips with variants) and `ScfDepoParametresiModel`. — [DİA models: SCF index](https://doc.dia.com.tr/doku.php?id=gelistirici:models:scf)
- The stock-card web-service page lists `scf_stokkart_listele/_getir/_ekle/_guncelle/_sil`, `scf_stokkart_varyant_listele`, `scf_stokkart_birimleri_*`, `scf_stokkart_fiyat_guncelle` and `scf_stokkart_hareket_listele`. Field lists are in downloadable example JSON files, which I did not read. — [DİA WS: Stok-Hizmet](https://doc.dia.com.tr/doku.php?id=gelistirici:wsapi:stokhizmet)
- The WS API index has pages for session/API key/login, list/get/add/update/delete service types, and a "WS Tester" tool. — [DİA WS API index](https://doc.dia.com.tr/doku.php?id=gelistirici:wsapi:anasayfa)
- **DİA Mobil.** The App Store listing describes stock, warehouse counting ("Depo Sayım"), orders and invoices on phones and tablets, with camera barcode scanning. I found no source confirming shelf-location (raf yeri) screens in DİA Mobil. — [DİA Mobil, App Store](https://apps.apple.com/app/id1231421691); [mwm.ai listing](https://mwm.ai/apps/dia-mobil/1231421691)
- DİA partner pages (Mavvo) advertise a stock and warehouse management module but describe no shelf fields. General web searches in Turkish turned up no DİA user-facing help on raf yeri. — [Mavvo: stock & warehouse management](https://mavvo.com.tr/en/stock-warehouse-management/)

### Inferences
- **Simplest integration.** Write the map's location string (for example "R3-C5") into the stock card's `rafyeri` field (32 chars) with `scf_stokkart_guncelle`. Coworkers then see it on the stock card and in DİA reports, with no extra modules. It is per stock card (model), not per variant or warehouse, which matches the "model -> location" grain.
- **The "proper" route.** Create one `scf_rafyeri` card per row/column cell, tied to the warehouse, then post `scf_rafyeri_fisi` slips that place stock quantities. This gives quantity-per-shelf and moves (`_key_scf_rafyeri_dest`). It probably needs the warehouse to have raf yeri tracking enabled (`scf_rafyeri_takipli_depo_listele`, `rafyeritakibi`), and maybe a licensed module. It is also much more process than a shop with one map-maker needs.
- **Credentials.** API access needs a DİA web-service user/API key and the server code (SUNUCUKODU). Those come from the shop's DİA admin or reseller. The app should not hold a privileged DİA password. A safer first step is a CSV the office imports, or a read-only API user. The task constraints also mean the agent cannot type real credentials; the user must provision them.
- **Row/column encoding.** Because `ScfRafyeriModel` has only `kod`, `aciklama` and `bolge` (no row/column/level), a consistent code scheme like "SATIR-03/KOLON-05" in `kod` is needed.

### Gaps
- I could not confirm which DİA licence or package includes raf yeri / DYS, or whether it is enabled by default. No pricing or licensing source was found.
- I did not read the example JSON request bodies (`scf_stokkart_guncelle`, `scf_rafyeri_ekle`). They are downloadable files not shown in page text, so the exact `kart` payload shape (e.g. whether `_key` is required on update) is unverified.
- I found no documentation of whether DİA Mobil supports raf yeri slips or shelf lookup.
- I found no forum or user posts describing real-world raf yeri use in DİA.

## Q3. Can a DİA stock card export include barcode, stock code/name, colour and size (variant) to map barcodes to model codes?

### Takeaway
The data exists in DİA, but it is spread across several linked tables: stock card, unit, unit barcode and variant. Barcodes hang off the stock card's units, not directly off variants. I found no documentation of what DİA's UI "Excel'e aktar" list export includes. The reliable way to get barcode -> stock code -> colour/size is either a DİA report or list view configured to include those columns (to be checked in the shop's DİA), or the API: `scf_stokkart_listele` + `scf_stokkart_birimleri_listele` (barcodes) + `scf_stokkart_varyant_listele`.

### Cited Findings
- **ScfStokkartBirimBarkodModel** has `barkod` (Unicode(32)), `barkodnotu`, `turu` ("S" fixed / "M" quantity-based), `_key_scf_stokkart_birimleri` (link to the unit, which links to the stock card) and `varyantdata` (Unicode, "Stok Birimi Varyant Datası"). It has no foreign key to a variant record. Variant info for a barcode is carried as unkeyed data in `varyantdata`. — [ScfStokkartBirimBarkodModel](https://doc.dia.com.tr/doku.php?id=gelistirici:models:scf_stokkart_birim_barkod_model)
- **ScfStokkartVaryantModel** links a stock card (`_key_scf_stokkart`) to up to 9 feature keys (`_key_scf_stokkart_ozellik1`-`9`, pointing at `ScfMalzemeOzellikKalemiModel`). Colour and size are features, not named columns. The page's example mentions S/M/L sizes and R/G/B colours. — [ScfStokkartVaryantModel](https://doc.dia.com.tr/doku.php?id=gelistirici:models:scf_stokkart_varyant_model)
- ScfStokkartModel has no plain barcode text field (only `_key_scf_karebarkod`, a link to the 2D-barcode model) and no colour or size fields. — [ScfStokkartModel](https://doc.dia.com.tr/doku.php?id=gelistirici:models:scf_stokkart_model)
- Turkish web searches for DİA stock-card Excel export with barcode and variant columns returned only other ERPs' docs (Logo ExcelTrans, Wolvox). — [Logo ExcelTrans doc](https://docs.logo.com.tr/download/attachments/50668190/ExcelTrans%20Tan%C4%B1t%C4%B1m%20Dok%C3%BCman%C4%B1.pdf?api=v2); [Akınsoft Wolvox](https://bilgibankasi.akinsoft.net/en/home/makale/2807-client-and-stock-data-transfer-from-excel-in-wolvox-erp-program)

### Inferences
- **If the shop's barcodes are per variant** (each colour/size has its own EAN), the export must join barcode -> unit -> stock card, and read colour/size from `varyantdata` or variant features. A plain stock-card list export probably shows only code, name and maybe a main barcode. A barcode list or variant report is more likely to have the right grain. This needs checking in the shop's DİA screens.
- **The "model" grain** for the location map is probably `stokkartkodu` (one stock card per model with colour/size variants), which matches the `rafyeri` field being on the stock card. If the shop instead opens a separate stock card per colour/size, the model code would have to be derived, for example from a code prefix or an `ekalan` field.
- **A practical first test** is to ask the office to export one model's barcodes from DİA. The columns that appear show which join is needed.

### Gaps
- I found no DİA help page or screenshot describing the columns of the stock-card or barcode list Excel export.
- I could not confirm the format of `varyantdata` (JSON? feature codes?). The model page gives no example.
- I did not verify whether the shop uses DİA variants at all or separate stock cards per colour/size.
