# Saha testi kontrol listesi (Salı 17 Kasım)

Tek soru: **Sütun bazında sayım hatası fazla saymaktan mı geliyor, eksik okumaktan mı?** Aşağıdakilerin
hepsi bu soruya cevap verecek veriyi toplamak için. Kayıtlar `İndirilenler/SweepScan/` klasörüne düşer.

---

## 1. Pazar / Pazartesi: telefonda ilk deneme (evde, ~1 saat)

**Kurulum:** adımlar [kurulum.md](kurulum.md) dosyasında. Kısaca: telefonda USB hata ayıklamayı aç,
kabloyu tak ve proje klasöründe şunu çalıştır:

```bash
.\install.bat
```

**Kontroller:**

| # | Ne yap | Ne görmelisin |
|---|---|---|
| 1 | Uygulamayı aç, kamera ve Bluetooth izinlerini ver | Önizleme açılır |
| 2 | ⚙ → *Kamera bilgisi* | Seviye, manuel sensör, AE öncelik modları. Ekran görüntüsü al. |
| 3 | `İndirilenler/SweepScan/caps-*.json` | Dosya var |
| 4 | Evdeki birkaç barkoda (kutu, kitap) tut | Kutular etiketlerin **tam üstüne** oturuyor. Yeşil = sayıldı, turuncu = okunuyor, kırmızı "!" = görüldü ama okunamadı. |
| 5 | HUD'un ilk satırının sonundaki boyut | 1080p'de 1920x1080 ya da 1080x1920; 16:9 dışı bir boyut çıkarsa söyle |
| 6 | Zoom 1,5× → *Bitir* → *Taramaya dön* | HUD'daki zoom hâlâ 1,5× |
| 7 | Pozlama → *Kısa 1,0 ms* | Etiket adında "(AE öncelik)" ya da "(manuel ISO)" yazıyor; HUD'da poz ≈ 1,0 ms |
| 8 | Kısa pozda görüntü | Karanlık ama etiket okunuyor. Simsiyahsa ya da hiç okumuyorsa not et. |
| 9 | Odak → *Sabit 0,9 m* | Uyarı çıkmıyor; HUD'da odak ≈ 0,9 m |
| 10 | Kayıt başlat, 20 sn tara, durdur | `.jsonl` ve kare klasörü oluşuyor |
| 11 | Telefonu hızla yana çevir | "BULANIK" ya da "YAVAŞLA" çıkıyor; dikey yavaş taramada (aşağı ya da yukarı) çıkmıyor |
| 12 | Birkaç barkod say, *Duraklat*'a bas | "Bu taramada N kutu" mesajı çıkıyor |
| 13 | *Geri al*'a **uzun bas** → *Son taramayı sil* | O taramanın kutuları siliniyor, öncekiler duruyor |
| 14 | Telefonu aynı barkodlara 1 dakika sabit tut | Sayı artmıyor |

**Bluetooth (laptop):**

| # | Ne yap | Ne görmelisin |
|---|---|---|
| 15 | PC → *Yeni PC eşleştir*, sonra Windows'ta *Cihaz ekle* | Durum "PC: <laptop adı>" |
| 16 | Not Defteri'ni aç → PC → *Test: 100 numaralı barkod* | Tam 100 satır, eksik, çift ya da bozuk rakam yok |
| 17 | 16'yı tuş basılı süre 40 ms ve yavaş PC profiliyle tekrarla | Aynı sonuç |
| 18 | Test yazarken laptopun Bluetooth'unu kapat, sonra aç | Yazma duruyor ve durum "PC: durdu" ya da "bağlantı koptu, duraklatıldı" oluyor. Geri bağlanınca **kendiliğinden devam etmiyor** ve silme tuşu gelmiyor. PC → *Gönderime devam* ile sürüyor. |
| 19 | Ana ekrana çık, geri gel | Klavye tekrar bağlanıyor |
| 20 | PC bağlı değilken *Test*'e bas | "Önce PC'ye bağlan" diyor, hiçbir şey kuyruğa girmiyor |

**Eşleştirme kuralı:** eşleştirmeyi **Windows'tan** başlat, ve o sırada SweepScan açık olsun, durum
satırında "PC: bağlı değil" yazsın. Telefonun kendi Bluetooth ayarlarından eşleştirirsen ya da
SweepScan kapalıyken eşleştirirsen Windows telefonu yalnızca "telefon" olarak kaydeder, klavye
olduğunu bilmez. Telefonda "Bağlanamadı" yazısı çıkar (telefon laptopa kulaklık gibi bağlanmaya
çalışır); bu, klavyeyle ilgili değildir.

Bu olduysa:
1. Windows: *Ayarlar → Bluetooth ve cihazlar → (telefon) → ⋯ → Cihazı kaldır*.
2. Telefon: *Ayarlar → Bağlantılar → Bluetooth → (laptop) → ⚙ → Eşlemeyi kaldır*.
3. SweepScan'i aç, "PC: bağlı değil" yazısını bekle, *PC → Yeni PC eşleştir*.
4. Windows: *Cihaz ekle → Bluetooth* → telefonu seç, iki ekranda kodu onayla.

Doğru eşleştiğini Windows'ta *Aygıt Yöneticisi → Klavyeler* altında yeni bir "HID Klavye Aygıtı"
görünmesinden anlarsın.

Bir şey beklenenden farklıysa ekran görüntüsünü ve o anki `events-*.jsonl` dosyasını bana gönder.

---

## 2. Salı sabahı: yanına al

- Telefon (tam şarj) ve şarj kablosu
- Cetvel ya da metre
- Kâğıt ve kalem: elle sayım için
- Laptop (Bluetooth açık), DİA açık

---

## 3. Etiket ölçümü (5 dk, bir kez)

Bir kutunun etiketini cetvelin yanında, **dik açıyla ve yakından** fotoğrafla. Not et:
- Barkodun sadece çizgili kısmının genişliği (mm)
- Etiketin tamamının genişliği ve yüksekliği (mm)

Bu ölçü, hangi çözünürlük ve mesafenin yettiğini hesaplamak için gerekiyor. Modül başına piksel buradan
çıkıyor.

---

## 4. Sütun testleri (gerçek sayımdan önce, ~30 dk)

**Hazırlık:**
- 3 sütun seç; her biri 20–40 kutu olsun, mümkünse içinde aynı barkodlu kutular bulunsun.
- Her sütunu önce **elle say** ve kâğıda yaz: toplam kutu sayısı. Vaktin olursa barkod bazında da yaz.

Her sütunu aşağıdaki sırayla tara. Her taramadan önce:
- ⚙ → *Saha kaydını başlat* ile kaydı başlat ve adını tablodaki gibi ver;
- *Yeni oturum* ile sayacı sıfırla.

| Kayıt adı | Ayar | Tarama |
|---|---|---|
| `S1-varsayilan` | Hiçbir şeye dokunma (zoom 1,5×, pozlama kısa 4 ms) | Yılan gibi: bir sütun aşağı, yandaki yukarı |
| `S1-3x` | Sağ kenardaki **3×** düğmesi | Aynı, biraz daha uzaktan |

S2 ve S3 için aynısını yap.

**Ayar turu (bir kez, ~3 dk):** ⚙ → *▶ Otomatik ayar turu*. Uygulama 20 saniyede bir zoom'u
(1,5× / 2× / 3×) ve pozlamayı (otomatik / 4 ms / 2 ms) kendisi değiştirir; her değişimde bip çalar.
Sen aynı 8–10 etiketi ileri geri tara, **tur bitene kadar ⚙ menüsünü açma**. Üstteki satırda adım,
geri sayım ve "bu adımda okunan" sayısı görünür. Turdaki okumalar gerçek sayıma eklenmez; ekrandaki
"0 kutu" bu yüzdendir. Kayıt kendiliğinden kaydedilir.

**Ek iki kayıt (vaktin olursa):**
- `S1-geri`: S1'i yukarıdan aşağı tara, sonra durmadan aşağıdan yukarı geri tara. Elle sayım aynı
  kalır; 0.3.11'den beri uygulama da aynı kalmalı. Kayıt, uzun bir geri dönüşte hareket ölçümünün
  kayıp kaymadığını gösterecek.
- `S1-ikili`: 2160p'de, kadrajda iki sütun olacak şekilde biraz uzaktan tara.

**Her taramadan sonra:**
- Kaydı durdur.
- Ekrandaki kutu sayısını ve "görülüp okunamayan / yetersiz okunan" sayılarını kâğıda yaz.

**Kurallar:**
- **Yön serbest, duraklatmak şart değil.** Bir sütunu aşağı, yanındakini yukarı tarayabilirsin; 16 Kasım
  kayıtlarında aşağı ve yukarı hareketle sayılan kutu sayısı yakın. Yan sütuna geçerken telefonu yavaşça
  kaydır; bu sırada okuma olmaz, "BULANIK" uyarısı çıkarsa normal.
- *Duraklat* yalnızca sütun sayısını elle sayımla karşılaştırmak ya da ara vermek için ("Bu taramada N kutu").
  **Duraklatınca uygulama gördüklerini unutur:** *Devam*'a bastığında ekranda sayılmış bir etiket varsa onu
  yeni kutu sanıp tekrar sayar. Devam etmeden önce kamerayı sayılmamış kutulara çevir.
- **Tekrar tarayacaksan önce sil.** Bir sütunun sayımı yanlışsa *Geri al*'a uzun bas, *Son taramayı sil*'i
  seç, sonra o sütunu baştan tara. Silmeden tekrar tararsan kutular iki kez sayılır.
- **Geri dönmek serbest (0.4.0).** Uygulama saydığı kutuları *Duraklat*'a basana kadar hatırlar ve
  geri dönüşte kutuyu üstündeki ve altındaki komşularıyla da tanır. 16 Kasım denemelerinde eski
  sürümler geri dönüşte üstteki 6–7 kutuyu tekrar saymıştı; bu davranış kayıtlarla düzeltildi, telefonda
  `kontrol` testiyle doğrulanmalı.

**Kontrol testi (kayıt açık, ~2 dk):** 10–12 kutuluk bir sütun seç, barkodlarını kâğıda yaz.
`kontrol-tek`: bir kez tara. `kontrol-geri`: aşağı tara, durmadan yukarı geri dön. İkisinde de ekrandaki
sayı kâğıttakiyle aynı olmalı. Kaydı mutlaka ⚙ → *■ Saha kaydını durdur* ile kapat.
- **Zoom:** sağ kenardaki **OTO** açıkken uygulama zoom'u kendisi seçer: barkodlar ~1 saniye boyunca
  küçük kalırsa bir kademe yakınlaştırır, çok büyükse uzaklaştırır. Değişikliği ekranda takip edilen
  etiket yokken yapar; az önce sayılan kutuları zoom'dan sonra da hatırlar. Elle bir zoom seçersen OTO
  kapanır.
- **Mesafe:** barkod ekranın genişliğinin üçte biri kadar görünsün (16 Kasım ölçümü: okunan
  barkodlar 1440 piksellik görüntüde ortanca 490 piksel). Yarısı kadar küçük de okunur; daha küçüğü
  okunmaz. 1,5×'te bu kabaca 30–50 cm, 3×'te 45–70 cm.
- **Telefonu dikey (çizgiler boyunca) hareket ettir, aşağı ya da yukarı.** Ölçüm: çizgilere dik 6 piksellik
  bulanıklık okumayı sıfırlıyor, çizgiler boyunca 20 piksel neredeyse hiç etkilemiyor.
- Diğer ayarlara dokunma. Bir ayarı değiştirirsen kayıt adına ekle (ör. `S2-3x-1440p`).
- Tarama başarısız olursa yeniden dene, ama **yeni ad** ver (`...-2`).
- Akşam `python tools\latency_report.py <klasör>` her etiket için "görüldü → okundu → sayıldı"
  süresini, ayar turunda da her adımı tek satırda verir.

---

## 5. Gerçek teslimat

Testlerde en iyi çıkan ayarla (büyük ihtimalle Kısa 1,0 ms + dikey) bütün teslimatı say.

1. **Kayıt:** kaydı aç, adı `teslimat-tam`.
2. **Sayım:** sütun sütun tara.
   - Her sütundan sonra *Duraklat*'a bas ve "Bu taramada N kutu" sayısına bak.
   - Sayı eksikse ya da "görülüp okunamayan" büyüdüyse: *Geri al*'a uzun bas → *Son taramayı sil* → o
     sütunu baştan tara.
   - Sıradaki sütuna geçip *Devam*'a bas. Hepsi bitince *Bitir*'e bas.
3. **Kontrol:** gözden geçirme ekranındaki toplamı kendi göz sayımınla karşılaştır.
4. **DİA'ya gönderim:**
   - DİA'da stok transfer fişini aç ve barkod alanına tıkla.
   - Telefonda *DİA'ya gönder*'e bas.
   - Gönderim sırasında laptopa dokunma.
5. **Sonuç kontrolü:**
   - DİA'daki satır sayısı ile telefondaki "gönderilen" sayısı aynı mı?
   - Hata penceresi çıktı mı, yarım kalmış barkod var mı? Not et.
   - Kutu başına süreyi ve toplam süreyi not et.

Bir şey ters giderse eski barkod tabancasına dön. Telefonun yazdığı CSV yedeği
`İndirilenler/SweepScan/session-*.csv` dosyasında durur.

---

## 6. Akşam: kayıtları bilgisayara al

1. Telefonu USB ile bağla.
2. `İndirilenler/SweepScan/` içindeki bütün dosyaları kopyala:
   - `.jsonl` kayıtları
   - kare klasörleri
   - `session-*.csv` yedekleri
   - `events-*.jsonl` olay kayıtları
   - `caps-*.json` yetenek dökümü
3. Kopyalanan `.jsonl` dosyalarını `core/src/test/resources/field/` klasörüne koy.
4. Her kayıt için yanına aynı adla bir `.expected` dosyası yaz. Biçimi şöyle:
   - Örnek dosya adı: `sweepscan-20261117-101500-S1-kisa1-dikey.expected`
   - İlk satır elle saydığın toplam kutu sayısı.
   - Barkod bazında saydıysan, sonraki her satıra `barkod adet` yaz.

```
34
8681234567890 2
8681234567906 1
```

5. **Kendin bak:** proje klasöründeki `tools\viewer.html` dosyasını çift tıkla, telefondan
   kopyaladığın `SweepScan` klasörünü sayfaya sürükle.
   - Üstte özet: kaç kutu saydı, elle sayımla farkı, uyarılar.
   - Ortada kayıt video gibi oynar; telefonda gördüğün yeşil, turuncu ve kırmızı çerçevelerle.
   - Sağda barkod listesi: hangi barkod doğru, hangisi fazla, hangisi eksik.
   - Altta zaman çizgisi: yeşil çizgiler sayımlar, kırmızılar okunamayan etiketler.
     "Sonraki okunamayan" düğmesi doğrudan sorunlu ana atlar.
   - Hiçbir şey internete gitmez; dosyalar yalnızca laptopta okunur.
6. Gerisini ben yaparım: bütün kayıtları 12 farklı ayarla tekrar oynatıp hangi ayarın kaç kutu
   yanlış saydığını çıkarırım. Saymanın hata yaptığı yerde, kayıttaki kareler sebebini gösterir.

---

## Kâğıda yazılacaklar (özet)

| Sütun | Elle sayım | Kayıt adı | Ekrandaki sayım | Okunamayan / yetersiz | Not |
|---|---|---|---|---|---|
| S1 | | S1-oto-dikey | | | |
| S1 | | S1-ev-dikey | | | |
| S1 | | S1-kisa1-dikey | | | |
| S1 | | S1-kisa1-yatay | | | |
| … | | | | | |
| Teslimat | | teslimat-tam | | | DİA satır: ___ · süre: ___ |
