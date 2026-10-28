# Phoneix Kullanım Kılavuzu

Phoneix, Android telefonunu Windows bilgisayarına aynı Wi‑Fi ağı üzerinden bağlar: bildirimler, pano, dosyalar, aramalar, ekran yansıtma, sistem monitörü ve tuş paneli (deck). Ana sayfa: [README](README.md) (İngilizce).

## 1. Kurulum

1. **Telefon:** [`releases/Phoneix-0.3.0.apk`](releases/Phoneix-0.3.0.apk) dosyasını telefonuna indir ve aç (Android sorarsa tarayıcın için "bilinmeyen kaynaklardan yükleme" iznini ver). Derlemek gerekmez.
2. **Bilgisayar:** [Node.js](https://nodejs.org) kur, sonra `desktop` klasöründe `npm install` ve `npm start` çalıştır; Phoneix penceresi açılır.
3. İsteğe bağlı ama önerilir: `adb shell pm grant app.phoneix android.permission.WRITE_SECURE_SETTINGS` — kablosuz ekran yansıtmada kablosuz hata ayıklamayı Phoneix'in kendiliğinden açabilmesini sağlar.
4. Ekran yansıtma için [scrcpy](https://github.com/Genymobile/scrcpy/releases) dosyalarını `C:\Users\<sen>\Android\scrcpy` klasörüne, `adb` için Android `platform-tools` klasörünü `C:\Users\<sen>\Android\Sdk\platform-tools` içine koy.

## 2. Eşleştirme

1. Bilgisayardaki Phoneix penceresinde **QR kodu** görünür.
2. Telefonda Phoneix'i aç, QR'ı tara, bilgisayar adını onayla.
3. Bilgisayarda "Telefon eşleşti" bildirimi çıkar. Eşleşme bir kez yapılır; telefon ve bilgisayar aynı ağa girince kendiliğinden bağlanır, uzun sessizlikten sonra tekrar bağlanınca "Telefon bağlandı" uyarısı gelir.

Telefonda ilk açılışta istenen izinleri (bildirim erişimi, pil kısıtlamasının kaldırılması vb.) ver; yoksa Android uygulamayı arka planda durdurabilir.

## 3. Özellikler

### Bildirimler
Telefonda **Hangi uygulamalar?** düğmesinden bilgisayara gelmesini istediğin uygulamaları seç. Cevap eylemi olan bildirimlere (ör. mesajlaşma uygulamaları) bilgisayardan yanıt yazabilirsin.

### Aramalar
Gelen aramada arayan kişi bilgisayarda görünür; oradan **cevapla / reddet / kapat / hoparlör** diyebilirsin. Bilgisayardan **Telefonu çaldır** ile kayıp telefonu bulabilirsin.

### Pano
Bilgisayarda kopyaladığın metin kendiliğinden telefona gelir. Telefonda kopyaladığını göndermek için Hızlı Ayarlar'daki **"Panoyu gönder"** karosuna dokun (ya da Paylaş → Phoneix).

### Dosyalar
- Telefondan bilgisayara: Paylaş → Phoneix. Dosyalar varsayılan olarak `İndirilenler\Phoneix` klasörüne düşer.
- Bilgisayardan telefona: dosyayı Phoneix penceresine sürükle ya da Dynamic Bar'a bırak.

### Ekran yansıtma
Phoneix penceresinde **Ekranı yansıt**'a bas. Telefon biçimli bir pencere açılır, "Bağlanılıyor" yazar, bağlanınca telefon ekranı pencerenin içinde belirir.
- Pencereyi üst çubuktan sürükleyebilirsin; telefon yan dönünce pencere de uyum sağlar; ✕ yansıtmayı kapatır.
- USB takılıysa USB'yi, değilse kablosuz bağlantıyı kullanır (ilk soğuk başlatma yaklaşık 3 sn).
- Fare ve klavye telefonu kontrol eder.

### Sistem monitörü
Phoneix'te **Telefon modları → Sistem monitörü**'ne bas (ya da telefonda ana ekrandaki mod düğmesi). Telefon yatay ve tam ekran olur; CPU, GPU, bellek, ekran belleği, sıcaklıklar ve ağ hızı canlı gösterilir. Çıkmak için sağ üstteki ✕ ya da Geri.

> **CPU sıcaklığı "—" görünüyor:** Windows bu bilgiyi yalnızca yönetici yetkisine açar. Phoneix penceresinde **Telefon modları → CPU sıcaklığı → Etkinleştir**'e bas: açık kaynak [LibreHardwareMonitor](https://github.com/LibreHardwareMonitor/LibreHardwareMonitor) indirilir ve tek bir yönetici onayıyla (UAC) oturum açılışında sessizce başlatılır. Sorun olursa aynı yerdeki **Onar** düğmesine bas. GPU sıcaklığı (NVIDIA) buna gerek duymadan çalışır.

### Deck (tuş paneli)
**Telefon modları → Deck** ile telefonda büyük tuşlardan oluşan bir panel açılır. Tuşları bilgisayardan tanımlarsın (Phoneix penceresi → **Deck tuşları**): sekmeler, ızgara ve tuş düzenleme paneli var, **Dene** düğmesiyle tuşu bilgisayarda test edebilirsin.

Tuş eylemleri:
| Eylem | Ne yapar |
|---|---|
| Bağlantı | Tarayıcıda adres açar (yalnızca http/https) |
| Uygulama | Başlat Menüsü listesinden uygulama seçersin, tuşta simgesi görünür |
| Tuş kısayolu | Ör. `ctrl+shift+m` gönderir |
| Medya | Oynat/durdur, sonraki, sessiz vb. |
| Dynamic Bar | Bara bir eylem gönderir |
| Yansıt / Kilitle | Ekran yansıtmayı açar / bilgisayarı kilitler |
| Komut | Kabuk komutu çalıştırır — varsayılan **kapalıdır**, Deck kartındaki anahtarla açılır |

Güvenlik: telefon yalnızca tuşun numarasını gönderir; ne yapılacağı bilgisayardaki kendi ayar dosyandan okunur.

### Tema
Telefonda **Ayarlar → Tema**: Koyu (varsayılan), Açık ya da Sistem. Bilgisayar uygulaması açık mavi temayı kullanır.

## 4. Sorun giderme

| Sorun | Çözüm |
|---|---|
| Telefon bağlanmıyor | İkisi de aynı ağda mı? Güvenlik duvarı ilk seferde Phoneix'e izin sorar, izin ver. Telefonda pil optimizasyonunu Phoneix için kapat. |
| Ekran yansıtma "scrcpy bulunamadı" diyor | scrcpy klasörünü Kurulum 4. adımdaki yere koy. |
| Kablosuz yansıtma bağlanmıyor | Telefonda Geliştirici seçenekleri'nde **Kablosuz hata ayıklama**'nın açık olduğundan emin ol; en kolayı telefonu bir kez USB ile bağlayıp yansıtmayı başlatmak. |
| Monitör/Deck ekranı telefonda açılmıyor | Android, başka bir uygulama öndeyken ekranı açmayı engelleyebilir; çıkan bildirime dokun. |
| CPU sıcaklığı "—" | Yukarıdaki LibreHardwareMonitor notuna bak. |
| Eşleşmeyi sıfırlamak istiyorum | Bilgisayarda Phoneix'te eşleşmeyi kaldırıp QR'ı yeniden tara. |

Gizlilik: tüm trafik yerel ağda kalır ve AES‑256‑GCM ile şifrelenir; bulut yoktur. Ayrıntılar için [PROTOCOL.md](PROTOCOL.md).
