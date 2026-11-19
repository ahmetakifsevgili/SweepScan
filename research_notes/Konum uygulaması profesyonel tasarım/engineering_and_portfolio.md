# What makes an Android side project look professional, applied to SweepScan

Scope note: the research was done on 2026-11-17. Repo facts below come from a direct look at `C:\Users\ahmet\QrScan` (branch v0.3, commit 80210f8 plus uncommitted changes). They are labelled **[repo]** and need no web source.

**Repo baseline [repo]:**
- About 11.4k lines of Kotlin, all in a single `:app` module, with 22 JVM test files.
- No `.github/workflows`, so there is no CI. No version catalog (`gradle/libs.versions.toml` is missing). No CHANGELOG.
- `@Database(... version = 1, exportSchema = false)` in `location/LocationDb.kt`, and there is no `app/schemas/`.
- No `ViewModel` classes anywhere in `app/src/main/java`. There are 5 `Executors.`/`Thread(` call sites (in `CameraRig`, `HidKeyboard`, `LocationStore`, `FrameSampler`, `MainActivity`) and no `lifecycleScope`/`launch {` use.
- The release build type has `isMinifyEnabled = false` and is signed with the **debug** key. `versionName = "0.6.0"`.
- `res/values-tr` exists, so TR/EN localisation is already started.
- There is a strong README (problem framing, comparison table, pipeline diagram). The APK is checked in under `dist/SweepScan.apk`.

---

## 1. Code architecture signals (modularisation, MVVM/UDF, Compose vs Views, DI, Room, coroutines, errors)

### Takeaway
Google's official recommendations mark these as "strongly recommended":
- a layered UI and data architecture with repositories
- UDF with screen-level ViewModels exposing state
- coroutines and Flows between layers
- dependency injection

Hilt itself is only "recommended", and manual DI is acceptable for simple apps. Since May 2026, Google is officially "Compose-first" and Views are in maintenance mode. For SweepScan, the most visible gaps are: no ViewModel/StateFlow layer, raw Executors instead of coroutines, and Room with `exportSchema = false`. Modularisation is a nice-to-have, and only at a coarse grain.

### Cited Findings
- Google rates "Use a clearly defined data layer" and "UI layer" as **strongly recommended**. It says to create repositories "even if they contain only a single data source", and that ViewModels must not touch data sources directly. Its examples of data sources explicitly include "Bluetooth data providers" and databases. — [Android architecture recommendations](https://developer.android.com/topic/architecture/recommendations)
- "Use coroutines and flows" to communicate between layers is **strongly recommended**. — [Android architecture recommendations](https://developer.android.com/topic/architecture/recommendations)
- More items rated **strongly recommended** on the same page:
  - UDF, where "ViewModels expose UI state using the observer pattern and receive actions from the UI through method calls"
  - keeping ViewModels free of `Context`/`Activity`
  - screen-level ViewModels
  - not sending one-off events from ViewModel to UI (turn them into state instead)

  Exposing a single `uiState: StateFlow` (built with `stateIn(WhileSubscribed(5000))`, or a `MutableStateFlow` for simple cases) is rated **recommended**. — [Android architecture recommendations](https://developer.android.com/topic/architecture/recommendations)
- A domain layer with use cases is "Recommended in big apps" only. — [Android architecture recommendations](https://developer.android.com/topic/architecture/recommendations)
- Dependency injection with constructor injection is **strongly recommended**. Hilt is only **recommended**: "Use Hilt or manual dependency injection in simple apps". Hilt is advised when there are "multiple screens with ViewModels", WorkManager, or back-stack-scoped ViewModels. — [Android architecture recommendations](https://developer.android.com/topic/architecture/recommendations)
- On 19 May 2026 (Nick Butcher) Google announced that Android UI development is "Compose First":
  - "build all new features with Compose and convert existing features when you touch them"
  - the `android.widget` Views that Compose replaces are in maintenance mode: critical bug fixes, no new features, no plans to deprecate or remove
  - Fragments, RecyclerView and ViewPager are "complete"
  - Layout Editor and Navigation Editor are in maintenance mode, and future Studio UI tools are Compose-only
  - Google published an XML-to-Compose migration skill at github.com/android/skills

  — [Android Developers Blog: Android UI development is Compose first](https://android-developers.googleblog.com/2026/05/android-ui-development-is-compose-first.html); recap in [17 things from Google I/O 2026](https://android-developers.googleblog.com/2026/05/17-things-android-developers-google-io.html)
- The architecture recommendations page now lists "Use Jetpack Compose" and "Use a single-activity application" (with Navigation 3) as strongly recommended. — [Android architecture recommendations](https://developer.android.com/topic/architecture/recommendations)
- TikTok's Android team reported a 58% code-size reduction for features migrated to Compose. — [Android Developers Blog, Mar 2026](https://android-developers.googleblog.com/2026/03/tiktok-reduces-code-size-by-58.html)
- A secondary blog says Google's migration tooling works for simple layouts but not for custom-drawn Views. Treat this as an unverified secondary claim. — [codercops blog](https://blog.codercops.com/blog/android-views-maintenance-mode-compose-first-2026)
- Now in Android's module types:
  - `app` holds scaffolding and navigation.
  - Each `feature:*` module is split into `api` (navigation keys) and `impl`.
  - Shared `core:*` modules include `core:data`, `core:database`, `core:model` and `core:ui`. Core modules may not depend on feature or app modules.
  - It explicitly aims for a "balance between overmodularizing a relatively small app" and large-codebase patterns, and says keeping a small layer in one module is fine.

  — [NiA Modularization Learning Journey](https://github.com/android/nowinandroid/blob/main/docs/ModularizationLearningJourney.md)
- Room:
  - Automated migrations fail if `exportSchema` is false.
  - Exported schema JSON files "represent your database's schema history" and should be stored in version control.
  - The schema directory is set with the Room Gradle plugin (`schemaDirectory("$projectDir/schemas")`).
  - `MigrationTestHelper` (room-testing) tests single migrations and "all migrations".
  - `fallbackToDestructiveMigration` "permanently deletes all data".
  - The current docs show a `room3` artifact and plugin (`androidx.room3`, `room3-testing:3.0.3`), so Room 3 is now current. SweepScan uses Room 2.7.2 [repo].

  — [Room: migrate your database](https://developer.android.com/training/data-storage/room/migrating-db-versions)
- A reviewer of student Android projects lists these anti-patterns:
  - copy-pasted code ("a huge red flag")
  - duplicated XML
  - avoiding custom views
  - "2–3 activities with 300–500 lines" each instead of an architecture pattern such as MVVM
  - no unit or UI tests
  - no animations

  — [David Bojkovski, dev.to](https://dev.to/davidbojkovski/checking-student-projects-after-working-for-more-than-a-year-as-an-android-engineer-part-2-986)

### Inferences
- **Compose:** a full migration is not worth it before the job hunt. The custom Canvas `PlanView` and the CameraX preview are exactly the kind of custom-drawn and camera UI where migration tools help least, and the scan loop is performance-sensitive. Following Google's own "new features in Compose, convert when touched" rule, it is worth building **one or two new or simple screens** (e.g. Review, Settings, Search results) in Compose with Material 3, hosting `PlanView` through `AndroidView` where needed. This shows Compose literacy and interop skill, which is a more senior signal than "everything in Compose". The cost of having no Compose at all in a 2026 Android portfolio is a keyword and credibility gap; it is not a code-quality problem.
- **MVVM/UDF:** adding `ScanViewModel` and `LocationViewModel` that expose `StateFlow<UiState>`, with Activities as thin renderers, is the single most "readable by a reviewer" architecture change. The pure-Kotlin `BarcodeTracker` already behaves like a domain component, so this is mostly wiring. Swapping the 5 executor/thread sites for `viewModelScope`/`Dispatchers.Default` or IO plus `callbackFlow` (camera frames, HID connection state) directly addresses a strongly-recommended item. One caution: the analyzer thread for CameraX ImageAnalysis legitimately needs an `Executor`, so keep that one and document why.
- **DI:** with about 4–6 screens and no WorkManager, a hand-written `AppContainer` with constructor injection is fully defensible per Google's own text. Write an ADR that says so. Hilt adds kapt/ksp build cost and is mainly a keyword benefit.
- **Modularisation:** a full NiA split would be over-modularisation at about 11k lines. The one high-signal split is a **pure Kotlin/JVM module for the tracker and counting logic** (`:core:tracking`, no Android dependency). It proves the core algorithm is platform-independent, makes JVM tests fast, and is a natural seam. `:core:database` is optional.
- **Room:** `exportSchema = false` plus no migration tests means any schema change will either crash or wipe a user's count. This is a professional-hygiene fix taking about half a day, and a good "I think about upgrades" talking point.

### Gaps
- I found no primary source quantifying how much hiring managers weigh Compose over Views for **juniors** specifically. The secondary claims about it ("Compose silence is a red flag") were about **senior** resumes ([resumegeni](https://resumegeni.com/blog/android-engineer/junior)).
- I did not verify Room 3 migration specifics beyond the docs page (e.g. whether 2.7 to 3.x requires code changes).
- Error-handling conventions (a Result type, sealed UI error states) are not covered in a single primary source I fetched. The architecture page only says `UiState` may be a sealed class containing "data, errors, and loading signals".

---

## 2. Quality signals (tests, screenshot tests, benchmarks, lint, CI, signing, versioning, changelog, crash reporting)

### Takeaway
Google's minimum test set is:
- ViewModel unit tests (including Flows)
- data-layer tests
- UI navigation tests in CI
- Macrobenchmark performance tests

It prefers fakes over mocks. Google also marks R8 and baseline/startup profiles as strongly recommended. Roborazzi gives JVM-only screenshot tests that also work for plain Views, which fits a Views app. SweepScan's tests are strong, especially the replay tests from field recordings, but they are invisible without CI. The release build is unsigned in practice (debug key) and un-minified.

### Cited Findings
- "Know what to test" is **strongly recommended**. At minimum Google lists:
  - unit tests for ViewModels including Flows
  - unit tests for repositories and data sources
  - "UI navigation tests that are useful as regression tests in CI"
  - Macrobenchmark tests for startup and rendering

  "Prefer fakes to mocks" is also **strongly recommended**. — [Android architecture recommendations](https://developer.android.com/topic/architecture/recommendations)
- Under Performance, all three are **strongly recommended**: enable R8 (full mode), generate Baseline and Startup Profiles, and test with Macrobenchmark. — [Android architecture recommendations](https://developer.android.com/topic/architecture/recommendations)
- Now in Android uses Roborazzi for screenshot tests "of certain screens and UI components". It ships a baseline profile (`app/src/main/baseline-prof.txt`). It has a `demo` flavor using "static local data to allow immediate building and exploring of the UI". It "notably does not use any mocking libraries" and relies on interface-based fakes instead. Its repo also has `spotless`, `.editorconfig` and `build-logic` directories, though the README doesn't describe them. — [Now in Android README](https://github.com/android/nowinandroid)
- Roborazzi:
  - runs on the JVM with Robolectric, so no emulator is needed
  - supports Compose nodes, Espresso `onView()`, and **plain `View` instances via `view.captureRoboImage()`**
  - needs `@GraphicsMode(NATIVE)`
  - Gradle tasks are `recordRoborazziDebug`, `compareRoborazziDebug` and `verifyRoborazziDebug`
  - its README describes a GitHub Actions pattern that records on `main`, uploads the baseline as an artifact, and verifies on PRs, uploading diffs

  — [Roborazzi README](https://github.com/takahirom/roborazzi)
- `gradle/actions/setup-gradle` (current tag `v6`) caches Gradle User Home, validates wrapper checksums by default, can submit a dependency graph, and captures Build Scan links. Its minimal workflow is checkout, then `setup-java` (temurin 17), then `setup-gradle@v6`, then `./gradlew build`. — [gradle/actions setup-gradle docs](https://github.com/gradle/actions/blob/main/docs/setup-gradle.md)
- Crash reporting without Firebase:
  - ACRA is an open-source, Android-only crash reporter that can send to self-hosted backends. — [ACRA GitHub](https://github.com/ACRA/acra); [CommonsWare ACRA chapter](https://commonsware.com/misc/ACRA-7p0.pdf)
  - Sentry's Android SDK and server are both open source and self-hostable; Mozilla's Focus for Android uses it. — [Firefox Focus docs: Crash Reporting with Sentry](https://firefox-source-docs.mozilla.org/mobile/android/focus-android/Crash-Reporting-with-Sentry.html)
  - Google-services-based crash reporting fails on devices without Play Services. — [floating-timer issue](https://bitbucket.org/newplan/floating-timer/issues/33/replace-crash-reporter-with-non-google)
- The "Keep a Changelog" convention (Added/Changed/Fixed sections per version, newest first) and SemVer `MAJOR.MINOR.PATCH` are the de-facto standards. — [keepachangelog.com](https://keepachangelog.com/en/1.1.0/); [semver.org](https://semver.org/). (Standard references; I did not re-fetch them this session.)
- detekt (static analysis) and ktlint (formatting) are the common Kotlin linters; NiA uses Spotless with ktlint. — [detekt.dev](https://detekt.dev/); [ktlint](https://pinterest.github.io/ktlint/); NiA repo listing above. (detekt and ktlint docs not re-fetched this session.)

### Inferences
- **Tests:** SweepScan's strongest quality asset is the **replay tests from field recordings**: a real-data regression harness for a CV tracker. Most portfolio apps have nothing like it. It should be:
  - (a) run in CI on every push
  - (b) surfaced in the README with a number (e.g. "N recordings, M boxes, count error X%")
  - (c) explained in a short write-up
- **CI is the cheapest high-signal fix.** A green badge plus one workflow (`assembleDebug`, `testDebugUnitTest`, `lint`, `detekt`/`ktlintCheck`) tells a reviewer within seconds that the tests actually run. Instrumentation tests can stay local or run on a Gradle Managed Device job later. Camera and BT tests can't run in CI anyway; say that explicitly.
- **Screenshot tests:** use Roborazzi rather than Paparazzi here. It supports Views and Compose with one tool, and NiA uses it. Good targets are `PlanView` (custom Canvas, where regressions are visual and hard to unit-test) and the review screen in light/dark and TR/EN. Commit golden images.
- **Benchmarks:** a Macrobenchmark plus a Baseline Profile is "strongly recommended" by Google but is lower ROI for a camera app with a single main flow. Measuring the analyzer's per-frame latency (decode time and tracker time on the actual phone) and reporting it in the README is more relevant and more impressive.
- **Release:** signing release builds with the debug key and `isMinifyEnabled = false` both read as "never shipped" to an Android reviewer.
  - Create a real upload or release keystore that is kept out of git, and inject it in CI via GitHub secrets.
  - Enable R8, with keep rules for ML Kit, OpenCV, zxing-cpp JNI and Room.
  - Publish signed APKs on **GitHub Releases**, each with a CHANGELOG entry, instead of committing `dist/SweepScan.apk` to git.
- **Crash reporting:** for a sideloaded app used by one stock room, ACRA writing a local crash file plus a "share report" intent is enough. Sentry's free tier gives a dashboard screenshot for the README. Firebase Crashlytics would be the most recognised name, but it adds Google Services. Any of the three is fine; the signal is "I observe production".

### Gaps
- I found no 2026 survey showing what share of hiring managers actually open CI config or test folders.
- I did not fetch Paparazzi docs to compare its current View support. The Roborazzi choice rests on Roborazzi's own docs and NiA's usage.
- I did not verify current detekt and ktlint versions or the Gradle Managed Devices CI setup.

---

## 3. Presentation signals (README, demo, diagrams, metrics, case study, ADRs, demo mode, distribution)

### Takeaway
Reviewers give a repo seconds, not minutes. The README must state the problem, show it working (GIF or video), and prove outcomes with numbers. For an app that needs a physical stock room, a **demo mode with sample data** (NiA's `demo` flavor pattern) is what turns "can't evaluate" into "tried it". Publishing to the Play Store as a new personal account requires a 12-tester, 14-day closed test, so GitHub Releases plus a demo video is the realistic primary channel.

### Cited Findings
- Hiring managers and recruiters often spend under ~30 seconds on a repo. A missing README makes a project look unfinished. Reviewers check naming, structure, separation of concerns and commit-message quality (e.g. 30 commits named "fix"/"update" signal sloppiness). — [MentorCruise: how to become an Android developer](https://mentorcruise.com/blog/how-to-become-an-android-developer-what-hiring-managers-actually-look-for/); [MentorCruise: GitHub as proof](https://mentorcruise.com/blog/how-to-turn-github-into-proof-of-your-engineering-skills/); [devplaybook checklist](https://devplaybook.cc/blog/developer-portfolio-checklist-20-things-hiring-managers). (Content-marketing sources; any statistics in them were unverifiable.)
- A live or published app is a stronger signal than a repo. Reviewers can see "your crash rate, your update cadence, how you respond to reviews". Tutorial clones (weather, TODO, calculator) "screen out". — [MentorCruise](https://mentorcruise.com/blog/how-to-become-an-android-developer-what-hiring-managers-actually-look-for/); [resumegeni junior Android guide](https://resumegeni.com/blog/android-engineer/junior)
- Hiring managers in 2026 say they can recognise wholly AI-generated repos. The advice is to show your own improvements, tests and docs. — [MentorCruise](https://mentorcruise.com/blog/how-to-become-an-android-developer-what-hiring-managers-actually-look-for/)
- Now in Android's `demo` flavor uses static local data to allow "immediate building and exploring of the UI", while the `prod` backend is not public. — [Now in Android README](https://github.com/android/nowinandroid)
- Play Console: personal developer accounts created after 13 Nov 2023 must run a closed test with **at least 12 testers opted in continuously for the preceding 14 days** before applying for production access. Organisation accounts are exempt. Open testing does not count, and approval is not automatic. — [Google Play Console Help 14151465](https://support.google.com/googleplay/android-developer/answer/14151465); [RevenueCat: Google Play 14-day rule](https://www.revenuecat.com/blog/engineering/google-play-14-day/)
- ADRs follow Michael Nygard's 2011 lightweight format: Title, Status, Context, Decision, Consequences. There is one record per architecturally significant decision, stored as Markdown in the repo. A changed decision gets a new ADR that supersedes the old one. — [Martin Fowler: Architecture Decision Record](https://www.martinfowler.com/bliki/ArchitectureDecisionRecord.html); [Nygard's original post (Cognitect)](https://www.cognitect.com/blog/2011/11/15/documenting-architecture-decisions); [lightweight ADR repo](https://github.com/peter-evans/lightweight-architecture-decision-records)

### Inferences
- **The README is already above average [repo].** It frames a real, non-obvious problem (counting physical boxes vs unique barcodes) with a comparison table and a pipeline diagram. What it lacks:
  - (1) a GIF or 30–60 s video at the very top (a sweep, then the count, then the text appearing in the ERP on the PC)
  - (2) a **results table from the field test**: boxes per delivery, counting accuracy against a manual count, time per delivery before and after, miscount types
  - (3) a "Try it without a stock room" section linking to a release APK with demo mode
  - (4) badges (CI, latest release, min SDK)
  - (5) an English-language summary of the Turkish research report
- **Metrics to report:**
  - count accuracy, as an exact match rate per pallet and mean absolute error in boxes
  - throughput in boxes per minute, and minutes per delivery versus the barcode-gun baseline
  - re-identification success when the phone goes back over boxes it already counted
  - per-frame latency on the actual device

  Projected weekly time saved can come from the stated "300–600 boxes a week". Label every number with n and date, and be honest about failure cases. Honest numbers are more credible than perfect ones.
- **Demo mode** is the highest-leverage presentation item for this specific app, because no reviewer can reproduce the stock room. Concretely:
  - (a) a "Replay recording" source that feeds a bundled field recording (the same data the replay tests use) through the real tracker and UI
  - (b) a sample depot layout for the location map with seeded `ColumnScan`/`Sighting` rows
  - (c) a "fake HID" sink that shows typed output on screen instead of needing a paired PC

  Do this as a `demo` product flavor or a runtime toggle. This mirrors NiA's demo/prod split, which is a recognisable pattern.
- **Case study:** a 1–2 page write-up (in `docs/` or a blog post) shaped as problem, constraints, approaches tried (naive dedup, time-window dedup, tracker), what failed in the field, and results. This is what an interviewer will ask about, and it converts a stock-room job into a "domain discovery and shipping to real users" story.
- **Distribution:** the 12-tester / 14-day closed test is feasible but a project in itself. For a portfolio, signed APKs on GitHub Releases plus a video are sufficient. A Play listing could be a later bonus (crash rate and update-cadence signals). Internal testing (up to 100 testers, no review) can still be used to get the Play pre-launch report, which includes an accessibility scan.
- **Hygiene that reads as amateur:**
  - committing build outputs (`dist/*.apk`)
  - `.bat` build scripts at the root without explanation
  - mixed-language docs with no English entry point
  - generic commit messages

  SweepScan's commit messages are already descriptive and versioned [repo]. That is a plus.

### Gaps
- I found no primary survey (e.g. Stack Overflow or LinkedIn) with a verified figure for how long reviewers spend on a portfolio repo. The "30 seconds" figure is from blog sources.
- I did not verify current internal-testing limits or whether internal testing still yields a pre-launch report for brand-new personal accounts in late 2026.

---

## 4. UI polish signals (design system, typography, motion, accessibility, localisation, empty/error states)

### Takeaway
Polish signals that reviewers notice quickly are:
- a consistent Material 3 theme (colour roles, type scale, shape) instead of hard-coded colours and sizes
- motion that explains state changes
- proper accessibility (labels, 48dp targets, contrast)
- complete localisation
- deliberate empty, loading and error states

Google provides free automated accessibility checks (Accessibility Scanner, ATF, and the Play pre-launch report) that make this measurable.

### Cited Findings
- Google recommends four kinds of accessibility testing: manual (TalkBack, Switch Access, Voice Access), analysis tools (Compose UI Check, Accessibility Scanner built on the Accessibility Test Framework), automated (Compose semantics-based tests), and user testing. — [Android: Test your app's accessibility](https://developer.android.com/guide/topics/ui/accessibility/testing)
- The Play pre-launch report runs ATF checks grouped into touch target size, low contrast, content labelling, and implementation (e.g. traversal order). — [Android: Test your app's accessibility](https://developer.android.com/guide/topics/ui/accessibility/testing)
- Compose UI Check mode in Android Studio flags low contrast and layout problems on different screen sizes, and previews the UI for colour-vision deficiencies. — [Android: Test your app's accessibility](https://developer.android.com/guide/topics/ui/accessibility/testing)
- Student-project reviewers note Android developers "rarely" invest in animations, and that adding them makes apps feel more polished. — [David Bojkovski, dev.to](https://dev.to/davidbojkovski/checking-student-projects-after-working-for-more-than-a-year-as-an-android-engineer-part-2-986)
- An unfinished or buggy project in a portfolio "says about you more than the bug itself". — summarised in search results from [lyrid.io portfolio mistakes](https://lyrid.io/post/5-critical-software-engineer-portfolio-mistakes-that-junior-developers-are-making) (secondary)

### Inferences
- **For SweepScan specifically:**
  - Audit for hard-coded colours and dimensions in layouts and in `PlanView`'s Paint objects, and route them through theme attributes (`?attr/colorPrimary`, `colorSurfaceVariant`, M3 type scale). Dynamic colour is optional for a work tool.
  - Make `PlanView` accessible: content descriptions per column or route, using `ExploreByTouchHelper` for the custom Canvas. This is a rare and impressive detail.
  - Add haptic and sound feedback on new-box detection. This matters in a noisy stock room and is a real UX rationale worth documenting.
  - Check 48dp touch targets for gloved or one-handed use.
  - Run Accessibility Scanner once and fix what it finds.
- **Localisation:** `values-tr` exists [repo]. Make sure every string is in both locales, add Roborazzi screenshots per locale, and show both languages in the README. TR/EN is a real-user signal, not decoration.
- **States:** standardise empty states (no scans yet, no layout imported), error states (camera permission denied, BT not paired or disconnected, HID host not connected) and loading states with one reusable component. These are exactly where a UDF `UiState` sealed class pays off visibly.
- **Motion:** keep it meaningful. Animate track boxes appearing and settling in the overlay, animate count increments, and animate the route on the floor plan. Avoid decorative motion in a scanning UI.

### Gaps
- I did not fetch Material 3 design-token docs (m3.material.io) or the 48dp / 4.5:1 contrast guidance pages directly in this session. Those standard figures (48×48dp minimum touch target; WCAG 4.5:1 for normal text) come from prior knowledge and should be checked against [Android accessibility: make apps more accessible](https://developer.android.com/guide/topics/ui/accessibility/apps).

---

## 5. What hiring managers say they look at, and common amateur anti-patterns

### Takeaway
Across sources, the recurring signals are:
- a real, original problem (not a tutorial clone)
- a clear README
- readable, consistently structured code with separation of concerns
- tests
- meaningful commit history
- evidence of shipping and maintaining (releases, crash data, update cadence)
- a modern stack (Kotlin, coroutines, Compose awareness)

I found no strong primary survey data; most sources are blogs. SweepScan is unusually strong on originality and real-user evidence, and weakest on the conventional "modern Android stack" checkboxes (ViewModel/Flow, CI, Compose, signed or minified release).

### Cited Findings
- Recurring themes: the README states the purpose fast; code shows consistent naming, logical structure and separation of concerns; commit messages are meaningful; a published app shows crash rate and update cadence; originality beats clones; repos must show your own understanding rather than wholesale AI generation. — [MentorCruise](https://mentorcruise.com/blog/how-to-become-an-android-developer-what-hiring-managers-actually-look-for/); [MentorCruise: GitHub as proof](https://mentorcruise.com/blog/how-to-turn-github-into-proof-of-your-engineering-skills/)
- In 2026, junior candidates with Java-only portfolios "raise immediate questions". One shipped app on Play that you wrote substantially helps clear screening, and tutorial replicas screen out. — [resumegeni junior Android guide](https://resumegeni.com/blog/android-engineer/junior) (career-content site; moderate reliability)
- Juniors tend to make things "a lot more complex than they usually need to be" (an interview in *Living by the Code*). — [Kodeco: interview with Zarah Dominguez](https://www.kodeco.com/books/living-by-the-code/v2.0/chapters/8-an-interview-with-zarah-dominguez)
- Student-project anti-patterns: copy-paste, duplicated XML, god Activities of 300–500 lines, no tests, no custom views, no animation. — [David Bojkovski, dev.to](https://dev.to/davidbojkovski/checking-student-projects-after-working-for-more-than-a-year-as-an-android-engineer-part-2-986)
- Google's own reference app (NiA) models what the official team considers professional: modular structure, interface-based fakes and no mocks, Roborazzi screenshot tests, baseline profiles, a demo flavor, and spotless formatting. — [Now in Android](https://github.com/android/nowinandroid)

### Inferences
- **Amateur anti-patterns to check SweepScan against:**
  - god Activities (check `MainActivity`/`LocationActivity` line counts)
  - business logic in Activities
  - raw threads or executors
  - `exportSchema = false`
  - release builds signed with the debug key
  - committed APKs and local scripts
  - no CI badge
  - hard-coded strings and colours
  - no demo path for reviewers
  - TODO-littered code
  - an empty or overlong README with no visuals
  - the over-engineering trap the Kodeco interview warns about, e.g. adopting Hilt plus 10 modules for an 11k-line app
- **SweepScan's differentiators to lead with:**
  - It has a real user and site (a shoe stock room), with weekly volumes.
  - It solves a non-trivial CV problem: multi-object tracking for counting identical barcodes, using optical flow for camera-motion compensation.
  - It has a field-recording replay test harness.
  - It is hardware-integrated (BT HID into an ERP).
  - It includes graph routing on a custom Canvas plan and PDF label generation.

  These are senior-flavoured topics. The conventional hygiene items exist mostly so that a reviewer's first 30 seconds don't filter it out before they see them.
- **On AI-assisted code:** given the 2026 sensitivity to fully AI-generated repos, the ADRs, case study and field-test write-up double as authorship evidence. They show decisions, trade-offs and failures only the builder would know. Be ready to explain any file in an interview.

### Gaps
- I found **no GDE-authored post** specifically on judging Android portfolio projects in 2025–2026. The proandroiddev article "What I look for in a Junior Android Dev" returned HTTP 403 and could not be read.
- I found no verified hiring-manager survey statistics; the figures quoted in content-marketing blogs could not be traced to primary surveys.

---

## Concrete checklist (tick-box form)

**Architecture**
- [ ] `ViewModel` + `StateFlow<UiState>` (sealed or data class) for the Scan, Review and Location screens; Activities only render and forward intents
- [ ] Replace `Executors`/`Thread` with coroutines (`viewModelScope`, `Dispatchers.Default`/IO, `callbackFlow` for camera and BT callbacks). Keep one documented analyzer `Executor` for CameraX
- [ ] Repositories in front of Room, HID and preferences; no data-source access from UI classes
- [ ] Manual DI `AppContainer` with constructor injection (plus an ADR on why not Hilt), or Hilt if you want the keyword
- [ ] Extract `:core:tracking` as a pure Kotlin/JVM module (tracker, ReadFilter, session counting)
- [ ] Room: `exportSchema = true`, Room Gradle plugin `schemaDirectory`, commit `app/schemas/`, one `MigrationTestHelper` test
- [ ] Optional: one new screen in Compose (M3), with `PlanView` via `AndroidView`

**Quality**
- [ ] GitHub Actions: `setup-java` 17, then `gradle/actions/setup-gradle@v6`, then `assembleDebug testDebugUnitTest lint detekt`/`ktlintCheck`; badge in the README
- [ ] Replay tests run in CI and print the accuracy summary as a job summary or artifact
- [ ] Roborazzi screenshot tests: `PlanView`, the review screen, light/dark and TR/EN
- [ ] `.editorconfig` plus ktlint/Spotless and detekt baseline
- [ ] Version catalog (`libs.versions.toml`)
- [ ] Release: real keystore (kept out of git, GitHub secrets), R8 on with keep rules, `versionCode`/`versionName` from tags
- [ ] `CHANGELOG.md` (Keep a Changelog), SemVer, signed APKs on GitHub Releases; remove `dist/*.apk` from git
- [ ] Crash reporting (ACRA local or share, or Sentry)

**Presentation**
- [ ] README top: one-line pitch, GIF or video, badges, "Try it" (demo APK)
- [ ] Field results table (n, date, accuracy, time per delivery before/after, failure modes)
- [ ] Architecture diagram (Mermaid) plus module and layer description
- [ ] `docs/adr/0001-…md` × 4–6 (tracker vs SDK dedup, BT HID vs ERP API, Views + selective Compose, manual DI, Room schema strategy, on-device-only/no backend)
- [ ] Case study (EN) and an English summary of the TR research report
- [ ] Demo mode: replay a bundled recording, sample depot layout, on-screen fake HID sink

**UI polish**
- [ ] M3 theme tokens everywhere (no hard-coded colours or dimensions, including `PlanView` Paints)
- [ ] Accessibility Scanner pass; `ExploreByTouchHelper` for `PlanView`; 48dp targets; contrast
- [ ] Complete TR/EN strings; locale screenshots
- [ ] Unified empty, error and loading components (camera permission, BT disconnected, no host, no layout)
- [ ] Purposeful motion and haptics on count events

---

## Prioritised top-10 improvements for this repo (effort for one developer, in days)

1. **GitHub Actions CI with badge** (build, unit tests including replay tests, Android lint, detekt/ktlint with baseline): **1 day.** The cheapest way to make 130 tests visible; it fixes the "do the tests even run?" doubt in seconds.
2. **README overhaul**: hero GIF or video (sweep, count, ERP), field-results table with n and dates, Mermaid architecture diagram, "Try it" section, English summary of the research: **1–1.5 days** (plus filming). The highest return on reviewer attention.
3. **Demo mode** (replay a bundled field recording through the real tracker, seeded depot map, on-screen fake HID output) as a flavor or toggle: **2–3 days.** It is the only way a remote reviewer can actually use the app; it mirrors NiA's `demo` flavor.
4. **Proper release pipeline**: real keystore via GitHub secrets, R8 enabled with keep rules (ML Kit, OpenCV, zxing-cpp JNI, Room), tag-driven version, signed APK on GitHub Releases, `CHANGELOG.md`, remove `dist/*.apk` from git: **1.5–2 days** (R8 keep-rule debugging on the phone is the risky part).
5. **ViewModel + StateFlow UDF for the Scan and Location screens, and executors replaced by coroutines**: **3–4 days.** It directly hits several "strongly recommended" Google items and is the most-scrutinised architecture signal.
6. **Room hygiene**: `exportSchema = true` with the Room Gradle plugin, committed `schemas/`, `MigrationTestHelper` test, remove any destructive fallback: **0.5–1 day.**
7. **ADRs (4–6) and a short case study** (problem, approaches, field failures, results): **1.5–2 days.** This is also strong authorship evidence in the AI-generated-code era.
8. **Extract `:core:tracking` pure-Kotlin JVM module** (tracker, filters, session counting), with convention-light Gradle and a version catalog: **1.5–2 days.** Modularisation done at the right grain, not over-modularised.
9. **Roborazzi screenshot tests** for `PlanView` and the review screen (light/dark, TR/EN), plus an accessibility pass (Accessibility Scanner fixes, `ExploreByTouchHelper` on `PlanView`, M3 tokens instead of hard-coded colours, unified empty and error states): **2.5–3 days.**
10. **One Compose screen** (e.g. Review or Settings) on an M3 theme with Views interop, plus lightweight crash reporting (ACRA or Sentry): **3–4 days** (Compose) + **0.5 day** (crash reporting). This shows Compose literacy in line with Google's 2026 Compose-first guidance without a risky full migration.

**Total ≈ 17–23 working days.** Items 1–4 and 6 (about 6–8.5 days) give most of the first-impression benefit and could be done before any application wave. Items 5, 7 and 8 are the interview-depth layer. Items 9 and 10 are polish and keyword coverage.
