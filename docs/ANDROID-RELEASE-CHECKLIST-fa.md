# چک‌لیست اجباری انتشار DV Game Android

هیچ نسخه‌ای بدون تکمیل موارد P0 منتشر نشود.

## P0 — Build و نصب

- [ ] Debug APK در CI ساخته می‌شود.
- [ ] Release AAB با کلید واقعی و امن ساخته می‌شود.
- [ ] نصب تمیز روی Android 8، 10، 12، 14، 15 و 16 آزمایش شده است.
- [ ] Upgrade از نسخه قبلی بدون حذف داده آزمایش شده است.
- [ ] `arm64-v8a` و ABIهای هدف داخل Bundle وجود دارند.
- [ ] native libraryها با دستگاه 16 KB page سازگارند.
- [ ] Manifest merged بررسی شده و VPN Service `exported=false` است.
- [ ] فقط Componentهای ضروری exported هستند.
- [ ] `allowBackup=false` و نصب internal-only بررسی شده‌اند.

## P0 — تونل

- [ ] مجوز `VpnService.prepare()` صحیح مدیریت می‌شود.
- [ ] Connect/Disconnect روی Main Thread اجرا نمی‌شود.
- [ ] Double tap دو اتصال هم‌زمان ایجاد نمی‌کند.
- [ ] Service destruction فوراً State را `Idle` می‌کند.
- [ ] یک بازی انتخاب‌شده از VPN عبور می‌کند.
- [ ] Chrome، Telegram و اپ انتخاب‌نشده از VPN عبور نمی‌کنند.
- [ ] فهرست خالی باعث Full-tunnel نمی‌شود.
- [ ] حذف بازی بعد از انتخاب با خطای کنترل‌شده مدیریت می‌شود.
- [ ] IPv4 و IPv6 هر دو تست شده‌اند.
- [ ] DNS leak وجود ندارد.
- [ ] Endpoint دامنه‌ای و IP مستقیم هر دو کار می‌کنند.
- [ ] MTU 1280 و MTU پنل جداگانه تست شده‌اند.

## P0 — پایداری

- [ ] چرخش صفحه State را تغییر نمی‌دهد.
- [ ] Background/Foreground اتصال را قطع نمی‌کند.
- [ ] Wi‑Fi به LTE/5G و برعکس آزمایش شده است.
- [ ] Airplane mode و بازگشت شبکه آزمایش شده است.
- [ ] Screen off حداقل ۳۰ دقیقه آزمایش شده است.
- [ ] Doze و Battery Saver آزمایش شده‌اند.
- [ ] Force stop و Process kill رفتار مشخص دارند.
- [ ] Reconnect دارای backoff و سقف تلاش است.
- [ ] ۲۴ ساعت اتصال پیوسته بدون leak یا crash اجرا شده است.

## P0 — امنیت

- [ ] PrivateKey، PresharedKey و token در Log دیده نمی‌شوند.
- [ ] Subscription URL در UI و گزارش خطا mask می‌شود.
- [ ] Config ذخیره‌شده با Android Keystore رمز شده است.
- [ ] HTTP عمومی پیش‌فرض خاموش است.
- [ ] HTTPS→HTTP redirect رد می‌شود.
- [ ] Response size، timeout و redirect count محدودند.
- [ ] parser محلی، sectionها، کلیدها، IPv4/CIDR، Endpoint، MTU و Keepalive را fail-closed اعتبارسنجی می‌کند.
- [ ] Payload مخرب یا بسیار بزرگ fail-closed است.
- [ ] Screenshot صفحه Secret مسدود است.
- [ ] Open-source license notices داخل اپ وجود دارد.

## P1 — کیفیت

- [ ] UI حالت‌های Preparing، Starting، Connected، Reconnecting، Blocked و Failed را جدا نمایش می‌دهد.
- [ ] آخرین Handshake، RX و TX نمایش داده می‌شوند.
- [ ] Polling آمار در Background متوقف می‌شود.
- [ ] بازی‌های بدون CATEGORY_GAME با جست‌وجوی All apps قابل انتخاب‌اند.
- [ ] انتخاب Exit و نمایش Ping وجود دارد.
- [ ] خطاهای Backend متن فارسی قابل اقدام دارند.
- [ ] RTL، فونت بزرگ و Accessibility آزمایش شده‌اند.
- [ ] Dark mode و صفحه‌های کوچک بدون overflow هستند.

## مقایسه با WireGuard رسمی

روی یک گوشی، یک شبکه، یک کانفیگ و یک Endpoint:

- [ ] Median ping اختلاف معنادار ندارد.
- [ ] p95 jitter اختلاف معنادار ندارد.
- [ ] Packet loss بیشتر نیست.
- [ ] Throughput حداقل ۹۵٪ اپ رسمی است.
- [ ] مصرف CPU و باتری غیرعادی نیست.
- [ ] زمان Connect و Reconnect ثبت و مقایسه شده است.

## ماتریس دستگاه پیشنهادی

- [ ] Google Pixel / Android خام
- [ ] Samsung One UI
- [ ] Xiaomi HyperOS
- [ ] Huawei یا Honor
- [ ] OnePlus/Oppo/Realme
- [ ] یک گوشی ضعیف با RAM محدود

## انتشار

- [ ] versionCode افزایشی است.
- [ ] Release notes دقیق است.
- [ ] Privacy Policy و Play Data Safety تکمیل شده‌اند.
- [ ] Crash reporting هیچ Secretی جمع نمی‌کند.
- [ ] Internal test موفق است.
- [ ] Closed test موفق است.
- [ ] Rollback و توقف Subscription ناسازگار تعریف شده است.
