# معماری DV Game — مسیر Alpha 9

## اصل موتور

DV Game از `libbox` مبتنی بر sing-box برای TUN و endpoint WireGuard استفاده می‌کند. مسیر قدیمی `GoBackend` حذف شده و تنها یک موتور تولیدی وجود دارد. آرشیو libbox نسخه 1.13.19 با SHA-256 ثابت در CI کنترل می‌شود.

## جریان داده

1. کاربر لینک HTTPS اشتراک را وارد می‌کند.
2. اپ پاسخ نسخه‌دار شامل وضعیت حساب، کانفیگ‌ها و کاتالوگ بازی را دریافت می‌کند.
3. اپ برنامه‌های نصب‌شده را با packageهای کاتالوگ پنل تقاطع می‌دهد.
4. کاربر بازی و سرور را انتخاب می‌کند.
5. parser محلی ساختار تک Interface/Peer، کلیدها، IPv4، DNS، MTU و Endpoint را بررسی می‌کند.
6. Endpoint قبل از ایجاد TUN روی شبکه فیزیکی resolve می‌شود؛ JSON نهایی فقط IPv4 عددی دارد.
7. TUN فقط package بازی تأییدشده را include می‌کند.
8. کانفیگ بازیابی با AES/GCM و کلید Android Keystore و اجاره محلی کوتاه ذخیره می‌شود.
9. ماشین حالت، retry و handover شبکه چرخه عمر موتور را مدیریت می‌کنند.

## مسیر پکت

- TUN: `stack=mixed`، UDP روی gVisor، MTU حداکثر 1280، `udp_timeout=10m`.
- Endpoint: WireGuard userspace، `workers=2`، تمام ترافیک بازی به `wg-game`.
- DNS بازی: DNS عددی پنل با detour از `wg-game` و قانون `hijack-dns`.
- سایر برنامه‌ها وارد TUN نمی‌شوند و اینترنت عادی دستگاه را حفظ می‌کنند.

## پایداری شبکه

- دامنه Endpoint در هر تلاش مجدداً resolve می‌شود.
- A recordها روی تلاش‌های متوالی چرخانده می‌شوند.
- keepalive روی Cellular حداکثر ۱۵ ثانیه و روی Wi-Fi/Ethernet حداکثر ۲۵ ثانیه است.
- `registerDefaultNetworkCallback` و `setUnderlyingNetworks` تغییر Wi-Fi/Cellular را مدیریت می‌کنند.
- دسترسی همگام به properties داخل `onAvailable` انجام نمی‌شود.

## مرز امنیتی

Game Split جلوی عبور تصادفی مرورگر و شبکه‌های اجتماعی از تونل را می‌گیرد. روی دستگاه rootشده یا APK دست‌کاری‌شده تضمین مطلق وجود ندارد. مراحل بعدی شامل device-bound key، امضای نامتقارن catalog، Play Integrity و تشخیص abuse سمت سرور است.

## محدودیت انتشار

این شاخه Alpha است. پیش از Production باید handover، reboot/Always-on، lockdown، DNS/IPv6 leak، Doze، NAT اپراتورهای ایران و ماتریس OEM روی دستگاه واقعی آزمایش شوند.
