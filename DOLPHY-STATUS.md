# حالة Dolphy — لقطة مُتحقَّقة

> آخر تحديث: 2026-10-31، بعد تنفيذ P0 وP1 (الجزء الآلي) على `main`.
> كل رقم في هذا الملف مأخوذ من تشغيل فعلي، لا من تقدير.

---

## ما هو مُتحقَّق الآن

| البوابة | الأمر | النتيجة |
|---|---|---|
| اختبارات الوحدات | `:app:testDebugUnitTest :core:model:test :core:hal:test :core:security:test :rootd:test :bridge:flipper:test` | **43 اختباراً، 0 فشل** |
| بناء debug | `:app:assembleDebug` | ناجح |
| تحليل ساكن | `detekt` (كل الوحدات) | ناجح مع baseline |
| lint | `:app:lintVitalRelease` | ناجح |
| فحص أسرار | gitleaks على 20+ commit | لا تسريبات |
| CI | `Android CI` + `Security` | أخضر على `main` |

---

## P0 — أمن الإصدار (مكتمل)

- توقيع الإصدار إلزامي؛ لا رجوع إلى مفتاح debug (`docs/security/RELEASE_SIGNING.md`).
- `PluginSignature`: ECDSA P-256/SHA-256 على المصدر القانوني؛ تلاعب ⇒ رفض دائم،
  مفتاح غير موثوق/بلا توقيع ⇒ رفض إلا بموافقة صريحة في شاشة المعاينة.
- `PluginUrlPolicy`: HTTPS فقط، رفض `http://`، متابعة تحويلات يدوية (5 hops)
  ورفض أي تخفيض إلى HTTP.
- تحصين WebView للإضافات: بلا وصول للملفات/المحتوى، `MIXED_CONTENT_NEVER_ALLOW`،
  بلا نوافذ أو تشغيل تلقائي، تنقّل HTTPS فقط.
- حذف `READ_EXTERNAL_STORAGE` غير المستخدم؛ الأذونات الخطرة المتبقية تُطلب
  وقت التشغيل مع تبرير.
- **قيود الشبكة المحلية:** كل طلبات LAN تمرّ عبر `LocalHttpPolicy.requireLocal`
  (راوترات، مسح LAN، كاميرات، طباعة IPP، بث TV، اختبار نطاق)، و
  `network_security_config.xml` يقصر الثقة على CAs النظام.
- Ktor المحلي: `LocalRequestPolicy` + ربط LAN + حد إطار + CSP + إخفاء الإدخال.

## P1 — البنية والبوابات (مكتمل آلياً)

- `detekt` على كل الوحدات مع `config/detekt/detekt.yml` و`detekt-baseline.xml`
  لكل وحدة: 1085 ملاحظة قديمة مُسجّلة، وأي مخالفة جديدة تُفشل CI.
- كتالوج الإصدارات: صفر إحداثيات مكتوبة يدوياً في `app/build.gradle.kts`.
- إزالة تجاوزات `dexOptions` و`kotlin.jvm.target.validation.mode` (بناء كامل مُتحقَّق).
- Dependabot أسبوعي مجمّع + فحص gitleaks على كل push/PR.
- وحدات مستقلة ومُختبرة: `core:model`, `core:hal`, `core:security`, `rootd`,
  `bridge:flipper`.
- Driver DSL و`RootdService` وترجمة عربية كاملة (203 سلسلة) موجودة.

## P2 — ما تبقّى فعلياً (غير منفَّذ)

1. **عزل الإضافات في عملية منفصلة** — اليوم هي permission model داخل نفس العملية
   (`docs/security/P2-PLAN.md`).
2. **`MainActivity` (~1600 سطر)** — تفكيك تدريجي إلى ViewModels/شاشات؛ حالياً
   مُسجّلة في baseline detekt ولا تُفشل البناء.
3. **AIDL daemon لـ `rootd`** — `PrivilegedBroker` يعمل كوسيط في الذاكرة.
4. **App Bundle / ABI splits** — الإصدار يوزّع APK عالمي بأربع معماريات؛ التقسيم
   يغيّر أسماء الملفات ويكسر تحديثات المستخدمين الحاليين، فلم يُفعَّل.
5. **Baseline profile** — يحتاج جهازاً/محاكياً لقياس البدء، لم يُنفَّذ هنا.

## P3 — التوثيق (مكتمل)

- `SECURITY.md` (سياسة الإبلاغ والنطاق)، `CONTRIBUTING.md` (حلقة التطوير
  والبوابات)، `docs/ARCHITECTURE.md` (حدود الوحدات وتدفّق البيانات والبوابات)،
  وتحديث `CHANGELOG.md`.

---

## ملاحظات صريحة

- إضافات الكتالوج الرسمي **غير موقّعة**؛ مسار المتجر يمرّر `allowUntrusted = true`
  لأن المحتوى منسّق داخل التطبيق ويُنزَّل عبر HTTPS. توقيع الكتالوج يحتاج مفتاحاً
  خاصاً لا يوجد في المستودع.
- دورة إذن USB الكاملة موجودة في `HardwareRuntime` لكنها لم تُختبر على جهاز
  حقيقي في هذه الجلسة (لا جهاز متصل).
- أي بند P2 يتطلب تغييرات واسعة أو جهازاً حقيقياً؛ لذلك لم يُعلَّم كمكتمل.
