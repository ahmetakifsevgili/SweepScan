# DİA API erişimi: e-posta taslakları ve sorular

DİA'nın Web Servis API'si (`https://SUNUCUKODU.ws.dia.com.tr/api/v3/scf/json`) klavye emülasyonunun
yerini alabilir. Telefon, tüm barkodları tek bir transfer fişi olarak doğrudan DİA'ya yazar. Bilinmeyen
barkodlar önceden görülür ve hiçbir satır kör girilmez. Bunun için üç şey gerekiyor:

1. **API anahtarı.** DİA destek, kullanım amacını anlatan bir e-postayla verir
   ([Session & API Key](https://doc.dia.com.tr/doku.php?id=gelistirici:wsapi:session)).
2. **Ayrı bir DİA kullanıcısı.** Yetkisi yalnızca stok kartı görme ve malzeme fişi ekleme. Bunu mağaza
   değil, Derinet merkezinin DİA yöneticisi ya da DİA bayisi açar.
3. **Sunucu kodu, firma kodu, dönem kodu.** Yine merkezden ya da bayiden alınır.

DİA hesabı Derinet'in. Bu yüzden sıra şöyle:
- **Önce merkez:** izin ve sorular (Taslak 1).
- **Sonra DİA destek:** API anahtarı (Taslak 2). Bunu merkezin bilgisiyle, mümkünse merkezi CC'ye
  ekleyerek gönder.

Maliyet: çağrı başına 0,0125 kontör, giriş ve çıkış ücretsiz
([WS genel](https://doc.dia.com.tr/doku.php?id=gelistirici:wsapi:genel)). Teslimat başına yaklaşık 2 çağrı
gerekir, yani yaklaşık 0,025 kontör. Kontörün TL karşılığını bulamadım; bunu bayi bilir.

---

## Taslak 1: Derinet merkezine / DİA yöneticisine

**Konu:** Viaport mağazası: kutu sayımını DİA'ya otomatik aktarma için API erişimi

Merhaba,

Viaport mağazasının deposunda çalışıyorum. Mesut Bey'in onayıyla, gelen sevkiyatları tek tek barkod
okutmak yerine telefon kamerasıyla toplu sayan bir uygulama geliştiriyorum (SweepScan). Şu an uygulama
saydığı barkodları Bluetooth klavye gibi DİA'daki "Stok transfer" ekranına yazıyor. Daha güvenli
olması için DİA'nın kendi Web Servis API'sini kullanmak istiyorum.

Bunun için sizden şunları rica ediyorum:

1. API kullanımına izin veriyor musunuz? DİA destekten API anahtarını ben isteyebilirim, sizi de
   bilgilendiririm.
2. Bu iş için ayrı bir DİA kullanıcısı açılabilir mi? Yetkiler yalnızca stok kartı ve barkod görme,
   Viaport depoları için malzeme fişi ekleme olsun.
3. Sunucu kodu, firma kodu ve dönem kodu nedir?

Ayrıca birkaç sorum var:

4. Mağazada yaptığımız "Stok transfer" (viaport-transfer → viaport-viaport) DİA'da hangi fiş
   tipine karşılık geliyor? Malzeme fişi "Depo Fişi (Transfer)" mi?
5. Aynı barkod ikinci kez okutulduğunda yeni satır yerine mevcut satırın miktarını artıran bir ayar
   var mı? Şu an her okutma ayrı satır açıyor.
6. DİA'da transfer fişine Excel'den kalem aktarma özelliği açık mı?
7. Merkez, mağazaya gelen sevkiyat için önceden bir giden transfer fişi oluşturuyor mu? Öyleyse
   sayımı bu fişle karşılaştırıp eksik ve fazla kutuyu teslimat bitmeden gösterebilirim.
8. Depolar arası transferde e-irsaliye ya da muhasebe açısından dikkat etmem gereken bir şey var mı?

Uygulama yalnızca mağaza içi sayım için. Satış, fiyat ya da cari verilerine dokunmuyor.

Teşekkürler,
Ahmet Akif Sevgili
Derinet Viaport

---

## Taslak 2: DİA destek (API anahtarı talebi)

DİA desteğin e-posta adresini kesin olarak bulamadım. DİA masaüstünde "Destek" menüsüne ya da
[doc.dia.com.tr](https://doc.dia.com.tr) sitesine bak, ya da merkezden/bayiden sor.

**Konu:** Web Servis v3 API anahtarı talebi: Derinet Viaport, mağaza sayım uygulaması

Merhaba,

Derinet firmasının Viaport mağazasında, depoya gelen ayakkabı kutularını telefon kamerasıyla toplu
sayan bir Android uygulaması geliştiriyorum. Uygulamanın saydığı barkodları DİA Web Servis v3 ile
aktarmak için API anahtarı talep ediyorum.

- **Firma:** Derinet (kullanıcı ve yetkiler merkezimizin DİA yöneticisi tarafından tanımlanacak)
- **Uygulama:** SweepScan, Android, kişisel olarak geliştirdiğim mağaza içi araç
- **Kullanılacak servisler:** `login` / `logout`, `scf_barkod_okut` (sayılan barkodların stok
  kartı ve varyantını bulmak için), `scf_malzeme_fisi_ekle` (`turu = 1`, depolar arası transfer fişi,
  haftada yaklaşık 2–4 fiş)
- **Tahmini kullanım:** teslimat başına 2–5 çağrı, haftada 20'den az çağrı
- **Önce** DİA WS Tester ile demo sunucuda denemek istiyorum.

İki sorum var:
1. `scf_barkod_okut`, varyantlı (beden/renk) stoklarda varyant anahtarını da döndürüyor mu?
2. API anahtarı geliştiriciye mi verilir, firmaya mı? Firma tarafında yapılması gereken bir onay var mı?

Teşekkürler,
Ahmet Akif Sevgili

---

## Kendin bakabileceklerin (e-postayı beklemeden)

- **Geçmiş bir transfer fişi.** DİA'da daha önce yapılmış bir "Stok transfer" fişini aç ve fiş tipini/türünü
  not et. Taslak 1'deki 4. soru böylece kendiliğinden cevaplanabilir.
- **DİA Mobil.** Telefona DİA Mobil'i kurup menülerde depo transferi ya da mal kabul ekranı var mı bak.
  Açıklamasında kamerayla tarama ve depo sayımı geçiyor, transfer geçmiyor.
- **Kontör hareketleri.** DİA masaüstünde "Kontör Hareketleri (msj1900)" ekranı açılıyorsa firmanın
  kontör bakiyesini görebilirsin.
