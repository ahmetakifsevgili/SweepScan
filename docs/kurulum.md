# SweepScan kurulumu

Hazır paket: `dist\SweepScan.apk` (sürüm 0.5.2, Galaxy S23 Ultra için arm64, ~82 MB). Paketi yeniden üretmek
istersen proje klasöründe `.\build.bat` çalıştır; testleri de koşar.

İki kurulum yolu var. İlki daha hızlı; ikincisi bilgisayar ayarı gerektirmez.

---

## Yol 1: USB ile (önerilen)

**Telefonda bir kez yapılacak ayar:**

1. *Ayarlar → Telefon hakkında → Yazılım bilgileri* → **Yapım numarası**'na 7 kez dokun.
2. *Ayarlar → Geliştirici seçenekleri* → **USB hata ayıklama**'yı aç.
3. Telefonu USB kabloyla bilgisayara bağla. Telefonda çıkan "USB hata ayıklamaya izin verilsin mi?"
   sorusuna **İzin ver** de.

**Bilgisayarda:** proje klasöründe şunu çalıştır (ya da `install.bat` dosyasına çift tıkla):

```bash
.\install.bat
```

Betik uygulamayı kurar, kamera ve Bluetooth izinlerini verir ve uygulamayı açar. Sonunda
`OK: SweepScan is installed and running on the phone.` yazmalı.

| Betik ne diyor | Ne yapmalı |
|---|---|
| `No phone found` | Kablo veri kablosu mu? USB hata ayıklama açık mı? Telefon kilidi açık mı? |
| `has not allowed this PC` | Telefonun kilidini aç, "İzin ver"e bas, betiği tekrar çalıştır. |
| `INSTALL FAILED` | Aşağıdaki "Kurulum engellenirse" bölümüne bak. |

---

## Yol 2: APK dosyasını telefona kopyalayarak

1. `dist\SweepScan.apk` dosyasını telefona gönder (USB dosya aktarımı, Google Drive ya da kendine e-posta).
2. Telefonda *Dosyalarım* uygulamasından dosyaya dokun.
3. "Bu kaynaktan yüklemeye izin ver" sorusu çıkarsa izin ver ve geri dönüp **Yükle**'ye bas.
4. Play Protect "bilinmeyen geliştirici" uyarısı verirse **Yine de yükle**'yi seç. Uygulama mağazadan
   gelmediği için bu uyarı normal.
5. Uygulamayı aç; kamera ve "yakındaki cihazlar" (Bluetooth) izinlerini ver.

---

## Kurulum engellenirse

- **Otomatik Engelleyici (Samsung).** Yeni One UI sürümlerinde varsayılan olarak açık gelir ve mağaza dışı
  kurulumu engeller. *Ayarlar → Güvenlik ve gizlilik → Otomatik Engelleyici*'yi kurulum için kapat,
  kurulumdan sonra tekrar açabilirsin.
- **İmza uyuşmuyor hatası.** Telefonda başka bir bilgisayarda derlenmiş eski bir SweepScan varsa önce onu
  kaldır. Kaldırmak, telefondaki kayıtlı sayımı da siler.

---

## İlk açılışta kontrol

1. "Nasıl kullanılır" kısa rehberi çıkıyor (sonra ⚙ → *Nasıl kullanılır* ile tekrar açılır).
2. Kamera görüntüsü geliyor; sağ kenarda OTO, 1×–3× zoom ve fener düğmeleri var.
3. Alttaki üç düğme (Geri al, büyük Duraklat, Bitir) telefonun gezinme çubuğunun **üstünde** duruyor.
4. Ses tuşu Duraklat / Devam yapıyor.

## Bluetooth ile DİA'ya yazma (bir kez)

1. SweepScan açıkken üstteki PC etiketine dokun → *Yeni PC eşleştir*.
2. Laptopta *Ayarlar → Bluetooth ve cihazlar → Cihaz ekle → Bluetooth* → telefonu seç, kodları onayla.
   Eşleştirmeyi telefonun kendi Bluetooth ayarlarından **başlatma**: Windows telefonu klavye olarak
   tanımaz (bkz. saha-testi.md, "Eşleştirme kuralı").
3. Sayım bitince *Bitir* → *DİA'ya gönder*. 5 saniyelik geri sayım sırasında laptopta DİA'nın barkod
   alanına tıkla; telefon, laptopta hangi alan seçiliyse oraya yazar.

Sonraki adımlar [saha-testi.md](saha-testi.md) dosyasında: evde ilk deneme, laptopla Bluetooth
eşleştirme ve salı günkü test.

---

## Güncelleme

Yeni bir sürüm geldiğinde aynı adımları tekrarla. Uygulama eskisinin üstüne kurulur; sayım ve ayarlar
silinmez.
