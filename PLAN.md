# Calibrate — תוכנית עבודה ומעקב התקדמות

אפליקציית Android TV שבודקת אוטומטית את שרשרת התמונה, מציגה תבניות בדיקה מדויקות ב-4K, ומדריכה בכיול
ה-Xiaomi TV S Mini LED 2025 65" (L65MA-SPLME). רצה על Homatics Box R 4K Plus (Amlogic S905X4, Android TV 14, API 34).

- Package: `io.github.kesemb2.calibrate` — קבוע לתמיד.
- סטאק: כמו Glass Home — AGP 9.4, Kotlin 2.4, Compose foundation (רכיבי פוקוס משלנו, לא tv-material), Media3
  ExoPlayer, SharedPreferences, org.json, R8 מלא, בלי ספריות native, חתימה קבועה ב-CI ופרסום Release.

## מצב

| שלב | תוכן | מצב |
|---|---|---|
| 0 | שלד, CI, חתימה, Release, מסך אבחון (חלק 1 – בדיקות סטטיות) | ✅ נבדק על הטלוויזיה (build-1) |
| 1 | צינור התבניות (CI + ffmpeg + ffprobe), הורדה, נגן 4K נקי, תבניות SDR | ✅ נבדק על הטלוויזיה (build-10, patterns-v2) |
| 2 | תבניות HDR10, בדיקת 23.976→24Hz מעשית, מסך סיכום ירוק/צהוב/אדום | **בעבודה** |
| 3 | מנוע האשף (JSON), זרימת SDR מלאה, שמירת ערכים | — |
| 4 | זרימות HDR10 ו-Dolby Vision, סיכום וייצוא (QR / טקסט) | — |
| 5 | ניסיוני: תבניות Dolby Vision (Profile 8.1 דרך dovi_tool) | אושר לניסיון |
| 6 | ליטוש: RTL, נגישות פוקוס, תקציבי גודל וביצועים | — |

כל שלב נגמר ב-APK חתום שמותקן דרך Downloader מ-
`https://github.com/kesemb2/tvcali/releases/latest/download/calibrate.apk`.

---

## בדיקת ההנחות הטכניות

### 1. הצגת 4K פיקסל-לפיקסל — מה נכון על ה-Homatics

מה כבר מדדנו ב-Glass Home (שלב 0 שם): **משטח ה-UI הוא 1920×1080** (density 2.0), ומצב התצוגה
(הפלט ב-HDMI) הוא **3840×2160**. כלומר כל מה שמצויר ב-Compose/Canvas/Bitmap עובר upscale פי 2.

האפשרויות:

| דרך | 4K אמיתי? | HDR10 עם מטא-דאטה? | הערה |
|---|---|---|---|
| ציור ב-Compose / Canvas | ❌ 1080p ‏upscale | ❌ | שכבת ה-OSD של Amlogic היא 1080p |
| `preferredDisplayModeId` = 3840×2160 | ❌ | ❌ | משנה רק את מצב הפלט, לא את רזולוציית ה-UI |
| SurfaceView עם `setFixedSize(3840,2160)` וציור Bitmap | לא מובטח | ❌ | מורכב על ידי HWC; ב-Amlogic בדרך כלל דרך אותה שכבת OSD |
| **וידאו HEVC דרך MediaCodec (Media3) ל-SurfaceView** | ✅ | ✅ | עובר ב-**video plane** של המפענח, באותו מסלול כמו Netflix/Plex |

**בחירה: וידאו HEVC ‏4K שמתנגן ב-Media3 ExoPlayer על SurfaceView** (לא TextureView — TextureView מעביר
דרך GPU ושכבת ה-UI ומאבד גם 4K וגם HDR). זו הדרך היחידה שבה (א) כל פיקסל של הקובץ מגיע לפלט בלי
scaling, (ב) הטלוויזיה מקבלת אות HDR10 אמיתי עם InfoFrame של מטא-דאטה סטטית (Mastering Display +
MaxCLL/MaxFALL) מתוך ה-SEI של הקובץ, ולכן נכנסת למצב התמונה של HDR, (ג) התאמת קצב פריימים פועלת
כמו בתוכן אמיתי.

מגבלות שצריך להכיר (ואיך מטפלים בהן):
- **4:2:0** — מפענחי החומרה תומכים רק ב-4:2:0, אז הכרומה ברזולוציה חצי. ה-luma (הבהירות) מדויקת
  פיקסל-לפיקסל, וזה מה שחשוב ל-PLUGE, clipping, גאמה, sharpness ו-overscan. בתבניות צבע נשתמש בשטחים
  ובקצוות מיושרים לזוגות פיקסלים. זה בדיוק מה שקורה בכל תוכן אמיתי.
- **דחיסה** — תבנית סטטית מקודדת ב-x265 עם QP נמוך מאוד (כמעט lossless; את ה-lossless האמיתי
  `transquant-bypass` לא נשתמש, כי לא כל מפענח חומרה תומך בו). ה-CI **מפענח בחזרה ובודק ערכי קוד**
  בנקודות מפתח (למשל שתיבות PLUGE הן בדיוק 16/12/20 ב-8 ביט או 64/48/80 ב-10 ביט), לא רק ffprobe.
- **Limited range** — וידאו הוא תמיד 16–235 (64–940 ב-10 ביט). התבניות מוגדרות בערכים האלה, והאשף
  יבדוק שהגדרות ה-HDMI בקופסה ובטלוויזיה (RGB/YCbCr, Limited/Full) לא חותכות שחור.
- ההוראות שמופיעות ב-OK הן שכבת UI 1080p מעל הווידאו — זה בסדר, כי כשהן מוסתרות המסך הוא 100% וידאו.

### 2. HDR על ה-Homatics

- `Display.Mode.getSupportedHdrTypes()` (API 34, מחליף את `getHdrCapabilities().supportedHdrTypes`) מחזיר
  DV / HDR10 / HLG / HDR10+ לפי ה-EDID שהקופסה רואה — **אחרי** ה-Hue Sync Box. אם DV חסר כאן, הסיבה
  כמעט תמיד ב-Sync Box (EDID/מצב passthrough), בכבל, או בהגדרת HDMI בטלוויזיה (HDMI 2.1 / Enhanced format
  בכניסה 2). זו בדיוק האזהרה שתבקש.
- `Display.getHdrCapabilities()` מחזיר גם את **ערכי הבהירות שהטלוויזיה מצהירה ב-EDID** (desired max /
  max average / min luminance) — בדיקה אוטומטית נוספת: אם הערכים ריקים או שונים ממה שה-Xiaomi אמורה
  להצהיר, ה-Sync Box כנראה מחליף את ה-EDID.
- `DisplayManager.getHdrConversionMode()` (API 34) — האם המערכת מעבירה HDR כמו שהוא (passthrough) או
  ממירה. זו "התאמת טווח דינמי" ברמת Android.
- `MediaCodecList` — קיום מפענח `video/dolby-vision` (ובאילו פרופילים), HEVC Main10, HDR10+.
- ההגדרות הייחודיות של Amlogic/Homatics (מדיניות HDR "לפי המקור/לפי התצוגה", מצב צבע HDMI, AFR של
  Amlogic) לא נגישות באפי ציבורי. בשלב 0 תריץ כמה פקודות adb (רשימה למטה), ולפיהן נחליט מה אפשר לקרוא
  אוטומטית ומה האפליקציה תשאל אותך.

### 3. התאמת קצב פריימים

- `DisplayManager.getMatchContentFrameRateUserPreference()` (API 31, ציבורי) — מחזיר Never / Seamless
  only / Always, כלומר את ההגדרה של Android TV. ב-Homatics עשויה להיות בנוסף הגדרת AFR משלה — ניבדק
  ב-adb.
- **בדיקה מעשית**: האפליקציה מנגנת קליפ 3840×2160 ב-23.976fps ב-Media3, מאזינה ל-
  `DisplayManager.DisplayListener` ומודדת את `display.mode.refreshRate` לפני, במהלך ואחרי. תוצאה:
  ✅ עבר ל-23.976/24Hz · 🟡 לא עבר אבל ההגדרה כבויה (מסבירים איך להדליק) · 🔴 ההגדרה דולקת ולא עבר.
  נבדוק גם 25 ו-50 (תוכן ישראלי/אירופאי) ונספור frame drops מ-Media3 בזמן הניגון.

### 4. Dolby Vision — האם אפשר לייצר תבניות

- **אין דרך רשמית וחינמית**: הכלים של Dolby לקידוד (Dolby Encoding Engine) ותבניות הבדיקה הרשמיות מגיעים
  ברישיון בתשלום.
- **יש דרך מעשית בקוד פתוח**: `dovi_tool` (רישיון MIT) יודע לייצר RPU של **Profile 8.1** (שכבת בסיס
  HDR10 + מטא-דאטה DV) מתוך JSON, ולהזריק אותו ל-HEVC. ה-MP4 מקבל `dvcC` ו-Media3 בוחר את מפענח ה-DV.
  זה שימוש פרטי בקבצים שאנחנו מייצרים, לא עקיפת הגנה — אבל זה לא "רשמי" ואין ערובה שה-Homatics יעביר
  אותו כ-DV (Amlogic תומך ב-P8.1, אז סביר שכן).
- **ההמלצה שלי**: זרימת ה-DV באשף מתבססת על הדרכה + ניגון תוכן DV מוכר (Netflix/Disney+), ושלב 5 יהיה
  ניסוי נפרד ל-P8.1. אם הטלוויזיה תציג את סמל ה-DV — נוסיף את התבניות לזרימה; אם לא — נוותר בלי לפגוע
  בשאר.

### 5. בדיקות אוטומטיות נוספות שאני מציע

1. ערכי ה-EDID של בהירות (סעיף 2) — סימן לכך שה-Sync Box מחליף EDID.
2. מצבי תצוגה: האם קיימים 23.976/24/25/50/59.94 ב-4K, והאם 4K@60 קיים (אחרת ה-Sync Box / הכבל מגבילים
   רוחב פס).
3. מפענחים: DV (פרופילים 5/7/8), HEVC Main10, AV1 10-bit, VP9 Profile 2.
4. **Frame drops** בזמן תבנית התנועה — אם יש drops, בדיקת judder לא אמינה.
5. **בדיקת 10-bit/banding אובייקטיבית למחצה**: שתי רמפות זו מעל זו (10 ביט מול 8 ביט). אם הן נראות זהות
   — משהו בשרשרת קוטם ל-8 ביט.
6. אחרי שהאשף בוחר "Filmmaker" — השוואה מול מה שהקופסה מדווחת (למשל אם HDR מומר ל-SDR).
7. גרסת Android / קושחה של הקופסה, ותזכורת אם השתנתה מאז הכיול הקודם ("הכיול נשמר לפי גרסה").

---

## השלבים בפירוט

### שלב 0 — שלד ואבחון
- Gradle (אותן גרסאות ומוסכמות כמו Glass Home), Manifest עם `INTERNET` בלבד, banner, אייקון.
- `.github/workflows/build.yml`: בדיקות יחידה, APK חתום, תקציב 10MB, בלי `.so`, אימות חתימה, Release.
- מסך אבחון ראשון (Part 1, החלק הסטטי): מצב נוכחי, כל המצבים הנתמכים, סוגי HDR, ערכי EDID, HDR conversion,
  match content frame rate, מפענחים. כל שורה עם צבע וטקסט הסבר.
- עיצוב: אותם tokens של Glass Home (רקע `#05060A`, Inter + Noto Sans Hebrew, פוקוס לבן, פינות רציפות),
  עברית/RTL לפי שפת המכשיר.
- **מה תבדוק בטלוויזיה**: שהאפליקציה עולה, שהמסך מציג 3840×2160, את רשימת סוגי ה-HDR (האם DV מופיע),
  ושהניווט בשלט עובד.

### שלב 1 — צינור התבניות ו-SDR
- `patterns/` ב-repo: מחולל Python (numpy) שמייצר כל פריים כתמונת 16-bit מדויקת → ffmpeg/x265 (HEVC Main10,
  BT.709, limited). כל קובץ: לולאה של 10–30 שניות.
- Workflow נפרד `patterns.yml` (ידני / כשמשתנה `patterns/`): מייצר, מריץ `ffprobe` על כל קובץ (רזולוציה,
  pix_fmt, primaries/transfer/matrix, fps, SEI), מפענח פריימים ובודק ערכי קוד בנקודות מפתח, ומפרסם Release
  בשם `patterns-vN` עם `manifest.json` (שם, גודל, SHA-256, גרסה). ה-Release הזה לא נמחק ב-prune של ה-APK.
- באפליקציה: הורדה בפתיחה הראשונה עם התקדמות, אימות SHA-256, שמירה ב-`filesDir` (בלי הרשאות אחסון),
  עדכון כשה-manifest משתנה.
- נגן: Activity מלא, SurfaceView, בלי שום UI כשהתבנית מוצגת; OK מציג/מסתיר הוראות; ◀ ▶ מחליפים תבנית.
- תבניות SDR: PLUGE, White clipping, סולם אפורים 11 שלבים + רמפה (8 מול 10 ביט), פסי צבע (75%/100%),
  Sharpness/overshoot (קווים בני פיקסל אחד וקצוות), Overscan (מסגרות ב-0/1/2/… פיקסלים מהקצה).
- **מה תבדוק**: ש-Overscan מציג את מסגרת הפיקסל החיצונית, שהקווים בני פיקסל אחד חדים ולא מטושטשים (הוכחה
  ל-4K native), ושב-PLUGE אפשר להבחין בהבדל.

### שלב 2 — HDR10 ובדיקה מעשית
- תבניות HDR10 (PQ, BT.2020, 10-bit) עם `master-display` ו-`max-cll` — בגרסת 1000 ניט ובגרסת 4000 ניט:
  Black clipping, White clipping/tone mapping (מדרגות 100…10000 ניט), סולם אפורים, ריבוע לבן זז (blooming),
  כתוביות על שחור, starfield, pan ב-23.976 לבדיקת judder ו-MEMC.
- ה-CI בודק ב-ffprobe `-show_frames` שיש "Mastering display metadata" ו-"Content light level metadata"
  עם הערכים הנכונים, ושה-transfer הוא `smpte2084` וה-primaries `bt2020`.
- בדיקת 23.976→24Hz (ו-25/50) עם DisplayListener, וספירת drops.
- **מסך הסיכום** של חלק 1: ירוק/צהוב/אדום, ולכל צהוב/אדום — מה לתקן ואיך. דברים שאי אפשר לקרוא (למשל
  AFR של Homatics) — האפליקציה שואלת אותך ושומרת את התשובה.
- **מה תבדוק**: שבזמן תבנית HDR הטלוויזיה מציגה תג HDR ועוברת למצב התמונה של HDR, ושבדיקת 24Hz מראה
  ירוק (ובתפריט המידע של הטלוויזיה רואים 24Hz).

### שלב 3 — מנוע האשף וזרימת SDR
- `app/src/main/assets/wizard.json` (ואפשר לדרוס אותו מקובץ ב-Release, כדי לעדכן בלי APK): לכל שלב — כותרת,
  מה לעשות, נתיב תפריט ברירת מחדל, ערך מומלץ, תבנית רקע, "נכון" מול "שגוי", בעברית ובאנגלית.
- מסך שלב: התבנית ברקע (וידאו), כרטיס זכוכית עם ההוראות, שדה "נתיב בתפריט" שאפשר לערוך (נשמר לך),
  שדה "הערך שבחרתי", וכפתורים **בוצע / דלג / לא מוצא את ההגדרה**. OK על הרקע = הסתרת הכרטיס.
- כל זרימה מתחילה בניגון תוכן מאותו סוג כדי שהטלוויזיה תיכנס למצב הנכון.
- **מה תבדוק**: את כל זרימת SDR מתחילה ועד סוף, שערכים נשמרים אחרי יציאה וחזרה.

### שלב 4 — HDR10, Dolby Vision, סיכום וייצוא
- זרימות HDR10 ו-DV (DV: מצב Dolby Vision Dark, Dolby Vision IQ, וניגון תוכן DV מוכר בהנחיה).
- מסך סיכום לכל פורמט, וייצוא: QR (מקודד כטקסט) + טקסט מלא. QR עם מחולל קטן משלנו או ZXing core.

### שלב 5 (ניסיוני) — תבניות DV P8.1
- `dovi_tool generate` ב-CI עם L1 סטטי, mux ל-MP4 עם `dvcC`, ובדיקה בטלוויזיה אם מופיע סמל DV.

### שלב 6 — ליטוש
- מעבר RTL מלא, פוקוס, אנימציות, בדיקת גודל APK וזיכרון.

---

## adb גם לטלוויזיה עצמה (Xiaomi)

חיבור adb לטלוויזיה פותח משהו שהקופסה לא יכולה: לראות **מה הטלוויזיה באמת מקבלת** בכניסת HDMI2
(רזולוציה, קצב, סוג HDR, אולי גם מצב התמונה הפעיל). שני שימושים:
1. עכשיו, בשלב 0: לאסוף מידע ולגלות אילו הגדרות תמונה של Xiaomi חשופות (`settings`, `dumpsys`).
2. אם יתברר שזה עובד: האפליקציה על ה-Homatics תתחבר לטלוויזיה ב-adb ברשת (ספריית adb קטנה ב-Kotlin,
   אישור חד-פעמי על מסך הטלוויזיה) ותאמת לבד ש-HDR10/DV/24Hz הגיעו לטלוויזיה, ואולי גם תקרא את ערכי
   ההגדרות במקום לשאול אותך. זה יהיה תוסף אופציונלי — האפליקציה תעבוד גם בלעדיו.

## מדידות שלב 0 (adb, 2026-11-27)

**שרשרת אמיתית (לפי HDMI-CEC בטלוויזיה):** Homatics ← Hue Sync Box 8K ← **מקרן קול** (eARC) ← HDMI 2 בטלוויזיה.
מקרן הקול נמצא במסלול הווידאו, ולכן הוא חשוד ראשון אם DV או 4K60 ייעלמו.

Homatics (Android 14, build UKG3.250803.001.8963, kernel 5.15):
- UI ‏1920×1080 (density 2), פלט 3840×2160 @ 59.94. מצבי 4K: ‏23.976/24/25/29.97/30/50/59.94/60.
- HDR מה-EDID (דרך Sync Box ומקרן הקול): DV, HDR10, HLG, HDR10+. ‏`dv_cap`: VSVDB V2, ‏DV_RGB_444_8BIT,
  DV_YCbCr_422_12BIT, LL_YCbCr_422_12BIT, ‏2160p60.
- ערכי בהירות ב-EDID: Max/Avg/Min = 0 (Android מציג ברירת מחדל 1000/500).
- `match_content_frame_rate=2` (Always), ‏`hdr_conversion_mode=1` (Passthrough), ‏`user_disabled_hdr_formats` ריק.
- פלט HDMI ב-SDR: ‏`444,8bit`.
- ב-dumpsys נראה `mOverrideDisplayInfo` עם מצב אחד בלי HDR ("Linux FHD"), אבל **דוח שלב 0 הראה שאפליקציה מקבלת
  את המידע המלא**: 19 מצבים, DV/HDR10/HLG/HDR10+, ושם ה-sink ‏"Mi TV". ה-API הציבורי מספיק.
- קבצי ה-sysfs של `amhdmitx` ו-`/sys/class/display/mode` **חסומים לאפליקציות** (SELinux, ‏EACCES). לכן מאפליקציה
  אי אפשר לקרוא את פורמט הפלט (8/10/12 ביט) ואת ערכי הבהירות הגולמיים ב-EDID — את אלה נבדוק דרך adb לטלוויזיה.

Xiaomi (MiTV-MOEU0, MediaTek MT9689, פאנל CSOT 65", ‏Android 14, ‏OS1.0.26.10.12.1):
- `settings get global TvSourcesStatus` → `hdmi2|HDR10` בזמן ניגון HDR10 — **הטלוויזיה מדווחת מה היא מקבלת**.
- הגדרות תמונה גלויות: `picture_mode=21`, ‏`picture_backlight=70`, ‏`tv_picture_light_sensor=0`. השאר כנראה ב-
  `vendor.mediatek.hardware.pq.IPq` — נמפה בשלב מאוחר יותר (לשנות הגדרה אחת ולהשוות `settings list`).

## פקודות adb לשלב 0 (להריץ פעם אחת ולהדביק לי את הפלט)

```bash
adb -s <BOX_IP>:5555 shell dumpsys display | grep -iE "mode|hdr|frame|refresh|conversion" | head -80
adb -s <BOX_IP>:5555 shell settings list global | grep -iE "hdr|frame|match|afr|hdmi|color|dolby|dv"
adb -s <BOX_IP>:5555 shell settings list secure | grep -iE "hdr|frame|match|afr"
adb -s <BOX_IP>:5555 shell settings list system | grep -iE "hdr|frame|afr|hdmi|color|dolby"
adb -s <BOX_IP>:5555 shell getprop | grep -iE "hdr|dolby|dv|frame|afr|hdmi|display"
adb -s <BOX_IP>:5555 shell cat /sys/class/amhdmitx/amhdmitx0/attr /sys/class/amhdmitx/amhdmitx0/hdr_cap /sys/class/amhdmitx/amhdmitx0/dv_cap
```

## יומן החלטות
- 2026-11-27: תבניות מוצגות כווידאו HEVC ב-SurfaceView (video plane) — היחיד שנותן 4K אמיתי ו-HDR10 עם
  מטא-דאטה. Compose רק לשכבת ההוראות.
- 2026-11-27: התבניות מקודדות ב-**HEVC Main10 lossless** (x265 `lossless=1`). בניסוי, QP 0–8 נתנו סטיות של עד 3–5
  קודים בקצוות; lossless נותן 0 בכל פיקסל ועדיין קטן (כל ה-SDR יחד ‏1.2MB). ה-CI משווה framemd5 של כל פריים מפוענח
  למקור. הסיכון: שמפענח החומרה לא יתמוך ב-lossless — לכן האפליקציה מפענחת פריים ראשון של כל תבנית עם אותו מפענח
  ומשווה לערכי ה-probe מה-manifest.
- 2026-11-27: כל התבניות הסטטיות ב-23.976fps, והנגן לא מבקש החלפת קצב (`VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF`), כדי
  שמעבר בין תבניות לא יגרום ל-resync של HDMI. בדיקת ה-24Hz והתנועה (שלב 2) יבקשו החלפה במפורש.
- 2026-11-27: בדיקת מפענח על הטלוויזיה: `c2.amlogic.hevc.decoder` מפענח את קובצי ה-lossless נכון (כל ה-probes תואמים).
  הוא לא נותן לאפליקציות P010, רק עותק 8 ביט, ולכן ההשוואה היא ב-8 ביט (±1). בעין: ה-checkerboard נראה, overscan תקין.
- 2026-11-27: **ממצא פתוח:** לוח משובץ של פיקסל אחד נראה אפור חלק מקרוב, גם במצב Game, אבל קווים של פיקסל אחד (אנכיים
  ואופקיים) נראים. כלומר אין scaling, אבל משהו מסנן פרטים דו-ממדיים. חשודים: עיבוד ה-video plane של Amlogic, ה-Sync
  Box / מקרן הקול, או הנעת הפאנל. נוספה תבנית `sdr_resolution` (‏patterns-v3) ובדיקת מפענח על פיקסלים בודדים בלוח.
  תוצאה: המפענח של הקופסה מוציא את הלוח נכון (26 פיקסלים). על המסך: 2/4 px, אלכסון וקווי 1 px נראים; רק לוחות 1 px
  אפורים. השלב הבא: אותו קובץ בנגן של הטלוויזיה (`diagnostics/`, H.264 / HEVC 8-bit), ואם צריך – Homatics ישירות לטלוויזיה.
- 2026-11-27: ה-repo הופך לציבורי (קבצי Release של repo פרטי מחזירים 404 בלי התחברות). ההיסטוריה כווצה ל-commit אחד
  כדי לא לחשוף פרטי רשת ביתית; התבניות פורסמו מחדש כ-patterns-v2.
- 2026-11-27: ב-CI (Ubuntu 26.04, ffmpeg 8.0.1, x265 4.1) הקבצים יצאו שונים מהמקור כשהקלט הגולמי לא תויג. חובה לתייג
  גם את הקלט (`-color_range tv -colorspace …` לפני `-i`), אחרת ffmpeg 8 מכניס המרה בשקט.
- 2026-11-27: התבניות נבנות כ-Y'CbCr ישירות (numpy), לא RGB→YCbCr ב-ffmpeg, כדי שאפורים יהיו מדויקים (Cb=Cr=512).
- 2026-11-27: חתימה במפתח חדש לאפליקציה (alias `calibrate`, SHA-256 ‏`255af18e…98 45f5`).
- 2026-11-27: Release נבנה גם מה-branch של הפיתוח, כדי שכל שלב יותקן ב-Downloader מ-`releases/latest`.
- 2026-11-27: אין live glass כמו ב-Glass Home: הפאנלים יושבים מעל וידאו ב-plane נפרד שאי אפשר לטשטש. שומרים על אותם צבעים, פונטים, צורות ופוקוס.
- 2026-11-27: Hue Sync Box הוא דגם 8K (תומך ב-DV ו-HDR10+ ב-passthrough לפי היצרן). שלב 5 (DV P8.1) אושר לניסיון.
- 2026-11-27: התבניות כ-Release assets נפרדים (`patterns-vN`), לא בתוך ה-APK.
