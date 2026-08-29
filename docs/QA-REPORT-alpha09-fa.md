# گزارش بازبینی Alpha 10 — فازهای ۰ و ۱ و Hotfix چرخه شبکه

## انجام‌شده در محیط محلی

- تطبیق دو آرشیو `0.2.0-alpha.8` و `feat/app-v1`: هر دو ۲۹ فایل و بدون اختلاف بودند.
- کنترل نسخه ورودی: `versionCode=10` و `versionName=0.2.0-alpha08`.
- اسکن تعادل delimiterهای ۱۸ فایل Kotlin.
- کنترل نبود مسیر اجرایی GoBackend؛ parser رسمی WireGuard موقتاً حفظ شده است.
- کنترل نبود statusهای منسوخ در کد.
- parse فایل GitHub Actions و کنترل trigger خودکار، جلوگیری از job تکراری PR، hash ثابت libbox و tag Alpha 10.
- کنترل ۳۲ بایتی بودن کلیدهای تست.
- کنترل نبود الگوی token/private key واقعی خارج از تست و مستندات.
- کنترل whitespace روی diff.

## تست‌های افزوده‌شده

- keepalive امن Cellular/Wi-Fi.
- backoff نمایی با سقف ۱۵ ثانیه.
- چرخش A recordها.
- تفکیک خطاهای retryable و blocked.
- عدم نمایش خطای خام موتور.
- اجباری بودن IPv4 عددی در JSON نهایی peer.
- حذف hostname از کانفیگ نهایی.
- حفظ همه IPv4های resolveشده و حذف موارد تکراری.
- قید `tunMtu <= endpointMtu` و خط مبنای workers/NAT.
- رد Peer چندگانه و کلید امنیتی تکراری.

## محدودیت این گزارش

محیط محلی Android SDK، Gradle و AAR libbox ندارد؛ بنابراین build واقعی در این محیط اجرا نشد. اولین push کد باید GitHub Actions را اجرا کند و فقط پس از سبزشدن `testDebugUnitTest` و `assembleDebug`، APK Alpha 10 برای تست گوشی استفاده شود.

## آزمون الزامی گوشی

1. اتصال اولیه با همان لینک و بازی موفق Alpha 8.
2. ورود lobby، ۱۵ دقیقه idle و شروع match.
3. سه بار Wi-Fi ↔ Cellular در lobby و حداقل یک بار حین match.
4. Airplane mode کوتاه و بازگشت شبکه.
5. بررسی اینکه مرورگر و Speedtest IP عادی را حفظ می‌کنند.
6. مقایسه latency، jitter داخل بازی، packet loss و مصرف باتری با Alpha 8.
