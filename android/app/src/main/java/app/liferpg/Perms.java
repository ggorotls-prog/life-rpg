package app.liferpg;

import android.Manifest;
import android.app.AppOpsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONObject;

// 📱 켜야 할 것 한 벌(2026-11-18 룡: 「기록마다 설정에 들어가 켜야 하는 건 너무 불편. 한 방에 켜게, 기록마다 필요한 건 그 기록을 깔 때, 안 켜면 못 쓰게」).
// 이 앱이 쓰는 허락을 한 곳에 적는다: 무엇을(id) · 어느 기록 것인가(pack, ""= 모두) · 지금 켜져 있나 · 어디서 켜나.
// 화면(index.html)은 이 목록으로 「켜야 할 것」을 그리고, 꺼진 것이 있으면 그 기록을 열지 않는다.
// 「한 번에 켜기」는 MainActivity.permStep이 꺼진 것을 차례로 연다(묻는 창은 한꺼번에, 설정 화면은 하나씩 갔다 오면 다음 것).
final class Perms {
    private Perms() {}

    static final class Item {
        final String id, pack, title, why, perm;   // perm = 묻는 창으로 받는 허락(없으면 설정 화면에서 켠다)
        Item(String id, String pack, String title, String why, String perm) { this.id = id; this.pack = pack; this.title = title; this.why = why; this.perm = perm; }
    }

    static final Item[] ALL = {
            new Item("notify", "", "🔔 알림", "승인·새 버전·「기록할까요?」를 알려 줘요", Build.VERSION.SDK_INT >= 33 ? "android.permission.POST_NOTIFICATIONS" : null),
            new Item("battery", "", "🔋 배터리 아끼기에서 빼기", "앱을 안 열어도 15분마다 확인해요", null),
            new Item("calendar", "업무", "📅 캘린더 보기", "마을과 「오늘」에 오늘 일정을 보여 줘요", Manifest.permission.READ_CALENDAR),
            new Item("phone", "관계", "📞 통화가 끝났는지 알기", "통화가 끝나면 누구와 했는지 물어요", Manifest.permission.READ_PHONE_STATE),
            new Item("calllog", "관계", "📞 통화 기록 읽기", "번호·시각·길이만 읽어요. 내용은 안 읽어요", Manifest.permission.READ_CALL_LOG),
            new Item("contacts", "관계", "👤 연락처 읽기", "이름을 보여 주고 뺄 번호를 골라요", Manifest.permission.READ_CONTACTS),
            new Item("overlay", "관계", "🪟 다른 앱 위에 표시", "통화가 끝나면 작은 창으로 물어요", null),
            new Item("restricted", "*", "🔓 제한된 설정 허용", "받아서 깐 앱은 이것을 먼저 눌러야 아래 것이 켜져요 (앱 정보 → 오른쪽 위 ⋮)", null),
            new Item("finNotice", "재정", "💳 은행·카드 알림 읽기", "입금·출금·결제 알림이 오면 「기록할까요?」를 물어요", null),
            new Item("sleepLock", "건강", "🌙 취침 잠금", "「잘게요」 뒤에 폰을 잠가요 (접근성에서 켜요)", null),
    };

    static Item find(String id) {
        for (Item i : ALL) if (i.id.equals(id)) return i;
        return null;
    }

    // 그 기록의 단독 앱이 같이 깔려 있나. 깔려 있으면 알림 읽기·통화 모으기·취침 잠금은 그 앱이 맡고 이 앱은 쉰다(원본 모듈 규칙)
    private static boolean has(Context c, String pkg) {
        try { c.getPackageManager().getPackageInfo(pkg, 0); return true; } catch (Exception e) { return false; }
    }

    static boolean on(Context c, String id) {
        try {
            Item it = find(id);
            if (it == null) return true;
            if ("관계".equals(it.pack) && has(c, "app.relrecord")) return true;
            if ("sleepLock".equals(id) && has(c, "app.liferoom")) return true;
            if (it.perm != null) return c.checkSelfPermission(it.perm) == PackageManager.PERMISSION_GRANTED;
            String pkg = c.getPackageName();
            switch (id) {
                case "notify": return Updater.notificationsOn(c);
                case "battery": { PowerManager pm = c.getSystemService(PowerManager.class); return pm == null || pm.isIgnoringBatteryOptimizations(pkg); }
                case "overlay": return Settings.canDrawOverlays(c);
                case "restricted": return restrictedOk(c);
                case "finNotice": { String s = Settings.Secure.getString(c.getContentResolver(), "enabled_notification_listeners"); return s != null && s.contains("/app.recordkit.fin.FinNotice"); }   // 재정 기록 단독 앱에서 켠 것도 켜진 것
                case "sleepLock": { String s = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES); return s != null && s.contains(pkg + "/") && s.contains("LockService"); }
                default: return true;
            }
        } catch (Exception e) { return true; }   // 모르면 막지 않는다
    }

    // 받아서 깐 앱(안드로이드 13+)은 「제한된 설정 허용」을 눌러야 알림 읽기·접근성을 켤 수 있다. 확인할 수 없으면 된 것으로 본다
    private static boolean restrictedOk(Context c) {
        if (Build.VERSION.SDK_INT < 33) return true;
        try {
            AppOpsManager ao = c.getSystemService(AppOpsManager.class);
            int m = ao.unsafeCheckOpNoThrow("android:access_restricted_settings", android.os.Process.myUid(), c.getPackageName());
            return m == AppOpsManager.MODE_ALLOWED || m == AppOpsManager.MODE_DEFAULT;
        } catch (Throwable t) { return true; }
    }

    /** 그 기록들(쉼표로, 예: "업무,재정")에 필요한 것 + 모두에게 필요한 것. [{id, pack, title, why, on}] */
    static String json(Context c, String packsCsv) {
        java.util.List<String> packs = java.util.Arrays.asList((packsCsv == null ? "" : packsCsv).split(","));
        JSONArray out = new JSONArray();
        boolean needRestricted = (packs.contains("재정") && !on(c, "finNotice")) || (packs.contains("건강") && !on(c, "sleepLock"));
        for (Item i : ALL) {
            try {
                if ("*".equals(i.pack)) { if (!needRestricted || on(c, i.id)) continue; }
                else if (!i.pack.isEmpty() && !packs.contains(i.pack)) continue;
                out.put(new JSONObject().put("id", i.id).put("pack", "*".equals(i.pack) ? "" : i.pack).put("title", i.title).put("why", i.why).put("on", on(c, i.id)));
            } catch (Exception ignored) {}
        }
        return out.toString();
    }

    /** 설정 화면에서 켜는 것: 여는 곳 */
    static Intent intent(Context c, String id) {
        String pkg = c.getPackageName();
        Uri me = Uri.parse("package:" + pkg);
        switch (id) {
            case "notify": return new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, pkg);
            case "battery": return new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, me);
            case "overlay": return new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, me);
            case "finNotice": return new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
            case "sleepLock": return new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            default: return new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, me);   // restricted · 묻는 창이 막힌 허락
        }
    }

    /** 그 설정 화면에서 무엇을 누르면 되는지(화면 위에 잠깐 뜨는 글) */
    static String hint(String id) {
        switch (id) {
            case "notify": return "알림을 켜고 뒤로 오세요";
            case "battery": return "「허용」을 눌러 주세요";
            case "overlay": return "「라이프 RPG」를 켜고 뒤로 오세요";
            case "restricted": return "오른쪽 위 ⋮ → 「제한된 설정 허용」을 누르고 뒤로 오세요";
            case "finNotice": return "「재정 기록 (은행·카드 알림…)」을 켜고 뒤로 오세요";
            case "sleepLock": return "「설치된 앱」 → 「라이프 RPG 취침 잠금」을 켜고 뒤로 오세요";
            default: return "「권한」에서 허용하고 뒤로 오세요";
        }
    }
}
