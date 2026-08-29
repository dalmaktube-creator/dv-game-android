# فاز ۱ — پایداری موتور و مسیر پکت

## ماشین حالت

`Idle → Preparing → Starting → Connected`

مسیرهای جانبی:

- `Connected → Reconnecting → Starting → Connected`
- `* → Stopping → Idle`
- خطای سیاست/اعتبار/مجوز → `Blocked`
- پایان تلاش‌های خطای شبکه/موتور → `Failed`

## سیاست بازیابی

- حداکثر ۵ تلاش در یک چرخه.
- backoff نمایی: ۱، ۲، ۴، ۸ و سقف ۱۵ ثانیه.
- هر تلاش دامنه Endpoint را دوباره و از طریق `Network.getAllByName` روی شبکه فیزیکی منتخب resolve می‌کند.
- تمام A recordهای متمایز نگه‌داری و بین تلاش‌ها چرخانده می‌شوند.
- هیچ hostname وارد JSON موتور نمی‌شود.

## handover شبکه

- `registerDefaultNetworkCallback` استفاده می‌شود.
- در `onAvailable` هیچ getter همگامی فراخوانی نمی‌شود؛ اطلاعات از `onCapabilitiesChanged` و `onLinkPropertiesChanged` گرفته می‌شوند.
- شبکه زیرین با `VpnService.setUnderlyingNetworks` به‌روزرسانی می‌شود.
- تغییر Wi-Fi/Cellular پس از debounce برابر ۷۵۰ میلی‌ثانیه، بازسازی کنترل‌شده موتور را آغاز می‌کند.

## تنظیمات کیفیت UDP

- Celluar: فاصله keepalive حداکثر ۱۵ ثانیه.
- Wi-Fi/Ethernet: فاصله keepalive حداکثر ۲۵ ثانیه.
- مقدار کمتر پنل حفظ می‌شود؛ صفر یا مقدار بیش از سقف با مقدار امن جایگزین می‌شود.
- MTU رابط TUN از MTU Endpoint بیشتر نمی‌شود و فعلاً سقف محافظه‌کارانه ۱۲۸۰ حفظ شده است.
- `workers=2`، `udp_timeout=10m` و `endpoint_independent_nat=false` خط مبنا هستند.
- MTU، workers و NAT در ساختار `PacketPathOptions` متمرکز شده‌اند تا در فاز آزمون کیفیت A/B شوند.

## پاک‌سازی و امنیت

- GoBackend و وابستگی WireGuard Android حذف شدند؛ مسیر تولید فقط libbox است.
- parser کوچک محلی، تعداد sectionها، کلیدهای ۳۲ بایتی، IPv4/CIDR، MTU، keepalive و Endpoint را اعتبارسنجی می‌کند.
- `StringIterator.len()` طول واقعی را برمی‌گرداند.
- اعلان foreground دکمه قطع اتصال دارد.
- خطاهای خام موتور به پیام فارسی قابل اقدام نگاشت می‌شوند.

## معیار پذیرش روی گوشی

1. اتصال و اجرای Mobile Legends روی Wi-Fi و Cellular.
2. سه بار جابه‌جایی Wi-Fi ↔ Cellular در lobby و حین match؛ بازیابی خودکار بدون بازکردن مجدد اپ.
3. ۱۵ دقیقه توقف در lobby و سپس شروع match بدون freeze ناشی از NAT.
4. مرورگر و Speedtest بیرون تونل باقی بمانند.
5. DNS/IPv4 leak برای پکیج بازی مشاهده نشود.
6. jitter بازی با Alpha 8 بدتر نشود؛ ICMP تنها شاخص کمکی است و نتیجه داخل بازی ملاک نهایی است.
