# Alpha 11 — مجوز اعلان و امضای ثابت نسخه‌های آزمایشی

## دامنه تغییر

این نسخه هیچ تغییری در موتور، MTU، Keepalive، NAT، workers، DNS، مسیر پکت یا منطق handover موفق Alpha 10 ایجاد نمی‌کند.

## مجوز اعلان

روی Android 13 به بالا، وجود `POST_NOTIFICATIONS` در Manifest کافی نیست. `MainActivity` در اجرای برنامه مجوز runtime را درخواست می‌کند تا اعلان foreground و دکمه قطع اتصال در Notification Drawer دیده شوند.

## امضای ثابت CI

GitHub Actions فایل PKCS12 را از دو Repository Secret بازسازی می‌کند:

- `DV_GAME_CI_KEYSTORE_BASE64`
- `DV_GAME_CI_KEYSTORE_PASSWORD`

Alias ثابت: `dv-game-ci`

SHA-256 certificate fingerprint:

`3F:4A:9E:EB:1B:C8:A7:D6:A4:EA:C4:6A:E5:BC:EC:D0:9A:D6:1E:C2:D6:4E:63:63:B4:CC:19:AA:64:A8:5B:1C`

Workflow بدون این دو Secret عمداً fail می‌شود؛ بنابراین دیگر APK با debug key تصادفی منتشر نخواهد شد. پس از Build نیز امضای APK با `apksigner` چاپ و fingerprint ثابت کنترل می‌شود.

> این کلید فقط برای Alpha/Testing است و نباید برای Play Store یا نسخه Production استفاده شود. کلید Production باید جداگانه، آفلاین و با سیاست backup مناسب ایجاد شود.

## ارتقا

به‌دلیل تفاوت امضای Alpha 10 با کلید جدید، برای نصب Alpha 11 یک بار دیگر حذف و نصب لازم است. از Alpha 11 به بعد، تا زمانی که همین کلید CI حفظ شود، نسخه‌های آزمایشی باید بدون حذف برنامه Upgrade شوند.
