# Location-based scanning workflows and handheld scanner integration (for SweepScan location mode)

Research date: 2026-11-17. Prices are as listed on the pages at that date; retailer prices move, so treat them as indicative.

## 1. Location-first workflows in WMS / inventory apps (step-by-step flows, screen contents, confirmation rules)

### Takeaway
Every mainstream system uses the same pattern: scan the location barcode first, which opens a location context; then scan items into it; then explicitly apply or validate. Odoo shows the expected contents of the location after the location scan, unless it is configured for a blind count. InvenTree treats "scan items into location" as a transfer of known items. SweepScan's "LOC label, then sweep, then confirm" maps directly onto this pattern.

### Cited Findings
- **Odoo Barcode, inventory count (v18):** the user taps "Inventory Count", walks to the location and scans the location barcode. Scanning "highlights the location and displays all of the products stored there". Each product scan adds +1 to that product's count (the example scans a desk stand three times to reach 3 units). An edit icon opens a keypad with +1/−1 buttons and a Quantity field. "Add Product" adds an item that is not on the list. The count is finished with **Apply** or by scanning a "Validate" command barcode, and a green banner confirms it — [Odoo 18 docs: Adjust inventory with barcodes](https://www.odoo.com/documentation/18.0/applications/inventory_and_mrp/barcode/operations/adjustments.html)
- **Odoo options:** "Count Entire Locations" makes a location scan load every product assigned to that location. Clearing "Show Quantity to Count" hides the expected quantities, which makes it a blind count. If multi-location is disabled, the location scan is skipped and the user scans products directly — [Odoo 18 docs](https://www.odoo.com/documentation/18.0/applications/inventory_and_mrp/barcode/operations/adjustments.html); [Odoo 16 docs](https://www.odoo.com/documentation/16.0/applications/inventory_and_mrp/barcode/operations/adjustments.html)
- **Odoo:** "to begin the adjustment, first scan the source location ... Then, scan the product barcode(s)". The barcode of a product can be scanned several times to increase its quantity — [Odoo 17 docs](https://www.odoo.com/documentation/17.0/fr/applications/inventory_and_mrp/barcode/operations/adjustments.html)
- **Odoo hardware requirement:** Odoo reads key events only. On Zebra devices you must enable DataWedge Keyboard Output and "Send Characters as Events", or Odoo will not recognise scans. On Datalogic devices the wedge mode must change from "Text injection" to "Key pressure" — [Odoo barcode device troubleshooting](https://www.odoo.com/documentation/18.0/applications/inventory_and_mrp/barcode/setup/device_troubleshooting.html)
- **InvenTree mobile app:** a Stock Location page has a "Scan Items Into Location" action. Scanning the barcode of a stock item that is already in the database transfers that item to the location. The reverse is also available: on a stock item's page, "Scan Into Location" moves the item to whichever location barcode you scan — [InvenTree docs: app barcodes](https://docs.inventree.org/en/stable/app/barcode/)
- **InvenTree known issue:** a user reported that the app showed an error after a scan-into-location even though the move had succeeded. A misleading error is a UX failure worth avoiding — [inventree-app issue #520](https://polar.sh/inventree/inventree-app/issues/520)
- **Infor WMS:** offers RF-assisted cycle counts by item, by location and by LPN. When a counter reports an item that is not expected at the location, the records are flagged with a *location mismatch* status and a note that the inventory already exists elsewhere. Recounting a location creates a new detail record, so the history of each attempt is kept — [Infor WMS 2026: cycle counts, item by location](https://docs.infor.com/wms/2026.x/en-us/useradminlib/sceinmgtug/bkk1612893562747.html); [Infor: reviewing RF-assisted cycle counts](https://docs.infor.com/wms/2026.x/en-us/useradminlib/sceinmgtug/gkq1612893932403.html)

### Inferences
- The SweepScan flow can mirror Odoo's almost one to one:
  1. Scan the LOC label (by camera or handheld scanner). The location becomes "open", and a header such as "D1 · Row 3 · Column 7" stays on screen.
  2. Show the column's last known contents (guided), or hide them (blind).
  3. Sweep the column.
  4. Review the ordered list (top to bottom) with a diff against the last state.
  5. Confirm with an Apply button, or by scanning the same LOC label again as a "close" gesture. This is analogous to Odoo's Validate barcode.
- Scanning the same location label a second time is a natural "close/confirm" command and needs no extra barcode. Scanning a *different* location label while one is open should end the current column: either save it with a prompt, or treat it as "close and switch". It should never silently merge the two.

### Gaps
- I did not fetch the ERPNext or Zebra/Honeywell demo-app flows. Odoo and InvenTree cover the pattern well enough. I also found no public source describing Odoo's line colours or error sounds in detail.

## 2. Error detection and prevention, and feedback conventions

### Takeaway
Professional systems prevent errors structurally: there is always an explicit open location, and an item scan without one is rejected. Discrepancies are flagged for review rather than auto-applied. Feedback is multi-channel, with distinct good and bad beeps, LED colours and vibration, because noise and glare defeat any single channel.

### Cited Findings
- **Socket Mobile Capture API:** a good read and a bad read are set separately through beep, LED and "rumble" (vibration). The bad-read example uses a red LED, a bad beep and a bad rumble. The docs note you may want to turn the sound off and use vibration only, depending on the environment — [Socket Mobile docs: User Level](https://docs.socketmobile.com/capture/csharp/en/latest/topicsUserLevel.html) (this URL now redirects to socketmobile.dev/docs; content as seen in the search snippet)
- **Honeywell scanner manuals:** the good-read beep pitch can be set to Low 1600 Hz, Medium 3200 Hz or High 4200 Hz — [Honeywell 1280i manual (manualsonline)](https://manualsonline.com/manuals/mfg/honeywell/1280i.html?p=29). One Honeywell scanner vibrates once on a good read and twice on a programming barcode — per search snippet from [manualslib / Honeywell-related manual](https://www.manualslib.com/manual/846851/Flash-Bt-49.html?page=72) (snippet only, not verified in full)
- **Honeywell DC productivity guidance:** combines "super bright LEDs, enhanced beeper volume, and vibrating tactile response" for loud, poorly lit distribution centres — [Honeywell AIDC blog](https://www.honeywellaidc.com/blog/2020/february/scanning-solutions-role-in-boosting-dc-worker-productivity)
- **A patent on tactile trigger feedback:** argues that visual (green spot) and audible cues are insufficient at long range or in noise, which is the motivation for haptics — [USPTO 12333377](https://image-ppubs.uspto.gov/dirsearch-public/print/downloadPdf/12333377)
- **Scandit MatrixScan Count, expected-list mode:** red icons mark scanned barcodes that are not on the expected list. A progress bar replaces the counter badge ("x of N expected found"). An optional "clear screen" button removes the AR overlays but keeps the scanned list — [Scandit docs: MatrixScan Count advanced](https://docs.scandit.com/sdks/capacitor/matrixscan-count/advanced/)
- **Variance practice:** counts outside tolerance get an "Out of Variance" status, and a reviewer posts or rejects them — [Infor WMS: resolving cycle count errors](https://docs.infor.com/wms/2023.x/en-us/useradminlib/sceinmgtug/vpq1612893888955.html). One example threshold triggers a recount when variance exceeds 2 units or 5% of expected — [diagnosticsignal blog](https://diagnosticsignal.pages.dev/industrial-tech/cycle-counting-vs-full-inventory-count) (low-authority source, so treat it as an example only)

### Inferences (proposed error rules for SweepScan, derived from the patterns above)
- **Item or sweep with no open location:** block it, using an error tone plus a double vibration plus a red banner ("Scan a location label first"). Alternatively, allow an "unassigned" buffer that must be assigned before saving. Blocking is the WMS norm.
- **Forgotten location scan, i.e. the user starts a sweep of the next column while the previous one is still open:** prefer explicit close (rescan the same LOC or press Apply). Add a heuristic: if the camera sees a *different* LOC QR inside the frame during a sweep, stop and ask "Box list belongs to column 7 or 8?", or auto-split at the boxes adjacent to the label.
- **Sweep crossing two columns:** SweepScan's tracker knows the order and the horizontal position. If a second LOC label or a large lateral jump is detected, flag "possible two columns" on the review screen and offer a split point.
- **Scanning a location twice:** the same LOC while open means close/confirm. The same LOC that was already saved today means "re-scan: replace or add?", defaulting to replace for a full sweep.
- **Wrong location (scanned D2 while the user is in D1, or an unknown code):** reject anything that does not parse as a LOC payload, with an error tone. A valid LOC from a different depot than the session's depot gets a warning dialog.
- **Undo:** a single-step "undo last action" (last location close, last item add), plus per-line delete on the review screen. Never auto-apply; keep the history of each save, as Infor does with recount records.
- **Feedback mapping, consistent across camera and scanner:**

  | Event | Tone | Haptic | Colour |
  |---|---|---|---|
  | Location opened | Short high beep | Single pulse | Blue/neutral header |
  | Item added | Short soft tick | None, or a light tick | Green |
  | Location saved | Rising two-tone | Single pulse | Green banner |
  | Error | Low long buzz | Double pulse | Red |

  Keep it configurable, with a vibration-only mode (Socket Mobile recommends this option).

### Gaps
- I found no published "standard" tone frequencies or vibration patterns that are common across vendors. Each vendor makes them configurable. The table above is a design proposal, not a sourced convention.

## 3. Blind vs guided count, full replacement vs incremental, unexpected items

### Takeaway
A blind count (expected quantities hidden) is the accuracy-preferred default in cycle-count practice, and Odoo supports both modes with a setting. For a column sweep that captures the entire physical contents, "full replacement of the location snapshot" is the right semantic. Unexpected items should be recorded and flagged (location mismatch), not rejected.

### Cited Findings
- Odoo shows expected quantities by default. Clearing "Show Quantity to Count" hides them, which gives a blind count — [Odoo 18 docs](https://www.odoo.com/documentation/18.0/applications/inventory_and_mrp/barcode/operations/adjustments.html)
- Industry guides describe blind counts, where the device hides the system quantity until the counter submits, as the default — [SG Systems glossary: cycle counting](https://sgsystemsglobal.com/glossary/cycle-counting/) (vendor glossary, moderate authority). Some guides recommend a blind recount by a different counter when variance is large — [skill-search page](https://skill-search-ten.vercel.app/skills/5d00c9287b833e36dae30b8678eb2dea6093777a) (low authority)
- Infor flags an item counted in a location where it is not expected as a *location mismatch* and notes it exists elsewhere, rather than rejecting the count — [Infor WMS 2026](https://docs.infor.com/wms/2026.x/en-us/useradminlib/sceinmgtug/bkk1612893562747.html)
- In Odoo, items not on the list are added manually with "Add Product", choosing the product, quantity and source location — [Odoo 18 docs](https://www.odoo.com/documentation/18.0/applications/inventory_and_mrp/barcode/operations/adjustments.html)
- InvenTree "scan into location" is incremental: each scanned item is *moved* into the location, and the rest of the location's contents are untouched — [InvenTree docs](https://docs.inventree.org/en/stable/app/barcode/)

### Inferences
- SweepScan should have two save semantics:
  - **(a) Column sweep = full replacement.** The new ordered list becomes the column's contents, and the previous snapshot is archived. This is analogous to Odoo's "Count Entire Locations".
  - **(b) Single-box add/move = incremental.** A handheld scan of one box while a location is open adds it, InvenTree style.
- On the review screen after a sweep, show a diff against the last snapshot: new boxes, missing boxes, and boxes that were last seen in another column ("moved from D1-03-05"). The last category is the location-mismatch flag. Accept by default, since the physical sweep is the truth, but make the changes visible.
- A guided mode (show the last known list) is useful for "find" or verification. A blind mode is better for audits. A per-session toggle is enough.

### Gaps
- I found no authoritative source on how often guided counts bias counters in practice. The blind-count preference is stated by guides without data.

## 4. Hardware integration on Android: HID keyboard wedge, vendor SDKs and intents, ring scanners, SPP, prices

### Takeaway
For a sideloaded app with a cheap scanner, the simplest robust option is **Bluetooth HID (keyboard wedge) captured in `Activity.dispatchKeyEvent`, without an EditText**. Decode the characters yourself from `KeyEvent.getScanCode()` (or keyCode plus meta), so that the phone's physical-keyboard layout cannot mangle the payload.

The Turkish Q layout in AOSP **does** remap the US '-' key and changes ':'. A US-mode scanner reading "LOC:A-03" on a phone set to Turkish physical layout will likely produce garbage such as "LOCŞA*03". Zebra DataWedge and Honeywell intents are clean and layout-free, but they only exist on those vendors' rugged devices (or their paired ring scanners). Socket Mobile's Capture SDK works but costs more, both for the device and for the developer membership.

### Cited Findings

**HID capture techniques**
- Overriding `Activity.dispatchKeyEvent` intercepts all key events before they reach the window. It works without a focused text field: buffer the `ACTION_DOWN` characters and finish on Enter. You should consume (return true) the scanner events, because otherwise a scanner "presses" any focused button. Some scanners and devices deliver a whole barcode as one event, readable with `getCharacters()`, and terminators vary (Enter or Tab) with scanner configuration — [StackOverflow 38531142 (mirror)](https://kiwix.lema.org/stackoverflow.com_en_all_2023-11/questions/38531142/android-get-barcode-scanner-input-without-edittext)
- A Zebra forum thread reports that `dispatchKeyEvent` did not fire on a device after an M→N update, and recommends intents for input without text fields — [Zebra developer forum thread 34999](https://developer.zebra.com/thread/34999)
- Known soft-keyboard side effects: a connected hardware scanner can make the on-screen keyboard stop appearing, or appear when no field is focused — [StackOverflow 51335302 (mirror)](https://kaweah.freedombox.rocks/kiwix/stackoverflow.com_en_all_2023-11/questions/51335302/trying-to-use-external-bluetooth-barcode-scanner-and-soft-keyboard)
- **BoxHero engineering write-up on HID scanners in a production mobile app** ([BoxHero engineering](https://www.boxhero.io/engineering/external-barcode-scanners-flutter)):
  - They replaced the hidden focused text field, which caused focus stealing and needed focus re-requested after every scan, with one global hardware-key handler.
  - On Android the handler ignores non-keydown and synthesized events, treats Enter as end of scan, and consumes only scanner keys, so volume and arrow keys still work.
  - The partial buffer is discarded when the scanning screen becomes inactive.
  - On iOS an active Korean, Japanese or Chinese IME turned "ABC-123" into Hangul Jamo. Their fix was to read raw HID codes and map them with their own base/shift table.
  - A Honeywell Xenon 1950g pressed Shift before releasing the previous key, so uppercase letters came out lowercase. They track modifier state from the raw stream.
  - Wired scanners plugged straight into a phone can be under-powered, so use a USB-C hub with Power Delivery.
  - "Keyboard connected" detection only tells you a keyboard is present, not that it is a scanner.

**Keyboard layout mismatch (the Turkish problem)**
- The scanner and the host must use the same keyboard layout, because the scanner sends key positions, not characters. Layouts are set by scanning configuration barcodes in the scanner manual. NETUM scanners default to French — [Odoo device troubleshooting](https://www.odoo.com/documentation/18.0/applications/inventory_and_mrp/barcode/setup/device_troubleshooting.html)
- "Only the characters of the keyboard layout set on your device can be displayed. Some special characters may not be displayed or may be displayed incorrectly" — [Cheqroom help](https://help.cheqroom.com/en/articles/2647562-why-is-my-scanned-data-displayed-incorrectly-in-the-app) (via search snippet)
- **AOSP Turkish layout** (`keyboard_layout_turkish.kcm`, main branch), from [AOSP source](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/packages/InputDevices/res/raw/keyboard_layout_turkish.kcm):
  - It contains scan-code→keycode remaps: `map key 12 SLASH`, `map key 13 MINUS`, `map key 43 COMMA`, `map key 51 EQUALS`, `map key 52 BACKSLASH`, `map key 53 PERIOD`, `map key 86 PLUS`.
  - Key outputs: SLASH gives `*` / `?`; MINUS gives `-` / `_`; SEMICOLON gives `ş` / `Ş`; PERIOD gives `.` / `:`; COMMA gives `,` / `;`; EQUALS gives `ö` / `Ö`.
  - In this layout, `:` comes only from Shift+PERIOD, and `-` only from unshifted MINUS.
- **Android input pipeline:** EventHub maps Linux key codes to Android key codes through the key layout (.kl) map. A KCM file can override scan-code→keycode with `map key`, and `applyKeyRemapping` happens after that, so `KeyEvent.getKeyCode()` is *already layout-affected*, while the scan code is not — [AOSP KeyCharacterMap.h](https://android.googlesource.com/platform/frameworks/native/+/main/include/input/KeyCharacterMap.h); [ExKeyMo docs on map key and Generic.kl](https://ris58h.github.io/exkeymo/docs.html)
- The TEC-IT "Barcode Keyboard" input method supports only English, German, French, Russian and Swedish layouts — [TEC-IT manual](https://www.tec-it.com/en/software/mobile-data-acquisition/barcode-keyboard/user-manual-1/Default.aspx)
- **SPP (serial / RFCOMM) mode** sends plain data rather than keystrokes, so layout is irrelevant, but the app must open the Bluetooth socket itself — [hid-barcode-scanner project notes](https://awesome.ecosyste.ms/projects/github.com%2Ffabi019%2Fhid-barcode-scanner). Many cheap ring scanners advertise "Bluetooth 4.2 HID, SPP and BLE" — per the price search results below.

**Zebra DataWedge (Zebra rugged devices only)**
- Create a profile associated with your package (activity `*`). Enable barcode input and intent output, set a custom action string, leave the category blank, and choose broadcast delivery. Register a BroadcastReceiver for that action.
- Extras: `com.symbol.datawedge.data_string` (the data), `com.symbol.datawedge.label_type`, `com.symbol.datawedge.source`, and a raw byte array `com.symbol.datawedge.decode_data`.
- Delivery can also be startActivity or startService. The sample suggests registering in onResume and unregistering in onPause.
- Sources: [Zebra DataWedge 15.0 BasicIntent1](https://techdocs.zebra.com/datawedge/15-0/guide/samples/basicintent1); [Zebra DataWedge intent output](https://techdocs.zebra.com/datawedge/7-4/guide/output/intent)
- The Zebra RS5100 ring scanner integrates via DataWedge on Zebra Android devices and reportedly also pairs with non-Zebra Android devices via Bluetooth HID or SPP — [Hubone traceability docs (third-party)](https://tracability.hubone.fr/docs/hardware/zebra/rs5100/overview) (not confirmed on an official Zebra page)

**Honeywell (Honeywell rugged devices)**
- The intent API uses `com.honeywell.aidc.action.ACTION_CLAIM_SCANNER`, with extras `com.honeywell.aidc.extra.EXTRA_SCANNER` (`dcs.scanner.imager` or `dcs.scanner.ring`) and `EXTRA_PROFILE`. It also has `ACTION_RELEASE_SCANNER` and `ACTION_CONTROL_SCANNER` with `EXTRA_SCAN`. Scanner profiles are configured under Settings > Scanning. There is also a "Data intent" output with a custom Action — [cnblogs reproduction (secondary)](https://www.cnblogs.com/pythonClub/p/18911187); [GAR integration guide](https://download.gar.no/doc/android/print/honeywell.htm). I did not reach the official Honeywell SDK page, so verify the constant names before relying on them.

**Socket Mobile**
- The CaptureSDK is "free" with a developer license, but registration needs a one-time Developer Community Membership fee. There is a 25% developer discount on hardware. The free SocketCam C820 uses the phone camera — [Socket Mobile: simplify development](https://socketmobile.com/developers/simplify-development-cycle); [Developer membership](https://www.socketmobile.com/products/developers-membership)
- One S740 listing says the Capture SDK is "available with subscription", which conflicts with the "free" claim — [Barcode Factory S740](https://www.barcodefactory.com/socket-mobile/scanners/s700-series/s740/cx3431-1881)

**Prices (as listed, November 2026)**
- **Socket Mobile S740 (2D):** about $365–$383 at third-party retailers — [Barcode Factory](https://www.barcodefactory.com/socket-mobile/scanners/s700-series/s740/cx3431-1881); [Monoprice](https://www.monoprice.com/product?p_id=45767)
- **Zebra RS5100 ring scanner:** MSRP $1,020–$1,065 (SHI), $1,248.79 (CDW), and $853.95 for the no-trigger variant with trigger and battery sold separately — [SHI](https://www.shi.com/product/45809825/RS5100-Single-Finger-Bluetooth-Ring-Scan); [CDW](https://www.cdw.com/product/zebra-evm-rs5100-ring-scanner/7532615); [PTS Mobile](https://www.ptsmobile.com/RS51C0-LNNMWR.html)
- **Generic 2D ring scanners (HID/SPP/BLE):** roughly $20–$40 (Sumicor at about $21.60, Adesso NuScan 4300B at about $36). Wearables like Symcode run about $90. Several listings are on dubious mirror domains — [Inkstation 2D ring](https://www.inkstation.com.au/2819/2d-bluetooth-wearable-ring-barcode-scanner-wireless-portable-bqr-code-scanner-p-30466.html); [Danbit finger scanner](https://www.danbit.dk/en/rfid-barcode-magnet-stribe-tags-pos/21901-2d-qr-1d-ccd-barcode-scanner-for-finger-3mil-bluetooth-usb-and-transfer-to-mobile-or-pda.html); [Unitech MS632 (ERS)](https://ers-online.co.uk/p8254/unitech-ms632-2d-wireless-ring-barcode-scanner-bluetooth)
- **Turkey:**
  - Zebra DS2278 (2D Bluetooth handheld): about 9,757–10,834 TL on Epey, 10,301 TL with cradle at MediaMarkt TR — [Epey DS2278](https://www.epey.com/barkod-okuyucu/zebra-ds2278.html); [MediaMarkt TR](https://www.mediamarkt.com.tr/tr/product/_zebra-cradle-ds2278-ds2278-sr7u2100prw-2d-kablosuz-usb-barkod-okuyucu-siyah-175880743.html)
  - Honeywell Voyager 1472g: about 9,899–13,107 TL — [Epey 1472G](https://www.epey.com/barkod-okuyucu/honeywell-1472g.html)
  - Cheaper Bluetooth 2D models from about 3,081 TL are listed on Akakçe — [Akakçe Bluetooth barkod okuyucu](https://www.akakce.com/barkod-okuyucu/bluetooth-barkod-okuyucu,3.html)

### Inferences
- **Recommended stack for SweepScan (S23 Ultra, sideloaded, cheap scanner):**
  1. **Primary: Bluetooth HID.**
     - Capture in `dispatchKeyEvent` on the scan screen only, with no EditText.
     - Buffer `ACTION_DOWN` events and end on `KEYCODE_ENTER` or `KEYCODE_TAB` (configure the scanner suffix to CR).
     - Also add an inter-key timeout, such as 50–100 ms, to flush partial buffers. This is my suggestion; no source gives a number.
     - Translate with **your own US-layout table keyed on `getScanCode()`** (plus your own Shift tracking, as BoxHero did). Do not use `getUnicodeChar()`/`getKeyCode()`, which the Turkish KCM remaps.
     - Optionally filter by `event.device?.name` so a real keyboard is not captured. This is unsourced; test it on the S23.
  2. **Secondary: SPP** for scanners that support it. This is layout-proof but needs Bluetooth permissions and RFCOMM socket code.
  3. **Optional adapters:** DataWedge or Honeywell intent receivers, only if a rugged device is ever used. These are cheap to add, since they are just a BroadcastReceiver.
  4. Socket Mobile CaptureSDK is not worth it at this stage, given the extra cost and the license ambiguity.
- On the S23, also check what Settings > General management > Physical keyboard is set to once a scanner is paired. If the phone's physical-keyboard layout and the scanner's layout both stay "English (US)", the plain characters will work. But staff phones may differ, so the scan-code decoding plus a tolerant parser is the robust choice.

### Gaps
- I did not confirm which physical-keyboard layout a Samsung One UI device auto-selects for a new Bluetooth HID device on a Turkish-locale phone. It may default to the locale. This needs a test on the actual phone.
- I found no published timing threshold that distinguishes a scanner from human typing.
- I did not find official Honeywell docs for the intent constants, nor a Socket Mobile Android/Kotlin price page with the current membership fee.
- Turkey availability of cheap ring scanners (Trendyol/Hepsiburada) was not checked specifically.

## 5. QR payload design for location labels (wedge-safe)

### Takeaway
Keyboard-wedge scanners transmit key positions, so any punctuation risks being remapped. ':' and '-' are both affected under the AOSP Turkish layout. The safest payload uses **only uppercase A–Z and digits 0–9**, with a fixed structure. A tolerant parser should also accept the legacy "LOC:" form and its known mangled variants.

### Cited Findings
- The scanner and host layouts must match, or characters differ — [Odoo device troubleshooting](https://www.odoo.com/documentation/18.0/applications/inventory_and_mrp/barcode/setup/device_troubleshooting.html)
- On the AOSP Turkish layout, the US '-' key position (scan code 12) is remapped to SLASH and yields `*`, and US Shift+';' yields `Ş`. `:` requires Shift+PERIOD, and the US '.' and '/' positions are remapped (`map key 52 BACKSLASH`, `map key 53 PERIOD`) — [AOSP keyboard_layout_turkish.kcm](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/packages/InputDevices/res/raw/keyboard_layout_turkish.kcm)
- Another vendor reports repeated dashes collapsing to a single dash after a scan, depending on a scanner setting — [Wasp support / search snippet](https://support.waspbarcode.com/kb/articles/pdf/wws800-850-characters-out-of-order-when-connected-with-bluetooth-2)
- IMEs can rewrite even plain ASCII like "ABC-123" when a CJK input method is active (iOS case) — [BoxHero engineering](https://www.boxhero.io/engineering/external-barcode-scanners-flutter)

### Inferences
- **Proposed payload:** a fixed alphanumeric pattern, for example `L` + depot (2 chars) + `R` + 2-digit row + `C` + 2-digit column, giving `LD1R03C07`. Alternatively use a short prefix such as `SSL`, plus a check digit or checksum character so a misread or mangled scan is rejected.
- Use **uppercase only**. On the Turkish Q layout, lowercase "i" typed via the US I key may become dotless "ı". This is my inference from the Turkish Q layout and was not verified in the fetched KCM excerpt. Uppercase also gives smaller QR codes in alphanumeric mode.
- **Parser:** normalise to upper case (with Locale.ROOT, so `'i'.uppercase()` does not produce 'İ'), and accept the old `LOC:<row>-<col>` form plus mangled variants (`Ş`/`ş` for `:`, `*` for `-`). Validate the checksum. This way camera reads (exact) and wedge reads (possibly mangled) resolve to the same location id.
- Print the human-readable location next to the QR ("D1 · R03 · C07") for manual entry fallback.

### Gaps
- I did not check whether QR alphanumeric mode excludes lowercase. From general QR knowledge, the alphanumeric set is 0–9, A–Z, space, $ % * + - . / :, but I have not sourced that here.
- I did not test any specific cheap scanner's default layout or "Turkish Q" config barcode availability.

## 6. Combining camera sweep and handheld scanner; camera bulk-scanning SDKs and "find item" AR UX

### Takeaway
Commercial camera SDKs split the jobs the same way SweepScan could: a bulk "count" view with a counter, a progress against an expected list and a list review (Scandit MatrixScan Count), and an AR "find" view that highlights matching items among many (MatrixScan Find). A handheld scanner fits best as the fast, glove-friendly input for location labels and single boxes, while the camera does bulk sweeps.

### Cited Findings
- **MatrixScan Count UI:**
  - A counter badge counts scanned codes.
  - When scanning against an expected list, a progress bar replaces the badge, and red icons mark codes not on the list.
  - A list icon shows the scanned items.
  - A "clear screen" button clears the overlays but keeps the list.
  - A floating shutter button is available for wrist-mounted devices.
  - Scandit says the built-in UI is integral and not recommended to drop.
  - Source: [Scandit MatrixScan Count advanced](https://docs.scandit.com/sdks/capacitor/matrixscan-count/advanced/); [Get started with MatrixScan Count](https://docs.scandit.com/data-capture-sdk/ios/get-started-barcode-count.html)
- **MatrixScan Find:** a pre-built AR UI that highlights items matching predefined criteria. In the brochure, non-matching scanned items get white overlays, and the colours are customisable. The built-in UI includes buttons and overlays that guide the search. It won an iF Design Award in the UX category — [Scandit MatrixScan Find](https://scandit.com/products/matrixscan-find); [Scandit blog: iF award](https://www.scandit.com/blog/matrixscan-find-if-design-award/); [Scandit Find Flutter docs](https://docs.scandit.com/data-capture-sdk/flutter/get-started-barcode-find.html)
- **MatrixScan AR:** for custom overlays, read the tracking data through a batch listener and draw your own overlay — [Scandit: Adding AR overlays](https://docs.scandit.com/7.6.5/sdks/android/matrixscan/advanced/)
- **Socket Mobile:** offers SocketCam (camera scanning) alongside hardware scanners under the same Capture API, so camera and hardware are interchangeable inputs in one SDK — [Socket Mobile developers](https://socketmobile.com/developers/simplify-development-cycle)
- **Honeywell intent API:** distinguishes `dcs.scanner.imager` (built-in) from `dcs.scanner.ring` (paired ring), so a rugged device can route ring and imager scans differently — [cnblogs (secondary)](https://www.cnblogs.com/pythonClub/p/18911187)

### Inferences
- **Hybrid scenario for SweepScan:**
  1. Ring or handheld scanner: scan the LOC label. The location opens with a beep and vibration, and the phone, held in the other hand or mounted, shows the header.
  2. Sweep the column with the camera. The tracker counts and orders the boxes, with a MatrixScan-Count-style badge and a "vs last snapshot" progress.
  3. Use the scanner for odd single boxes, such as damaged labels or boxes in a hard-to-reach spot. Each one is an incremental add to the open location.
  4. Close by rescanning the same LOC or pressing Apply.
  5. The camera path alone is still possible: the camera reads the LOC QR at the top or bottom of the column at the start of the sweep.
- **Treat every input source as one event stream** (`LocationScanned`, `BoxScanned`, `SweepCompleted`) with a `source` field (camera, hid, spp, intent). The state machine is then the same regardless of hardware, mirroring Socket Mobile's camera/hardware unification.
- **For "find a model":** a MatrixScan-Find-style AR highlight in a live camera view of a column (matching boxes outlined in colour, others dimmed or white) is the proven UX. Pair it with the stored location answer ("D1 · R03 · C07, 4th box from top") so staff walk to the column first and then confirm visually.

### Gaps
- I did not research Dynamsoft or Cognex bulk-scan UX specifically, nor Scandit licensing costs, which are typically quote-based and not public. I found no public UX guideline document for MatrixScan Find beyond the product and docs pages.
