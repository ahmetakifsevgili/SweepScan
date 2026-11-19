# Getting barcodes from an Android phone into a Windows ERP field: Bluetooth HID keyboard vs. alternatives

Research date: 2026-11-14. Context: SweepScan (Kotlin, Galaxy S23 Ultra, One UI / Android 14-16) uses `BluetoothHidDevice` to type digits + Enter into the DİA "Stok transfer" barcode field on a slow Windows laptop. The laptop drops input when scans come quickly, and the app cannot see whether DİA accepted each line.

## 1. How reliable is BluetoothHidDevice (Android versions, OEM restrictions, Samsung, pairing, stuck keys, layouts, report ID)?

### Takeaway
`BluetoothHidDevice` is a public API (API 28 / Android 9+). Google has not documented any platform-level removal through Android 16. The real risks are:
- OEMs or ROMs disabling the HID Device profile (Fairphone 6 / LineageOS / e/OS in 2026, and a reported Motorola regression in 2026).
- The app being auto-unregistered when it is not in the foreground.
- Lost key-release packets causing endless repeated keys.
- Windows keeping stale pairing/SDP state.
- Layout mismatch.

HID is a one-way channel with no application-level acknowledgement. It can never tell the phone whether DİA accepted a line.

### Cited Findings
**API contract**
- `BluetoothHidDevice` was added in API level 28. It is reached via `BluetoothAdapter.getProfileProxy`. Apps targeting Android 12 (S) or higher need the runtime permission `BLUETOOTH_CONNECT` — [Android Developers: BluetoothHidDevice](https://developer.android.com/reference/android/bluetooth/BluetoothHidDevice)
- `registerApp` rules, from the same page:
  - "Only one application can be registered at one time."
  - "When an application is registered, the HID Host service will be disabled until it is unregistered."
  - "The app will be automatically unregistered if it is not foreground."
  - Registration status must be tracked via `Callback#onAppStatusChanged`. The boolean return value only means the command was sent.
  — [Android Developers: BluetoothHidDevice](https://developer.android.com/reference/android/bluetooth/BluetoothHidDevice)
- `connect()` and `disconnect()` also only return "true if the command is successfully sent". The real connection state must come from `onConnectionStateChanged`. `connect()` fails if the app is not registered — [Android Developers](https://developer.android.com/reference/android/bluetooth/BluetoothHidDevice)
- `sendReport(device, id, data)` sends over the interrupt channel. `id` is "Report Id, as defined in descriptor. Can be 0 in case Report Id are not defined in descriptor". `data` excludes the report ID. There is no delivery or acceptance acknowledgement for interrupt reports — [Android Developers](https://developer.android.com/reference/android/bluetooth/BluetoothHidDevice)
- The host can switch the device between `PROTOCOL_BOOT_MODE` (0) and `PROTOCOL_REPORT_MODE` (1) via `onSetProtocol`. The default is report mode "unless notified otherwise" — [Android Developers](https://developer.android.com/reference/android/bluetooth/BluetoothHidDevice)

**OEM and ROM restrictions (2026)**
- **Motorola, issue #553** in Fabi019/hid-barcode-scanner, opened 2026-07-16 and closed 2026-07-18:
  - After an Android update the reporter dated to about April 2026, the app could not obtain the HID proxy. A separate HID test utility showed "no profile detected".
  - The reporter believed the update "prevents the access to the HID Bluetooth Keyboard Proxy from third party apps".
  - The workaround was to switch the app to RFCOMM/SPP mode, find the PC's incoming COM port (COM4) with PuTTY, and use a custom script to pass the data into the active window.
  - The Android version was not specified, and the cause was not confirmed by the maintainer.
  — [GitHub issue #553](https://github.com/Fabi019/hid-barcode-scanner/issues/553)
- **Fairphone 6**, forum thread of 2026-06-25:
  - HID device registration fails, while the same app works on a Samsung Galaxy S21.
  - `adb shell dumpsys package com.android.bluetooth | grep -i HidDevice` returned nothing, which suggests the service is not registered.
  - A 2026-09-08 follow-up says e/OS disabled the profile on purpose (commit "FP6: Bluetooth: Disable HID Device Profile"). An April LineageOS change by a Qualcomm engineer also disabled it. A Magisk module re-enabled it, which requires root.
  — [Fairphone forum](https://forum.fairphone.com/t/bluetoothhiddevice-profile-not-exposed-on-fairphone-6/132462)
- The /e/OS community says the profile has been disabled since /e/OS v4.0 — [e/OS community](https://community.e.foundation/t/re-enabling-bluetooth-hid-profile-on-fairphone-6/84637)
- A targeted search found no Google release note or AOSP bug describing an Android 14, 15 or 16 platform change that removes the HID Device profile for third-party apps — (search result summary; see Gaps)

**Samsung**
- The Fabi019 tracker has only two issues mentioning Samsung, and neither is about HID:
  - #571, Galaxy A23: black camera screen (closed 2026-09-13).
  - #391, Galaxy S24 Ultra: IMEI scan problem (closed 2025-05-09).
  — [Fabi019 issues search "samsung"](https://github.com/Fabi019/hid-barcode-scanner/issues?q=samsung)
- HID Device registration worked on a Samsung Galaxy S21 (2026 report) — [Fairphone forum](https://forum.fairphone.com/t/bluetoothhiddevice-profile-not-exposed-on-fairphone-6/132462)
- A Samsung community thread reports general Bluetooth connection problems with small devices on the S25 / Android 15. The cause is unconfirmed and the thread is not specific to HID Device — [Samsung EU community](https://eu.community.samsung.com/t5/galaxy-s25-serie/s25-ultra-und-bluetooth-probleme/m-p/11755440/highlight/true)

**Pairing and connection (Fabi019 README)**
- If the connect dialog appears but never connects, the phone was usually paired before. The fix is to unpair on both sides, remove the phone from the PC, and pair again from the app.
- If clicking a device does nothing, the Bluetooth proxy may have been interrupted. Restart the app or toggle Bluetooth. If that fails, the phone may not support the profile.
- The app is GPL-3.0 licensed. It also offers an RFCOMM (SPP) mode, which needs extra PC software.
— [Fabi019/hid-barcode-scanner README](https://github.com/Fabi019/hid-barcode-scanner)

**Stuck keys**
- README "stuck key" section:
  - Connection instability can lose the final key-release packet, so a key repeats endlessly on the PC.
  - To recover, toggle Bluetooth on the PC or send a new key.
  - To prevent it, raise "Transmit delay" above 20 ms or tune the L2CAP QoS (token rate, peak bandwidth, latency).
  — [Fabi019 README](https://github.com/Fabi019/hid-barcode-scanner)
- **Issue #564** (2026-08-18), Windows PC:
  - On one Android device the Enter suffix repeats endlessly. On another, the last character of the barcode repeats.
  - The laptop had to be force-restarted.
  - It was closed the same day with no maintainer response or fix.
  — [GitHub issue #564](https://github.com/Fabi019/hid-barcode-scanner/issues/564)
- A third-party project (UsmanXTech/BtRemote) has a pull request titled "Fix Bluetooth HID lifecycle and keyboard release handling". This shows release handling is a recurring bug class in such apps — [GitHub PR](https://github.com/UsmanXTech/BtRemote/pull/1)

**Windows caching of old pairing / descriptor state**
- No Microsoft source was found confirming that Windows caches the HID descriptor. The community evidence:
  - A BTstack developer reports that Windows caches SDP records even across re-pairing — [btstack-dev](https://groups.google.com/g/btstack-dev/c/L-LFMLHXbn8)
  - Nordic DevZone threads report that a changed HID descriptor only takes effect after the OS re-bonds — [Nordic DevZone](https://devzone.nordicsemi.com/f/nordic-q-a/124385/is-it-possible-to-change-hid-descriptor-dynamically-after-softreset-with-the-same-bonded-device/549606)
- An AOSP / e/OS Bluetooth commit (title seen via search, page fetch was rate-limited): if the HIDD descriptor is under 256 bytes but its SDP attribute uses SIZE_IN_NEXT_WORD, Windows no longer correctly accepts the HIDD connection. Descriptor and SDP encoding details therefore matter to Windows — [e/OS gitlab commit](https://gitlab.e.foundation/e/os/android_packages_modules_bluetooth/-/commit/f42322cd8c94c19890912b8ab08d83998afcfa73)

**Keyboard layout**
- HID sends raw key codes, so the layout chosen in the phone app must match the PC's layout — [Fabi019 README](https://github.com/Fabi019/hid-barcode-scanner)
- On Turkish F (KBDTUF), the top-row number keys produce digits 1-0 unshifted. This is inferred from page element order and should be checked against the Shift-states view. Numpad keys are listed under the Num Lock label — [kbdlayout.info KBDTUF](https://kbdlayout.info/kbdtuf)

### Inferences
- **Digits are layout-safe on both Turkish layouts.** Sending top-row usages 0x1E-0x27 (1..0) plus Enter (0x28) should give digits on both Turkish Q and Turkish F, with no Shift needed. Keypad usages (0x59-0x62) depend on the Num Lock state, so a laptop with Num Lock off would get navigation keys instead. Avoid keypad usages unless the app first reads the host's LED output report (`onSetReport` / `onInterruptData`) and confirms Num Lock is on.
- **Watch the report ID in boot protocol.** A descriptor with Report ID 1 is fine in report protocol. If the host ever calls `onSetProtocol(BOOT)`, boot-format reports carry no report ID, so a prefixed report could be misparsed. SweepScan should handle `onSetProtocol` and log it. The common Windows path is report mode.
- **The foreground rule matters for SweepScan.** Because the app "will be automatically unregistered if it is not foreground", a screen-off or app switch during a sweep can silently drop the HID link. Queued scans must not count as "sent" until `onConnectionStateChanged(CONNECTED)` is seen again.
- **One-UI firmware updates should be re-tested.** The S23 Ultra is not reported broken. Given the Motorola and Fairphone precedents, OEMs can disable the profile at will, so after every One UI update, check `getProfileProxy` / `onAppStatusChanged` and run `dumpsys bluetooth_manager`.
- **Send the full key-up with every character.** An all-zero key-up report after every key, a trailing "all keys released" report after Enter, and a periodic empty report when idle all limit the stuck-key failure mode seen in #564.

### Gaps
- No primary Google or Samsung source on Android 14/15/16 or One UI 6-8 changes to the HID Device profile. No Samsung S2x-specific HID Device bug reports were found.
- The Android version in the Motorola report (#553) is unknown.
- Microsoft does not document the Windows SDP/descriptor caching behaviour.
- No measured battery-impact data was found for `BluetoothHidDevice`.

## 2. Is BLE HID over GATT (HOGP) a viable alternative on Android without root?

### Takeaway
In principle, yes: an Android app can act as a GATT server and advertise a HID service. There is an open-source Apache-2.0 library for this (kshoji/ble-hid-peripheral-for-android). It brings its own pitfalls (bonding and encryption, required Battery and Device Information services, advertising appearance), and it still has no acknowledgement path from the ERP. It does not solve the core problem.

### Cited Findings
- kshoji's "BLE HID peripheral for Android" library implements HOGP on Android. It is Apache-2.0 licensed with about 245 stars, and it emulates a keyboard or mouse peripheral that a PC can connect to — [ecosyste.ms listing](https://awesome.ecosyste.ms/projects/github.com%2Fkshoji%2Fble-hid-peripheral-for-android)
- HOGP requires the Battery Service and Device Information Service alongside the HID Service. The advertisement should carry the keyboard Appearance value and the HID service UUID — [Goodix BLE HID app note](https://goodix-ble-wiki-en.readthedocs.io/latest/AppNote/BLE/BLE%E5%BA%94%E7%94%A8/BLE%20HID%E5%BA%94%E7%94%A8%E7%AC%94%E8%AE%B0.html)
- The device that sends HID keys must be the advertising peripheral / GATT server, with the host as central — [Nordic DevZone](https://devzone.nordicsemi.com/f/nordic-q-a/7271/hid-over-gatt-hogp-and-custom-services-on-android)

### Inferences
- HOGP changes the transport, not the reliability model: it is still keyboard emulation with no ERP acknowledgement. It is not worth the engineering effort for SweepScan unless classic HID Device gets disabled on the target phone.

### Gaps
- No 2025-2026 field reports were found on HOGP from Samsung phones to Windows 11, or on whether One UI restricts BLE peripheral advertising of the HID UUID.

## 3. Alternatives: Wi-Fi/USB to a Windows agent (SendInput / UI Automation), SPP/COM port, and existing products

### Takeaway
Commercial "phone as scanner" products put a small desktop server on the PC, reached over the LAN, which types into the active window. Examples are Barcode to PC (GPL-3.0 open source, freemium) and TEC-IT Scan-IT to Office (free desktop add-ins, paid app subscription). Bluetooth SPP into a Windows virtual COM port plus a small forwarding script is the documented fallback when HID is blocked (Fabi019 #553).

A custom agent gives SweepScan what HID cannot: a two-way channel for acknowledgements, a check on which window has focus, and pacing on the PC side.

### Cited Findings
**Barcode to PC**
- Server for Windows, macOS and Linux; app for Android and iOS.
- Works on a LAN without internet. Offline scans can be synced later.
- Keyboard emulation into the active window is included in all tiers.
- Free tier: 1 phone, 300 scans total, one month.
- Paid tiers: Starter (2 phones, 1,500 scans/month), Pro (5 phones, 10,000/month), Unlimited (lifetime), Enterprise.
- Exact prices were not readable on the page and appeared contradictory ("$9.99 / year" vs "$196 lifetime" for Unlimited). Confirm with the vendor.
— [barcodetopc.com/pricing](https://barcodetopc.com/pricing/)
- Barcode to PC server source is on GitHub under GPL-3.0 (1,279 commits). The README points to nut.js and node_mdns, which suggests nut.js-based typing and mDNS discovery. This is inferred, not stated — [fttx/barcode-to-pc-server](https://github.com/fttx/barcode-to-pc-server)

**TEC-IT Scan-IT to Office**
- The desktop Add-Ins and Tools, including a "Smart Keyboard Wedge" for Windows and macOS that works with "arbitrary applications", are "free of charge in any case".
- The mobile app is a free demo or a paid subscription, with bulk licensing available.
— [TEC-IT Scan-IT to Office selection page](https://www.tec-it.com/en/software/selection/office-barcode-scanner/EntryPage3.aspx), [TEC-IT STO](https://tec-it.com/sto)
- TEC-IT also sells TWedge, a universal keyboard-wedge / data-acquisition tool — [TEC-IT TWedge](https://tec-it.com/twedge)

**SPP / COM port**
- The workaround in #553: Fabi019's RFCOMM/SPP mode sends to a Windows incoming COM port (COM4), and a script forwards the data to the active window — [issue #553](https://github.com/Fabi019/hid-barcode-scanner/issues/553)

**SendInput (Windows)**
- Events in one `SendInput` call are inserted serially and "are not interspersed with other keyboard or mouse input events" — [Microsoft Learn: SendInput](https://learn.microsoft.com/en-us/windows/win32/api/winuser/nf-winuser-sendinput)
- `SendInput` is subject to UIPI: it can inject only into apps at equal or lower integrity. When UIPI blocks it, "neither GetLastError nor the return value will indicate the failure". A non-elevated agent will silently fail to type into an ERP running as administrator — [Microsoft Learn: SendInput](https://learn.microsoft.com/en-us/windows/win32/api/winuser/nf-winuser-sendinput)
- `SendInput` "does not reset the keyboard's current state". Already-pressed keys can interfere, so check them with `GetAsyncKeyState` — [Microsoft Learn: SendInput](https://learn.microsoft.com/en-us/windows/win32/api/winuser/nf-winuser-sendinput)

### Inferences
- **A custom agent (C#/.NET, a few hundred lines) fits SweepScan's scale better than buying a product.** It would:
  - listen on a LAN WebSocket or TCP socket, or over USB through `adb reverse`;
  - receive `{seq, barcode}`;
  - check that the foreground window belongs to DİA;
  - type with `SendInput` using `KEYEVENTF_UNICODE` (layout-independent);
  - optionally verify via UI Automation;
  - return an ACK or NACK.
  The agent must run at the same integrity level as DİA (both non-elevated, or both elevated).
- **Barcode to PC is a quick way to benchmark Wi-Fi typing before building the agent**, but its free tier (300 scans) is too small for real counts. Its GPL-3.0 licence means copying its server code into a closed SweepScan agent is not allowed.
- **SPP/COM is the lowest-effort fallback if HID breaks after a One UI update.** It is two-way capable, but still needs a PC-side forwarder.

### Gaps
- No public documentation was found for Barcode to PC's typing delay, target-window, or acknowledgement settings.
- No PhoneScan2PC / Scan2PC licensing details were retrieved.
- No DİA-specific integration API was researched here. DİA's web-service API, if available, would bypass keyboard emulation entirely, and is worth checking separately.

## 4. Can a Windows agent verify the ERP received input? How do professional wedges handle focus and pacing?

### Takeaway
Professional scanners do not verify. They only pace output, using configurable inter-character, inter-function and inter-message delays:
- Zebra: 0 / 20 / 40 ms keystroke delay.
- Honeywell: 5 ms steps, up to 495 ms on some models and up to 5,000 ms on the Voyager 1250g.

Checking which window has focus and reading back the result via UI Automation are only possible with a PC-side agent.

### Cited Findings
**Zebra**
- Bluetooth HID "Keyboard Keystroke Delay" (RS6100) options: No Delay 0 ms (asterisk, likely default), Medium 20 ms, Long 40 ms. It "sets the delay, in milliseconds, between emulated keystrokes" — [Zebra RS6100 PRG](https://docs.zebra.com/us/en/mobile-computers/wearable/rs6-series/rs6100-product-reference-guide/bluetooth-communications/hid-host-parameters/keyboard-keystroke-delay.html)
- The DS2278 Product Reference Guide has an "HID Keyboard Keystroke Delay" parameter. Its exact values were not verified — [notice-facile DS2278 manual](https://www.notice-facile.com/en/manual/1310981/zebra+ds2278)
- Symbol LS4208 has an optional intra-keystroke delay: an extra pause "between each emulated key depression and release" — [ManualsLib LS4208](https://www.manualslib.com/manual/916757/Symbol-Ls-4208.html?page=71)
- Zebra DataWedge (Android keystroke output):
  - Inter-character delay defaults to 0 ms.
  - The key-event delay for control characters should be raised in steps of 100 ms (max 1000) "until errors cease".
  - The docs warn that this "can negatively effect application performance".
  — [Zebra DataWedge Keystroke Output](https://techdocs.zebra.com/datawedge/latest/guide/output/keystroke/)

**Honeywell**
- Intercharacter delay is programmable in 5 ms steps: 0-495 ms on 2020/3800-series manuals, up to 5000 ms on the Voyager 1250g.
- These manuals note intercharacter delays are not supported in USB serial emulation.
— [ManualsLib Honeywell 2020](https://www.manualslib.com/manual/607138/Honeywell-2020.html?page=107), [manualsdir Voyager 1250g](https://www.manualsdir.com/manuals/90212/honeywell-voyager-1250g.html?page=81)

**Other vendors**
- Argox and Mindeo manuals: if transmission is too fast, "the system may not be able to receive all characters". Inter-character delay is the remedy — [ManualsLib Argox AS-8020](https://www.manualslib.com/manual/2653759/Argox-As-8020.html?page=52)

### Inferences
**Possible verification steps for a Windows agent** (based on general Win32/UIA knowledge; not tested against DİA):
- `GetForegroundWindow` plus `GetWindowThreadProcessId`, and a process-name or window-title match before typing. NACK with "WRONG_WINDOW" if the check fails.
- UI Automation (`FocusedElement`, or reading the barcode edit box's `ValuePattern`) to confirm the field is empty before typing and cleared after Enter. If DİA's grid exposes UIA, compare the row count or last-row barcode before and after.
- If DİA uses owner-drawn controls (common in Delphi/DevExpress ERPs), UIA may expose nothing. Fallback: a per-session counter shown to the user, plus a final reconciliation between SweepScan's count and DİA's document line count.

**Pacing**
- For a slow PC, the bottleneck is likely DİA's per-Enter processing (lookup and row insert), not character rate.
- An inter-scan gap tied to an ACK (agent path), or a long fixed post-Enter delay (HID path), matters more than inter-character delay.

### Gaps
- No primary Microsoft UI Automation documentation was fetched in this session.
- No DİA-specific information on UIA exposure was found.
- No published vendor-recommended inter-message (post-Enter) delay value was found. Honeywell exposes the setting, but the defaults were not retrieved.

## 5. Recommended pacing values and a robust protocol for slow PCs

### Takeaway
**If HID stays:** a key-down to key-up gap of 20-40 ms, an all-keys-released report after every key and after Enter, and an adaptive post-Enter gap starting at 1.5-3 s. Drive it from a single serial queue on the phone that never sends the next barcode until the previous one is fully released, with a user-visible count to reconcile. HID cannot confirm acceptance.

**For real reliability:** a Wi-Fi or USB agent protocol with sequence numbers, ACK after the agent has verified focus and typed, retry on timeout, and idempotent de-duplication on the agent side.

### Cited Findings
- Fabi019 recommends a transmit delay above 20 ms to avoid stuck keys — [Fabi019 README](https://github.com/Fabi019/hid-barcode-scanner)
- Zebra uses 20 ms (medium) and 40 ms (long) keystroke delays for hosts that need slower input — [Zebra RS6100 PRG](https://docs.zebra.com/us/en/mobile-computers/wearable/rs6-series/rs6100-product-reference-guide/bluetooth-communications/hid-host-parameters/keyboard-keystroke-delay.html)
- Zebra DataWedge advises raising delays in 100 ms steps until errors stop — [Zebra DataWedge](https://techdocs.zebra.com/datawedge/latest/guide/output/keystroke/)
- `SendInput` batches are atomic with respect to other input, but are silently blocked by UIPI — [Microsoft Learn: SendInput](https://learn.microsoft.com/en-us/windows/win32/api/winuser/nf-winuser-sendinput)

### Inferences
**HID path (current SweepScan), suggested settings to test:**
- key-down to key-up: 25-40 ms (current 25 ms is at the low end; try 40 ms);
- key-up to next key-down: 20-40 ms;
- after the Enter key-up: 1.5 s minimum, adaptive up to 3 s.
- Add a "slow PC" profile that doubles all of these.
- Make the send queue strictly serial and persistent on the phone, with states: queued, sending, sent-unconfirmed.
- On disconnect, mark the barcode in flight as "uncertain" and ask the user to check the last DİA line before resending. A blind resend creates a duplicate line.
- Never queue while not CONNECTED.
- A QoS change (e.g., `SERVICE_GUARANTEED`) is untested; any QoS or descriptor change requires a re-pair on Windows.

**Agent path protocol:**
- Phone sends `{session_id, seq, barcode, ts}`.
- Agent keeps the last N `(session_id, seq)` pairs and ignores duplicates. This gives idempotent retries.
- The agent then:
  1. checks that DİA is the foreground window and the barcode field has focus (UIA if available);
  2. types the barcode and Enter in one `SendInput` batch (`KEYEVENTF_UNICODE`);
  3. waits for DİA to settle (the field clears, or the grid row count increments, or a fixed 1-2 s);
  4. replies `ACK {seq, verified: true|false}` or `NACK {seq, reason}`.
- Phone sends the next item only after the ACK. That makes pacing self-adjusting to the PC's speed.
- Retry with backoff after a 5 s timeout, using the same seq.
- At session end, compare SweepScan's total against DİA's document line count.

### Gaps
- No empirical study was found that gives "safe" inter-scan delays for slow Windows ERP clients. The values above are engineering estimates that should be checked in the Tuesday field test, ideally by logging timestamps and comparing against DİA line counts.
