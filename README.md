# 📱 GSM Cloud Relay Broker & Web Terminal (v2.7.1)

نظام سحابي متكامل ومتقدم لتحويل هاتف أندرويد مزود بشريحة SIM إلى بوابة اتصال خلوي سحابية ذكية (GSM Cloud Gateway)، مع هاتف طرفي متكامل عبر الويب (Web Terminal) ولوحة مراقبة وتحكم لحظية (Live Monitoring Dashboard).

[![Deploy to Render](https://render.com/images/deploy-to-render-button.svg)](https://render.com)

---

## 🌟 أبرز مميزات النظام (الإصدار v2.7.1):

1. **جسر النقل الصوتي عالي الحساسية (Smart High-Gain Acoustic Bridge):**
   - نقل فوري لصوت المكالمة الخلوية GSM إلى الهاتف الطرفي عبر مصادر `VOICE_RECOGNITION` و `UNPROCESSED` غير الخاضعة لكتم الصدى (Zero Echo-Cancellation Muting).
   - مضخم صوتي رقمي برمجياً (+16dB Gain) مع محدد صوت ناعم (Soft Cubic Limiter).
2. **محرك التعافي الذاتي من الصمت (Self-Healing Source Hunter):**
   - فحص مستمر لسعة الصوت في كل حزمة، والتبديل التلقائي اللحظي بين 6 مصادر صوتية دون قطع المكالمة في حال حدوث أي كتم من الهاردوير.
3. **وضع الخصوصية الصامت للبلوتوث (Silent Bluetooth SCO Bridge):**
   - إمكانية كتم صوت الغرفة التي يوجد بها الهاتف الوسيط بنسبة 100% وتحويل مسار الصوت بالكامل إلى أي سماعة أو وصلة بلوتوث.
4. **لوحة تحكم ومراقبة حية محمية برقم سري (Live Dashboard):**
   - محمية برقم سري مشفر (`527149`).
   - عرض لحظي للهواتف الطرفية والوسيطة المتصلة، نسبة شحن البطارية (Battery %)، نوع الشبكة، واسم شركة الاتصالات (Carrier).
   - مراقبة مستوى الصوت (VU Meters) ونبضات الـ Ping/Latency.
5. **الاتصال الداخلي بين الهواتف الطرفية (Internal Intercom / Extensions):**
   - كل هاتف طرفي يحمل رقم تحويلة ثابت (مثل `#101` و `#102` و `#105`).
   - إمكانية الاتصال الصوتي الداخلي المباشر عالي النقاء بين أي هاتفين طرفيين بدون استخدام الشريحة الخلوية.
6. **بث نغمة وصوت فحص تجريبي (Audio Test Stream):**
   - فحص جودة واستقرار الاتصال الصوتي في أي وقت بنغمات شيم متناسقة قبل إجراء المكالمات الخلوية.
7. **إبقاء الشاشة مضاءة ومنع انقطاع الاتصال (Keep-Alive):**
   - قفل المعالج والواي فاي (WAKELOCK & WIFILOCK) لضمان العمل 24/7 دون نوم الهاتف.

---

## 🚀 النشر التلقائي السحابي على Render (Continuous Deployment):

المستودع مجهز بالكامل بملف `render.yaml` و `package.json` للنشر التلقائي على منصة **Render**:

### خطوات الربط مع Render لأول مرة:
1. سجل دخولك على [Render Dashboard](https://dashboard.render.com).
2. اضغط على **`New +`** ثم اختر **`Web Service`**.
3. اختر ربط مستودع GitHub وحدد المستودع:
   ```text
   https://github.com/modyforex3-blip/relay2
   ```
4. اترك الإعدادات الافتراضية كما هي:
   - **Environment:** `Node`
   - **Build Command:** `npm install`
   - **Start Command:** `node server.js`
   - **Plan:** `Free`
5. اضغط **`Create Web Service`**.
6. سيبدأ Render بالبناء التلقائي وتشغيل السيرفر خلال أقل من دقيقة، ومع كل `git push` على فرع `main` سيقوم Render بإعادة النشر والمزامنة تلقائياً!

---

## 📱 تحميل تطبيق الأندرويد للهاتف الوسيط:

- **أحدث إصدار مباشر:** [`GSM-Cloud-Relay-Gateway-v2.7.1.apk`](GSM-Cloud-Relay-Gateway-v2.7.1.apk)
- **ملف مضغوط:** [`GSM-Cloud-Relay-Gateway-v2.7.1.zip`](GSM-Cloud-Relay-Gateway-v2.7.1.zip)
- **شهادات التوقيع الرقمي:** موقّع بشهادات أندرويد الرسمية `v1 + v2 + v3`.

---

## 🔄 أداة المزامنة التلقائية مع GitHub (Auto-Sync Script):

لتحديث المستودع ومزامنة كل التعديلات وملفات الـ APK الجديدة مع GitHub و Render بضغطة واحدة:
- في ويندوز: اضغط مرتين على الملف:
  ```batch
  sync-to-github.bat
  ```
أو عبر PowerShell:
  ```powershell
  .\sync-to-github.ps1
  ```

---

## 🔐 معلومات الحماية والوصول:

- **الرقم السري للوحة التحكم (Dashboard PIN):** `527149`
- **الغرفة الافتراضية (Room):** `GSM-1001`
- **المنفذ الافتراضي:** `5050` (أو منفذ البيئة التلقائي `$PORT` في Render)
