# سرویس نتینو (Cloudflare Worker)

سه کار انجام می‌دهد:

| آدرس | کار |
|---|---|
| `GET /sub` | لیست رایگان نتینو (Base64، لینک اشتراک برای v2rayNG، v2rayN، Hiddify، NekoBox و …) |
| `GET /sub/plain` | همان لیست، هر کانفیگ در یک خط |
| `POST /results` | نتیجه‌های ناشناس برنامه: کدام کانفیگ روی کدام اپراتور کار کرد |
| `POST /issue` | گزارش مشکل از داخل برنامه؛ به تلگرام شما فرستاده می‌شود |

لیست از GitHub خوانده می‌شود (هر ۵ دقیقه تازه)، پس همیشه با لیست اصلی یکی است. برای درخواست‌هایی که از ایران می‌آیند،
کانفیگ‌هایی که روی همان اپراتور (شمارهٔ AS) برای دیگران کار کرده‌اند اول می‌آیند. IP هیچ‌کس ذخیره نمی‌شود؛ فقط
«اپراتور + شناسهٔ کانفیگ + تعداد موفق/ناموفق»، و نتیجه‌های قدیمی‌تر از ۳ روز پاک می‌شوند.

## راه‌اندازی (یک بار، حدود ۱۰ دقیقه)

همه رایگان است (پلن رایگان Cloudflare کافی است).

1. در [dash.cloudflare.com](https://dash.cloudflare.com) حساب بسازید.
2. Node.js را نصب کنید و در همین پوشه (`tools/worker`):
   ```
   npx wrangler login
   npx wrangler d1 create netino
   ```
   دستور دوم یک `database_id` چاپ می‌کند: آن را در `wrangler.toml` به‌جای `PUT-THE-ID-FROM-wrangler-d1-create-HERE` بگذارید.
3. جدول را بسازید و سرویس را منتشر کنید:
   ```
   npx wrangler d1 execute netino --remote --file schema.sql
   npx wrangler deploy
   ```
   آدرسی مثل `https://netino.<نام-حساب>.workers.dev` چاپ می‌شود.
4. **گزارش مشکل به تلگرام:**
   - در تلگرام با [@BotFather](https://t.me/BotFather) یک ربات بسازید و توکنش را بگیرید.
   - ربات را به کانال یا گروه خصوصی خودتان اضافه کنید (یا به خودش پیام بدهید) و شناسهٔ آن چت را بگیرید (مثلاً با [@userinfobot](https://t.me/userinfobot)؛ برای کانال معمولاً با `-100` شروع می‌شود).
   - سپس:
   ```
   npx wrangler secret put TG_TOKEN
   npx wrangler secret put TG_CHAT
   ```
5. **دامنهٔ خودتان (پیشنهادی):** `workers.dev` در ایران گاهی فیلتر است. در Cloudflare یک دامنه اضافه کنید و در
   Workers ← netino ← Settings ← Domains & Routes یک Custom Domain مثل `sub.your-domain.com` بسازید.
6. **معرفی به برنامه‌ها:** در GitHub: مخزن ← Settings ← Secrets and variables ← Actions ← Variables ← `NETINO_API` =
   آدرس سرویس (مثلاً `https://sub.your-domain.com`، بدون `/` آخر). در به‌روزرسانی بعدی لیست (حداکثر ۲ ساعت) در
   `api.txt` شاخهٔ configs قرار می‌گیرد و برنامه‌ها (اندروید و ویندوز) خودشان پیدایش می‌کنند. برای عوض کردن دامنه
   هم کافی است همین متغیر را عوض کنید؛ به‌روزرسانی برنامه لازم نیست.

لینک اشتراک برای کانال تلگرام: `https://sub.your-domain.com/sub`
