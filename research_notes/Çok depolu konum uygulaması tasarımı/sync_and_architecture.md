# Data sharing/sync options and a portfolio-quality architecture for SweepScan (2-5 users, 3 stock rooms, offline basement)

Research date: 2026-11-17. Free-tier numbers were read from the official pricing/limits pages on that date.

## Q1. Sharing/sync options and trade-offs: (a) one phone + CSV export, (b) Google Sheets, (c) Firestore, (d) Supabase (+PowerSync), (e) self-hosted/edge server

### Takeaway
At 2-5 users and a few thousand sightings a day, every hosted option's free tier is far larger than SweepScan needs. Firestore is the cheapest path to real multi-device offline sync for one developer: its Android SDK has offline persistence on by default and handles queued writes and last-write-wins. Supabase is a Postgres-plus-SQL story that looks good on a CV, but it needs PowerSync (or a custom outbox) for offline use, and free projects pause after a week of inactivity. Google Sheets is best as a read-only mirror for coworkers, not as the sync backend. The main reasons are a 60 requests/min per-user quota and the fact that service-account keys must not ship inside an APK.

### Cited Findings

**(c) Firebase Firestore, Spark (free) plan**
- Firestore Standard edition on Spark: "1 GiB total" stored data, "10 GiB/month" egress, "50K reads/day", "20K writes/day", "20K deletes/day". Enterprise edition on Spark: 1 GiB, "40K write units/day", "50K read units/day", "50K realtime update units/day". — [Firebase pricing](https://firebase.google.com/pricing)
- Authentication on Spark: "50K MAUs" (Identity Platform). The pricing page does not list a separate limit for anonymous or email-link auth. Phone/SMS auth shows "Not applicable" on Spark and is billed per SMS on Blaze. — [Firebase pricing](https://firebase.google.com/pricing)
- Hosting on Spark: "10 GB" storage and "360 MB/day" transfer, with custom domain and SSL. This is enough for a small read-only lookup web page. — [Firebase pricing](https://firebase.google.com/pricing)
- Cloud Storage entries on Spark read "Not applicable", so storing photos of labels in Firebase would require Blaze. App Hosting is Blaze-only. Quotas apply "at the project-level". — [Firebase pricing](https://firebase.google.com/pricing)
- Firebase SQL Connect (the Postgres product) is only a 3-month trial on Spark. If the project is not upgraded, the instance is archived and then deleted after a further 90 days, so it is not a free long-term option. — [Firebase pricing](https://firebase.google.com/pricing)
- Offline behaviour: "For Android and Apple platforms, offline persistence is enabled by default." The default cache is 100 MB and can be changed through `PersistentCacheSettings` (minimum 1 MB, or `CACHE_SIZE_UNLIMITED`). Local writes are synced on reconnect, and "For multiple changes to the same document, it's last write wins." — [Firestore offline docs](https://firebase.google.com/docs/firestore/manage-data/enable-offline)
- Offline queries run against cached documents only. A collection query with nothing cached returns empty, while a single-document get returns an error. `isFromCache` on the snapshot metadata marks data that may be stale. Metadata-only changes need `MetadataChanges.INCLUDE` to fire. — [Firestore offline docs](https://firebase.google.com/docs/firestore/manage-data/enable-offline)
- The official Android offline-first guide names Firestore as the "managed" alternative to a custom Room+WorkManager stack: "The SDK handles disk caching, offline write queuing, and bidirectional sync automatically." — [Android offline-first guide](https://developer.android.com/topic/architecture/data-layer/offline-first)
- Security-rules patterns:
  - Rules can compare `request.auth.uid` against document data, or read a user doc such as `get(/databases/(database)/documents/users/$(request.auth.uid)).data.admin == true`. Each such `get()` in rules is billed as a read.
  - Custom claims, set server-side with the Admin SDK, avoid that extra read. — [Firebase rules and auth](https://firebase.google.com/docs/rules/rules-and-auth?authuser=8)
- Anonymous auth: a community cookbook advises limiting what anonymous users can do. The sign-in provider value in the token can be checked to block anonymous users (an unverified Stack Overflow suggestion). — [Fireship rules cookbook](https://fireship.io/snippets/firestore-rules-recipes); [SO mirror](https://kiwix.lema.org/stackoverflow.com_en_all_2023-11/questions/53419498/firebase-rules-how-to-block-anonymous-access)

**(d) Supabase, Free plan**
- Free plan limits:
  - "500 MB database size" per project, 500 MB RAM on Shared CPU
  - 5 GB egress plus 5 GB cached egress
  - 50,000 MAU
  - 1 GB file storage
  - 500,000 Edge Function invocations
  - "Limit of 2 active projects"
- Pause policy: "Free projects are paused after 1 week of inactivity." — [Supabase pricing](https://supabase.com/pricing)
- PowerSync (an offline-sync layer for Postgres/Supabase):
  - Free plan: 2 GB synced per month, 50 peak concurrent clients, 500 MB hosted.
  - Inactivity: free projects are deactivated after 1 week. The same page also says 2 weeks, which is inconsistent.
  - Pro plan from $49/month. A free self-hostable "Open Edition" also exists. — [PowerSync pricing](https://www.powersync.com/pricing)
- PowerSync Kotlin SDK (`com.powersync:core`):
  - Targets Android, JVM and Apple through KMP.
  - "The SDK places every write to the SQLite database in an upload queue." Your `PowerSyncBackendConnector.uploadData` forwards the queued writes to your backend.
  - A `connector-supabase` artifact exists, and the SDK "works with SQLDelight and Room". — [PowerSync Kotlin SDK docs](https://docs.powersync.com/client-sdk-references/kotlin-multiplatform)
- ElectricSQL: not researched in this pass. See Gaps.

**(b) Google Sheets as backend**
- Sheets API v4 quotas:
  - Reads: 300/min per project, 60/min per user per project.
  - Writes: the same limits as reads.
  - A batch request counts as one request.
  - Requests time out after 180 s, and a 2 MB payload is recommended.
  - Exceeding a quota returns HTTP 429. Retry with exponential backoff: 2^n s plus up to 1,000 ms jitter, capped at 32-64 s. — [Sheets API limits](https://developers.google.com/workspace/sheets/api/limits)
- Cost: standard use is free, but Google says exceeding request quotas "is planned to incur charges" to a Cloud billing account later in 2026. — [Sheets API limits](https://developers.google.com/workspace/sheets/api/limits)
- Service-account auth from an Android app is widely flagged as unsafe. Advice includes "Never distribute service account keys with your app", obfuscation "only delays extraction", and the recommended fix is a server-side proxy. — [SO mirror](https://kaweah.freedombox.rocks/kiwix/content/stackoverflow.com_en_all_2023-11/a/45508703); [Guardsquare](https://www.guardsquare.com/blog/google-api-key-restirctions-mobile-app-security); [dev.to](https://dev.to/brightdevs/securing-your-google-service-account-key-in-builds-447j)
- Apps Script web app as a mini API:
  - `doGet`/`doPost` handlers, with `e.parameter` and `e.postData.contents` carrying the request data.
  - It can return JSON through ContentService or HTML through HtmlService.
  - "Execute as me" means "the script always executes as you, the owner of the script, no matter who accesses the web app". This keeps credentials server-side. — [Apps Script web apps](https://developers.google.com/apps-script/guides/web)
- Apps Script quotas, consumer (gmail.com) account vs Workspace:
  - Script runtime: 6 min per execution on both.
  - Simultaneous executions: 30 per user and 1,000 per script on both.
  - URL Fetch: 20,000/day (Workspace: 100,000).
  - Trigger runtime: 90 min/day (Workspace: 6 h). — [Apps Script quotas](https://developers.google.com/apps-script/guides/services/quotas)

**(e) Small self-hosted / edge server**
- Cloudflare D1 on the Workers Free plan: "5 million / day" rows read, "100,000 / day" rows written, "5 GB (total)" storage. Limits reset at 00:00 UTC. The page did not state the Workers free request limit. — [Cloudflare D1 pricing](https://developers.cloudflare.com/d1/platform/pricing/)
- PowerSync Open Edition can be self-hosted and supports Postgres, MongoDB, MySQL and SQL Server. — [PowerSync pricing](https://www.powersync.com/pricing)

**(a) One phone as source of truth + file export**
- The Android guide notes that local data sources can be "Simple files" or Room, and that "The local data source is the canonical source of truth". A single-device app with export is just the degenerate case with no network data source. — [Android offline-first guide](https://developer.android.com/topic/architecture/data-layer/offline-first)

### Inferences
- **Volume check (rough estimate):**
  - Assumed load: one sweeper logging about 500-2,000 sightings/day, plus 2-4 searchers.
  - Firestore: 20K writes/day and 50K reads/day leave plenty of headroom if sightings are written one doc each and searchers read from the local cache or a denormalized "column snapshot" doc.
  - Risk: a fresh install that downloads the whole sightings history, or listeners on large collections. Mitigate by syncing only current column snapshots plus recent sightings.
- **Recommended phasing:**
  - **(1) Now:** single phone, Room, plus a CSV/XLSX export and an "import from file" for the barcode→model list. Zero infrastructure, and it ships before the field test.
  - **(2) Next:** Room stays the source of truth, a sync module pushes an append-only sightings outbox to Firestore through WorkManager, and other phones pull.
  - Optional: a Cloud-Function-free mirror for non-app viewers (see Q3).
  - This keeps Firestore behind a `NetworkDataSource` interface, so it can be swapped for Supabase or a self-hosted server later. That swappability also reads well in a CV write-up.
- **Firestore SDK as the local DB vs Room:** using the Firestore SDK alone as the local DB (no Room) is less work, but FTS is impossible and queries are limited. SweepScan needs partial model-code search, so Room stays the local source of truth and Firestore is only a transport.
- **Auth for 2-5 coworkers:**
  - Simplest workable option: Firebase anonymous auth, plus an allowlist of UIDs in a `members` doc that the owner approves by hand. Each phone shows its UID or a join code.
  - Better option: Google sign-in or email-link sign-in, with the allowlist keyed by email.
  - Either way, security rules should deny everything unless the UID is a member.
- **Supabase trade-offs:**
  - The 1-week pause is a real risk for a shop app that may sit idle during holidays. A keep-alive ping is a workaround but against the spirit of the free tier.
  - PowerSync's free tier also deactivates on inactivity.
  - Two moving parts (Supabase + PowerSync) are heavy for a one-person project. They do make the strongest "offline-first Postgres" CV story.
- **Sheets as primary backend is fragile:**
  - The 60 req/min per-user quota.
  - No server-side conflict handling.
  - Row-index addressing breaks under concurrent edits.
  - An OAuth consent setup is needed for each coworker.
  - Use it only as an output (mirror) or for the ERP model-list import.
- **Self-hosting:** Cloudflare Workers + D1 is the "small self-hosted server" with no machine to babysit. It does mean writing your own sync API, auth and conflict logic, which is the most work.
- **Privacy:** the data is stock locations and model codes, not personal data, apart from "who swept". Store a short display name or user ID, not emails, in sightings. Google/Firebase and Supabase both host outside Türkiye. Check that the shop owner is fine with stock data in a third-party cloud.

### Gaps
- ElectricSQL's current (late 2026) Android/Kotlin story and pricing were not checked.
- Firebase's pricing page gives no separate numbers for anonymous or email-link auth. It is assumed they count toward the 50K MAU, but this is unverified.
- Not checked against official docs:
  - Firestore offline behaviour of `FieldValue.serverTimestamp()`, which typically shows null or an estimate until acknowledged.
  - Transactions failing offline.
  - `hasPendingWrites`.
- Google Sheets user-OAuth from Android (Credential Manager + AuthorizationClient scopes for Sheets) was not researched in detail.
- PocketBase or other single-binary self-hosted backends were not researched.
- The Workers Free daily request limit was not on the D1 page.

## Q2. Offline-first sync patterns on Android (Room as source of truth, outbox/WorkManager, LWW per column, conflicts, tombstones, server timestamps)

### Takeaway
Google's official pattern is: Room is the canonical source of truth, the UI observes Flows from Room, writes go to Room first ("lazy writes") and are queued, a unique WorkManager job with a CONNECTED constraint drains the queue with exponential backoff, and conflicts are resolved with last-write-wins by timestamp. For SweepScan, the cleanest model is to make sightings immutable and append-only, which avoids most conflicts. "What is in column X now" then becomes a derived per-column snapshot, resolved by the latest sweep.

### Cited Findings
- "The local data source is the canonical source of truth for the app. It should be the exclusive source of any data that higher layers of the app read." — [Android offline-first guide](https://developer.android.com/topic/architecture/data-layer/offline-first)
- The guide describes three write strategies:
  - **Online-only writes**, for things like bank transfers.
  - **Queued writes**, for analytics and logs.
  - **Lazy writes**: "Write to the local data source first, then queue the write to notify the network at the earliest opportunity… This approach is the correct choice when the data is critical to the app." — [Android offline-first guide](https://developer.android.com/topic/architecture/data-layer/offline-first)
- Last write wins: "devices attach timestamp metadata to the data they write to the network. When the network data source receives them, it discards any data older than its current state while accepting those newer than its current state." — [Android offline-first guide](https://developer.android.com/topic/architecture/data-layer/offline-first)
- Pull-based sync suits short offline periods. Push-based sync means "The app can remain offline indefinitely" and "Works well for relational data", but "Versioning data for conflict resolution is nontrivial" and the network data source must support sync. — [Android offline-first guide](https://developer.android.com/topic/architecture/data-layer/offline-first)
- How Now in Android runs its sync worker:
  - `enqueueUniqueWork(SyncWorkName, ExistingWorkPolicy.KEEP, …)` at startup.
  - Expedited with `RUN_AS_NON_EXPEDITED_WORK_REQUEST` as the fallback.
  - `NetworkType.CONNECTED` constraint.
  - A `CoroutineWorker` that returns `Result.retry()` on failure, which gives automatic exponential backoff. — [Android offline-first guide](https://developer.android.com/topic/architecture/data-layer/offline-first)
- "For firmer guarantees about the order in which the queue is drained, use a more robust queue implementation with a data persistence API like Room or DataStore. A Worker can then be set up to sequentially drain this queue." — [Android offline-first guide](https://developer.android.com/topic/architecture/data-layer/offline-first)
- The guide recommends separate local and network models (e.g. `AuthorEntity`/`NetworkAuthor`) and an external model (`Author`), with `asEntity()`/`asExternalModel()` mappers kept internal to the data layer. — [Android offline-first guide](https://developer.android.com/topic/architecture/data-layer/offline-first)
- Firestore's own conflict policy for offline writes to the same document is last write wins. — [Firestore offline docs](https://firebase.google.com/docs/firestore/manage-data/enable-offline)
- PowerSync implements the same outbox idea internally: every local SQLite write goes into an upload queue, and `uploadData` forwards it to the backend. — [PowerSync Kotlin SDK docs](https://docs.powersync.com/client-sdk-references/kotlin-multiplatform)

### Inferences
These are design recommendations derived from the cited patterns.

- **IDs:** use client-generated UUIDv7 (or ULID) primary keys for every synced row (sighting, location, depot). Offline inserts on two phones then never collide, and the IDs sort by time.
- **Sightings are immutable events:**
  - Fields: `id`, `location_id`, `barcode`, `model_code?`, `box_count`, `seen_at` (device clock), `device_id`, `user_id`, `sweep_session_id`.
  - Never update them, only insert or soft-delete (`deleted_at`). Inserts from two phones merge trivially, with no conflict.
- **Column snapshot = derived state:**
  - The current contents of a column come from the most recent sweep session that covered that column.
  - Rule (LWW per column): take the sweep session with the greatest `finished_at` for that `location_id`. Its sightings are the truth for the column, and older sweeps are history.
  - If two people sweep the same column offline, the later finish wins. The earlier sweep stays visible as history ("Ali saw 3 boxes at 10:02, Ayşe saw 2 at 10:15"). Nothing is lost, and the UI can flag a disagreement when two sweeps within N minutes differ.
- **Outbox table:** `outbox(id, entity, entity_id, op, payload_json, created_at, attempts)`.
  - Write it in the same Room transaction as the domain write.
  - A single unique `SyncWorker` drains it in order, marks rows done, and returns `Result.retry()` on network errors.
  - Pull uses a per-collection cursor (`last_synced_server_time` stored in DataStore). The server or Firestore query returns rows with `updated_at > cursor`.
- **Tombstones:** deletes of layouts, locations or sightings set `deleted_at` and `updated_at`, and they sync like updates. Other phones need to learn about deletes. Purge tombstones older than about 90 days locally.
- **Clocks:**
  - Server time (Firestore `serverTimestamp`, or Postgres `now()`) is used for ordering the sync cursor.
  - Device time (`seen_at`) is used for display and LWW between sweeps.
  - Phone clocks are usually NTP-synced. Keep both values, and never use device time as the pull cursor.
- **Reference data:** the barcode→model mapping and the rack layout change rarely and are edited mainly by the sweeper or owner. Plain LWW per row (`updated_at`) is fine for them.

### Gaps
- No primary source was fetched for Firestore `serverTimestamp` estimates while offline (`ServerTimestampBehavior.ESTIMATE`). Verify this in the Firestore docs before relying on it.
- No measured data was found on real-world clock skew on Android phones.

## Q3. How coworkers without the app can look up a location (read-only web page or Google Sheet)

### Takeaway
There are three viable options. The easiest is a Google Sheet that the app (or an Apps Script) fills with a flat "model → depot/row/column/boxes/last seen" table, shared view-only and searched with Ctrl+F on any phone. A static or Firebase Hosting page (10 GB storage, 360 MB/day free) can read a public read-only snapshot. An Apps Script `doGet` web app with HtmlService can serve a search box while running "as me", with no credentials on the client.

### Cited Findings
- Firebase Hosting on Spark: "10 GB" storage and "360 MB/day" transfer, with custom domain and SSL included. — [Firebase pricing](https://firebase.google.com/pricing)
- An Apps Script web app can return an HtmlService page or JSON from `doGet`. "Execute as me" runs the script under the owner's identity "no matter who accesses the web app". — [Apps Script web apps](https://developers.google.com/apps-script/guides/web)
- Apps Script consumer quotas: 6 min per execution, 30 simultaneous executions per user, 20,000 URL Fetch calls/day. — [Apps Script quotas](https://developers.google.com/apps-script/guides/services/quotas)
- Sheets API writes: 60/min per user, and a batch counts as one request. A periodic batched `values.update` of the whole lookup table is well inside quota. — [Sheets API limits](https://developers.google.com/workspace/sheets/api/limits)

### Inferences
- **Lowest effort, no backend:** the sweeper's app exports `konumlar.csv`/`.xlsx` and shares it, for example to a WhatsApp group or Google Drive. Coworkers open it in Sheets. This needs no code beyond the export that already exists.
- **Live mirror without keys in the APK:**
  - The app POSTs a JSON snapshot to an Apps Script `doPost` (deployed "Execute as me"), with a shared secret in the body.
  - The script writes the Sheet, and the Sheet is shared view-only with coworkers. The Apps Script URL acts as an unguessable endpoint, and the secret limits abuse.
  - This is weaker than real auth but appropriate for non-sensitive stock locations.
- **If Firestore is the backend:**
  - Expose a `public_lookup` collection (model_code → locations) that the app writes on sync. Make it readable with no auth, or protect it with a simple shared link token.
  - Host a tiny static search page on Firebase Hosting.
- **Comparison:** the Sheet option is the friendliest for non-technical coworkers (familiar UI, works on any phone). The web page is the better portfolio piece.

### Gaps
- The "Who has access" options for Apps Script web apps (Anyone vs Anyone with Google account) were not stated on the fetched page. Confirm them in the deployment dialog.
- Sheets' own limits on cell count and file size for a large lookup table were not checked.

## Q4. Architecture that looks professional on a CV without over-engineering (modules, Room schema + FTS, migrations, DI, navigation, testing, CI)

### Takeaway
Copy Now in Android's shape at a reduced size: `:app`, a handful of `:core:*` modules (model, database, data, designsystem, testing, and later network/sync) and 3-4 `:feature:*` modules (scan, map, search, settings). Use Hilt, Room with exported schemas, AutoMigrations and a `MigrationTestHelper` test, and FTS on model codes. Test with fakes rather than mocks, Robolectric/Roborazzi screenshot tests on the JVM, and a GitHub Actions workflow that runs lint, unit tests and screenshot verify. NiA itself says to balance "not over-modularizing a relatively small app", and to keep the data layer in one module until it grows.

### Cited Findings

**Modules (Now in Android)**
- NiA's modules:
  - `app`: navigation and scaffolding.
  - `feature:*:api` (navigation keys) and `feature:*:impl` (UI + ViewModels).
  - `core:data` (repositories), `core:database` (Room, DAOs, migrations), `core:datastore`, `core:network`, `core:model`, `core:ui`, `core:designsystem`, `core:common` (dispatchers, Result), `core:testing`.
  - `sync`, `benchmarks`, `app-nia-catalog`. — [NiA modularization journey](https://github.com/android/nowinandroid/blob/main/docs/ModularizationLearningJourney.md)
- Dependency rules: feature `impl` modules depend only on other features' `api` modules, and core modules never depend on feature or app modules. "Code used by only one feature should stay in that feature's module." Granularity "can be increased over time", for example by keeping a small data layer in one module. — [NiA modularization journey](https://github.com/android/nowinandroid/blob/main/docs/ModularizationLearningJourney.md)

**DI, testing and benchmarks (NiA)**
- NiA uses Hilt.
- It avoids mocking libraries: data-layer components are interfaces, and test doubles implement them with test-only hooks.
- Screenshot tests use Roborazzi (`recordRoborazziDemoDebug`, `verifyRoborazziDemoDebug`), and baselines are recorded on Linux CI.
- Startup is optimized with a baseline profile at `app/src/main/baseline-prof.txt`, generated with Macrobenchmark. — [NiA README](https://github.com/android/nowinandroid)

**Roborazzi**
- It runs on the JVM with Robolectric, so no emulator is needed. It supports Compose and can generate tests from `@Preview` functions.
- Tasks: `recordRoborazziDebug`, `compareRoborazziDebug`, `verifyRoborazziDebug`.
- The README suggests a two-workflow GitHub Actions setup: a "store" job on main that uploads baseline screenshots, and a "verify" job on PRs.
- Unlike Paparazzi, it works with Robolectric, so tests can use Hilt and interact with components before capturing. — [Roborazzi README](https://github.com/takahirom/roborazzi)

**Room FTS and indices**
- Room supports `@Fts3`, `@Fts4` and `@Fts5` entities. "Use `@Fts3` only if your app has strict disk space requirements."
- FTS tables must use an INTEGER primary key named `rowid`.
- Tokenizers: `TOKENIZER_SIMPLE`, `TOKENIZER_PORTER`, `TOKENIZER_UNICODE61`.
- There are options for external-content tables. — [Room defining data](https://developer.android.com/training/data-storage/room/defining-data)
- With `BundledSQLiteDriver`, "Room supports all FTS versions regardless of the Android SDK version". With `AndroidSQLiteDriver`, FTS support depends on the platform SQLite. — [Room defining data](https://developer.android.com/training/data-storage/room/defining-data)
- Indices: `@Entity(indices = [Index(value = [...])])`, plus `unique = true` for uniqueness constraints. — [Room defining data](https://developer.android.com/training/data-storage/room/defining-data)
- Current Room docs reference `androidx.room3` artifacts (e.g. `androidx.room3:room3-testing:3.0.3`, plugin `androidx.room3`), and migrations use `SQLiteConnection`. — [Room migrations](https://developer.android.com/training/data-storage/room/migrating-db-versions)

**Room migrations**
- `@AutoMigration(from, to)` with an `AutoMigrationSpec` (`@RenameColumn`, `@DeleteColumn`, `@RenameTable`, `@DeleteTable`). These "rely on the generated database schema", so `exportSchema` must be true and the schema JSON committed to VCS. — [Room migrations](https://developer.android.com/training/data-storage/room/migrating-db-versions)
- `MigrationTestHelper` tests single migrations and all migrations. `fallbackToDestructiveMigration` "permanently deletes all data", and narrower variants exist (`…From`, `…OnDowngrade`). — [Room migrations](https://developer.android.com/training/data-storage/room/migrating-db-versions)

**State in the UI layer**
- ViewModels convert repository Flows to `StateFlow` via `stateIn(WhileSubscribed(5_000))`. Composables collect with `collectAsStateWithLifecycle()`. UI state is modelled with sealed interfaces (Loading/Success/Error). — [Android offline-first guide](https://developer.android.com/topic/architecture/data-layer/offline-first)

### Inferences

**Proposed module layout for SweepScan.** This is about 10 modules, small but recognisably NiA-style.
```
:app                      – MainActivity, NavHost, Hilt app, top-level destinations
:core:model               – pure Kotlin: Depot, Rack, Location, Sighting, Model, SweepSession
:core:database            – Room DB, entities, DAOs, FTS, migrations, schemas/ (committed)
:core:data                – repositories (interfaces + OfflineFirst impls), CSV/XLSX import-export
:core:designsystem        – theme, big-touch components for gloves/stock room
:core:testing             – fakes (FakeSightingRepository…), TestDispatcherRule, fixtures
:core:camera  (or :core:scanning) – CameraX + ML Kit barcode/OCR pipeline (already exists in SweepScan)
:feature:scan             – sweep flow (existing scan screen, review screen)
:feature:map              – depot/rack/column layout + QR labels
:feature:search           – partial model-code search → locations
:feature:settings
:sync  (later)            – SyncWorker, outbox drain, NetworkDataSource (Firestore impl)
```
- Skip NiA's `api`/`impl` split per feature. With 4 features it is over-engineering. Plain `:feature:x` modules with navigation routes are enough.

**Room schema sketch**
| Table | Columns | Indices / constraints |
|---|---|---|
| `depot` | `id` TEXT PK (UUID), `name`, `updated_at`, `deleted_at` | |
| `rack` (row) | `id`, `depot_id` FK, `label`, `sort_order`, `column_count` | `(depot_id, sort_order)` |
| `location` (column) | `id`, `rack_id` FK, `column_index`, `qr_code` | unique on `qr_code`; unique on `(rack_id, column_index)` |
| `sweep_session` | `id`, `user_id`, `device_id`, `started_at`, `finished_at` | |
| `sighting` | `id`, `location_id` FK, `sweep_session_id` FK, `barcode`, `box_count`, `seen_at`, `user_id`, `source` (SCAN/OCR/MANUAL), `deleted_at`, `updated_at` | `(location_id, seen_at)`, `(barcode)`, `(sweep_session_id)` |
| `model` | `code` PK, `name`, `color`, `size_range`, `source` (ERP/OCR) | |
| `barcode_model` | `barcode` PK, `model_code` FK, `source`, `updated_at` | `(model_code)` |
| `model_fts` | `@Fts4(contentEntity = Model::class)` over `code` and `name` | |
| `outbox` | (see Q2) | |

**Partial model-code search**
- FTS MATCH supports prefix queries (`'AB12*'`) but not infix matching.
- For "contains" search on short codes, a plain `LIKE '%x%'` over a few thousand rows is fast enough.
- A professional touch: use FTS for prefix and word search, fall back to `LIKE` for infix, and say so in the README.

**Write-up angle**
- Including the FTS entity plus a DAO test shows SQL depth on the CV.

**Hilt vs manual DI**
- Hilt is the industry-recognized choice and what NiA uses, so it is worth it for the CV.
- Manual DI (an `AppContainer`) is defensible for a 1-person app, but reviewers expect Hilt.
- Koin is acceptable but is a less standard choice for Android-only work.

**Navigation**
- Use Compose Navigation with type-safe `@Serializable` routes, or Navigation 3 if the project already moved to it. Verify the current stable version before choosing.

**Testing (high value, low cost)**
1. Room DAO tests with an in-memory DB under Robolectric (JVM, fast). Cover the FTS search, the "latest sweep per column" query, and tombstone filtering.
2. `MigrationTestHelper` test for every schema version.
3. ViewModel tests with fakes from `:core:testing` and Turbine for Flows.
4. Pure-Kotlin unit tests for the label parser and the conflict rule.
5. 3-5 Roborazzi screenshot tests of key screens (map, search result, review).

**CI**
- One `.github/workflows/ci.yml`: checkout, setup-java 17/21, a Gradle cache action, then `./gradlew lint testDebugUnitTest verifyRoborazziDebug assembleDebug`.
- Upload the APK as an artifact, so coworkers can sideload it from GitHub Releases.
- Optional: a release workflow on tag push that signs the APK with secrets.
- Badges in the README.

**Signals that a CV reviewer notices**
- An architecture diagram in the README.
- An ADR folder (`docs/adr/0001-room-as-source-of-truth.md`, `0002-append-only-sightings.md`).
- The committed Room schema JSONs.
- A "how sync conflicts are resolved" section.
- Real field-test results, e.g. scan accuracy and time per column.

**Over-engineering to avoid at this scale**
- A separate domain module and use-cases for every call.
- feature api/impl splits.
- KMP.
- Baseline profiles and macrobenchmarks. These are nice later, not now.

### Gaps
- Current stable versions in late 2026 were not checked for: Navigation (Compose Nav vs Navigation 3), Hilt, Roborazzi, and whether "Room 3" (`androidx.room3`) is stable and recommended for new projects. The Room docs reference `room3-testing:3.0.3`, but the release notes page was not fetched.
- Whether FTS4 `contentEntity` works with the `BundledSQLiteDriver` + Room 3 combination was not verified.
- No specific source was fetched on recruiter or hiring-manager views of "what makes an Android portfolio project look professional". The CV advice above is inference from NiA's practices.
