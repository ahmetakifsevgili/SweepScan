# SweepScan: what's left to finish (research notes, Nov 2026)

Short research pass (vendor docs, Android docs, forums) on what makes stockroom "runner" and
item-location apps useful, checked against what SweepScan v0.7.x already has.

Already in the code, so not proposed again: request statuses `NEW / SENT / MISSING`
(`request/Requests.kt`), a reply through WhatsApp's own reply action (RemoteInput), "Yok"
flagging the row (`RowFlags`), a Quick Settings tile (`SayTileService`), 4 static app shortcuts
(say, find, requests, rows), and a manual zip backup/restore of DB + `layout.json` with
`wal_checkpoint(FULL)` (`LocationStore.backup/restore`).
Gaps found in the code: `android:allowBackup="false"`; the request queue lives in
SharedPreferences JSON capped at 60 items; the reply action is cached in one `@Volatile`
field, so a process restart loses it.

---

## 1. Runner / back-of-house request systems

| System | What it does with requests |
|---|---|
| **NewStore Runner** | Associate looks up stock and sends a request to the stockroom. Types: *Try On* (bring to the associate or fitting room) and *Replenishment* (refill the shelf). Statuses are Pending, In Progress and Completed. The requester gets notified on **accepted**, **completed** and **product unavailable**, and when a note is added. The runner can mark an item **unavailable** (an icon everyone sees) and later mark it **available** again. A request can hold several items with quantities. The list shows who asked, the type and the time. |
| **Tulip Runner** | Search store inventory, request delivery to a specific place in the store, live notifications, wearables support, reports for managers. |
| **Mercaux Floor Runner** | A "basket" sent from the floor to the stockroom, size filter, and a message back to the floor when an item is unavailable so the seller can offer an alternative. Adoption tips: enough devices, one "digital guru" per store, and a fixed daily window when using the app is mandatory. |
| **SOTI (blog)** | Describes the "before" state: photos dropped in a group chat, approvals lost, nobody can see the status. That is the WhatsApp-group pain SweepScan sits on top of. |
| **Zebra Workcloud Task Mgmt** | Routes tasks to the right associate, escalates, triggers follow-up tasks automatically, and tracks each task to verified completion. Marketing level only, no timer details. |
| **Kitchen display systems (analogy)** | Each ticket shows its **age** and changes colour (green, yellow, red), with "past due" and a later flashing "late" level. One tap **bumps** a ticket (done), **recall** brings the last bumped ticket back, there is a list of bumped tickets, and new or urgent tickets play a sound. |
| **Pickup SLA practice** | Timestamp pick start, pick done and handoff. Example alert rule: orders older than 25 min against a 30-min target. Example rule for a missing item: acknowledge within 15 min. |

Takeaways for a one-person stockroom:
- The **status sent back to the requester** is the core value. Every vendor has it, and it is what replaces the "did you see my message?" call.
- An **accepted / in progress** state is standard. SweepScan has no "Bakıyorum" step yet.
- **Age on each open request** (KDS colours) is cheap and makes a queue of 5 requests readable at a glance. A hard SLA is overkill for one shop.
- **Several items per trip**: requests hold several items (NewStore) or a basket (Mercaux). SweepScan has a pick list with a route. It only needs a one-tap "collect all open requests".
- **Unavailable must be reversible** (NewStore). A row flagged as missing should be cleared once the shoe turns up.
- **Undo / recall** of a mistaken "Gönderdim" (KDS recall).
- Daily stats: Tulip reports for managers, and KDS keeps the list of done tickets.

## 2. Replying to WhatsApp from the app

How it works (and how the app already does it): the `NotificationListenerService` takes the
notification's `Action` that has `RemoteInput`s, puts the text under each `resultKey`, calls
`RemoteInput.addResultsToIntent` and sends the `actionIntent`. Auto-reply apps and libraries
(Reflex, ReplyAuto, the SO answers) all work this way.

Limits and risks:
- **The action is only valid while WhatsApp's notification exists.** If the operator opens the
  chat, swipes the notification away, or WhatsApp merges it into a group summary, the
  PendingIntent may be cancelled (`CanceledException`) or go to a stale conversation. Fix:
  - Store the action **per conversation**, keyed by the `sbn.key` or the group title, not one global field.
  - On a miss, scan `getActiveNotifications()` for the group's notification again before giving up.
  - Prefer actions with `SEMANTIC_ACTION_REPLY` or a remote input that allows free text.
- **Process death** wipes the cached action, which is a `@Volatile` field today. The rescan above covers this.
- **Fallback when no reply is possible:** copy the reply text to the clipboard, open the chat
  with the group's saved `contentIntent` (or launch WhatsApp), and show a "paste and send"
  toast. Never fail silently. Show "reply not sent" on the request row.
- **Listener going silent** is a known Android problem: access stays granted but callbacks stop
  until access is toggled off and on. Mitigation:
  - Handle `onListenerDisconnected()` by calling `requestRebind()`.
  - Show a warning on the home screen if notification access is off or nothing was received for a long time during shop hours.
  - Exempt the app from battery optimisation (Samsung: "never sleeping apps").
- **Sideload restriction:** Android 13+ greys out notification access for sideloaded APKs until
  you use App info, ⋮, "Allow restricted settings". Android 15 adds an extra confirmation
  dialog. Samsung Auto Blocker can also block it. Keep this in the onboarding text.
- **WhatsApp ToS:** the messaging guidelines forbid "unofficial clients, bulk messaging,
  auto-messaging… or automation to harm WhatsApp or our users". A short reply that a human
  triggers by tapping, through WhatsApp's own notification, in your own group, is the same as
  typing in the notification's reply field. Low risk. **Do not** add unattended auto-replies or
  bulk sends, because automated volume is what gets accounts flagged. Keep replies opt-in, as
  they are now.
- Group replies go to the whole group (no quoting a specific message). Put the model and size in
  the reply text so people know which request it answers. The app already does this.

## 3. Small Android conveniences (one-handed in a stockroom)

Order of value for this app:
1. **Notification actions** (Gönderdim / Yok / Dinle / Söyle). This is where the operator already
   is when a request arrives: zero navigation, usable with a thumb, works from the lock screen. Most value is here.
2. **App shortcuts** (long-press the icon). Launchers show about 4, which SweepScan already has.
   Possible tweak: make one dynamic, e.g. "Açık istekler (3)", and call `reportShortcutUsed`.
3. **Quick Settings tile**: already exists. Google's guidance says don't use a tile only to
   launch the app, keep at most 2 per app, and use `requestAddTileService()` (Android 13+) to offer
   adding it in context. Show the open-request count as the tile subtitle.
4. **Home screen widget** (open requests plus the oldest one's age): nice but the most work
   (Glance/RemoteViews), and it overlaps with the notification. Low priority.
5. **Voice input**: already the main input. Keep the hold-to-talk target big and near the bottom of the screen.

## 4. Data safety without a backend

- **Auto Backup:**
  - Free, 25 MB per app in the user's Google Drive, only the latest copy is kept.
  - Runs about once a night, only when the device is idle, on Wi-Fi and at least 24 h after the last backup.
  - If the data is over 25 MB, the backup is skipped.
  - Restores on reinstall, **including `adb install`**, before the first launch.
  - Covers `getDatabasePath`, `filesDir` and SharedPreferences, but not cache or no-backup dirs.
  - Needs backup switched on in the device settings, and is end-to-end encrypted when the device has a screen lock.
  - SweepScan currently has `allowBackup="false"`, so there is **no safety net at all**. Turning
    it on with `dataExtractionRules` (and `fullBackupContent` for Android ≤11) costs a few lines
    of XML. It is a second line of defence, not a reliable one: the device may never back up.
- **SAF backup (primary):** the existing zip is the right format. To finish it:
  - **Automatic daily backup:** the user picks a folder once (`ACTION_OPEN_DOCUMENT_TREE` +
    `takePersistableUriPermission`). Then a daily `WorkManager` job writes
    `sweepscan-YYYYMMDD.zip` and keeps the last 7. A Google Drive or USB folder picked in the system picker works too.
  - Include the request history, settings and row flags (SharedPreferences) in the zip, plus a
    `manifest.json` (app version, DB schema version, date, row counts) so restore can refuse a
    newer schema or a corrupt file.
  - Restore: validate first, then swap files, then **restart the app process**. Room keeps the
    old instance open otherwise, and the restore pitfalls in the forums are all about this.
  - The "last backup: 2 days ago" line on the home or settings screen is the cheapest reminder.

## 5. "Not where the system says" feedback loops

- Inventory accuracy in stores averages about 65%. Footwear is the hard case because one style has
  dozens of size SKUs and boxes get moved during try-ons (Zebra, 2026).
- Pattern across vendors: a discrepancy becomes a **task right away** (Frogmi: anomaly, check task,
  then a correction task if confirmed). Count the shelf and the back room separately. Count often
  for fast sellers and for items with repeated complaints (Sentra).
- NewStore: unavailable is a visible status and can be reversed.
- For SweepScan (today, "Yok" flags the row):
  - Ask a one-tap **reason**: "rafta yok", "bu numara yok", "başka yerde buldum".
  - "Başka yerde buldum" opens find-in-camera or a quick single-box scan and **moves the location**
    instead of flagging, which teaches the map.
  - Put flagged rows into a "Tekrar taranacak (3)" card on the home screen, cleared by the next Raf tara of that row.
  - Count misses per row and per model. A row that misses twice in a week goes to the top.
  - A later "Gönderdim" for the same model and size clears its flag ("found after all").

---

## Ranked: 8 features to finish the app

| # | Feature | Value | Effort |
|---|---|---|---|
| 1 | **Reliable reply + visible fallback.** Store the reply action per conversation and recover it with `getActiveNotifications()` after a restart. If the reply still fails, copy the text, open the chat and mark the row "yanıt gitmedi". | Very high. The answer reaching the floor is the whole point (every runner app has it). Today it can silently fail. | S–M (½–1 day) |
| 2 | **Listener health.** `onListenerDisconnected` → `requestRebind`. Home-screen warning when access is off or the listener is silent. Battery-optimisation exemption prompt. Restricted-settings steps in onboarding. | High. If the listener dies, the app looks broken on the field-test day. | S (½ day) |
| 3 | **"Bakıyorum" status + age colours.** New `TAKEN` status (optional auto-reply "👀 bakıyorum"). Each open request shows "4 dk", turning yellow after about 5 min and red after about 10. Undo the last mark (KDS recall). | High. Floor staff know someone is on it, and the queue is readable at a glance. | S (½ day) |
| 4 | **Automatic daily backup** to a chosen folder (SAF tree + WorkManager, keep 7) with requests and prefs included, manifest/schema check, restart after restore. Also turn on Auto Backup with rules as a second safety net. | High. Losing the map means re-sweeping three depots. Currently `allowBackup=false`. | M (1 day) |
| 5 | **Closing the not-found loop.** Reason on "Yok". "Başka yerde buldum" moves the location. "Tekrar taranacak" card on home. Flag cleared by the next sweep or a later "Gönderdim". | Medium–high. Keeps the map honest, and the map is what makes search worth using. | S–M (1 day) |
| 6 | **Collect all open requests in one trip.** One button on the queue or in a summary notification (InboxStyle "3 açık istek") feeds the existing pick list and route. Mark each as sent from the pick list. | Medium–high. Rush hours bring several requests at once (NewStore and Mercaux both batch). | S (½ day, mostly wiring) |
| 7 | **Request history in Room + daily stats card.** Today: requests, sent, missing, median response time, busiest hour, most-missed models. Keep 90 days instead of the last 60 items. | Medium. Shows the value to the shop owner, and good numbers for the CV ("median 3 min"). | M (1 day) |
| 8 | **Duplicate merge + urgent flag.** The same model and size within 10 min becomes one request ("×2, Ayşe + Mehmet"). A word like "acil" / "müşteri bekliyor" jumps the queue, with a distinct sound. | Medium. Fewer double trips and less noise. | S (½ day) |

Not recommended now: a home screen widget (the notification and tile already cover it), hard
SLAs or escalation (only one runner), unattended auto-replies (WhatsApp ToS risk).

---

## Sources

- NewStore Runner docs: https://docs.newstore.com/docs/using-the-runner
- NewStore Runner intro: https://www.newstore.com/articles/runner
- Tulip Runner (App Store): https://apps.apple.com/us/app/id1514788623
- Mercaux Floor Runner tips: https://blog.mercaux.com/en/customers/floor-runner-tips
- SOTI, retail restock process: https://soti.net/resources/blog/2024/does-this-sound-like-your-retail-restock-solution/
- Zebra Workcloud Task Management: https://www.zebra.com/us/en/software/workcloud-solutions/workcloud-workforce-optimization-suite/workcloud-task-management.html
- Zebra, "The $32M size 9" (footwear inventory accuracy): https://www.zebra.com/ap/en/blog/posts/2026/the-32m-size-9-the-hidden-cost-of-inaccurate-inventory.html
- Toast KDS bump bars: https://doc.toasttab.com/doc/platformguide/platformKitchenUsingBumpBars.html
- Fullcount KDS (past due / late): https://kb.fullcount.net/portal/en/kb/articles/kitchen-display-system-kds
- Xenial kitchen tickets: https://www.xenial.com/product-documentation/en/xenial-kitchen-management/xkm-interface-overview/kitchen-tickets.html
- Oracle Simphony KDS (recall timer): https://docs.oracle.com/cd/E76065_01/doc.29/e69880/c_featuresandupdates_kds.htm
- Pickup SLA patterns: https://racklify.com/encyclopedia/how-to-measure-and-enforce-store-pickup-slas-kpis-tools-and-penalties/
- Order lookup / exception SLAs: https://us.fitgap.com/stack-guides/fixing-omnichannel-confusion-at-the-counter-with-unified-order-lookup-and-pickup-workflows
- Replying via NotificationListener + RemoteInput (SO): https://kaweah.freedombox.rocks/kiwix/stackoverflow.com_en_all_2023-11/questions/40369508/how-to-utilize-android-nougat-s-direct-reply-feature-with-a-notificationlisten
- B4X notification reply (resultKey): https://www.b4x.com/android/forum/threads/notification-reply.91070/latest
- Listener stops receiving (B4X): https://www.b4x.com/android/forum/threads/notification-listener-stops-catching-notifications.90535/latest
- AOSP remote-input lifetime extension commit: https://android.googlesource.com/platform/frameworks/base/+/cf5499bd7d4c
- Reflex (auto-reply library): https://pub.dev/documentation/reflex/latest
- WhatsApp messaging guidelines: https://www.whatsapp.com/legal/messaging-guidelines?lang=en
- Android 13 restricted settings: https://www.esper.io/blog/android-13-sideloading-restriction-harder-malware-abuse-accessibility-apis
- Android 15 restricted settings: https://androidauthority.com/android-15-restricted-settings-sideloading-3481098
- Quick Settings tiles guide: https://developer.android.com/develop/ui/views/quicksettings-tiles
- App shortcuts overview: https://developer.android.com/develop/ui/views/launch/shortcuts
- Auto Backup: https://developer.android.com/identity/data/autobackup
- Room backup and restore (CommonsWare): https://commonsware.com/Room/pages/chap-backup-005
- Room restore and reopen: https://androidexplained.github.io/android/room/2020/10/03/room-backup-restore.html
- Frogmi on phantom inventory: https://frogmi.com/en/blog/phantom-inventory-in-retail-what-it-is-why-it-happens-and-how-to-eliminate-it/
- Sentra on stockouts and phantom inventory: https://www.sentra.app/articles/stockouts-phantom-inventory
