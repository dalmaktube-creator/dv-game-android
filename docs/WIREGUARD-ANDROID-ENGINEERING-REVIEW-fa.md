# بررسی مهندسی WireGuard Android برای DV Game

تاریخ بررسی: 2026-08-28  
مرجع اصلی: [WireGuard/wireguard-android](https://github.com/WireGuard/wireguard-android)  
نسخه سورس بررسی‌شده: tag `1.0.20260315` / commit `e7b3a3c118836e112620b1302a8ba1873ad4daac`  
نسخه فعلی کتابخانه در DV Game: `com.wireguard.android:tunnel:1.0.20260102`

## هدف

این سند مرجع اجباری توسعه DV Game است تا کیفیت تونل، کارایی، امنیت، پایداری پس‌زمینه و نصب‌پذیری نسبت به برنامه رسمی WireGuard افت نکند. هر تغییر در لایه VPN باید با این سند و چک‌لیست انتشار تطبیق داده شود.

## نتیجه اجرایی

- هسته تونل را از نو نمی‌نویسیم؛ از کتابخانه رسمی `tunnel` استفاده می‌کنیم.
- روی گوشی عادی از `GoBackend` و `wireguard-go` استفاده می‌شود؛ Root لازم نیست.
- Backend کرنل (`WgQuickBackend`) فقط روی دستگاه Rootشده و دارای ماژول کرنل کاربرد دارد و در MVP پشتیبانی نمی‌شود.
- قابلیت Game Split از مسیر رسمی `IncludedApplications` پیاده می‌شود. `GoBackend` آن را به `VpnService.Builder.addAllowedApplication()` تبدیل می‌کند.
- بخش‌هایی که باید اختصاصی بسازیم: Subscription، انتخاب بازی، انتخاب Exit، State machine، ذخیره امن، آمار، reconnect، UX و اتصال به WG Gaming Panel.

## 1. معماری رسمی

### 1.1 ماژول‌ها

- `tunnel/`: Parser کانفیگ، کلیدها، Backend، آمار و native `wireguard-go`.
- `ui/`: مدیریت چند تونل، ذخیره کانفیگ، import/export، Always-on، Boot، QR و رابط رسمی.
- `GoBackend`: مسیر استاندارد بدون Root و انتخاب اصلی DV Game.
- `WgQuickBackend`: مسیر کرنل با Root؛ برای محصول عمومی مناسب نیست.

### 1.2 مسیر اتصال GoBackend

1. بارگذاری `libwg-go.so`؛ اگر loader عادی شکست بخورد، کتابخانه از APK/Split APK استخراج و بارگذاری می‌شود.
2. بررسی مجوز کاربر با `VpnService.prepare()`.
3. شروع Service داخلی کتابخانه و انتظار حداکثر دو ثانیه برای آماده‌شدن آن.
4. Resolve کردن Endpointهای دامنه‌ای؛ حداکثر ۱۰ تلاش با فاصله یک ثانیه.
5. ساخت `VpnService.Builder`.
6. اعمال Included/Excluded Applications، Address، DNS، Search Domain، Route و MTU.
7. `setBlocking(true)` و ساخت TUN.
8. تحویل file descriptor به `wireguard-go`.
9. `protect()` کردن socketهای IPv4 و IPv6 برای جلوگیری از loop شدن خود تونل.
10. نگهداری handle برای State و Statistics.

### 1.3 محدودیت‌های Backend

- `GoBackend` در هر Process فقط یک تونل فعال نگه می‌دارد.
- نام تونل باید حداکثر ۱۵ کاراکتر و مطابق `[a-zA-Z0-9_=+.-]` باشد. نام `dv-game` معتبر است.
- فراخوانی اتصال blocking است و باید همیشه روی `Dispatchers.IO` انجام شود.
- اگر تونل جدید بالا نیاید، Backend تلاش می‌کند تونل قبلی را برگرداند.
- تغییر کانفیگ تونل فعال عملاً Down/Up ایجاد می‌کند؛ UI نباید آن را بدون اطلاع کاربر زیاد تکرار کند.

## 2. Game Split

در `[Interface]` باید این فیلد ساخته شود:

```ini
IncludedApplications = com.mobile.legends, com.tencent.ig
```

قواعد:

- Included و Excluded هم‌زمان ممنوع‌اند و Parser رسمی خطا می‌دهد.
- قبل از اتصال باید نصب‌بودن تمام Packageها دوباره بررسی شود؛ ممکن است بازی بعد از Scan حذف شده باشد.
- فهرست خالی نباید به اتصال Game Split منجر شود، چون فهرست خالی در Android به معنی عبور همه برنامه‌هاست.
- برنامه DV Game نباید داخل IncludedApplications قرار گیرد.
- تشخیص `ApplicationInfo.CATEGORY_GAME` کافی نیست؛ بعضی بازی‌ها category درست ندارند. صفحه انتخاب باید حالت «همه برنامه‌های قابل اجرا» و جست‌وجوی دستی داشته باشد.
- برای Android 11+ فقط Query مربوط به launcher apps استفاده شود و تا حد ممکن از `QUERY_ALL_PACKAGES` پرهیز شود تا پذیرش Play Store سخت نشود.

## 3. Routing، DNS و MTU

### Route

`AllowedIPs` هر Peer مستقیماً به Routeهای Android تبدیل می‌شود. برای Game Split معمولاً کانفیگ Client باید default routeهای IPv4 و در صورت پشتیبانی IPv6 را داشته باشد؛ محدودسازی اصلی توسط Package انجام می‌شود.

### Kill-switch semantics

کد رسمی اگر دقیقاً یک Peer و default route داشته باشد، خانواده‌های بدون Route را آزاد نمی‌کند. اگر default route کامل وجود نداشته باشد، برای جلوگیری از خرابی split routing از `allowFamily(AF_INET/AF_INET6)` استفاده می‌کند. نباید این رفتار را با دستکاری متن کانفیگ خراب کنیم.

### DNS

- DNS کانفیگ به `VpnService.Builder.addDnsServer()` داده می‌شود.
- DNS فقط برای برنامه‌های Included داخل VPN اعمال می‌شود.
- Endpoint دامنه‌ای پیش از بالا آمدن تونل Resolve می‌شود و ممکن است اتصال تا حدود ۱۰ ثانیه منتظر بماند.
- باید خطای `DNS_RESOLUTION_FAILURE` جدا و قابل فهم نمایش داده شود.
- تست DNS leak برای هر دو IPv4 و IPv6 اجباری است.

### MTU

اگر MTU تنظیم نشده باشد، Backend رسمی `1280` می‌گذارد. DV Game نباید بدون داده میدانی MTU را خودکار افزایش دهد. تنظیم MTU باید از Subscription پنل بیاید و روی Wi‑Fi و LTE/5G آزمایش شود.

## 4. State و Lifecycle

پیاده‌سازی رسمی State را در سطح `Application` و `TunnelManager` نگه می‌دارد، نه داخل Activity. عملیات Backend روی IO و تغییر State نمایشی روی Main انجام می‌شود. خطاها State را بازخوانی و اصلاح می‌کنند.

### الزام برای DV Game

- `WireGuardController` نباید Activity-scoped باشد.
- یک `Application`-scoped `TunnelRepository` با `StateFlow` لازم است.
- `Tunnel.onStateChange()` نباید خالی باشد؛ باید State واقعی را منتشر کند.
- چرخش صفحه، تغییر زبان، رفتن اپ به Background و بازسازی Activity نباید State را گم کند.
- Destroy شدن `VpnService` باید UI را فوراً به DOWN ببرد.
- همه عملیات Connect/Disconnect با Mutex سری شوند تا Double tap و Race ایجاد نشود.
- Timeoutهای جدا برای Permission، Service startup، DNS و Tunnel activation لازم است.
- خطاهای رسمی Backend باید به پیام‌های مشخص کاربر تبدیل شوند، نه یک پیام عمومی.

### Always-on و Game Lock

کتابخانه Callback رسمی `GoBackend.setAlwaysOnCallback()`، همچنین `isAlwaysOn()` و `isLockdownEnabled()` دارد. برای Game Lock باید:

1. آخرین Profile معتبر و بازی‌های انتخابی به‌صورت امن ذخیره شوند.
2. Callback سیستم State را از Repository بازیابی کند.
3. پس از Boot/Process death اتصال بدون نیاز به Activity قابل بازیابی باشد.
4. رفتار IncludedApplications همراه Lockdown روی سازندگان مختلف گوشی تست شود.
5. قبل از تست واقعی نباید Game Lock را «تضمین قطع اینترنت سایر اپ‌ها» معرفی کنیم.

## 5. آمار و تشخیص سلامت

`getStatistics()` برای هر Peer این اطلاعات را می‌دهد:

- RX bytes
- TX bytes
- آخرین Handshake

آمار رسمی پس از حدود ۹۰۰ میلی‌ثانیه stale محسوب می‌شود. پیشنهاد DV Game:

- هنگام بازبودن صفحه، polling هر ۱ ثانیه.
- در Background polling متوقف شود.
- Connected واقعی فقط با UP بودن interface سنجیده نشود؛ Handshake و RX/TX نیز بررسی شوند.
- حالت‌ها: `Disconnected`, `RequestingPermission`, `Connecting`, `Connected`, `Degraded`, `Reconnecting`, `Disconnecting`, `Error`.
- Reconnect کور و سریع ممنوع؛ backoff مانند 1s, 2s, 5s, 10s, 30s و سقف تلاش لازم است.
- تغییر Wi‑Fi/LTE با `ConnectivityManager.NetworkCallback` مشاهده شود؛ ابتدا فرصت Roaming طبیعی WireGuard داده شود و فقط در صورت stale handshake، reconnect انجام شود.

## 6. کارایی

### آنچه از هسته رسمی می‌گیریم

- پردازش native توسط `wireguard-go`.
- socket protection صحیح.
- TUN در حالت blocking.
- آمار مستقیم از userspace backend.
- fallback بارگذاری native library برای APKهای Split.

### قواعد DV Game

- Config parsing، HTTP و Backend calls هرگز روی Main Thread اجرا نشوند.
- polling آمار سریع‌تر از یک ثانیه ممنوع است.
- اسکن برنامه‌ها cache شود و فقط هنگام Resume/Package change به‌روزرسانی شود.
- آیکون برنامه‌ها lazy و با اندازه محدود Decode شوند.
- هیچ Packet inspection در Kotlin انجام نشود.
- Logging نباید PrivateKey، PresharedKey، Subscription token یا متن کامل Config را ثبت کند.
- معیار پذیرش: اختلاف throughput و latency با اپ رسمی در یک دستگاه و یک کانفیگ باید در محدوده خطای آزمایش باشد.

## 7. امنیت

### کلید و Subscription

- PrivateKey و token نباید در Log، Crash report، Clipboard یا Analytics وارد شوند.
- ذخیره دائمی باید با کلید AES-GCM در Android Keystore انجام شود؛ فایل رمز‌شده در internal storage و `allowBackup=false`.
- Screenshot صفحه‌ای که PrivateKey نشان می‌دهد باید با `FLAG_SECURE` محافظت شود.
- URL اشتراک ممکن است خود یک Secret باشد؛ در UI mask و در Log redact شود.
- پاسخ HTTP باید سقف اندازه داشته باشد؛ پیشنهاد 1 MiB.
- status code، content type، redirect count و timeout کنترل شوند.
- Redirect از HTTPS به HTTP ممنوع باشد.
- HTTP عمومی به‌صورت پیش‌فرض ممنوع؛ فقط opt-in برای IP/LAN و با هشدار صریح.
- TLS certificate validation سیستم حذف نشود. Pinning تنها در صورت طراحی rotation و backup pin اضافه شود.

### Parser

- ابتدا payload decode، سپس `Config.parse()` رسمی اجرا شود.
- دستکاری `IncludedApplications` بهتر است در آینده با `Interface.Builder` انجام شود، نه صرفاً ویرایش رشته.
- حداکثر تعداد Peer، Route و DNS برای جلوگیری از payload مخرب محدود شود.
- Config ناقص یا دارای attribute ناشناخته fail-closed باشد.

### سطح حمله Android

- Service کتابخانه `exported=false` و دارای `BIND_VPN_SERVICE` است؛ این رفتار باید در manifest نهایی حفظ شود.
- Activityهای داخلی DV Game فقط در صورت نیاز exported باشند.
- Receiver یا intent کنترل تونل در MVP نباید عمومی شود.
- APK release باید signed، minified و resource-shrunk باشد.
- Apache-2.0 notice کتابخانه و وابستگی‌ها در صفحه Licenses درج شود.

## 8. نصب‌پذیری و سازگاری

سورس رسمی فعلی:

- `minSdk 24`
- `compileSdk 36`
- Java 17
- AGP 9.1
- نسخه سورس `1.0.20260315`

DV Game فعلاً `minSdk 26` دارد؛ یعنی Android 8.0 به بالا. این تصمیم پوشش مناسب و هزینه تست کمتر می‌دهد.

### ABI و Native Library

باید APK/AAB برای حداقل این ABIها بررسی شود:

- `arm64-v8a` — اجباری
- `armeabi-v7a` — در صورت تصمیم محصول برای گوشی‌های قدیمی
- `x86_64` — Emulator/Chromebook در صورت نیاز

برای الزامات دستگاه‌های جدید و Play Store، سازگاری 16 KB memory pages باید روی AAB نهایی بررسی شود. سورس رسمی جدید از `ANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON` استفاده می‌کند؛ نباید فرض کنیم هر نسخه قدیمی AAR الزام جدید را پوشش می‌دهد.

### نصب و ارتقا

- applicationId باید از ابتدا نهایی شود؛ تغییر آن برای Android یک اپ جدید می‌سازد.
- Signing key باید Offline backup و دسترسی محدود داشته باشد.
- versionCode همیشه افزایشی باشد.
- Upgrade نباید selected games، profile و state امن را از بین ببرد.
- Downgrade رسمی پشتیبانی نشود مگر با migration طراحی‌شده.
- نصب روی Work Profile، Dual Apps و Private Space جداگانه تست شود.
- `installLocation="internalOnly"` مانند اپ رسمی توصیه می‌شود.

## 9. وضعیت فعلی DV Game و Blockerها

اسکلت فعلی Proof of Concept است و هنوز Production-ready نیست.

### Blockerهای P0

1. Controller داخل Activity ساخته می‌شود و با بازسازی Activity State از دست می‌رود.
2. `onStateChange()` خالی است؛ قطع Service یا سیستم در UI منعکس نمی‌شود.
3. Always-on callback و بازیابی پس از Process death پیاده نشده است.
4. `android:usesCleartextTraffic="true"` به‌صورت سراسری فعال است.
5. Subscription response سقف اندازه، بررسی status code و کنترل downgrade redirect ندارد.
6. Release signing، R8، Shrink resources و تست نصب APK/AAB وجود ندارد.
7. تست واقعی Tunnel، DNS leak و Per-app routing وجود ندارد.
8. حالت Game Lock صرفاً Settings را باز می‌کند و هنوز تضمین‌شده نیست.

### موارد P1

- انتخاب بازی فقط بر اساس CATEGORY_GAME است و تعدادی بازی را جا می‌اندازد.
- کانفیگ/کلید ذخیره نمی‌شود؛ برای MVP امن است ولی Auto-connect را غیرممکن می‌کند.
- فقط اولین Config پاسخ Subscription استفاده می‌شود و انتخاب Exit نداریم.
- آمار Handshake/RX/TX، health state و reconnect وجود ندارد.
- مدیریت Package حذف‌شده بین انتخاب و اتصال وجود ندارد.
- dependency فعلی `1.0.20260102` است؛ tag جدیدتر `1.0.20260315` باید از نظر انتشار Maven و regression بررسی شود.

## 10. برنامه اجرای پیشنهادی

### مرحله A — هسته قابل اتکا

- Application-scoped Repository + StateFlow + Mutex.
- مدل خطای کامل Backend.
- Config parser/validator و HTTP امن.
- Test واحد برای IncludedApplications و payloadها.
- Disconnect واقعی در Service destruction.

### مرحله B — Game Split واقعی

- Scanner بازی + All apps fallback + جست‌وجو.
- بررسی Package درست قبل از اتصال.
- تست دو اپ هم‌زمان: بازی از VPN، مرورگر از شبکه عادی.
- DNS leak و IPv6 test.

### مرحله C — پایداری

- NetworkCallback، stale-handshake detector و reconnect با backoff.
- آمار و quality state.
- تست Wi‑Fi↔LTE، Airplane mode، Sleep، Doze، Battery Saver و Process kill.

### مرحله D — نصب و انتشار

- Release signing و Play App Signing.
- R8/shrink، Baseline Profile در صورت نیاز و native symbol handling.
- AAB، ABI و 16 KB page validation.
- Privacy policy، Data Safety و Open-source notices.
- Internal testing سپس Closed testing.

## منابع اصلی

- [WireGuard Android source](https://github.com/WireGuard/wireguard-android)
- [GoBackend.java](https://github.com/WireGuard/wireguard-android/blob/master/tunnel/src/main/java/com/wireguard/android/backend/GoBackend.java)
- [Interface.java](https://github.com/WireGuard/wireguard-android/blob/master/tunnel/src/main/java/com/wireguard/config/Interface.java)
- [Config.java](https://github.com/WireGuard/wireguard-android/blob/master/tunnel/src/main/java/com/wireguard/config/Config.java)
- [TunnelManager.kt](https://github.com/WireGuard/wireguard-android/blob/master/ui/src/main/java/com/wireguard/android/model/TunnelManager.kt)
- [Official embedding guide](https://www.wireguard.com/embedding/)
- [Android VpnService guide](https://developer.android.com/develop/connectivity/vpn)
