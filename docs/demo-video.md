# Demo paketi: LinkedIn videosu, gönderi metni, kaydırmalı PDF

Hedef: 60–90 saniyelik dikey (9:16) bir video, aynı hikâyeyi anlatan bir LinkedIn gönderisi ve
belge olarak paylaşılacak 8 sayfalık bir PDF. Videoyu sen çekersin; uygulama çekim için hazır.

## 1. Çekimden önce

- **İzin:** mağaza müdüründen depoda çekim için izin al. Raflarda marka ve fiyat etiketleri, görüntüde
  müşteri ya da çalışan olabilir.
- **Sunum modu:** Ayarlar → *Sunum modu (video çekimi için)* → Başlat.
  - Örnek depo haritası açılır, "ÖRNEK VERİ" bandı gizlenir.
  - Talepler kendiliğinden düşer: ilki 6 sn sonra, sonra 40 sn'de bir, bildirimle. Gönderen "Kasa 1",
    "Reyon" gibi görünür; kimsenin adı yok.
  - Gerçek talep listesi ve WhatsApp grubu bu sırada okunmaz, gösterilmez, gruba cevap gitmez.
  - Talepler ekranında **Prova grubu** görünür: gelen mesajlar ve uygulamanın "✅ Gönderdim" cevapları
    WhatsApp'taki gibi akar. Videoda otomasyonu göstermek için bu ekranı kullan.
  - Çekim bitince aynı yerden **Kapat**.
- **Telefon:**
  - Rahatsız Etme açık, ama SweepScan bildirimlerine izin ver (talep bildirimi videoda görünsün).
  - Parlaklık yüksek, bildirim çubuğunda başka uygulama simgesi olmasın.
  - Ekran kaydı: Hızlı ayarlar → *Ekran kaydedici* → "Ses yok", "Dokunmaları göster" kapalı.
- **İkinci telefon (varsa):** depoda kendi elini ve rafı çekmek için. Ekran kaydı ile gerçek görüntü yan
  yana kurgulanınca en güçlü sonuç çıkar.

## 2. Çekim listesi (≈ 80 sn)

| Süre | Ekran / sahne | Ekrandaki yazı (kurguda) |
|---|---|---|
| 0–4 sn | Depo koridoru, raflar dolu (ikinci telefon) | "Ayakkabı deposunda bir kutuyu bulmak 5 dakika sürüyordu." |
| 4–9 sn | Bildirim düşer: "0171 3310 · 39 → Büyük depo · 3. sıra · alttan 2. raf" | "Mağazadan talep anında depoya düşüyor." |
| 9–20 sn | Bildirime dokun → cevap kartı → **3D uçuş**: girişten rafa süzülüyor, kutular parlıyor | "Uygulama yerini biliyor: sıra, raf katı, rafın son fotoğrafı." |
| 20–26 sn | Kartı kaydır: rafın gerçek fotoğrafı, önden görünüş şeridi | — |
| 26–34 sn | Gerçek rafta kutuyu alırken (ikinci telefon) | "Yürü, al." |
| 34–38 sn | **Gönderdim** → durum yeşil | "Mağaza tek dokunuşla haber alıyor." |
| 38–55 sn | Raf tara: kamera bir sıra boyunca, kutular yeşil ✓ ile sayılıyor, Bitir | "Rafı bir kere tara: her model nerede, uygulama hatırlıyor." |
| 55–68 sn | Sevkiyat say: kutular sayılıyor, sayaç artıyor | "Sevkiyat sayımı kamerayla, ERP'ye Bluetooth klavye olarak yazılıyor." |
| 68–80 sn | Ana ekran → son kare: teknolojiler ve GitHub adresi | "Kotlin · CameraX · ML Kit · OpenCV · Filament 3D · Jetpack Compose" |

İpuçları:
- Her sahne bir iş anlatsın. Ayar ekranı, menü, yükleme anı kesilsin.
- Yazılar büyük ve kısa; videoların çoğu sessiz izleniyor.
- Müzik telifsiz olsun (CapCut / YouTube Ses Kitaplığı); konuşma yoksa altyazı şart değil.
- Kapak karesi: 3D uçuşun son karesi (parlayan kutular + raf fotoğrafı).

## 3. LinkedIn gönderisi

**Türkçe**

> Çalıştığım ayakkabı mağazasının deposunda bir kutuyu bulmak dakikalar sürüyordu. Mağazadan "0171 3310,
> 39 numara" diye soruluyor, depoda raf raf aranıyordu.
>
> Bunu çözmek için kendi Android uygulamamı yazdım: **SweepScan**.
>
> 📦 Rafı kamerayla bir kere tarıyorsunuz; uygulama barkodları sayıyor, her modelin hangi sırada ve
> hangi katta olduğunu hatırlıyor.
> 🔔 Mağazadan gelen talep anında depoya düşüyor; cevap kartında rota, raf katı ve rafın son fotoğrafı var.
> 🧭 3D'de kamera girişten rafa kendiliğinden gidiyor, aranan kutular parlıyor.
> 🚚 Sevkiyat sayımı da kamerayla; sayılan barkodlar ERP'ye Bluetooth klavye gibi yazılıyor.
>
> Teknik tarafı: Kotlin, CameraX, ML Kit + zxing-cpp, OpenCV (optik akış), kendi çoklu barkod takip
> algoritmam, Room, Jetpack Compose, Filament (3D, glTF), yerel ağda mDNS ile bulunan küçük bir sunucu.
> Saf Kotlin çekirdekte 130+ birim testi; telefondan alınmış kayıtlar her derlemede tekrar oynatılıyor.
>
> Sahadan ilk sonuç: __ (ör. "ortalama bulma süresi 4 dk → 40 sn").
>
> Kod: github.com/__ · Geri bildirime açığım!
>
> #Android #Kotlin #ComputerVision #JetpackCompose #Retail #Warehouse

**English**

> Finding one shoe box in our stock room used to take minutes: the shop floor asks for "0171 3310, size
> 39", someone walks rack after rack.
>
> So I built my own Android app: **SweepScan**.
>
> 📦 Sweep a rack once with the camera: it counts the barcodes and remembers which row and which shelf
> every model is on.
> 🔔 A request from the shop floor reaches the stock room instantly, with the route, the shelf level and
> the latest photo of that shelf.
> 🧭 In 3D the camera flies from the entrance to the rack by itself; the boxes you need glow.
> 🚚 Delivery counts by camera too, typed into the ERP as a Bluetooth keyboard.
>
> Under the hood: Kotlin, CameraX, ML Kit + zxing-cpp, OpenCV optical flow, my own multi-barcode
> tracker, Room, Jetpack Compose, Filament (3D, glTF), a small LAN server found over mDNS. 130+ unit
> tests in a pure-Kotlin core; recordings from the phone are replayed on every build.
>
> First result from the floor: __.
>
> Code: github.com/__ · Feedback welcome!
>
> #AndroidDev #Kotlin #ComputerVision #JetpackCompose #Retail

## 4. Kaydırmalı PDF

`python tools/make_carousel.py` → `dist/SweepScan-carousel.pdf` (1080 × 1350, 8 sayfa). Ekran
görüntülerini `build/phone/` klasöründen alır; önce cihaz testleriyle görüntüleri yenile (sunum modunda
bant görünmez). LinkedIn'de "Belge ekle" ile paylaşılır; videodan bir gün sonra ikinci gönderi olarak iyi
gider.
