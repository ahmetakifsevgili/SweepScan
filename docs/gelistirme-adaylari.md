# Geliştirme adayları: repo taraması ve koda uyarlanabilirlik

Tarama tarihi: 15 Kasım 2026. Repo bilgileri (yıldız, lisans, son güncelleme) GitHub API'sinden, sürüm
bilgileri Maven Central ve Google Maven'dan alındı. "Koda nasıl takılır" sütunu SweepScan'in kendi
dosyalarına bakılarak yazıldı. Hiçbiri henüz uygulanmadı.

Lisans kuralı: MIT, Apache-2.0 ve BSD kodları (bildirimi korunarak) projeye alınabilir. GPL ve AGPL
kodları kopyalanırsa SweepScan de GPL/AGPL olmak zorunda kalır; onlardan yalnızca fikir alınır.

---

## 1. Salıdan önce yapılabilecekler (küçük, sayımı değiştirmez)

**Durum: üçü de 0.3.2 sürümünde yapıldı ve emülatörde doğrulandı.**
- 1.1: hareket verisi 55 karenin 55'inde kayda girdi. İki ölçümün de güvenli olduğu 43 karede, yeni
  ölçümün görüntü merkezindeki kayması eski ölçümle en fazla 1,1 piksel farklı çıktı.
- 1.2: zxing-cpp'nin okuduğu 19 tespitin 19'unda çizgi sayısı kayda girdi. Hepsi aynı etiketti ve değer
  hep 2 çıktı; bu bilginin işe yarayıp yaramadığı ancak saha verisiyle anlaşılır.
- 1.3: okunamayan etiket kalın kırmızı çerçeve, pembe dolgu ve "!" rozetiyle çiziliyor.

Üçü de ya yalnızca kayda veri ekler ya da yalnızca görünümü değiştirir. Amaç: salı günü çekilen kayıtlar,
sonraki fikirleri **yeniden tarama yapmadan** bilgisayarda denemeye yetsin.

| # | Ne | Kaynak | Koda nasıl takılır | Efor |
|---|---|---|---|---|
| 1.1 | Kareler arası hareketi yalnızca öteleme olarak değil, dönme ve ölçekle birlikte de ölç ve **kayda yaz** | [BoT-SORT](https://github.com/NirAharon/BoT-SORT) (MIT) `tracker/gmc.py`: `estimateAffinePartial2D` + RANSAC | `MotionEstimator.measure()` zaten eşleşen nokta çiftlerini tutuyor. `Calib3d.estimateAffinePartial2D` pakette hazır (kontrol edildi). Sonuç `FieldLogger`'daki `motion` alanına 6 sayı olarak eklenir. Sayma mantığı aynı kalır. | ~2 saat |
| 1.2 | zxing-cpp'nin "kaç tarama çizgisi aynı sonucu verdi" bilgisini kayda yaz | [zxing-cpp](https://github.com/zxing-cpp/zxing-cpp) (Apache-2.0) | Kullandığımız 3.1.1 sürümünde `Result.lineCount` var (kontrol edildi). `ZxingEngine.decodeRegion()` bunu döndürür, kayıttaki `zx` alanına eklenir. | ~1 saat |
| 1.3 | Okunamayan etiketin kırmızı işaretini belirginleştir | Scandit ve barKoder'ın arayüz yaklaşımı (fikir) | `OverlayView.onDraw()`: kalın çerçeve, yarı saydam dolgu, "!" işareti. | ~1 saat |

1.1 neden önemli: şu an kayıtta yalnızca öteleme var. Dönme ve ölçek kaydedilmezse, 2.3'teki daha iyi
hareket modelini salı verisiyle sınayamayız; aynı sütunları tekrar çekmek gerekir.

---

## 2. Salı verisi gelince bilgisayarda denenecekler

Bunların hepsi oynatma testinde yeni bir ayar olarak ya da kayıtlı PNG kareler üzerinde denenir. Hangisi
sayım hatasını düşürürse uygulamaya o girer.

| # | Ne | Kaynak | Koda nasıl takılır | Efor |
|---|---|---|---|---|
| 2.1 | Yalnızca Code 128 kabul et | Araştırma raporu A4 | ML Kit şu an Code 128, EAN-13, EAN-8 ve ITF arıyor (`MlKitEngine`). Her okumanın formatı zaten kayıtta (`f`). `TrackerConfig`'e format listesi, `ReadFilter`'a `BAD_FORMAT` kararı, `ReplayTest`'e bir varyant eklenir. | 1–2 saat |
| 2.2 | zxing-cpp'nin kullanmadığımız ayarları: `binarizer`, `tryDenoise`, `minLineCount`, kırpmayı 2× büyütüp okuma | [zxing-cpp](https://github.com/zxing-cpp/zxing-cpp) (Apache-2.0) | Ayarların hepsi 3.1.1'in Android sarmalayıcısında var (kontrol edildi). Önce bilgisayarda, kayıtlı karelerde zxing-cpp'nin Python sürümüyle karşılaştırılır; kazanan ayar `ZxingEngine`'e yazılır. | Yarım gün |
| 2.3 | Benzerlik (dönme + ölçek) hareket modelini saymaya uygula | [BoT-SORT](https://github.com/NirAharon/BoT-SORT) (MIT) | `Motion` sınıfı 2×3 matris taşır, `BarcodeTracker` kutuları `translate` yerine bu matrisle taşır. 1.1 yapıldıysa kayıtlarla ölçülür. Bilinen sınır: README'deki "yalnızca öteleme" maddesini kapatır. | 1–2 gün |
| 2.4 | Görüntü bulanıkken jiroskopla hareket tahmini | Karpenko ve ark. 2011 (video sabitleme) | Jiroskop özeti zaten her karede kayıtta. `BarcodeTracker.chooseShift()`'e dördüncü kaynak olarak eklenir. | ~1 gün |
| 2.5 | Oynatma testine kimlik ölçütleri: iz bölünmesi, kimlik değişimi | [Scanly](https://github.com/Yangjunjie-Lin/Scanly) (MIT) `benchmark/tracking/` | `ReplayTest` şu an yalnızca sayım hatasını basıyor. Aynı kutunun kaç ize bölündüğü de basılırsa hatanın sebebi görünür. Kod değil, fikir alınır (TypeScript). | Yarım gün |

2.2 için not: zxing-cpp'nin Python paketini kurmak istedim, izin verilmediği için kurmadım. Bu yüzden
ayarların fark yaratıp yaratmadığını **ölçmedim**; yalnızca ayarların var olduğunu doğruladım.

---

## 3. İhtiyaç çıkarsa (daha büyük işler)

| # | Ne | Kaynak | Koda nasıl takılır | Ne zaman | Efor |
|---|---|---|---|---|---|
| 3.1 | Sütun resmi: taranan sütunun tek resmi, üstünde yeşil/kırmızı işaretler | Scandit patenti US11886954B2 (fikir), Dynamsoft panorama (fikir) | OpenCV'nin Android paketinde hazır `Stitcher` **yok** (kontrol edildi). Ama gerek de yok: sahne kayması zaten hesaplanıyor; küçültülmüş kareler `Imgproc.warpAffine` ile bir tuvale yapıştırılır. Gözden geçirme ekranına eklenir. | Saymaya güven oluşunca | ~2 gün |
| 3.2 | Bluetooth LE klavye yedeği | [BLE-HID-Peripheral-for-Android](https://github.com/kshoji/BLE-HID-Peripheral-for-Android) (Apache-2.0, 260 yıldız, Eylül 2026'da güncellenmiş) | Yazma mantığımız taşıma katmanından ayrı: `HidLink` arayüzünde yalnızca `isConnected()` ve `send()` var. `KeyboardPeripheral.java` uyarlanıp ikinci bir `HidLink` olur; `HidTypist` ve `HidSender` değişmez. README'de Windows ile denendiği **yazmıyor**. | Laptop klasik Bluetooth klavyeyi kabul etmezse | 1–2 gün |
| 3.3 | Etiket dedektörü: barkodu hiç bulunamayan etiketi de görmek | Veri: salı kareleri + [barcode_detection_dataset](https://github.com/Certseeds/barcode_detection_dataset) (CC-BY-SA). Model: Apache lisanslı bir dedektör; Ultralytics YOLO AGPL olduğu için alınmaz. | OpenCV'nin `dnn` modülü pakette hazır (kontrol edildi), yeni kütüphane gerekmez. Çıktısı `FramePipeline`'da "bulundu ama okunamadı" tespiti olarak eklenir. | Kayıtlar ML Kit'in etiket kaçırdığını gösterirse | Günler, sonucu belirsiz |
| 3.4 | DİA Web Servis ile doğrudan fiş yazma ve beklenen liste | DİA geliştirici belgeleri | Yeni bir `dia/` paketi; gözden geçirme ekranına "API ile gönder". | API anahtarı gelince | 3–5 gün |
| 3.5 | zxing-cpp 3.2.0 | [zxing-cpp sürümleri](https://github.com/zxing-cpp/zxing-cpp/releases) | Tek satır sürüm değişikliği. Android ikili dosyası %43 küçülüyor, bir Code 128 düzeltmesi var. Şu an yalnızca aday sürüm (rc1, 13 Kasım); kararlı sürüm beklenmeli. | Kararlı sürüm çıkınca | 15 dk |

---

## 4. Alınmayacaklar

| Repo / fikir | Neden |
|---|---|
| [hid-barcode-scanner](https://github.com/Fabi019/hid-barcode-scanner) (GPL-3.0), [barcode-to-pc-server](https://github.com/fttx/barcode-to-pc-server) (GPL-3.0) | Lisans. Fikirleri (her tuştan sonra bırakma raporu, ayarlanabilir gecikme) zaten kendi kodumuzla uygulandı. |
| [boxmot](https://github.com/mikel-brostrom/boxmot) (AGPL-3.0), [BarBeR](https://github.com/Henvezz95/BarBeR) (AGPL-3.0) | Lisans. BarBeR ölçüm yöntemi olarak yararlı, kodu alınamaz. |
| [norfair](https://github.com/tryolabs/norfair) (BSD), [OC-SORT](https://github.com/noahcao/OC_SORT) (MIT), [ByteTrack](https://github.com/FoundationVision/ByteTrack) (MIT) | Python; hareket eden nesneler için tasarlanmış. Kutularımız sabit, hareket eden kamera. İki aşamalı eşleştirme fikri zaten alındı. |
| ARCore ile 3B konum | Analiz görüntüsü çözünürlüğü küçük etiketleri okumaya yetmiyor (araştırma raporu). |
| OpenCV `BarcodeDetector` | Pakette var ama Code 128 okuduğu doğrulanamadı. |
| ML Kit güncellemesi | Yok. 17.3.0 hâlâ en son sürüm (Google Maven, 15 Kasım). |
| OpenCV 5.1, CameraX 1.6 | Bize gereken bir düzeltme içerdiğini doğrulamadım; saha testinden önce büyük sürüm değişikliği gereksiz risk. |
| [Barcode_1D](https://github.com/iyyun/Barcode_1D) (MIT, C++), [deblurrinator](https://github.com/coltonomous/deblurrinator) (MIT, 0 yıldız) | Kodlarını incelemedim; küçük ve kanıtlanmamış projeler. Bulanıklığın asıl çözümü kısa pozlama. |
| [Scanly](https://github.com/Yangjunjie-Lin/Scanly), QR-GEN kodu | Kendi belgelerine göre gerçek telefonda doğrulanmamış. Yalnızca 2.5'teki ölçüm fikri alınır. |

---

## 5. Taramada çıkan bir eksik

Repoda **lisans dosyası yok**. GitHub'a koymadan önce bir lisans seçilmeli (MIT ya da Apache-2.0). Lisanssız
bir repo CV'de "açık kaynak" sayılmaz; başkası kodu yasal olarak kullanamaz. Apache lisanslı kütüphaneler
(zxing-cpp, OpenCV, CameraX, ML Kit örnekleri) her iki seçenekle de uyumlu.
