# DİA ERP integration options for SweepScan (Derinet / Viaport stock transfer intake)

Legend: **[VERIFIED-DOC]** = read directly on doc.dia.com.tr (DİA's own developer docs, publicly readable without login at time of research, 2026-11-14). **[VERIFIED-OTHER]** = other primary source (App Store listing, etc.). **[INFERRED]** = my reasoning, not stated by DİA.

## 1. DİA Web Service API: base URL, auth, API key, cost, permissions, security

### Takeaway
DİA has a public, well-documented JSON-over-HTTPS "Web Service v3" API. You log in with a DİA user + an API key issued by DİA (requested by email), get a `session_id`, and pass `session_id` + `firma_kodu` + `donem_kodu` to every call. Each call costs 0.0125 kontör (login/logout free), the user needs the same permissions as in the desktop client, optional per-user IP whitelisting applies, and TLS 1.2 is mandatory.

### Cited Findings
- Developer docs index `gelistirici:wsapi:anasayfa` lists: Genel Bakış (Nereden Başlamalıyız, Kontör Takibi, Güvenlik, Servis İsimlendirmesi, Input/Response), Test Aracı (DİA WS Tester), Session & API Key (API Key / Login / Logout), Yetkili Firma-Dönem, service types (Listeleme/Getirme/Ekleme/Güncelleme/Silme), Hata Takibi, Rapor alınması, training videos, examples (`ornek_1..5`, `devdaysornek_1..12`, `ornek_proje` C#, `ornek_script`), and the module index (SCF sub-pages: `cari`, `stokhizmet`, `tsif`, `kasa`, `dys`). **[VERIFIED-DOC]** — [DİA WS API anasayfa](https://doc.dia.com.tr/doku.php?id=gelistirici:wsapi:anasayfa)
- Endpoint for the SCF module shown on the Stok-Hizmet page: `https://SUNUCUKODU.ws.dia.com.tr/api/v3/scf/json` (pattern `/api/v3/<module>/json`; SUNUCUKODU = the customer's server code). **[VERIFIED-DOC]** — [SCF Stok-Hizmet](https://doc.dia.com.tr/doku.php?id=gelistirici:wsapi:stokhizmet). A third-party Node client uses demo base `https://diademo.ws.dia.com.tr/api/v3/` — [dia-node-client mirror](https://identichosting.duckdns.org/EmreMutlu99/dia-node-client)
- Getting started: install the "DİA WS v3 Test Aracı" (Windows / Linux / macOS) which shows service names + sample JSON and can call the demo server; learn kontör logic; login to get `session_id`; learn firma/dönem logic. **[VERIFIED-DOC]** — [WS genel](https://doc.dia.com.tr/doku.php?id=gelistirici:wsapi:genel)
- Cost: "Her web servis çağrısı için 0.0125 kontör düşmektedir"; login/logout don't consume kontör; usage visible in desktop screen "Kontör Hareketleri (msj1900)" or via `sis_kontor_sorgula`. **[VERIFIED-DOC]** — [WS genel](https://doc.dia.com.tr/doku.php?id=gelistirici:wsapi:genel)
- Security: only TLS 1.2 after 30 Oct 2022; SSL-encrypted; session created with a DİA system user; that user must be authorized for the same action in the desktop client (e.g. without "Cari Kart Listeleme" permission `scf_carikart_listele` returns an authorization error); if "İzin Verilen IP'ler" is set for the user, calls are only accepted from those IPs. **[VERIFIED-DOC]** — [WS genel](https://doc.dia.com.tr/doku.php?id=gelistirici:wsapi:genel)
- Naming: services are named after models, e.g. `scf_carikart_ekle / _getir / _guncelle / _listele / _sil`. Every call except login needs `session_id`, `firma_kodu`, `donem_kodu`; ekle/guncelle take a `kart` object and return `key` (+ log in `msg`); getir/sil take `key`; getir/listele return data in `result`. **[VERIFIED-DOC]** — [WS genel](https://doc.dia.com.tr/doku.php?id=gelistirici:wsapi:genel)
- API key: "DİA tarafından verilen, tekil bir anahtar"; obtained by emailing DİA support describing the intended use and the application; authorized staff reply and complete the process; the key is used only in the login call. **[VERIFIED-DOC]** — [Session & API Key](https://doc.dia.com.tr/doku.php?id=gelistirici:wsapi:session)
- Login request: `{"login": {"username": "ws", "password": "ws", "disconnect_same_user": "True", "params": {"apikey": "xxx"}}}` → success `{'code': '200', 'msg': '<session_id>'}`, failure `{'code': '401', 'msg': 'NOUSER'}`. Logout: `{"logout": {"session_id": "..."}}`. Session timeout 1 hour, reset by each call. **[VERIFIED-DOC]** — [Session & API Key](https://doc.dia.com.tr/doku.php?id=gelistirici:wsapi:session)
- Firma/dönem codes are obtained via the "Yetkili Firma, Dönem Bilgilerinin Alınması" page (`gelistirici:wsapi:yetkili_firma_donem`) — listed in the index; not opened in detail. **[VERIFIED-DOC, index only]** — [anasayfa](https://doc.dia.com.tr/doku.php?id=gelistirici:wsapi:anasayfa)

### Inferences
- **[INFERRED]** Cost is trivial: a transfer of ~50 boxes done as 1 `scf_barkod_okut` (batch list) + 1 `scf_malzeme_fisi_ekle` ≈ 2 calls ≈ 0.025 kontör. Even per-barcode lookups (50 calls) ≈ 0.6 kontör. The TL price of a kontör was not found (it's a DİA dealer/contract item).
- **[INFERRED]** `disconnect_same_user: "True"` would kick out an interactive user if the integration logs in with the same username — use a dedicated "ws"/integration user.
- **[INFERRED]** The API key is issued to the *developer/application*, but the DİA user, firma/dönem and permissions belong to the customer (Derinet). Because Derinet is a chain renting DİA, the person who can create users/permissions is Derinet HQ's DİA admin or their DİA bayi (dealer), not the Viaport store.

### Gaps
- The "Güvenlik" and "Kontör Takibi" sub-pages beyond the summary on `genel` were not separately opened; the TL price of kontör and whether WS usage requires an extra license were not found.
- Whether API keys are free / have an approval SLA is not documented publicly.

## 2. Exact methods/models for a warehouse transfer slip and barcode lookup

### Takeaway
There is **no** `scf_stokfis_ekle` / `scf_fis_ekle` / `scf_depo_transfer_*` service. A warehouse-to-warehouse stock transfer in DİA's API is a **Malzeme Fişi** with `turu = 1` ("Depo Fişi (Transfer)"), created with **`scf_malzeme_fisi_ekle`** (model `ScfMalzemeFisiModel`, lines in `m_kalemler` = `ScfMalzemeFisiKalemiModel`, with source `_key_sis_depo` and target `_key_sis_depo_dest`). Barcode lookup is **`scf_barkod_okut`** (TSIF page) taking `barkodtipi` + `barkodlist` (batch). Irsaliye (`scf_irsaliye_ekle`) has source/dest depot fields too but its `turu` list has no transfer type.

### Cited Findings
- Stok-Hizmet page services include `scf_stokkart_listele/getir/ekle/guncelle/sil`, `scf_stokkart_varyant_listele`, `scf_stokkart_birimleri_*`, `scf_malzeme_fisi_getir` / `scf_malzeme_fisi_ekle` ("Malzeme fişi ekler"), `scf_sayimfisi_ekle`, `scf_sayimfisi_kalemi_ekle`, `scf_sayimfisi_kalemi_toplu_ekle`, `scf_sayimfisi_kalemi_varyant_ekle`, `scf_sayimfisi_depo_sayimi_aktar`, `scf_stok_depo_miktarlari_listele`, `scf_stok_depobazinda_listele`, `scf_stok_varyant_miktarlari_listele`, `scf_stokkart_barkod_uret` (generates a barcode; not lookup), `scf_karebarkod_listele`. No `stokfis`/`ambar`/`depo_transfer` service names appear. **[VERIFIED-DOC]** — [SCF Stok-Hizmet](https://doc.dia.com.tr/doku.php?id=gelistirici:wsapi:stokhizmet)
- `ScfMalzemeFisiModel.turu` (Integer16, default 1): 1 = "Depo Fişi (Transfer)", 2 Üretimden Giriş, 3 Sarf, 4 Fire, 5 Devir, 6 Sayım Fazlası, 7 Sayım Eksiği, 8 Sair Giriş, 9 Sair Çıkış, 10 Maliyet+, 11 Maliyet−, 12/13 Devir Konsinye Giriş/Çıkış. Header fields: `fisno` (Unicode16), `tarih`, `saat`, `_key_sis_depo` ("Depo"), `_key_sis_depo_dest` ("Depo (Hedef)"), `_key_sis_sube`, `_key_sis_sube_dest`, `aciklama1..3`, `belgeno`, `durum` (T tamamlandı / D devam / B bekliyor), `istemcitipi` (G masaüstü, M mobil, W web servis, V veri aktarım, …), `kilitli`, `stokmiktaretki`, `toplam`. Sub-model: `ScfMalzemeFisiKalemiModel`. **[VERIFIED-DOC]** — [ScfMalzemeFisiModel](https://doc.dia.com.tr/doku.php?id=gelistirici:models:scf_malzeme_fisi_model)
- `ScfMalzemeFisiKalemiModel` fields: `_key_scf_stokkart` (Stok Kodu), `_key_scf_stokkart_birimleri` (Birim), `_key_sis_depo`, `_key_sis_depo_dest`, `kalemturu` (MLZM/HZMT), `miktar`, `anamiktar`, `birimfiyati`, `tutari`, `sirano`, `note`, **`sonokutulanbarkod`** ("Son Okutulan Barkod") and **`barkodokutmasayisi`** ("Barkod Okutma Sayısı"). **[VERIFIED-DOC]** — [ScfMalzemeFisiKalemiModel](https://doc.dia.com.tr/doku.php?id=gelistirici:models:scf_malzeme_fisi_kalemi_model)
- Official example `scf_malzeme_fisi_ekle.json` (shown for `turu: 3` Sarf): structure `{"scf_malzeme_fisi_ekle": {"session_id", "firma_kodu", "donem_kodu", "kart": {... "fisno": "WS000001", "tarih": "2026-04-25", "saat", "_key_sis_depo": {"depokodu": "R5050"}, "_key_sis_depo_dest": 0, "_key_sis_sube": {"subekodu": "R50"}, "_key_sis_doviz": {"adi": "TL"}, "durum": "T", "turu": 3, "m_kalemler": [ {"_key_scf_stokkart": {"stokkartkodu": "AI00000057"}, "_key_scf_stokkart_birimleri": 19823, "_key_sis_depo": {"depokodu": "R5050"}, "kalemturu": "MLZM", "miktar": "1.000000", "anamiktar": "1.000000", "birimfiyati": "12.000000", "sirano": 9, "m_varyantlar": [{"_key_scf_stokkart_ozellik1": 261143, "_key_scf_stokkart_ozellik2": 261152, ..., "miktar": "1.000000"}]}, {"_key_scf_stokkart": 19822, ...} ]}}}`. Key point: foreign keys can be given either as the numeric `_key` or as a lookup object by code (`{"stokkartkodu": ...}`, `{"depokodu": ...}`, `{"subekodu": ...}`); variant lines use `m_varyantlar` with `_key_scf_stokkart_ozellik1..9`. **[VERIFIED-DOC]** — [scf_malzeme_fisi_ekle.json](https://doc.dia.com.tr/lib/exe/fetch.php?media=gelistirici:wsapi:scf_malzeme_fisi_ekle.json)
- `scf_barkod_okut` (on TSIF page): `barkodtipi` required (`BARKOD`, `SERILOT`, `HIZMET`, `SERIARALIK`), `barkodlist` optional array; returns matching stock cards; requires "Stok Kart Görüntüleme" permission. Example: `{"scf_barkod_okut": {"session_id": "...", "firma_kodu": ..., "donem_kodu": ..., "barkodtipi": "BARKOD", "barkodlist": ["9374863219048"]}}`. **[VERIFIED-DOC]** — [SCF TSIF](https://doc.dia.com.tr/doku.php?id=gelistirici:wsapi:tsif); [scf_barkod_okut.json](https://doc.dia.com.tr/lib/exe/fetch.php?media=gelistirici:wsapi:scf_barkod_okut.json)
- TSIF services: `scf_talep_*`, `scf_teklif_*`, `scf_siparis_*`, `scf_teslimat_fisi_*`, `scf_irsaliye_listele/getir/ekle/guncelle/sil/listele_ayrintili/faturalandir`, `scf_fatura_*`, `scf_barkod_okut`, `scf_fiyat_hazirla`. Irsaliye cancel = `scf_irsaliye_guncelle` with `"iptal": "X"`. **[VERIFIED-DOC]** — [SCF TSIF](https://doc.dia.com.tr/doku.php?id=gelistirici:wsapi:tsif)
- `ScfIrsaliyeModel.turu`: 1 Mal Alım, 2 Perakende Satış, 3 Toptan Satış, 4/5 Konsinye Giriş/Çıkış, 6 Mal Alım İade, 7 Perakende Satış İade, 8 Toptan Satış İade, 9/11 Konsinye iade, 12 Özel Giriş, 13 Özel Çıkış, 15 Müstahsil — **no transfer type**; header has `_key_sis_depo_source`, `_key_sis_depo_dest`, `fisno`, `belgeno2` (İrsaliye No). Line model `ScfIrsaliyeKalemiModel` has `kalemturu`, `miktar`, `anamiktar`, `_key_scf_kalem_birimleri`, `_key_sis_depo_source/_dest`, `sonokutulanbarkod`, `barkodokutmasayisi`, `birimfiyati`. **[VERIFIED-DOC]** — [ScfIrsaliyeModel](https://doc.dia.com.tr/doku.php?id=gelistirici:models:scf_irsaliye_model); [ScfIrsaliyeKalemiModel](https://doc.dia.com.tr/doku.php?id=gelistirici:models:scf_irsaliye_kalemi_model)
- DYS (Depo Yönetim Sistemi) page covers only shelf locations: `scf_rafyeri_*`, `scf_rafyeri_fisi_*`, `scf_rafyeri_fisi_islem` (with `barkodkontrol` / `serilotkontrol`); no transfer, mal kabul or terminal services. **[VERIFIED-DOC]** — [SCF DYS](https://doc.dia.com.tr/doku.php?id=gelistirici:wsapi:dys)
- Open-source: "dia-node-client" (Node.js, WS v3) — supports only login/logout and the RST (restaurant) module; SCF is on its to-do list. Found only on a mirror host, original GitHub repo not confirmed. **[VERIFIED-OTHER, low confidence]** — [dia-node-client mirror](https://identichosting.duckdns.org/EmreMutlu99/dia-node-client). DİA's own sample project is C# (`ornek_proje`). — [WS örnekler](https://doc.dia.com.tr/doku.php?id=gelistirici:wsapi:ornek)

### Inferences
- **[INFERRED]** Proposed SweepScan payload for a received transfer (to be validated in WS Tester on the demo server first):
  ```json
  {"scf_malzeme_fisi_ekle": {"session_id": "...", "firma_kodu": 1, "donem_kodu": 1,
    "kart": {"turu": 1, "tarih": "2026-11-17", "fisno": "SWS000001",
      "_key_sis_depo": {"depokodu": "viaport-transfer"},
      "_key_sis_depo_dest": {"depokodu": "viaport-viaport"},
      "aciklama1": "SweepScan sayım",
      "m_kalemler": [
        {"kalemturu": "MLZM", "_key_scf_stokkart": {"stokkartkodu": "<from scf_barkod_okut>"},
         "_key_scf_stokkart_birimleri": <birim key>, "miktar": "3", "anamiktar": "3",
         "_key_sis_depo": {"depokodu": "viaport-transfer"},
         "_key_sis_depo_dest": {"depokodu": "viaport-viaport"},
         "sonokutulanbarkod": "<EAN>",
         "m_varyantlar": [{"_key_scf_stokkart_ozellik1": <beden>, "_key_scf_stokkart_ozellik2": <renk>, "miktar": "3"}]}]}}}
  ```
  "viaport-transfer"/"viaport-viaport" may be depot *names* rather than `depokodu`; the real codes must be read via a sis depo listing.
- **[INFERRED]** Shoe barcodes usually map to a stock card + variant (size/colour). Whether `scf_barkod_okut` returns the variant `ozellik` keys was not verified; if not, a variant/barcode listing (`scf_stokkart_varyant_listele`) is needed. This is the main technical risk.
- **[INFERRED]** SweepScan can do one batch `scf_barkod_okut` call with all distinct barcodes, show unknown barcodes in the app (instead of DİA's skip-with-error), aggregate quantities, then post one slip — strictly better than keyboard wedge for error handling.
- **[INFERRED]** The desktop "Stok transfer" screen with source "viaport-transfer" → target "viaport-viaport" is most likely Malzeme Fişi turu 1 (Depo Fişi/Transfer) — but Derinet might use a different flow (e.g. a Mağaza transfer screen or an irsaliye-based flow with e-irsaliye). Confirm by opening a past transfer in DİA and checking its screen code/fiş type, or via `scf_malzeme_fisi_listele`/`getir` once API access exists. Note: the "viaport-transfer" depot looks like an "in transit" depot, so HQ probably creates an outgoing slip to it and the store creates the receiving slip.

### Gaps
- `scf_malzeme_fisi_listele` / `_guncelle` / `_sil` existence on the page not confirmed in the read portion (only `_getir`, `_ekle` seen).
- Return shape of `scf_barkod_okut` (does it include unit and variant keys?) not verified.
- `devdaysornek_1..12` pages and `ornek_script` were not read; no other open-source SCF client (PHP/Python/C#) was found on GitHub in my searches.
- Whether a turu-1 slip via WS affects the e-irsaliye obligation (GİB requires e-irsaliye for shipments between own warehouses/branches for e-irsaliye taxpayers per a cited özelge) — [Alomaliye](https://www.alomaliye.com/2018/10/22/sirketin-depo-ve-subelere-transfer-edecegi-mallari-e-irsaliye-belgesi-ile-belgelendirip-belgelendirmeyecegi/) — is an HQ/accounting concern; the receiving side probably just books stock, but not verified.

## 3. DİA desktop/web client features, Excel/terminal import, DİA Mobil

### Takeaway
DİA's own **DİA Mobil** app (free on App Store, by Dia Yazılım A.Ş.) supports Depo Sayım, Stok, Sipariş, İrsaliye, Fatura and scans with the **phone camera** or a Bluetooth scanner — it is the "official" phone path, but its description doesn't mention depolar arası transfer or mal kabul. I could not verify an "Excel'den kalem aktar" or terminal text-file import for stock slips from public sources.

### Cited Findings
- DİA Mobil (App Store id1231421691, seller DIA YAZILIM SANAYI VE TICARET A.S., free, v26.10.1 dated Oct 19): Cari, Stok, Depo Sayım, Teklif, Sipariş, İrsaliye, Fatura, Görev Takip, CRM, Kasa; integrates with the central DİA system online; phones/tablets scan via camera, Bluetooth scanners as alternative; warns that camera focus affects scan performance and heavy-volume users should test devices. Depolar arası transfer and mal kabul are not mentioned. **[VERIFIED-OTHER]** — [App Store: DİA Mobil](https://apps.apple.com/app/id1231421691)
- The malzeme fişi model's `istemcitipi` enumerates M (mobil), W (web servis), V (veri aktarım), P (POS) etc., confirming DİA records slips from mobile, WS and data-transfer channels. **[VERIFIED-DOC]** — [ScfMalzemeFisiModel](https://doc.dia.com.tr/doku.php?id=gelistirici:models:scf_malzeme_fisi_model)
- Line models carry `sonokutulanbarkod` and `barkodokutmasayisi`, i.e. DİA tracks barcode-read counts per line. **[VERIFIED-DOC]** — [ScfMalzemeFisiKalemiModel](https://doc.dia.com.tr/doku.php?id=gelistirici:models:scf_malzeme_fisi_kalemi_model)
- Sayım fişi services exist incl. bulk line add (`scf_sayimfisi_kalemi_toplu_ekle`) and `scf_sayimfisi_depo_sayimi_aktar`. **[VERIFIED-DOC]** — [SCF Stok-Hizmet](https://doc.dia.com.tr/doku.php?id=gelistirici:wsapi:stokhizmet)

### Inferences
- **[INFERRED]** `barkodokutmasayisi` suggests the desktop barcode mode can increment quantity on an existing line (aggregation) depending on settings; the observed one-line-per-scan behaviour in Viaport may be a setting, not a hard limit. Ask the DİA admin/bayi.
- **[INFERRED]** DİA Mobil requires a DİA mobile user/license from the customer (app free, but access is via the customer's DİA); whether it has a transfer-receive screen must be checked in the app with Derinet's credentials.

### Gaps
- No public source found for "Excel'den aktar" into Malzeme Fişi lines, "toplu barkod" text import, or a DİA "El Terminali" product; DİA's help/video pages were not reachable in my searches. Pricing/licensing of DİA Mobil seats not found.

## 4. Third-party integrators and handheld-terminal solutions

### Takeaway
Public evidence of DİA-specific terminal integrators was thin. Found: Strada (Excel invoice → DİA), the dia-node-client project, and competitor-ecosystem equivalents (Mikro Porkod, Logo, Nebim). DİA bayileri (dealers) typically build WS-based add-ons, but I found no named terminal product posting stock transfers to DİA.

### Cited Findings
- Strada's "Fatura Dönüştürücü" imports Excel invoices into DIA ERP with column mapping — invoice-focused, not stock slips. — [Strada](https://strada.tr/en/solutions/fatura-donusturucu)
- dia-node-client (Node.js WS v3 client; login/logout + RST only). — [mirror](https://identichosting.duckdns.org/EmreMutlu99/dia-node-client)
- Other ERPs' terminal apps for comparison: Mikro's Porkod (count with Bluetooth scanner) — [Porkod](https://mwm.ai/apps/porkod/1380445746); Nebim V3 brochure — [Nebim](https://www.nebim.com.tr/i/assets/images/v3_brosur/Nebim_V3_BR17_02_TekSayfa.pdf)

### Inferences
- **[INFERRED]** SweepScan's niche (camera-based multi-box counting rather than one-scan-per-box) is not something DİA Mobil or typical terminal apps do; the API route lets SweepScan be a DİA "add-on" that any DİA customer could use with their own API key/user.

### Gaps
- No list of DİA solution partners/terminal integrators was retrieved; dia.com.tr partner pages not read.

## 5. Recommendation for a shop without IT

### Takeaway
**Now (Tuesday field test): keep the keyboard-wedge approach** — zero approvals, zero cost, works with the existing "Barkod" button. **Later: API** — `scf_barkod_okut` + `scf_malzeme_fisi_ekle` (turu 1) — needs (a) an API key from DİA by email, (b) a dedicated DİA user with stock-card view + malzeme fişi add permissions created by Derinet HQ's DİA admin/bayi, (c) server code + firma/dönem codes, (d) possibly an IP whitelist; cost ~0.0125 kontör/call.

### Cited Findings
- Requirements and costs as above — [WS genel](https://doc.dia.com.tr/doku.php?id=gelistirici:wsapi:genel); [Session & API Key](https://doc.dia.com.tr/doku.php?id=gelistirici:wsapi:session)

### Inferences
- **[INFERRED] Path A (now): keyboard wedge.** Improve robustness: send barcode + Enter with a configurable inter-key delay; pause/confirm between scans; keep SweepScan's own list as the source of truth and show a reconciliation (count in app vs lines in DİA); since DİA skips unknown barcodes with an error dialog, SweepScan should pre-flag barcodes it hasn't seen and let staff handle the dialog. Risk: focus loss in the desktop client, dialogs swallowing keystrokes.
- **[INFERRED] Path B (low-effort middle): CSV/Excel export** of barcode+qty from SweepScan as a paper trail / for an import feature if Derinet's DİA has one (unverified — ask bayi).
- **[INFERRED] Path C (later): direct API.** Flow: login → `scf_barkod_okut` (batch) → show unknowns → build `m_kalemler` (aggregate per stock+variant) → `scf_malzeme_fisi_ekle` with `turu: 1`, source/target depots → logout. Prototype first on the DİA demo server with the WS Tester. Do not embed the API key/password in the phone app long-term; a tiny backend (or at least secure storage) is preferable, and an IP whitelist would force a backend with a fixed IP.
- **[INFERRED] Approvals needed for Path C:** Derinet management's OK (it's their ERP data), DİA API key (email to DİA support, by the developer), DİA user + permissions by Derinet's DİA admin/bayi, agreement on whether the store or HQ creates the receiving slip, and confirmation that this matches how transfers are booked today (fiş type, e-irsaliye handling).
- **[INFERRED]** Alternatively evaluate DİA Mobil on a store phone first — if it has a transfer screen with camera scanning it may already satisfy the shop, and SweepScan's value is then the multi-box camera counting.

### Gaps
- Kontör TL price, API-key turnaround, and whether Derinet's DİA contract allows third-party WS access are unknown; must be asked to Derinet HQ / DİA bayi.
