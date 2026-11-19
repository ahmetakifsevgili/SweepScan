# Konum haritası: "0159 2000 nerede?"

**Hedef:** ürün (model) kodunu yazınca, depoda hangi satır ve sütunda durduğunu göstermek.

**Kapsam dışı:** tam stok takibi, adet doğruluğu, satışları düşmek.

Arka plan ve kaynaklar: [`reports/Depoda model konum haritası.md`](../reports/Depoda%20model%20konum%20haritası.md).

## Uygulamada (0.7.1, 17 Kas 2026): etiketsiz, sıra sıra

- **Konum = depo + sıra.** Her rafa QR gerekmez. Tarama ekranında *HARİTA*'ya geç, açılan listeden
  sırayı seç (bir sonraki sıra önceden seçili gelir), sırayı **sol başından sağa** tara. Duraklat
  sırayı kapatmaz, kaldığın yerden devam edersin; **Bitir** kaydeder ve sıradaki sırayı sorar.
- **Sıranın neresi:** kutuların taranma sırasından çıkar: "ortalarda · soldan 5. model". Krokide
  sıranın üstünde nokta, rota oraya; önden görünümde o kısım turuncu.
- İstersen sıra başına bir QR (`LOCB301`) okutarak da sıra seçilebilir; zorunlu değil.

## Uygulamada (0.6.0, 17 Kas 2026)

İlk sürüm telefonda. Kroki ve sütun sayıları elle çizilen krokiden alınmış ilk tahminler; depoda
sayılıp *Konum ara → Menü → Düzeni değiştir* ile düzeltilmeli.

- **Depolar:** Büyük depo `B` (sıra 1–10, 4 kat; 17 Kas çizimine göre), Küçük depo `K` (sıra A, B karşılıklı, 4 kat), Orta alan deposu
  `O` (sıra C, D, tek kat). Konum kodu `B-3-07`; QR içeriği `LOCB307` (yalnızca harf ve rakam: Türkçe
  klavye düzenindeki bir el okuyucusu `:` ve `-` işaretlerini bozuyor). Eski `LOC:2-07` etiketleri de okunur.
- **Etiket:** *Konum ara → Menü → Etiket bas*, depo seç, 7,5 cm ya da 5 cm. A4 PDF; yazdır ya da paylaş.
  Yazıcıda ölçek %100.
- **Harita taraması:** tarama ekranında üstteki *SAYIM* çipine dokun → *HARİTA*. Sütunun QR'ını ekranın
  ortasında okut (çift bip, üstte `B-3-07 · 0 kutu`), sonra sütunu tara. Bir sonraki etiket, Duraklat
  ya da Bitir sütunu kaydeder; aynı sütunun eski kaydı silinir. Etiket okunmadan sayılan kutu kaydedilmez
  (uyarı sesi). Geri al son kutuyu, Geri al'a uzun basmak açık sütunun kutularını siler. Bu modda PC'ye
  hiçbir şey yazılmaz.
- **Model:** sayılan her kutunun üstündeki "0169 051 / KAHVE / 36" satırı okunur; 3 okumanın en az
  2'si aynı modeli söyleyince barkodun modeli kaydedilir. Alternatif: *Menü → Model listesi içe aktar*
  (DİA'dan Excel → CSV; her satırda barkod ve model kodu olması yeter).
- **Arama:** *Konum ara* (tarama ekranındaki büyüteç). Model kodu (ya da bir kısmı), barkod ya da
  `B-3-07`. Krokide modelin olduğu sütunlar turuncu, en yakını mavi ve girişten yolu çizili; altta
  sıranın önden görünüşü. Krokide sütuna dokununca içi (yukarıdan aşağı) çıkar. Yeşil sütun = bu
  hafta tarandı, kahverengi = daha eski.
- **Dışa aktarma:** *Menü → Haritayı dışa aktar (CSV)*.

Tasarım araştırması: [`reports/Çok depolu konum uygulaması tasarımı.md`](../reports/Çok%20depolu%20konum%20uygulaması%20tasarımı.md).

## Nasıl çalışacak

1. **Etiket.** Her sütun için bir QR yapıştırılır. İçeriği `LOC:<satır>-<sütun>`, örneğin `LOC:2-07`.
   - **Yeri: kutu etiketleriyle aynı hizada.** Sütunun önündeki dikme, raf kenarı ya da sütunun yanı
     olabilir. Arkadaki duvar olmaz. Benzetimde (`tools/simulate_label_reading.py`), telefon ~60 cm'deki
     kutulara odaklıyken 1 m gerideki etiket hiçbir boyutta okunmadı. 50 cm gerideki etiketi yalnızca
     7,5 cm ve üstü okudu.
   - **Boyut: 7,5 cm.** 1,5× zoom ve 1440p'de benzetim 2,5–3,5 m'den okudu; 45° yandan bakışta da 2–2,5 m.
     5 cm'lik etiket 1,5–2 m'ye kadar okundu. Telefonda okuma testi sayfasıyla doğrulanmalı.
   - **Akış:** sütuna başlarken kamerayı önce QR'a tut. Odak ortadaki nesneye ayarlandığı için etiket
     biraz gerideyse bile yarım saniyede netleşir.
2. **Tarama.** *Harita modunda* önce sütunun QR'ı okunur. Ekranın üstünde "SATIR 2 · SÜTUN 07" bandı çıkar
   ve bip sesi gelir. Sonra sütun her zamanki gibi taranır. Okunan her barkod o sütuna yazılır.
3. **Sütun değişimi.** Yeni bir QR görülünce önceki sütun kapanır ve yenisi açılır. QR okunmadan kutuya
   başlanırsa uygulama kutuları **yazmaz** ve "önce sütun etiketini okut" der. Açık olan sütunun QR'ı
   tekrar görülürse yok sayılır. Konum değiştiğinde ve yanlış bir şey olduğunda iki ayrı ses çalar
   (InvenTree uygulamasının "konuma okut" akışındaki gibi: `StockLocationScanInItemsHandler`).
4. **Yeniden tarama.** Bir sütun yeniden tarandığında o sütunun eski içeriği silinir, yerine yenisi
   yazılır. Kutular taşındıkça harita böyle güncel kalır. Her kayıt "en son görüldü" tarihini taşır.
5. **Arama.** Model kodu (8 hane, ör. `0159 2000`; kısmi yazım ve boşluksuz yazım da olur) ya da
   herhangi bir kutunun etiketi okutulur. Sonuç:

   ```
   0159 2000  SİYAH
     38  → 2-07 (2 kutu) · 1-03 (1)      görüldü: 18 Kas
     39  → 2-07 (1)                       görüldü: 18 Kas
     40  → 1-12 (3)                       görüldü: 11 Kas  ⚠ eski
   ```

## Barkod → model kodu

Barkod numarası modeli içermiyor (GS1 kuralı, büyük ihtimalle DİA'nın iç numarası). Model kodu iki
kaynaktan gelir; ikisi de bir kez öğrenilip kalıcı olarak saklanır:

1. **DİA listesi (öncelikli).** Ofisten barkod, stok kodu, renk ve numara sütunlarını içeren bir Excel/CSV
   alınır ve uygulamaya bir kez aktarılır. En güvenilir kaynak budur.
2. **Etiket yazısı (yedek).** Barkodun üstündeki "0159 2041 / SİYAH / 38" satırı ML Kit ile okunur
   (`OcrEngine`'in tanıyıcısı ve `LumaCrop` yeniden kullanılır; okuma alanı barkodun **üstüne** doğru
   genişletilir).
   - Rakam beklenen yerlerde O→0, I/l→1, S→5, B→8 düzeltmesi yapılır.
   - Renk, Türkçe renk sözlüğüyle eşleştirilir.
   - Numara 17–50 arasında olmalı; buçuklu numaralar da kabul edilir.
   - Aynı barkod için en az 3 karede 3 okuma gelmeli ve en az ⅔'ü aynı modeli söylemeli; ancak o zaman
     eşleşme kilitlenir. Kilitlenen barkod için yazı tanıma bir daha çalıştırılmaz.

Eşleşmesi henüz olmayan barkod da konumuyla saklanır. Model kodu sonradan öğrenildiğinde arama onu da
bulur.

## Veri (telefonda, Room)

| Tablo | Alanlar |
|---|---|
| `location` | kod (`2-07`), satır, sütun |
| `sighting` | barkod, konum, kutu sayısı, en son görüldü — sütun yeniden taranınca o sütunun satırları değiştirilir |
| `barcode_model` | barkod, model kodu, renk, numara, kaynak (DİA / OCR / elle), oy sayısı |

Paylaşım (ilk sürümde): haritayı CSV olarak dışa aktarma. Ortak çevrimiçi tablo (Google Sheets) ve DİA'nın
`rafyeri` alanına yazma sonraki adımlar.

## Kodda değişecekler

- **`MlKitEngine`:** `FORMAT_QR_CODE` eklenir.
- **Konum kodları:** `LOC:` ile başlayan değerler, sayısal barkod kurallarından (`BarcodeRules`) **önce**
  ayrılır. Sayılmazlar, yalnızca sütunu değiştirirler.
- **`AutoZoom`:** QR'ları barkod boyu hesabına katmaz.
- **Yeni sınıflar:**
  - `LocationMap`: sütun açma/kapama, anlık görüntü yazma.
  - `ModelReader`: model satırı OCR'ı ve oylama.
  - Room veritabanı.
  - Arama ekranı.
- **Etiket basma:** `tools/make_location_labels.py`.

## Aşamalar

| Aşama | İş | Süre |
|---|---|---|
| 0 | Etiket provası: test sayfasını bas, 0,5 / 1 / 1,5 m'den oku; depo kroki (satır × sütun) | yarım gün + depoda 15 dk |
| 1 | QR okuma, Harita modu, sütun anlık görüntüsü, Room, "kutuyu okut → nerede" | 2–3 gün |
| 2 | Barkod → model: DİA CSV içe aktarma + etiket yazısı oylaması | 2–3 gün |
| 3 | Arama ekranı (model kodu, kısmi yazım, numara × konum, "eski" uyarısı), CSV dışa aktarma | 1–2 gün |

## Etiket basma

```bash
python -m venv tools\.venv
```

```bash
tools\.venv\Scripts\pip install segno pillow
```

```bash
tools\.venv\Scripts\python tools\make_location_labels.py test
```

```bash
tools\.venv\Scripts\python tools\make_location_labels.py labels --rows 1-2 --cols 1-20 --size 75
```

Çıktılar `samples\labels\` altında. Yazıcıda **"gerçek boyut / %100"** seçilmeli; "sayfaya sığdır" seçilirse
QR küçülür ve hesaplanan okuma mesafesi tutmaz.

## Depoda ölçülecekler

- Kaç satır ve her satırda kaç sütun var; etiket her sütunda nereye konabilir.
- Test sayfasındaki 5 / 7,5 / 10 cm'lik QR'lar normal tarama hızında hangi mesafeden okunuyor.
- Etiketin üstündeki model satırı kaç kutuda doğru okunuyor (100 kutuluk örnek).
- Sütunların bir hafta içinde ne kadar değiştiği (aynı 5–10 sütunu birkaç gün tarayarak).
