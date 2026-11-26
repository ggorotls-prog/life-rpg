package app.liferpg;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.webkit.WebView;

import org.json.JSONObject;

import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

// ⛪ 아빠 집의 영성 기록 = 영성 기록 앱 그 자체(2026-11-16 룡 「기록 앱은 늘 내용물이 똑같다」, 설계안 11절 3단계).
// 내용은 원본 한 벌: 영성 기록 저장소(personal-record) android/spiritkit(영성 기록 앱과 같이 씀, 빌드 때 받아 씀).
// 여기는 manifest에 올릴 이어받기 클래스만(manifest 이름 .SpiritApp$Screen 처럼). 이름은 바꾸지 않는다(위젯·바로가기 설정이 이 이름에 붙는다).
// 이 앱만의 것: RPG 로그인으로 영성 기록에도 바로 들어가기(SignIn), 새 버전 확인 없음(RPG 것이 따로 있음).
// 영성 기록 앱이 같이 깔려 있으면 묵상 알림은 그 앱만 울린다(원본 Spirit.quiet).
public final class SpiritApp {
    private SpiritApp() {}

    /** 영성 기록 화면(묵상·SOC·일기·가정예배·감사·칭찬). 영성 기록 앱 MainActivity와 같은 것 */
    public static class Screen extends app.recordkit.spirit.SpiritScreen {
        @Override
        protected void onPageReady() { SignIn.check(this, web); }
    }

    /** 감사·칭찬·SOC·기도제목 작은 적기 창(One hand에서도 바로 열게 exported) */
    public static class Quick extends app.recordkit.spirit.QuickAddActivity {}
    /** 🎤 아빠 집 ＋ = 말로 적는 나눌거리(2026-11-26 룡: 누르면 바로 마이크, 말한 뒤 종류 고르기) */
    public static class Voice extends app.recordkit.spirit.VoiceAddActivity {}
    /** 잠금화면 앞 마을의 아빠 집 ＋: 같은 창을 잠금 위에. 적기만 하고 다른 화면은 열지 않는다 */
    public static class LockVoice extends app.recordkit.spirit.VoiceAddActivity {
        @Override
        protected void onCreate(android.os.Bundle b) {
            setShowWhenLocked(true);
            super.onCreate(b);
        }

        @Override
        protected boolean onLock() {
            android.app.KeyguardManager k = getSystemService(android.app.KeyguardManager.class);
            return k != null && k.isKeyguardLocked();
        }

        @Override
        public void startActivityForResult(Intent i, int code, android.os.Bundle opts) {
            if (onLock()) return; // 잠금 중에는 다른 화면(로그인 등)을 잠금 뒤에 열지 않는다
            super.startActivityForResult(i, code, opts);
        }
    }

    static Intent voice(Context c, boolean lock) {
        return new Intent(c, lock ? LockVoice.class : Voice.class).addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
    }
    /** 홈 화면 위젯: 감사 · 칭찬 · SOC · 기도제목 */
    public static class Widget extends app.recordkit.spirit.RecordWidget {}
    /** 묵상 알림 시각·7분 타이머·폰 켤 때 다시 맞추기 */
    public static class Reminder extends app.recordkit.spirit.ReminderReceiver {}
    /** 7up 7분 타이머 알람 소리 */
    public static class TimerRing extends app.recordkit.spirit.TimerService {}

    /** 그 탭(묵상·SOC·일기·가정예배·감사·칭찬)으로 영성 기록 화면을 연다 */
    static Intent screen(Context c, String tab) {
        Intent i = new Intent(c, Screen.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (tab != null && !tab.isEmpty()) i.putExtra(app.recordkit.spirit.SpiritScreen.EXTRA_TAB, tab);
        return i;
    }

    // RPG 로그인으로 영성 기록 로그인 쿠키(pr=) 받기(사이트 /api/login?rpg=1, DB 함수 rpg_work_token이 RPG 열쇠를 확인한다)
    static final class SignIn {
        private SignIn() {}
        private static long last;

        /** 영성 기록 로그인이 없거나 끊겼으면 받아 둔다(작은 적기 창·위젯용). 마을을 열 때 부른다 */
        static void ensure(Context c) {
            Context app = c.getApplicationContext();
            new Thread(() -> renew(app)).start();
        }

        // 화면이 열릴 때마다: 로그인이 없거나 끊겼으면 다시 받아 새로 연다(룡 「로그인은 한 번만」). 20초에 한 번만.
        static void check(Activity a, WebView web) {
            if (web == null) return;
            Context c = a.getApplicationContext();
            new Thread(() -> {
                if (renew(c)) a.runOnUiThread(() -> web.reload());
            }).start();
        }

        private static boolean renew(Context c) {
            if (Store.get(c).token().isEmpty()) return false;
            synchronized (SignIn.class) {
                if (System.currentTimeMillis() - last < 20_000) return false;
                last = System.currentTimeMillis();
            }
            try {
                if (app.recordkit.spirit.Spirit.signedIn() && me(c) != 401) return false;
            } catch (Exception e) {
                return false; // 인터넷이 안 되면 다음에
            }
            return fetch(c);
        }

        // 지금 쿠키로 /api/me를 부른 결과(401 = 로그인 끊김)
        private static int me(Context c) throws Exception {
            HttpURLConnection h = (HttpURLConnection) new URL(app.recordkit.spirit.Spirit.BASE + "/api/me").openConnection();
            try {
                h.setConnectTimeout(10_000);
                h.setReadTimeout(15_000);
                String ck = android.webkit.CookieManager.getInstance().getCookie(app.recordkit.spirit.Spirit.BASE);
                if (ck != null) h.setRequestProperty("Cookie", ck);
                int code = h.getResponseCode();
                if (code == 200) {
                    java.io.InputStream in = h.getInputStream();
                    String body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    if (!new JSONObject(body).optBoolean("loggedIn", false)) return 401;
                }
                return code;
            } finally {
                h.disconnect();
            }
        }

        private static boolean fetch(Context c) {
            String rpg = Store.get(c).token();
            if (rpg.isEmpty()) return false;
            HttpURLConnection h = null;
            try {
                h = (HttpURLConnection) new URL(app.recordkit.spirit.Spirit.BASE + "/api/login?rpg=1").openConnection();
                h.setRequestMethod("POST");
                h.setConnectTimeout(10_000);
                h.setReadTimeout(15_000);
                h.setDoOutput(true);
                h.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                try (java.io.OutputStream o = h.getOutputStream()) {
                    o.write(new JSONObject().put("token", rpg).toString().getBytes(StandardCharsets.UTF_8));
                }
                if (h.getResponseCode() != 200) return false;
                java.util.List<String> set = h.getHeaderFields().get("Set-Cookie");
                if (set == null) return false;
                for (String s : set) {
                    if (s.startsWith("pr=")) { app.recordkit.spirit.Spirit.useCookie(s.split(";")[0]); return true; }
                }
            } catch (Exception ignored) {
            } finally {
                if (h != null) h.disconnect();
            }
            return false;
        }
    }
}
