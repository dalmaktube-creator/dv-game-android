# مرجع تثبیت‌شده DV Game Alpha 8

این سند خط مبنای سالمی را ثبت می‌کند که پیش از شروع فاز پایداری روی گوشی واقعی تأیید شد.

## هویت نسخه

| مورد | مقدار |
|---|---|
| شاخه | `feat/app-v1` |
| کامیت | `8efd5ea998f7ec9246bd3fa19efbbbde0e95dfb8` |
| نسخه | `0.2.0-alpha08` |
| versionCode | `10` |
| APK | `DV-Game-0.2.0-alpha08-debug.apk` |
| SHA-256 APK | `03c8642817fb1e6baf6e6bb65d6583082da1fa042fa5de3b6938c435a010f7ee` |
| libbox | `proother/sing-box-lib v1.13.19` |
| آرشیو libbox | `libbox-android.aar.zip` |
| SHA-256 آرشیو libbox | `493bed8217b7c25d5699f964a7fcedd0d4d2d79abc72e4070a89709833b74f57` |

## نتیجه آزمون میدانی

- دستگاه: Redmi Note 14، Android 14
- اینترنت: MobinNet
- Mobile Legends با کیفیت مشابه کانفیگ WireGuard مستقیم اجرا شد.
- فقط پکیج بازی از تونل عبور کرد.
- مرورگر و Speedtest همچنان IP واقعی MobinNet را نمایش دادند.
- هدف بنیادی Game Split محقق شد.

## پارامترهای مسیر پکت

| لایه | پارامتر | Alpha 8 |
|---|---|---|
| TUN | stack | `mixed`؛ TCP سیستم و UDP روی gVisor |
| TUN | address | `172.19.0.1/30` |
| TUN | MTU | `min(peerMtu, 1280)` |
| TUN | auto_route | `true` |
| TUN | endpoint_independent_nat | `false` |
| TUN | udp_timeout | `10m` |
| Endpoint | type/system | `wireguard` / `false` |
| Endpoint | workers | `2` |
| Endpoint | udp_timeout | `10m` |
| DNS | server | اولین DNS عددی پنل، detour از `wg-game` |
| Route | final | `wg-game` |
| Route | DNS | `hijack-dns` |

## دو رگرسیون ممنوع

1. موتور بدون build tag مربوط به Clash API نباید وارد پروژه شود. آرشیو libbox در CI با SHA-256 بالا pin شده است.
2. دامنه Endpoint هرگز نباید وارد JSON نهایی موتور شود. DNS باید پیش از TUN و روی شبکه زیرین انجام شود.

## بازتولید

1. checkout کامیت ثبت‌شده.
2. دانلود آرشیو libbox نسخه 1.13.19 و کنترل SHA-256.
3. استخراج `libbox.aar` در `app/libs/`.
4. اجرای `gradle :app:testDebugUnitTest :app:assembleDebug --stacktrace` با JDK 17، Gradle 8.10.2 و Android SDK 35.
5. ساخت موفق و مقایسه رفتار روی دستگاه با Alpha 8. SHA-256 APK بالا هویت فایل مرجع است، نه الزام بازتولید بایت‌به‌بایت؛ چنین الزامی فقط پس از reproducible شدن کامل زنجیره build معتبر است.

> نسخه بعدی باید رفتار موفق Alpha 8 را حفظ کند و فقط پایداری چرخه اتصال، handover شبکه و خطاپذیری را ارتقا دهد.
