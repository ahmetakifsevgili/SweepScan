# Changelog

All notable changes, newest first. Versions follow the app's `versionName`.

## 3.1.0 — 2026-11-20

- **Updates from the depot phone:** shop-floor phones no longer need the APK sent around. The
  depot server tells its version (`/api/ping`) and serves the app it runs (`GET /api/app`, PIN
  required); a shop phone with an older version shows "Yeni sürüm: … · Güncelle", downloads it
  with a progress bar and hands it to Android's installer (`PackageInstaller` session), which asks
  the user and keeps the data. Checked in `DepotServerTest`: same bytes as the depot's APK, no APK
  without the PIN, and the system's confirmation screen comes up.
- **Onboarding:** icon, title and text sit together in the middle, with a short fade between pages.
- **Location search:** the typed code is centred on the search icon.

## 3.0.1 — 2026-11-19

- **Error report:** if the app closes by itself, a short report (error, app version, phone model)
  is kept on the phone, the newest five. *Ayarlar → Hata raporu* shares them as one text file or
  deletes them; nothing leaves the phone otherwise. Checked with a real crash on the emulator.
- **TalkBack:** the stock-room plan and a row's front view now say what they mark ("Depo planı,
  işaretli yer: K-A-01, B-3-01") instead of being unnamed pictures; checked in `LocationScreenTest`.
- **Faster start-up (Baseline Profile):** the new `:baselineprofile` module records what a cold
  start runs; the profile ships inside the APK (`assets/dexopt/baseline.prof`) and
  `profileinstaller` compiles it on install, also for an APK installed from a file. On the
  Android 16 emulator the median cold start went from 1003 ms to 909 ms (8 runs each, release
  build); regenerate with `gradlew -Pemulator :app:generateBaselineProfile`.

## 3.0.0 — 2026-11-19

The finished app: what is left is the field test.

- **Home-screen widget:** waiting requests (tap = the queue), *Okut* (read a box) and *Ara* (search)
  in one tap; redrawn whenever the queue changes.
- **Swipe on requests:** a waiting request's card swiped right is *Gönderdim*, swiped left is *Yok*,
  with a confirm / reject haptic; the buttons stay. The card says where a request came from
  (WhatsApp, shop phone, typed in, rehearsal group).
- **Review and send rebuilt in Compose** (the last list screen still on XML views): the count as
  a hero card with a typed-into-DİA progress bar, the PC link as a capsule with a status dot, and
  every barcode with its **model, colour and size** when the map knows them (imported list or label
  text), grouped by model and sorted by size; barcodes not known yet come last. A − / + stepper
  with a haptic tick, the send button on a bottom bar, Material 3 dialogs for send / add / clear.
- **Countdown before typing** is a full-screen ring (5 → 0) with "click into DİA's barcode field",
  instead of a dialog whose text changed every second.
- **Live progress while typing into DİA:** a progress notification ("DİA'ya yazılıyor 23 / 40 ·
  ~1 dk"); on Android 16+ a progress-centric notification that asks to be a Live Update (status-bar
  chip, Samsung's Now Bar). Leaving the app mid-send (Android then drops the keyboard) leaves it as
  "paused"; a tap brings the review screen back.
- **Find a product:** the collapsed sheet now also takes the navigation bar's height, so *Sıradakine
  git* / *Etiket bas* sit above the gesture handle instead of under it.
- Device tests run on an Android 16 emulator as well (`-Pemulator` adds x86_64 libraries; the
  release stays arm64): all 37 pass on the R8 build.
- **App language** can be chosen per app in the phone's settings (Android 13+; Turkish / English).
- Dark start screen on Android 12+ (no white flash); the Rows list shows a spinner while it loads
  instead of an empty screen; a shorter search hint on Home that is not cut off.
- Screenshot tests also capture the review screen and the widget; `ScreensSmokeTest` presses the
  Compose review screen through the accessibility tree (Espresso still fails on Android 17).
- `SendNotificationTest`: the progress notification is posted, ongoing, progress-centric with the
  Live Update request, and cancelled. On an Android 17 emulator the system promoted it (status-bar
  chip); smoke and notification tests pass there (3 / 3).

## 2.4.0 — 2026-11-19

The WhatsApp automation made safe and rehearsed; every request on one path.

- **Answers can no longer reach the wrong chat.** The group was matched by "the title contains the
  group's name", and the reply action of any such notification was kept: a one-to-one chat with
  someone whose name contained the group's name could have received "✅ Gönderdim". Now only a
  *group conversation* whose name starts with the configured one counts, and the reply action is
  kept together with its chat; an answer is only written into the request group.
- **Our own replies are not requests.** WhatsApp shows our "✅ Gönderdim: 0159 2000 39" back in the
  chat; it could have been read as a new request for that code. Messages of the phone's owner and
  our reply texts are skipped.
- **No message lost.** Duplicates are recognised by the message itself (time, sender, text, kept 2
  hours) instead of "newer than the last one": a late message or two in the same millisecond are
  both taken; request ids are unique; notification ids no longer overflow.
- **One intake:** WhatsApp, shared messages, shop-floor phones, typed and rehearsal requests all go
  through `Intake`; what "Gönderdim" / "Yok" does depends on the source (group reply, shop phone
  reads the status, typed has no chat). The Requests screen refreshes the moment anything changes.
- **Rehearsal group (presentation mode):** scripted messages are built as WhatsApp group
  notifications and go through the real path; the answers land in a rehearsal chat on the Requests
  screen instead of WhatsApp.
- Tests: `WhatsAppFlowTest`, 17 scenarios with fake WhatsApp notifications whose reply actions lead
  to the test itself (nothing reaches WhatsApp; the debug app has no notification access). The
  screenshot tests draw the 3D card off screen, so the answer screenshot shows it.
- `docs/olcum-formu.md` + `tools/make_measure_form.py`: measuring sheet for the real stock rooms.

## 2.3.0 — 2026-11-19

3D, done properly; ready for a demo video.

- **Flight to the shelf in 3D.** Every answer card now has a short film: the camera starts high
  above the entrance, glides over the walking route, comes down and stops in front of the rack;
  the found boxes glow amber on their shelf (pulsing), a pin turns above them, a frame on the
  floor marks where to stand, and the latest photo of that shelf stands beside the rack, turned
  to the camera. No gestures needed; tap to replay. It draws only while on screen and stops a
  while after landing.
- Rendered with **Filament 1.75.1** (Google's physically based renderer): low sun with soft
  shadows, sky / floor ambient light, ambient occlusion, bloom on the glow, 4× MSAA, distance fog.
  The scene is built in `:core` (`TwinMesh`: boxes → five-face meshes grouped by look; `Glb`: a
  binary glTF writer with vertex colours, emissive strength, an unlit embedded JPEG; `FlyPath`: the
  smoothed, eased camera path) and loaded with gltfio. APK +2.9 MB.
- **Presentation mode** (Settings): sample map, no "sample data" banner, a separate request list
  with requests arriving by themselves every 40 s from shop-floor roles; the real queue and the
  WhatsApp group are not read, shown or answered meanwhile.
- **Demo package:** `docs/demo-video.md` (preparation, shot list, LinkedIn posts in Turkish and
  English) and `tools/make_carousel.py` (8-page LinkedIn document from the phone screenshots).
- Tests: `TwinMeshTest` (faces wind outward, the floor faces up, a valid GLB, the flight starts
  high, lands in front of the shelf and never jumps between frames) and the device test
  `ShelfSceneTest`, which renders the flight off screen and checks the landing shows the glow.

## 2.2.0 — 2026-11-18

Cleaner, easier to use.

- **The answer sheet can be closed.** On Find location the bottom sheet can now be pushed all the
  way down or closed with ✕; the map then uses the whole screen, and *Show details* brings the
  sheet back. A new search opens it again by itself. Before, the sheet could only shrink to its
  low height and sprang back.
- **No 3D on the phone.** The 3D view did not help in the stock room (the 2D plan, the row's front
  view and the shelf photo answer "where" faster), so the toggle, the renderer and its tests are
  gone. The web panel keeps its 3D view; its shaders now live with the server.
- **Modern camera screen:** the picture fills the screen and the controls float on soft gradients;
  torch and settings are round buttons at the top; zoom is one capsule above the buttons, as in a
  phone's camera app; undo / finish are translucent pills either side of the main button.
  Barcode frames are rounded, counted boxes get a green check badge and their last five digits in a
  capsule. Reading one box (*Barkod okut*) shows an aiming frame that turns green while a label is
  read. Task names in sentence case.
- **Home:** a big *Barkod okut* button at the bottom, in thumb reach; the requests tile no longer
  repeats the waiting count.

## 2.1.0 — 2026-11-18

Review pass and the find screen fixed.

- **Find location: the place is visible.** The map now follows the bottom sheet: when the
  sheet settles half open (as it does for every answer), the map is fitted to the part above it
  and the found row and route are brought into view. Before, the highlight sat behind the sheet.
  Depot chips and the 2D / 3D switch share one row, so the map gets the room. 2D is the default.
- **3D moves like a map:** one finger drags the floor (the point under the finger follows it),
  pinch zooms and twist turns around the point between the fingers, two fingers up or down tilt;
  round zoom in / zoom out / show everything buttons above the sheet. Gesture maths in `:core`,
  tested.
- Quieter answer sheet: one filled main action, outlined secondary ones; sizes in one scrolling line.
- **Depot server security:** wrong PINs are limited (5 per address, 30 overall per minute, then a
  minute's wait), no CORS headers (no other web page can call the API), "busy" instead of a false
  "asked" when the depot cannot answer in time.
- Shelf photos: only the shelf around the label is cut from the frame on the camera thread; an
  in-memory index means searching never reads files on the main thread; photos are decoded in the
  background; sweeping the same row again takes new photos.
- Smaller fixes: the requests screen refreshes only while shown, the 3D scene is not rebuilt while
  2D is shown, home reads its status once, turning a depot phone into a shop phone asks first.

## 2.0.0 — 2026-11-18

A professional base: new screens, a smaller app, a clean core.

- **Jetpack Compose + Material 3** for the home screen, requests, the shop-floor screen, the row
  list and settings, on one design system (`ui/theme`: palette with amber only for targets,
  status colours that always come with a word, type scale, shapes, shared components). The camera,
  2D plan and 3D view stay Views.
  - Home: big job tiles with live status lines; requests move to the top in amber while some wait;
    the shop link's address and PIN when it is on.
  - Requests: today's numbers as tiles (today, sent, not there, waiting, average minutes, from the
    shop), where each request came from (WhatsApp / shop app), refreshes by itself.
  - Shop floor: MVVM (`ShopViewModel`, `StateFlow`), PIN and name in Material dialogs, "In the
    depot: …" while typing (debounced).
  - Rows: this week's progress bar, the suggested row framed, status pills.
  - Settings: grouped cards.
- **R8** for the release build: 27.1 → 24.0 MB with Compose added (22.7 MB before Compose). Keep
  rules for OpenCV's native code; every device test also runs against an R8 build (`-Pr8test`),
  including a new OpenCV optical-flow test.
- **`:core` module** in plain Kotlin: tracker, layout and routes, sweep diff, shelf level, the 3D
  scene and camera maths, the request parser; its 126 tests (with the replayed phone recordings)
  run on any JVM.

## 1.5.0 — 2026-11-18

The shop floor and the depot on one system, and answers you can see.

- **Shelf photos:** while a row is swept, the first read of each label keeps a small photo of the
  shelf around it (the label framed in amber). The answer card shows the latest one ("Son
  görüldüğü an · dün 14:20"); tap for a large view. Latest per label, ≈ 30 KB each; Settings →
  *Raf fotoğrafları* shows how many and deletes them.
- **Which shelf:** the phone's tilt (game rotation vector) plus where the label was in the
  picture give the shelf from the floor: "alttan 2. raf". The row strip and the 3D view mark only
  that shelf. Settings → *Kat ayarı*: phone height, distance to the rack, shelf height.
- **Shop floor ↔ depot over the shop's Wi-Fi, no account:** Settings → *Mağaza bağlantısı* turns
  on a small server on the depot phone (foreground service, mDNS, 4-digit PIN). A phone set to
  *Bu telefon mağazada* opens on the **Mağaza** screen: scan the shoe (its barcode is turned into
  model and size by the depot), say or type the code, see "In the depot: B-3 · ortalarda" while
  typing, and follow each request (*Alındı → Bakılıyor → Gönderildi / Yok*) with a buzz on change.
  The depot gets the usual request notification; shop requests are not answered in WhatsApp.
- **Depo paneli:** the same server serves a page for the shop PC's browser: today's numbers,
  the live queue with *Bakıyorum / Gönderildi / Yok*, requests by hour, most asked models, and the
  stock rooms in 3D (the app's own scene and shaders, WebGL2) with waiting requests' rows lit.
- 3D: no rack roof (the top shelf shows from above); depth range follows the camera so the route
  no longer flickers from far away; frame capture for tests.
- Tests on the phone: the 3D renderer on the GPU off-screen, shelf photos on the answer card, the
  server end to end (PIN, scanned barcode → request with its place, status back, panel, scene).

## 1.4.0 — 2026-11-18

First step of the "Pro" plan: the stock rooms in 3D.

- **3D view** on *Yer bul* (2D / 3D switch at the top right, remembered): racks with their
  shelves, shoe boxes on every shelf, low cut-away walls, a 1 m floor grid. Flies in on first
  open; one finger turns, two fingers move, pinch zooms, twist turns, double tap shows everything
  again, tap a rack to choose it.
- A found model: its band of the row glows, a pin and a beam of light above it, a frame on the
  floor where to stand, the route flowing towards it; the camera flies to stand in front of it.
  Other rows dim. Pick lists show numbered stops; with no search the floor in front of each row
  shows how fresh its last sweep is.
- Own OpenGL ES 3.0 renderer (one instanced cube, ~8 000 boxes in two draw calls, lighting,
  edges and fog in the shaders): no 3D library, the APK stays at 27 MB.
- `tools\twin_preview.bat` draws the same scenes with the same shaders in a browser (WebGL2), to
  check the look on a PC; scene, camera, picking and fitting maths are unit-tested.

## 1.3.0 — 2026-11-17

Final pass before the phone test and the stock room.

- Requests: own vibration pattern; a request opened from its notification, the queue or after
  *Say* shows Sent / Not there on the answer card; the requests card moves to the top of the
  home screen with the number waiting.
- Search: bigger, bold "where along the row"; similar codes suggested when a code is not on the
  map; recent searches remember where the model was ("0159 2000 · B-3").
- Rows: this week's progress in the row list; Finish says how many labels have no model yet.
- Texts cleaned of the old column/QR wording; shorter counting guide; unused texts removed.
- MIT licence; rollout, test checklist, demo script and CV notes in `docs/yayin-ve-cv.md`.

## 1.2.0 — 2026-11-17

The request loop closed: from "someone asked" to "the floor knows it is on its way".

- **Talepler** (requests queue): today's numbers (requests, sent, not there, waiting, average
  time to answer); waiting requests first, coloured by age (green < 5 min, yellow < 15, red);
  **Gönderdim**, **Yok**, **Bakıyorum**; long press on a done one puts it back; **Collect all in
  one walk** turns the waiting requests into the shortest pick route; add a request by hand.
- **Replies in the group** ("✅ Gönderdim: 0209 13243 46", "❌ Stokta yok", "⏳ Bakıyorum")
  through WhatsApp's own reply action, also from the notification's buttons; recovered after a
  restart from the notifications still shown; when WhatsApp's notification is gone the answer
  is copied and the chat opened to paste it. Can be turned off.
- **Not there:** "Yok" for a model the map placed, or *Burada değil* on the answer card, flags the
  row; flagged rows come first in the row list ("kontrol et") until swept again.
- The same code asked again within 20 minutes is one request. The listener asks to be rebound
  when Android drops it; the home screen says when notification access is gone.
- Launcher shortcuts (Söyle, Ürün bul, Talepler, Raf tara), a quick settings tile to say a code,
  map backup to one file and restore, Android backup of the map, layout, settings and requests.

## 1.1.0 — 2026-11-17

Requests from the shop floor's WhatsApp group, answered by the app.

- **Written requests** ("0209 13243 siyah açma 46") in the chosen group ("Ürün isteme telsiz") are
  read from WhatsApp's notification and answered with a notification: "0209 13243 → Büyük depo ·
  3. sıra · ortalarda · soldan 5. model · 46 numara · Ayşe 16:53"; tap for the route.
- **Voice requests** get a notification with *Dinle* (opens the chat) and *Söyle* (say the code;
  the phone's Turkish speech recognition finds it). The app cannot hear voice notes itself.
- Recent requests on the home screen; a microphone in the search bar.
- Ayarlar → WhatsApp talepleri: on/off, group name, the two Android steps for notification
  access of an app installed from a file. Only that group's notifications are read; nothing
  leaves the phone.

## 1.0.1 — 2026-11-17

Rows are skimmed, not counted: walk along a row and read one box per model.

- The camera beeps only for a label not read before in the row; the counter shows labels read.
- No box counts on the map: answers show the sizes seen ("38 · 39 · 40"); "what changed" is
  presence only (+new · −gone).
- The model is learned from two agreeing reads of the label text, read twice as often; model
  codes of other brands ("178 137581") are recognised.

## 1.0.0 — 2026-11-17

Built around the three jobs people actually do, not around the camera.

- **Home screen** (the app starts here): search bar with a camera button (scan a box to search),
  three big job cards — *Ürün bul*, *Raf tara*, *Sevkiyat say* — with live status (how many rows
  are up to date and which is next; PC connected or not), recent searches.
- **One job per camera screen:** counting a delivery, sweeping a row, finding boxes, scanning to
  search. No mode switching on the camera; a back chip names the job. The PC chip and Bluetooth
  permission belong to counting only; the camera permission is asked when a job needs it.
- **Row list** for *Raf tara*: every row with how fresh it is, the suggested next one on top.
  **Finish** shows what was saved and changed, Undo, and "Next: B-4 →". Leaving with an unsaved
  row asks first.
- **Answer first:** a search shows a big card — depot and row, where along the row, sizes, a strip
  of the row, Route and Find in camera — with size chips ("39") and the other places below.
  Scanning or typing a barcode shows its whole model with that size chosen. Empty map and
  not-found states say what to do.
- **Settings in one place:** PC connection, layout and labels, import/export, sample data; the
  camera's test settings hidden under *Gelişmiş*.
- **Introduction** on first start (3 pages, "try with sample data").

## 0.7.1 — 2026-11-17

Rows instead of columns: no labels needed.

- **A location is a whole row** ("Büyük depo · 3. sıra"). In map mode you choose the row on
  the screen (the next row is preselected), sweep it from its left end, pause as often as needed
  (the row stays open) and **Finish** saves it. A QR per row still works but is optional.
- **Where along the row:** the sweep order gives the place: "ortalarda · soldan 5. model". The
  plan shows a dot on the row and routes to it; the front view shows a band at that spot. The
  row page lists the models left to right with their sizes.
- Small depot racks have 5 shelves. Search accepts "B-3".
- Database version 3 (`lastOrder` per barcode), migration from 0.7.0 keeps everything.

## 0.7.0 — 2026-11-17

Location map, second version: history you can trust, a redesigned Find location screen.

- **Sweep history.** Every column sweep is kept (database version 2, `sweep` + `sweep_line`);
  a re-sweep no longer erases what was there. The 0.6.0 database is migrated with every swept
  column as the first sweep of its history (migration tested on the device).
- **What changed.** After each column the scan screen shows "+2 new · −1 gone" and offers
  **Undo**. A column's page lists its history with the difference to the sweep before.
- **Find location, redesigned.** Full-size plan with a bottom sheet (peek / half / full), depot
  chips, overview of the map's freshness, "sweep next" list, last sweeps.
- **Plan.** Muted base with outlined racks; amber only for what was searched, a white ring for
  the chosen column, a cased blue route (colour-blind safe), hatched racks for old sweeps,
  labels that never cover a column, animated focus.
- **Pick list.** Add search hits or columns, get the shortest walking order from the entrance
  (nearest neighbour + 2-opt over corridor distances), numbered stops on the plan.
- **Find in camera.** From any result: the scan screen marks the wanted boxes ("BURADA"),
  beeps and vibrates when one appears; nothing is counted.
- **Sample data.** Menu → Show sample data: a separate demo database (12 models, history), so
  the app can be tried without the shop; the real map is never touched.
- **Front view.** Shelf numbers, the target's column number highlighted.
- **Big depot redrawn from the numbered sketch:** 10 rows (the second island is read from both
  sides: 9 and 10), the middle island's solid core and the block by the door drawn as obstacles;
  in the small depot A and B face each other across one aisle (photo). Location codes accept
  two-character rows (`LOCB1003` = B-10-03).
- CI on GitHub Actions (unit tests, lint, debug APK).
- **Smaller APK (83 → under 30 MB)**, so it can be shared as a file: native libraries and code
  compressed in the APK; the MediaPipe appearance embedder removed (it was off: on real labels it
  scored the same and different barcodes alike, 0.59 vs 0.59).

## 0.6.0 — 2026-11-17

- Location map for three depots: map mode (column QR label, then sweep), search by model /
  barcode / location, plan with route from the entrance, front view of a row, layout editing,
  A4 QR labels printed from the phone, CSV import (ERP model list) and export.
- QR payload `LOCB307` (letters and digits only, survives a Turkish keyboard layout on a
  Bluetooth scanner); older `LOC:B-3-07` labels still read.
- Model learned from the label's model line by text recognition (2 of 3 reads agree).

## 0.5.2

- Printed-number fallback (OCR) when the bars cannot be read, live typing into the PC,
  review screen, crash fix on Finish.

## 0.4.0

- New scan screen, automatic zoom, re-identification when sweeping back up a column; tested on
  the phone.

## 0.3.x

- Camera defaults from the first phone test, settings tour, recording viewer, realistic emulator
  tests, stacks of identical boxes counted correctly.
