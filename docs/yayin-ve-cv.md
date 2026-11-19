# Depoya alış, yayın ve CV

## 1. Depoya alış (ilk hafta)

**Gün 0, kurulum (20 dk)**
1. APK'yı kur (eski sürümün üzerine; kayıtlar kalır).
2. Ayarlar → **WhatsApp talepleri**:
   - "Kısıtlı ayarlar" → ⋮ → *Kısıtlı ayarlara izin ver*;
   - "Bildirim erişimi" → *SweepScan talepleri* açık;
   - geri dön ve aç. Grup adı: "Ürün isteme".
3. Ayarlar → **Düzeni değiştir**: her sıranın kat sayısı ve hangi yöne baktığı. Yanlış olanı düzelt.
4. Ofisten DİA'dan barkod + model kodu listesi alınabilirse: Excel → CSV → Ayarlar → **Model listesi içe aktar**.
5. Ayarlar → **Haritayı yedekle** (boş da olsa; dosyanın nereye gittiğini öğrenmek için).
6. Ayarlar → **Kat ayarı**:
   - tararken telefonun yerden yüksekliği, rafa uzaklık ve bir katın yüksekliği; bir kere metreyle ölç;
   - "alttan 2. raf" tahmini bu üç sayıdan çıkar.
7. Ayarlar → **Mağaza bağlantısı → Depo sunucusu**: aç. Bildirimde adres ve PIN görünür (ör. 192.168.0.8:8737 · PIN 4821).

**Gün 0, mağaza telefonları (her biri 2 dk)**
1. Aynı APK'yı kur → Ayarlar → **Bu telefon mağazada**.
2. Mağaza ekranı depo telefonunu Wi-Fi'da kendisi bulur; PIN'i bir kere sorar.
3. ⚙ → **Adım**: talepte görünecek ad ("Kasa 1", "Ayşe").
4. Mağaza ve depo aynı Wi-Fi'da olmalı. Misafir ağı cihazları birbirinden ayırıyorsa ana ağı kullan.

**Yönetici paneli:** mağaza bilgisayarının tarayıcısında Ayarlar → Mağaza bağlantısı → **Yönetici paneli**'ndeki adres.

**Gün 1, ilk tam tarama (30–45 dk)**
- Raf tara → önerilen sıra → sol baştan sağa, her modelden bir kutu → Bitir → Sıradaki.
- 14 sıra bitince Ayarlar → Haritayı yedekle.

**Hafta 1, her gün**
- Talepler uygulamadan cevaplanır: bildirimde **Gönderdim / Yok**, sesli olanlarda **Söyle**.
- Mağaza uygulamasından gelenler aynı kuyruğa düşer. Cevabı mağaza telefonu kendi görür; WhatsApp'a yazılmaz.
- "Yok" ya da "Burada değil" çıkan sıra o gün yeniden taranır (Raf tara'da en üstte "kontrol et").
- Salı ve perşembe sevkiyattan sonra değişen sıralar taranır.

**Hafta sonunda yazılacak sayılar** (Talepler ekranı)

| Ölçü | Hedef | Sonuç |
|---|---|---|
| Günlük talep | – | |
| Doğru yer oranı = Gönderdim / (Gönderdim + Yok) | ≥ %80 | |
| "Haritada yok" oranı | ≤ %20 | |
| Ortalama yanıt süresi | ilk günlerden kısa | |
| Mağaza uygulamasından gelen talep payı | haftadan haftaya artan | |
| "alttan N. raf" doğru mu (10 örnek) | ≥ 8/10 | |

## 2. Telefonda kontrol listesi (2.0.0'dan önce)

- [ ] `tools\decode_bench.bat <telefon-ip>:5555`: bütün cihaz testleri geçiyor. Kapsam: veritabanı geçişi 1→2→3,
      ekranlar, konum ekranı, örnek veriyle ekran görüntüleri.
- [ ] Başka bir telefondan gruba "0209 13243 46" yaz → bildirim geliyor, yer doğru.
- [ ] Bildirimde **Gönderdim** → grupta "✅ Gönderdim: …" görünüyor.
- [ ] Sesli mesaj → "🎤 Sesli talep" bildirimi → Söyle → yer açılıyor → Gönderdim.
- [ ] Uygulama simgesine uzun bas: kısayollar. Bildirim paneli: "Kodu söyle" kutucuğu.
- [ ] Yedekle → Geri yükle → harita aynı.
- [ ] Raf tara: yeni modelde bip, aynı modelde sessiz; Bitir kartı; Sıradaki.
- [ ] Sevkiyat say → PC'ye gönder (DİA'da satırlar aynı).
- [ ] Ana ekrana SweepScan widget'ı ekle: bekleyen sayısı doğru, *Okut* ve *Ara* tek dokunuşla açılıyor.
- [ ] Talepler: kartı sağa kaydır → Gönderdim, sola → Yok; titreşim geliyor.
- [ ] Sevkiyat say → Bitir: kutular model · renk altında gruplu, numara solda; − / + titreşimli.
- [ ] DİA'ya gönder → 5'ten geri sayan halka → yazarken bildirimde "DİA'ya yazılıyor 3 / 10"; Android 16+'da durum çubuğunda çip.
- [ ] Gönderim sırasında uygulamadan çık → bildirim "duraklatıldı"; dokununca sayım ekranı açılıyor.
- [ ] Telefon Ayarları → Uygulamalar → SweepScan → Dil: Türkçe / English seçilebiliyor.
- [ ] Sunum modu: prova grubunda talepler akıyor, Gönderdim cevabı prova grubuna düşüyor.
- [ ] Yer bul: alt panel aşağı itilince ya da ✕ ile kapanıyor, harita tüm ekranı alıyor; "Ayrıntıları göster" geri açıyor.
- [ ] Barkod okut: ortadaki çerçeveye tutulan kutu okununca çerçeve yeşile dönüyor, ses + titreşim geliyor.
- [ ] Raf tara → bir sıra → Yer bul: cevapta rafın fotoğrafı ve "alttan N. raf" var, kat doğru.
- [ ] Depo sunucusu açık → ikinci telefon "Bu telefon mağazada" → kendiliğinden bağlanıyor → Okut → depo telefonuna bildirim → Gönderdim → mağaza telefonunda "Gönderildi".
- [ ] Bilgisayarda yönetici paneli açılıyor; kuyruk, sayılar ve 3D görünüyor.
- [ ] Cihaz testleri: `am instrument … -e class com.sweepscan.location.ShelfMemoryTest,com.sweepscan.server.DepotServerTest` (telefon kilitliyken de çalışır).

## 3. Yayına hazırlık (GitHub)

- Depoyu herkese açık yapmadan önce:
  - kişisel bilgi yok: ekran görüntüleri yalnızca örnek veri modunda;
  - WhatsApp ekran görüntüsü, iş arkadaşı adı ve telefon IP'si yok (kod ve belgeler bu açıdan tarandı);
  - `samples/` ve `dist/` zaten dışarıda.
  - Not: git geçmişindeki eski commit'lerde bir iş arkadaşının adı örnek olarak geçiyor. Geçmişsiz temiz bir
    ilk commit ile yayınlamak en temizi.
- **README'nin başı:** 30 saniyelik GIF, video bağlantısı, "Using it" tablosu, sahadan sayılar.
- **Releases:** `SweepScan-2.0.0.apk` (Ayarlar → Örnek veri ile dükkân olmadan denenebilir).
- **Lisans:** MIT (`LICENSE`).

**Ekran görüntüleri** (örnek veri açık): ana sayfa, cevap kartı, kroki + rota, Talepler, sıra listesi, raf
tarama kamerası.

**Demo video ve LinkedIn:** çekim listesi, sunum modu, gönderi metinleri ve PDF için `docs/demo-video.md`.

## 4. CV maddeleri (sahadan gelen sayılarla güncellenecek)

**Türkçe**
- Çalıştığım ayakkabı mağazasının deposu için Android uygulaması tasarlayıp tek başıma geliştirdim (Kotlin,
  CameraX, ML Kit, OpenCV, Room, Filament). Uygulama depoda her gün kullanılıyor.
- Bulunan rafa 3D yol gösterme yazdım (Filament, fiziksel tabanlı çizim):
  - sahne saf Kotlin çekirdekte üretilip glTF (GLB) olarak yazılıyor; kamera girişten rafa kendiliğinden süzülüyor;
  - aranan kutular kendi rafında parlıyor, yanında rafın son fotoğrafı duruyor;
  - aynı sahne yönetici panelinde WebGL2 ile çiziliyor.
- Raf tararken her etiketin fotoğrafını ve hangi katta olduğunu (telefonun eğimi + görüntüdeki konum) kaydettim;
  arama cevabı "alttan 2. raf" ve rafın son fotoğrafıyla geliyor.
- Hesap ve bulut gerektirmeden mağaza katı ile depoyu bağladım:
  - depo telefonu yerel ağda sunucu (mDNS ile bulunuyor, PIN korumalı);
  - mağaza telefonları talep gönderip durumunu canlı izliyor;
  - yönetici tarayıcıdan panel açıyor.
- Arayüzü Jetpack Compose + Material 3 ile, ortak bir tasarım sistemi üzerine yeniden kurdum (MVVM, StateFlow).
  Saf Kotlin çekirdeği Android'den ayrı bir modüle aldım (126 JVM testi). R8 ile APK'yı küçülttüm; bütün cihaz
  testleri küçültülmüş sürüme karşı da koşuyor.
- Mağaza katının WhatsApp'tan gönderdiği ürün taleplerini okuyup ürünün depodaki yerini bildiren bir talep
  kuyruğu ekledim; yanıt tek dokunuşla gruba gidiyor. Ortalama yanıt süresi __ dk'dan __ dk'ya indi.
- Etiket gerektirmeyen bir depo haritası kurdum:
  - konum, kameranın tarama sırasından çıkarılıyor;
  - veritabanı sürümlü ve geçmişli (Room geçişleri test edildi);
  - toplama rotası en kısa sırayla hesaplanıyor (Dijkstra + 2-opt).
- Kamerayla çoklu barkod takibi ve kutu sayımı (kendi takip algoritmam, sahadan kayıtlarla tekrar oynatılan
  testler). Sayılan barkodlar ERP'ye telefon Bluetooth klavye olarak yazdırılıyor.

**English**
- Designed and built, solo, an Android app used daily in a shoe shop's stock room (Kotlin, CameraX, ML Kit,
  OpenCV, Room, Filament).
- 3D guidance to the found shelf (Filament, physically based rendering):
  - the scene is generated in the Android-free Kotlin core and written as glTF (GLB); the camera flies from the entrance to the rack by itself;
  - the wanted boxes glow on their shelf next to the latest photo of that shelf;
  - the same scene renders in the web dashboard through WebGL2.
- Shelf photo memory and shelf-level estimation (device tilt + label position in the frame) captured while
  sweeping, so answers read "shelf 2 from the floor" with the latest photo of the shelf.
- Linked the shop floor and the stock room with no accounts or cloud:
  - the stock-room phone runs a LAN server (mDNS discovery, PIN);
  - shop-floor phones send requests and follow their status live;
  - a browser dashboard shows the live queue, daily KPIs and the 3D view.
- Rebuilt the UI in Jetpack Compose + Material 3 on a shared design system (MVVM, StateFlow); split the
  Android-free core into its own Kotlin module (126 JVM tests); enabled R8 and run the device test suite
  against the minified build.
- Request queue that reads product requests from the shop floor's WhatsApp group and answers with the
  item's stock-room location; one-tap replies to the group; average response time cut from __ to __ min.
- Label-free stock-room map:
  - location inferred from the camera sweep's order;
  - versioned, append-only history with tested Room migrations;
  - shortest pick routes (Dijkstra + 2-opt).
- Multi-barcode camera tracking and box counting with a custom tracker, validated by replaying field
  recordings. Results are typed into the ERP with the phone acting as a Bluetooth HID keyboard.
