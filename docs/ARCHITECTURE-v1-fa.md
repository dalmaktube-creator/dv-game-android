# معماری نسخه اولیه DV Game

## اصل سازگاری

DV Game موتور WireGuard را بازنویسی نمی‌کند. کتابخانه رسمی `com.wireguard.android:tunnel:1.0.20260102`، `GoBackend`، `VpnService` و parser رسمی کانفیگ استفاده می‌شوند. تفاوت محصول فقط در ورودی لینک‌محور و محدودکردن تونل به بازی‌های مورد تأیید پنل است.

## جریان داده

1. کاربر لینک HTTPS موجود `/sub/<id>` را وارد می‌کند.
2. اپ همان لینک را با `format=dvgame` فراخوانی می‌کند.
3. پنل پاسخ نسخه‌دار شامل وضعیت حساب، کانفیگ‌ها و کاتالوگ بازی می‌دهد.
4. اپ برنامه‌های launchable گوشی را با packageهای کاتالوگ تقاطع می‌دهد.
5. هیچ انتخاب دستی برنامه یا package name وجود ندارد.
6. قبل از parse، هر Included/ExcludedApplications دریافتی حذف و فقط package بازی تأییدشده تزریق می‌شود.
7. کانفیگ با AES/GCM و کلید Android Keystore رمز می‌شود.
8. `GoBackend` تونل را برقرار می‌کند و سپس بازی اجرا می‌شود.

## مرز امنیتی

این نسخه جلوی استفاده تصادفی از مرورگر و شبکه‌های اجتماعی را می‌گیرد. در دستگاه rootشده یا APK دست‌کاری‌شده تضمین مطلق وجود ندارد. مرحله بعد شامل device-bound key registration، Play Integrity، امضای نامتقارن catalog و تشخیص abuse در سرور است.

## سازگاری پنل

API جدید با wrapper افزوده می‌شود و ساخت Client، Subscription page، دانلود `.conf`، QR و استفاده در WireGuard رسمی تغییر نمی‌کنند.

## محدودیت انتشار

این شاخه Alpha است. پیش از Production باید handover شبکه، reboot/Always-on، lockdown، DNS/IPv6 leak، Doze و ماتریس OEM تست شود.
