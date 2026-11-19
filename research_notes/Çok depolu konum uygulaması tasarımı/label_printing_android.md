# Generating and printing QR location labels from an Android (Kotlin) app

Scope: "generate all rack labels for a depot and print them" (100-150 labels over three depots, reprinting single labels later). Label content: QR (~75 mm incl. quiet zone, ECC level Q, payload like `LOC:B-2-07`) plus large human-readable code. Research date: 2026-11-17. Prices are from undated web snapshots unless stated; check live before buying.

## 1. Generating the QR code on-device (ZXing, qrcode-kotlin, Nayuki)

### Takeaway
All three libraries can do this; ZXing core is the most common choice and exposes the three controls needed (ECC level, forced version, quiet-zone margin) as `EncodeHintType` hints, but its `MARGIN` hint and its output scaling need care. Nayuki's library is the cleanest for "give me the module grid, I'll draw it myself" (ideal for vector PDF output). For `LOC:B-2-07` the code is tiny (version 1), so each module is large and robust at 75 mm.

### Cited Findings
- ZXing `EncodeHintType.ERROR_CORRECTION`: "Specifies what degree of error correction to use, for example in QR Codes"; for QR the value type is `ErrorCorrectionLevel` (L/M/Q/H). — [ZXing javadoc EncodeHintType](https://zxing.github.io/zxing/apidocs/com/google/zxing/EncodeHintType.html)
- ZXing `QR_VERSION`: "Specifies the exact version of QR code to be encoded" (1-40); if the data does not fit in the forced version, encoding fails; default is automatic (smallest that fits). — [ZXing javadoc](https://zxing.github.io/zxing/apidocs/com/google/zxing/EncodeHintType.html); [tessl ZXing hints reference](https://tessl.io/registry/tessl/maven-com-google-zxing--core/files/docs/reference/configuration-hints.md)
- ZXing `MARGIN`: the general javadoc says "margin, in pixels"; for QR codes it is interpreted as the quiet zone in modules, default 4. Sources disagree in wording, so test it. — [ZXing javadoc](https://zxing.github.io/zxing/apidocs/com/google/zxing/EncodeHintType.html); [tessl ZXing hints reference](https://tessl.io/registry/tessl/maven-com-google-zxing--core/files/docs/reference/configuration-hints.md)
- ZXing also has `QR_MASK_PATTERN` (0..NUM_MASK_PATTERNS-1), `CHARACTER_SET`, and `QR_COMPACT` hints. — [ZXing javadoc](https://zxing.github.io/zxing/apidocs/com/google/zxing/EncodeHintType.html)
- Usage pattern: hints map keyed by `EncodeHintType`, passed to `QRCodeWriter().encode(contents, BarcodeFormat.QR_CODE, width, height, hints)`, which returns a `BitMatrix`. The default ECC is L according to one reference and M according to another. Either way, set `ERROR_CORRECTION = Q` explicitly. — [tessl QR reference](https://tessl.io/registry/tessl/maven-com-google-zxing--core/files/docs/reference/qrcode.md); [ZXing hints search summary](https://tessl.io/registry/tessl/maven-com-google-zxing--core/files/docs/reference/configuration-hints.md)
- qrcode-kotlin (g0dkar): MIT licence. Android artifact `io.github.g0dkar:qrcode-kotlin-android`. Described as generating QR codes "without any other dependencies". The latest version seen on Maven Central was 4.5.0, and the README shows 4.1.1. — [Maven Central qrcode-kotlin-android](https://central.sonatype.com/artifact/io.github.g0dkar/qrcode-kotlin-android); [Maven Central qrcode-kotlin](https://central.sonatype.com/artifact/io.github.g0dkar/qrcode-kotlin); [ecosyste.ms](https://awesome.ecosyste.ms/projects/github.com%2Fg0dkar%2Fqrcode-kotlin)
- Nayuki QR Code generator: MIT licence. Java is the reference implementation (about 1,260 lines, no dependencies beyond the standard library). Maven coordinates `io.nayuki:qrcodegen`. The library outputs raw modules and the caller paints them. — [Project Nayuki page](https://www.nayuki.io/page/qr-code-generator-library); [GitHub nayuki/QR-Code-generator](https://github.com/nayuki/QR-Code-generator)
- Nayuki Java API: `QrCode.encodeText(String text, QrCode.Ecc ecl)` and `QrCode.encodeSegments(List<QrSegment> segs, Ecc ecl, int minVersion, int maxVersion, int mask, boolean boostEcl)`. With `boostEcl`, the library may raise the ECC level "if it doesn't increase the version number". — [Nayuki javadoc package summary](https://nayuki.io/res/qr-code-generator-library/javadoc/io/nayuki/qrcodegen/package-summary.html); [Project Nayuki page](https://www.nayuki.io/page/qr-code-generator-library)
- Nayuki's demo exposes the "Border" (quiet zone) setting in modules and the "Scale" setting in pixels per module, so the border is a separate parameter of rendering, not of encoding. — [Project Nayuki page](https://www.nayuki.io/page/qr-code-generator-library)

### Inferences
- Payload `LOC:B-2-07` uses only QR alphanumeric characters (0-9, A-Z, space, `$ % * + - . / :`), so encoders pick alphanumeric mode. 10 chars fits version 1 (21x21 modules) at ECC Q (the standard V1-Q alphanumeric capacity is 16 chars; this figure comes from the QR capacity table, not from a source fetched in this session). With a 4-module quiet zone on each side the symbol is 29 modules wide, so at 75 mm the module is about 75/29 ≈ 2.6 mm. That is very large and should scan easily at 2-3 m on a phone.
- Keep payloads uppercase. Lowercase forces byte mode and may push the code to a larger version. Pin `QR_VERSION = 1` (or Nayuki `minVersion = maxVersion = 1`) for depots with codes ≤16 chars. All labels then have identical module size, and an overlong code fails loudly instead of silently shrinking the modules.
- Nayuki's `encodeText` probably calls `encodeSegments` with `boostEcl = true`, which would quietly turn Q into H when there is room. This is not verified on the page. If an exact level Q matters, call `encodeSegments(..., boostEcl = false)`. Leaving it boosted would not hurt scanning.
- ZXing `QRCodeWriter.encode(width, height)` scales the matrix by an integer factor and pads the remainder. A requested pixel size therefore does not give an exact module size. The most accurate approach is to encode at size 0/0 (or 1/1) to get the raw `BitMatrix` (1 px per module), then draw each dark module as a rectangle at the exact mm size yourself. This is based on ZXing behaviour from prior knowledge, not re-verified here.
- Drawing modules as `canvas.drawRect` on a `PdfDocument` page canvas should produce vector rectangles in the PDF, so the code is crisp at any printer DPI. Drawing a Bitmap embeds a raster image instead. (Inference from how the PdfDocument canvas works; not checked in a source.)
- Licence: ZXing is Apache-2.0 (prior knowledge; not re-fetched). qrcode-kotlin and Nayuki are MIT. All three are fine for a closed-source app with attribution.

### Gaps
- I did not confirm the default ECC level of ZXing QRCodeWriter in the official source (references say L or M). Set it explicitly.
- I found no measured APK-size impact for each library. ZXing core is a few hundred KB, which is prior knowledge and unverified. The app likely already depends on ZXing or ML Kit for scanning.

## 2. Making the PDF and printing from Android (PdfDocument, PrintManager, PrintHelper, Mopria, share)

### Takeaway
Build a multi-page A4 PDF with `PdfDocument`/`PrintedPdfDocument` (units are PostScript points, 1/72 inch, so 1 mm = 2.8346 pt). Send it either through `PrintManager.print()` with a `PrintDocumentAdapter`, or share/save the PDF file. `PrintHelper` is for bitmaps and always scales to fit or fill, so it is not suitable for exact-size sticker sheets. Neither Android nor Mopria documents a "100 %/actual size" guarantee. The app needs an on-sheet calibration ruler plus a user offset or scale correction.

### Cited Findings
- Custom print flow: get `PrintManager` via `context.getSystemService(Context.PRINT_SERVICE)` and call `printManager.print(jobName, adapter, printAttributes)`. The last argument gives hints and pre-set options such as orientation. — [Android Developers: Print custom documents](https://developer.android.com/training/printing/custom-docs)
- `PrintDocumentAdapter` lifecycle: `onStart` (optional), `onLayout` (called whenever the user changes page size or orientation; must report page count via `PrintDocumentInfo.Builder(...).setContentType(CONTENT_TYPE_DOCUMENT).setPageCount(n)` and `callback.onLayoutFinished(info, changed)`), `onWrite` (render the requested `PageRange`s into the `ParcelFileDescriptor`, then `onWriteFinished`), and `onFinish`. These callbacks run on the main thread, so heavy rendering should go to a background thread. — [Android Developers: Print custom documents](https://developer.android.com/training/printing/custom-docs)
- `PrintedPdfDocument(context, printAttributes)` creates pages that match the selected media. Drawing is done with a `Canvas`, and "elements are specified in points, which is 1/72 of an inch", with origin 0,0 at the top-left. Many printers cannot print to the paper edge, so account for unprintable margins. — [Android Developers: Print custom documents](https://developer.android.com/training/printing/custom-docs)
- The sample shows `computePageCount()` changing items per page with `mediaSize.isPortrait`. For sticker sheets, lock to A4 portrait and a fixed grid instead. — [Android Developers: Print custom documents](https://developer.android.com/training/printing/custom-docs)
- `androidx.print.PrintHelper` is a "Helper for printing bitmaps". It offers `printBitmap(jobName, Bitmap | Uri, callback)`, `setScaleMode(SCALE_MODE_FIT = 1 "image will be scaled but leave white space" | SCALE_MODE_FILL = 2 "fill the paper and be cropped", default FILL)`, `setOrientation` (default landscape), `setColorMode`, and `systemSupportsPrint()`. The `setScaleMode` javadoc text has FIT and FILL swapped compared with the constants, a documentation bug. — [androidx PrintHelper reference](https://developer.android.com/reference/androidx/print/PrintHelper)
- The Android framework source defines separate fitting modes (fit-to-page, scale-to-fit, scale-to-fill) in the print pipeline. — [/e/OS android_frameworks_base commit](https://gitlab.e.foundation/e/os/android_frameworks_base/-/commit/aec1417ca9eb63209668ac17da90cf8a07c6076c)
- Users report that Android print dialogs (HP plugin, Default Print Service) often offer only "Fit"/"Full page" and no explicit 100 % option, and that labels or patterns print at the wrong size. HP staff suggest looking for "Actual Size" or "Custom scale", or printing from Adobe Acrobat Reader. This evidence is anecdotal. — [HP forum: postage labels A4 only on Android](https://h30434.www3.hp.com/t5/Mobile-Printing-Cloud-Printing/Postage-labels-pdf-prints-full-size-A4-only-on-android/td-p/9028475); [HP forum: sewing pattern scaling](https://h30434.www3.hp.com/t5/Mobile-Printing-Cloud-Printing/Printing-a-sewing-pdf-pattern-from-Android-phone-cannot/m-p/6974609); [HP forum: incorrect size from phone](https://h30434.www3.hp.com/t5/Mobile-Printing-Cloud-Printing/Incorrect-size-while-printing-from-my-phone/td-p/9072456)
- Mopria's Android FAQ lists supported settings (colour, copies, duplex, orientation, page range, media size). No scaling control is listed. — [Mopria Android FAQ](https://mopria.org/androidfaq); [Mopria FAQ](https://mopria.org/faq)
- The Default Print Service and vendor plugins are toggled under Settings > Connected devices > Printing, and which service is used can change scaling behaviour. — [search summary of Android printing guides](https://whizz-tech.com/support/android-pdf-viewer-prints-top-half-only/)

### Inferences
- Recommended architecture:
  1. A `LabelSheetRenderer` draws N labels per A4 page onto a `PdfDocument` (plain `android.graphics.pdf.PdfDocument` with `PageInfo.Builder(595, 842, i)` for A4 in points), writes to `cacheDir/labels/depot-B.pdf`, and is reusable for the print adapter and for sharing.
  2. **Print**: a `PrintDocumentAdapter` that copies or renders that PDF. Pass `PrintAttributes.Builder().setMediaSize(MediaSize.ISO_A4).setMinMargins(Margins.NO_MARGINS)` as the hint.
  3. **Share/Save**: `FileProvider` + `Intent.ACTION_SEND` (`application/pdf`) so the user can send it to a PC (WhatsApp/Drive/email) and print from Acrobat with "Actual size". This is the most reliable 100 % path for a stock room.
- Make the PDF page exactly A4 and keep content inside a ~5 mm safe zone where the sticker template allows. If the print service treats the page as "fit to printable area", it shrinks the content only slightly (a few %). That changes a 2.6 mm module by under 0.1 mm, which is harmless for scanning but can misalign stickers.
- Calibration: print a test page on plain paper with 100 mm horizontal and vertical rulers and crosshairs at the sticker corners. Let the user enter the measured length (gives a scale factor) and an X/Y offset in mm (stored per printer in DataStore), and apply them as a `canvas.translate/scale` before drawing. The user holds the test page over a sticker sheet against the light to check alignment.
- Reprinting a single label on an A4 sheet: support a "start at position k" option (skip already-used stickers on a partly used sheet). This is standard practice in label software.
- `PrintHelper.printBitmap` is fine for a quick single-label printout on plain paper but not for sticker sheets (it scales to fit or fill).

### Gaps
- I found no official Android or Mopria documentation on whether the Default Print Service rescales a PDF whose page size equals the selected media. This must be tested on the stock room's actual printer.
- Turkish-market Wi-Fi printer behaviour (e.g. specific HP/Canon/Epson plugins) is untested.

## 3. A4 sticker sheet templates available in Turkey and which fit a 75 mm QR + text

### Takeaway
Tanex (the common Turkish brand, sold widely via Akakçe/n11/Idefix) has the standard sizes 105x74 mm (8/sheet), 99.1x67.7 mm (8/sheet) and 70x37 mm (24/sheet) at about 340-380 TL per 100 sheets. None of these holds a 75 mm square QR *plus* large text. 105x74 fits a ~70 mm QR (incl. quiet zone) with a text column beside it. A 105x148 mm (A6, 4/sheet) or 210x148 mm (A5, 2/sheet) sheet fits 75 mm plus large text. Plain A4 paper with lamination or a pouch is also an option.

### Cited Findings
- Tanex TW-2374, 105x74 mm, 8 labels/sheet, 100 sheets, laser: from 376,00 TL on Akakçe (~36 sellers). — [Akakçe Tanex](https://www.akakce.com/tanex.html); [Akakçe Tanex etiket](https://www.akakce.com/etiket/tanex.html)
- Tanex TW-2008, 99.1x67.7 mm (8/sheet), laser: from 343,20 TL on Akakçe (~32 sellers). — [Akakçe Tanex](https://www.akakce.com/tanex,1.html)
- Tanex TW-2037, 70x37.125 mm, 24/sheet, 100 sheets: ~374 TL on n11. TW-2324, 70x35 mm: 366,60 TL (Akakçe) / 470 TL (Idefix). — [n11 Tanex](https://www.n11.com/arama?m=Tanex); [Idefix Tanex](https://www.idefix.com/marka/tanex-20039695); [Akakçe Tanex etiket](https://www.akakce.com/etiket/tanex.html)
- The same sizes exist from European brands (Avery Zweckform ELA043 99.1x67.7 mm; HERMA 4470 105x74 mm; Q-Connect KF10659 105x74 mm). Templates are interchangeable across brands. — [aio.lv Avery ELA043](https://aio.lv/en/product--avery-zweckform-ela043--12255298.md); [aio.lv HERMA 4470](https://aio.lv/en/product--herma-4470--1598858.md); [aio.lv Q-Connect KF10659](https://aio.lv/en/product--q-connect-kf10659--12041002.md)

### Inferences
- Fit check against the 75 mm requirement:
  - 70x37 / 70x35: far too small (QR max ~33 mm). Reject.
  - 99.1x67.7: QR max ~63 mm incl. quiet zone, text column ~33 mm wide. Workable but below target.
  - 105x74 (TW-2374): QR ~70 mm incl. quiet zone (module ≈ 2.4 mm at version 1) + 35 mm text column for a vertical `B-2-07` stack, or rotate the text. This is the best small-format compromise and cheap: 150 labels ≈ 19 sheets, so one 100-sheet pack covers all three depots several times.
  - A6 105x148 (4/sheet) or A5 210x148 (2/sheet): full 75 mm QR on top + 40-60 mm tall text below. Recommended if column uprights are wide enough. Check Tanex/Avery A6 codes. Not verified in this session.
- The quiet zone counts as part of the label's white area, so the QR can bleed nearly to the sticker edge on the quiet-zone side. Leave ≥2 mm for die-cut and printer misalignment.
- The calibration offsets from section 2 matter most for 8-up sheets with no gutters (105x74 sheets usually have zero margins: 2x105 = 210, 4x74 = 296 ≈ A4 297). Many inkjet/laser printers cannot print the outer ~3-5 mm, so on edge-to-edge sheets keep the content ≥5 mm from the outer sheet edges.

### Gaps
- No source fetched for Tanex A6/A5 product codes or for "Mürekkep" brand sheets; Tanex polyester/weatherproof A4 sheets are not confirmed either.
- Prices are undated Akakçe/n11 snapshots (seen 2026-11); TL prices move fast.

## 4. Thermal label printers with Android SDKs (Brother, Zebra, generic ESC/POS/TSPL)

### Takeaway
For one-off reprints, a thermal printer is nicer than A4 sheets, but the width limit matters. Brother QL-8xx (62 mm max) cannot print a 75 mm QR; it needs a 4-inch (~100-110 mm) printer such as Brother QL-1100 series, Zebra ZD/ZQ, or a generic Xprinter XP-420B-class TSPL printer. Brother and Zebra have official Android SDKs. Generic printers rely on community ESC/POS libraries or hand-written TSPL commands. For 100-150 labels plus occasional reprints, A4 sheets + an office printer are cheaper. A thermal printer is a "phase 2" convenience.

### Cited Findings
- Brother Print SDK for Android: the latest is v4.13.2 (download page lists 09/08/2026, 26.3 MB zip). An earlier search snippet listed v4.13.0 dated 09/08/2025, so treat the exact version and date as uncertain. Brother "strongly recommends" v4.x; v4 is backward-compatible with v3 APIs and new models will only be supported by v4. It is downloaded as a zip after accepting an agreement. The terms and Maven availability are not stated on the page. — [Brother Mobile SDK download](https://support.brother.com/g/s/es/dev/en/mobilesdk/download/index.html)
- Brother's supported-printer list includes QL-820NWB/QL-820NWBc with Wi-Fi, Bluetooth (with a footnote) and USB. The release notes say the SDK treats QL-820NWBc as QL-820NWB and QL-1110NWBc as QL-1110NWB. — [Brother SDK supported printers](https://support.brother.com/g/s/es/htmldoc/mobilesdk/overview/supported-printer.html); [Brother SDK Android release notes](https://support.brother.com/g/s/es/htmldoc/mobilesdk/about/release-notes-android.html); [Brother SDK model list](https://support.brother.com/g/s/es/dev/en/mobilesdk/model/index.html)
- QL-820NWB: direct thermal, 300x600 dpi, USB/Ethernet/Wi-Fi/Bluetooth, black+red printing on special red roll, max label width 62 mm, auto cutter. Prices: €129.99 (Bechtle DE), $239.99 (Brother USA), £263.45 ex VAT (UK). No Turkish price found. — [aio.lv QL-820NWBc](https://aio.lv/en/product--brother-ql820nwbcvmxx1--7232155.md); [Bechtle QL-820NWBc](https://bechtle.com/de-en/shop/brother-ql-820nwbc-td-300dpi-et-printer--4691391--p); [Brother USA QL-820NWB](https://www.brother-usa.com/p/desktop-label-printers/QL820NWB); [Brother UK QL-820NWB](https://www.brother.co.uk/labelling/ql-printers/ql-820nwb)
- A Turkish user guide for QL-810W/820NWB exists on Brother's site, which suggests the models are sold in Turkey. — [Brother TR user guide PDF](https://download.brother.com/welcome/docp100287/ql-810w_820nwb_tur_ug_05.pdf)
- Zebra Link-OS Multiplatform SDK: Android connections over TCP/IP, Bluetooth and USB OTG. The current download page lists testing on Android 14, 15 and 16. One index lists Android SDK version 2.14.5198, and 3.0 docs exist. — [Zebra Link-OS SDK downloads](https://www.Zebra.com/us/en/support-downloads/printer-software/link-os-multiplatform-sdk.html); [Zebra blog: getting started with Link-OS Android SDK](https://developer.zebra.com/blog/learning-how-get-started-linkos-android-sdk); [Link-OS SDK 3.00 docs](https://techdocs.zebra.com/link-os/3-00)
- Bluetooth to Zebra from Android needs `BLUETOOTH_CONNECT` (plus `BLUETOOTH_SCAN` for discovery); legacy `BLUETOOTH`/`BLUETOOTH_ADMIN` use `maxSdkVersion=30`. You send ZPL by MAC address. — [zsdk Flutter wrapper docs](https://pub.dev/documentation/zsdk/latest/)
- Generic ESC/POS: DantSu/ESCPOS-ThermalPrinter-Android (Java, MIT, ~1.3k stars, Bluetooth/TCP/USB, JitPack, last push May 2024). Alternatives are okarmazin/escpos4k (Kotlin) and ringga-dev/kmp-printer (KMP). — [GitHub DantSu/ESCPOS-ThermalPrinter-Android](https://WWW.GITHUB.COM/DantSu/ESCPOS-ThermalPrinter-Android); [ecosyste.ms DantSu](https://awesome.ecosyste.ms/projects/github.com%2Fdantsu%2Fescpos-thermalprinter-android); [GitHub escpos4k](https://github.com/okarmazin/escpos4k); [klibs kmp-printer](https://klibs.io/project/ringga-dev/kmp-printer)
- No dedicated, maintained native-Android TSPL library was found. The closest is a React Native fork with a `BluetoothTscPrinter` class. — [react-native-bluetooth-escpos-printer-fork README](https://cdn.jsdelivr.net/npm/react-native-bluetooth-escpos-printer-fork@0.0.28/README.md)
- Xprinter XP-420B (4-inch, "110 mm"/A6 class, 203 dpi, USB with optional Bluetooth/Wi-Fi/LAN): about Rp1.0-1.32 million in Indonesia (~US$60-80). No Turkish listing price found. — [Tokopedia XP-420B](https://www.tokopedia.com/barcoderetail/printer-barcode-thermal-label-printer-xprinter-xp-420b-usb-bluetooth); [regard.ru XP-420B](https://www.regard.ru/product/75812/printer-etiketok-xprinter-xp-420b-usbwifi/description); [manuals.plus TR XP-420B](https://tr.manuals.plus/asin/B0F8N6THPC)

### Inferences
- Width limits vs a 75 mm QR: 62 mm Brother QL-8xx gives a QR of at most ~58 mm. That fails the 75 mm target but still scans well, since module ≈ 2 mm for version 1. A 4-inch class (QL-1100/1110 at 103 mm per prior knowledge, Zebra ZD421/ZQ630 at 104 mm, Xprinter XP-420B at ~108 mm) fits 75 mm QR + text on 100x100 or 100x150 mm labels.
- Easiest integration path for a generic printer: render the label as a 1-bit Bitmap at 203 dpi (8 dots/mm) and send TSPL `SIZE 100 mm,100 mm` / `GAP 3 mm,0` / `CLS` / `BITMAP` / `PRINT 1` over a Bluetooth SPP `RFCOMM` socket. Alternatively use the printer's native `QRCODE` command. This needs about 100 lines of code, no SDK. TSPL syntax comes from prior knowledge, not from a source fetched here.
- Zebra: ZPL has a native QR command (`^BQ`) so the printer renders the code (from prior knowledge; the search did not surface ZPL docs). Zebra hardware costs much more than generic printers.
- Brother SDK: you pass a Bitmap or PDF and a label-size enum. It handles Wi-Fi, Bluetooth and roll detection. The download licence terms need reading before shipping.

### Gaps
- No Turkish TL prices were found for Brother QL-820NWB/QL-1110NWB, Zebra ZD421, or Xprinter XP-420B. Check Hepsiburada/Trendyol/Akakçe.
- The Brother SDK licence terms (redistribution, cost) are behind an agreement page and were not read.
- TSPL support and the exact print width of XP-420B were not confirmed from an Xprinter spec sheet.
- Compatibility of DK-22205 (62 mm continuous) is documented by Brother; secondary sources were contradictory and I did not verify it.

## 5. Durability: paper vs synthetic, lamination, thermal fading, placement, holders, floor labels

### Takeaway
In an indoor shoe stock room (dry, no UV, some abrasion from boxes), laser/inkjet-printed paper A4 stickers last long enough, especially laminated or under a clear label-holder strip. Direct thermal paper fades (heat, light, friction) and is the weakest option for permanent rack labels. Durable thermal labels need thermal transfer with resin ribbon on polyester/polypropylene. Floor labels need special abrasion-resistant materials and are not recommended here.

### Cited Findings
- Direct thermal labels are vulnerable to heat, light and friction and can fade until barcodes stop scanning. One guide recommends thermal transfer for anything stored beyond ~90 days. — [racklify: direct thermal vs thermal transfer](https://racklify.com/encyclopedia/direct-thermal-paper-vs-thermal-transfer-choosing-the-right-label/); [durafastlabel: choosing DT vs TT](https://www.durafastlabel.ca/solutions/choosing-between-direct-thermal-and-thermal-transfer-labels)
- Thermal transfer on polyester with resin ribbon is claimed to last 10-25 years indoors (vendor claim). Ribbon matching: wax for paper, wax-resin for coated paper and light synthetics, resin for polyester/polypropylene. — [durafastlabel 2026 guide](https://durafastlabel.com/blog/the-2026-guide-to-thermal-transfer-labels-choosing-for-durability-and-speed/); [HPRT thermal transfer guide](https://hprt.com/blog/Thermal-Transfer-Labels-Materials-Printers-vs-Direct-Thermal.html)
- Polyester is generally rated most durable (abrasion, UV, moisture, chemicals). Polypropylene is flexible and moisture-resistant, suited to curved or cold surfaces, and less durable than polyester. — [HPRT thermal transfer guide](https://hprt.com/blog/Thermal-Transfer-Labels-Materials-Printers-vs-Direct-Thermal.html); [myassettag TT guide](https://www.myassettag.com/blog/thermal-transfer-barcode-labels-the-complete-guide/)
- Warehouse labelling guide: lamination or a protective film protects against abrasion and moisture. Magnetic or slot-in labels suit "frequently rearranged locations without adhesive residue". — [boxic24 warehouse labelling system](https://boxic24.com/english/ratgeber/warehouse-labeling-system)
- Retro-reflective rack labels allow long-range scanning (up to ~50 ft with long-range scanners), which is overkill for phone scanning at 2-3 m. — [Barcode Factory: retro-reflective labels](https://blog.barcodefactory.com/blog/improve-warehouse-efficiency-with-retro-reflective-labels)
- All lifespan figures above come from vendor or label-seller blogs; I found no independent fade testing.

### Inferences
- For SweepScan: print with a laser printer (toner does not run when wet, unlike most inkjet) on Tanex sheets. Optionally cover with clear self-adhesive laminate or slide into transparent C-profile label holders on the uprights. The holders also make single-label reprints trivial: swap the paper insert, no scraping.
- Glossy lamination can cause specular glare that hurts phone QR decoding under overhead lights. Prefer matte laminate (inference; not sourced).
- Avoid thermal paper for permanent column labels. If a thermal printer is bought, use synthetic direct-thermal or thermal-transfer labels for permanent ones and use paper only for temporary labels.
- Floor labels: skip. Shoe boxes stand in front of floor positions, and floor labels get scuffed by trolleys.

### Gaps
- No source on adhesive performance on painted steel uprights versus galvanised steel, or on the Turkish availability of magnetic label holders and C-profile ticket holders.

## 6. Label layout best practice (font size at 2-3 m, arrows, colour per depot)

### Takeaway
Use about 25 mm character height per 3 m of reading distance. At 2-3 m that means 20-25 mm cap height, so 30-40 mm is comfortable for `B-2-07`. Pair colour (one colour per depot) with text, never colour alone. Add a directional arrow when a label sits between two columns, and keep the most significant part of the code largest.

### Cited Findings
- Rule of thumb: "roughly 25 mm character height per 3 metres of distance". Floor-level locations work with 50 mm labels; high levels scanned from a forklift need 75-100 mm labels. — [boxic24 warehouse labelling system](https://boxic24.com/english/ratgeber/warehouse-labeling-system)
- Colour coding: use a fixed colour per level or zone, keep it to 6-8 colours, avoid red-green pairs (colour-vision deficiency), and always pair colour with text and a barcode so labels remain usable when printed monochrome. Yellow-black (ISO 3864) marking is for hazards and traffic routes, not storage locations. — [boxic24 warehouse labelling system](https://boxic24.com/english/ratgeber/warehouse-labeling-system)
- Other legibility standards are more conservative: minimum character height = 0.004 x reading distance (preferred 0.006 for critical information or poor light). That gives 8-12 mm at 2 m and 12-18 mm at 3 m. — [tpub label placement standard](https://nuclearpowerradiation.tpub.com/s1044cn1/Label-Placement-Cont-D-18.htm)
- Rack-labelling vendors also discuss consistent placement height, directional arrows on multi-level beam labels, and the location hierarchy (aisle-rack-level-position) on the label. — [Mecalux: rack labels](https://www.mecalux.com/blog/rack-labels-warehouse); [Barcode Factory: rack labelling best practices](https://blog.barcodefactory.com/blog/warehouse-rack-labeling-best-practices); [Creative Safety Supply: rack labeling](https://creativesafetysupply.com/articles/rack-labeling)

### Inferences
- Suggested layout for a 105x74 sticker (landscape): left 70x70 mm QR (version 1, ~2.4 mm modules, 4-module quiet zone); right 35 mm column with depot letter `B` in a coloured block (~25 mm), `2` and `07` stacked in bold condensed sans at ~22-25 mm cap height, and an arrow (◀/▶/▼) showing which column the label belongs to.
- Suggested layout for A6 105x148 portrait: top 75 mm QR; below it `B-2-07` at 35-40 mm cap height across the full 105 mm width (fits ~6 characters of a condensed font), plus a thin depot colour band and an arrow. Add small text with the depot name and print date for reprint tracking.
- Use a coloured band only as a supplement (depot colours like blue/orange/purple avoid red-green). The QR area must stay pure black on white. Print the QR in black even on colour printers, because coloured QR codes lower contrast.
- Human-readable text must equal the payload minus the prefix (`B-2-07` vs `LOC:B-2-07`), so a worker can type it if the QR is damaged. This fits the app's existing OCR fallback idea.
- Test-scan from 2-3 m before mass printing. At ~2.4-2.6 mm modules, phones should decode from well beyond 3 m (inference; consistent with the module-size reasoning above, not measured here).

### Gaps
- I found no authoritative standard specifically for phone-based QR reading distance versus module size. The research-sibling notes on reading-distance simulation (repo commit c87896b) may cover this.
- No source tested arrow conventions or label placement height for shoe-box racks specifically.
