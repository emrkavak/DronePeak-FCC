# DronePeak kumanda arayüzü

## Kabul edilen işlevsel temel

Kullanıcının talebi üzerine FCC/CE, keepalive, 4G, LED, cihaz bilgisi ve seri numarası işlemleri
FreeFCC’nin `597157bd52120dfeb9677f79a8ad46b6027ce8dc` commit’ine eşitlendi.

Korunan istisnalar:

- DronePeak adı, `com.dronepeak.app`, mevcut tercihler deposu, sürüm ve imzalama yapılandırması.
- Türkçe/İngilizce sunum. Upstream operasyon metinleri iş mantığında korunur; `TextCatalog` bunları yalnızca gösterirken çevirir.
- DronePeak APK güncelleme/kurulum akışı, doğrulama ve tanılama. Profil güncellemeleri FreeFCC’den gelir; indirilen profilleri yükleyen mevcut depolama adaptörü korunur.
- `SerialResolution` içindeki eşdeğer saf ifadeler JVM testleri için ayrılmıştır.

Bu eşitleme önceki fork’a göre bilinçli işlevsel değişiklik içerir: DUML yanıtında ANY hedefe yönelik özel kabul ve
telemetriyi ayıklayarak yanıt arama kaldırılmış, upstream’in yanıt okuma/routing doğrulaması ve `device_info.json`
profili geri alınmıştır. Seri numarası ve 4G işlemleri de upstream yöntemiyle çalışır. Kaynak karşılaştırması
`tools/verify_upstream.py` ile tekrar edilebilir; gerçek donanımın çalıştığını kanıtlamaz.

## Görsel davranış

Grafit zemin, düz paneller, 1dp kenarlık, 12dp köşe ve anlam taşıyan durum renkleri kullanılır.
Drone renderı dekoratiftir; bağlı model veya canlı telemetri iddiası taşımaz.

Yerleşim Android’in kullanılabilir dp ölçülerine göre seçilir. 600dp’den geniş kök pencerede menü rail’e döner;
kontrol alanı 540dp genişliğe ulaştığında iki sütun kullanır. Kısa yatay pencerelerde bu eşikler 560dp ve 460dp olur; dört yardımcı işlem tek sıraya yerleşir. Dikey görünüm aynı bileşenleri üst üste yerleştirir.
Geniş ekranlarda içerik 1040×520dp ile sınırlandırılır. Android’in fiziksel ppi değeri ekran yoğunluğu yerine kullanılmaz.

Ana kontrol ekranında hiçbir işlem kaydırma arkasına saklanmaz. Donanım meşgul/bağlantı eksik nedenleri görünürdür.
LED kumandası ayrı port kullandığından FCC kilidi sırasında açık kalır. Dokunma alanları en az 48dp, ana düğmeler
56dp’dir. Uzun ikincil durum metinleri kendi alanlarında kaydırılabilir; büyük yazıda tam satır yüksekliği ayrılır.

FCC/CE durumu yazılan komutu gösterir; hava aracı onayı olarak sunulmaz. 4G yazma tamamlandı mesajı hava aracında
kontrol edilmesini ister. Upstream’de severity alanı bulunmadığı için metinler nötrdür; metinden renk çıkarılmaz.

## Doğrulama ve önizlemeler

- `assembleDebug`, `assembleRelease` ve `testDebugUnitTest`: başarılı; 30 test, 0 başarısızlık.
- `:app:lintDebug`: 0 hata, 19 uyarı. Uyarılar SDK/dependency eskiliği, eski SDK koşulları, extraction rules ve mevcut launcher kaynaklarıyla ilgilidir.
- Kaynak eşitliği: transport, lock, servis, receiver, bütün profil JSON’ları, upstream state varsayılanları ve donanım ViewModel yöntemleri doğrulandı.
- Yerel emülatörde 20 durum/pencere görüntüsü alındı. Kontrol düğmelerinin ekran içinde kaldığı ve 48dp dokunma boyutu denetlendi.
- Gerçek uygulamada dil değiştirme ve Bilgi/Günlük sekmelerine dokunarak geçiş kontrol edildi. Donanım düğmelerine basılmadı.
- Release manifestinde debug önizleme aktivitesi bulunmadığı kontrol edildi.
- Yerel release APK mevcut anahtarla imzalandı; `apksigner verify --verbose` v3 imzasını doğruladı. Dosya: [DronePeak-design.apk](output/DronePeak-design.apk).

| Pencere | Önizleme |
| --- | --- |
| RC 2 yatay, 768×432dp | [Kontrol](output/design/rc2-fcc-tr.png) |
| Dikey, 432×768dp | [Kontrol](output/design/portrait-fcc-tr.png) |
| RC Pro 2 için yatay temsil, 960×540dp | [Kontrol](output/design/rcpro2-fcc-en.png) |
| RC Pro 2 için dikey temsil, 540×960dp | [Kontrol](output/design/rcpro2-portrait-fcc-tr.png) |
| RC 2 yoğun ekran ayarı, 640×360dp | [Kontrol](output/design/rc2-dense-fcc-tr.png) |
| %130 yazı boyutu | [Kontrol](output/design/rc2-large-text-fcc-tr.png) |

Ölçüler örnek Android pencereleridir; gerçek kumandalardaki vendor yoğunluğu ayrıca ölçülmelidir.
**Gerçek RC/aircraft bağlantısı ve DUML işlemleri test edilmedi.**

Yerel tekrar:

```sh
python3 tools/verify_upstream.py
./gradlew assembleDebug testDebugUnitTest :app:lintDebug -PdronePeakRepo=emrkavak/DronePeak-FCC
python3 tools/capture_design.py --adb "$ANDROID_HOME/platform-tools/adb" --smoke
```

Son komut açık bir yerel emülatör gerektirir; gerçek cihaz seri numaralarını kabul etmez.
`DesignPreviewActivity` yalnızca debug source set’indedir. Üretim Compose bileşenlerini sahte durumlarla ve
no-op işlem adaptörüyle render eder; ViewModel oluşturmaz, ağ/servis/hardware işlemi başlatmaz.

RC2 üzerinden mevcut kurulumun güncellenmesi için yayın sürümü `1.5.5-dp.5` / versionCode `34` olarak artırıldı. Tag: `v1.5.5-dp.5`.

## Görsel kaynağı

Final bitmap: [drone_hero.png](app/src/main/res/drawable-nodpi/drone_hero.png), 1536×1024, alpha kanallı.
Üretim: yerleşik `imagegen` aracı; CLI veya harici görsel bağlantısı kullanılmadı.
Tam üretim ve şeffaflaştırma promptları [drone-prompt.txt](output/design/drone-prompt.txt) dosyasındadır.

## Authenticator activation adapter

Version activation gates ViewModel entry points, boot startup and keepalive service startup/ticks. These checks are accepted adapters; DUML frame contents, transport, lock ownership and timing remain pinned. `tools/verify_upstream.py` strips only the exact activation checks before comparing hardware bodies. See ACTIVATION.md for setup and limitations.

## Floating shortcut

The Control header has a 48dp shortcut settings button. Its switch requests Android display-over-other-apps permission. The independent specialUse foreground service renders a 32dp app icon in a 48dp window; dragging within 24dp of any safe screen edge docks it into a 12dp inward-facing arrow at 35% opacity. Arrow taps reveal, icon taps reopen Control. The window is hidden while MainActivity is visible. Normalized position/edge and user preference persist; no BootReceiver hook is added. Stopping the shortcut never changes FCC keepalive.

Local QA artifacts live in ignored `output/shortcut/`. Test Android overlay permission grant/revocation, all four edges, rotation, notification stop, page routing, and app restart before checking on actual RC 2/RC Pro 2 firmware. JVM geometry tests do not establish DJI compatibility.

Validation: debug/release builds and 39 JVM tests passed. Full debug lint: 0 errors, 19 warnings. Android 36.1 local emulator checks passed for permission navigation, all four dock edges/reveal, existing Activity Control routing, rotation, toggle disable, permission revocation stopping the service and restoring the enabled preference on app reopen. Real DJI controller/firmware checks remain outstanding.
