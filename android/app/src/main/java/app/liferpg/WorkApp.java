package app.liferpg;

import android.app.Activity;
import android.content.Context;
import android.webkit.WebView;

import org.json.JSONObject;

import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

// 📋 업무 기록 = 업무 기록 앱 그 자체(2026-11-16 룡 「기록 앱은 늘 내용물이 똑같다」, 설계안 11절).
// 내용은 원본 한 벌: 업무 기록 저장소(greenjui-calls) android/workkit(업무 기록 앱과 같이 씀, 빌드 때 받아 씀).
// 여기는 manifest에 올릴 이어받기 클래스만(한 파일에 모음, manifest 이름 .WorkApp$Screen 처럼). 이름은 바꾸지 않는다(위젯·One hand 설정이 이 이름에 붙는다).
// 이 앱만의 것: RPG 로그인으로 업무 기록에도 바로 들어가기(SignIn), 새 버전 확인 없음(RPG 것이 따로 있음).
// 업무 기록 앱이 같이 깔려 있으면 알림은 그 앱만 울린다(원본 Work.quiet).
public final class WorkApp {
    private WorkApp() {}

    /** 업무 기록 화면(요청받은 일·맡기기·대화방). 업무 기록 앱 MainActivity와 같은 것 */
    public static class Screen extends app.recordkit.work.WorkScreen {
        @Override
        protected void onPageReady() { SignIn.check(this, web); }
    }

    /** ＋ 할 일 적기 작은 창(One hand에서도 바로 열게 exported) */
    public static class Quick extends app.recordkit.work.QuickAddActivity {}

    /** 잠금화면 앞 마을의 ＋ 수집 말풍선(2026-11-19 룡 「잠금 안 풀고 적기」): 같은 적기 창을 잠금 위에 띄운다.
     *  잠금 위에서는 적기만: 📷 사진(갤러리가 열림)은 숨기고, 다른 화면(업무 기록 로그인 등)은 열지 않는다. 목록은 잠금을 풀어야 보인다 */
    public static class LockQuick extends app.recordkit.work.QuickAddActivity {
        @Override
        protected void onCreate(android.os.Bundle b) {
            setShowWhenLocked(true);
            super.onCreate(b);
            hidePhoto(getWindow().getDecorView());
        }

        private boolean locked() {
            android.app.KeyguardManager k = getSystemService(android.app.KeyguardManager.class);
            return k != null && k.isKeyguardLocked();
        }

        // 잠금 중에는 다른 화면(로그인·사진 고르기)을 열지 않는다. startActivity도 결국 이 길을 지난다
        @Override
        public void startActivityForResult(android.content.Intent i, int code, android.os.Bundle opts) {
            if (locked()) {
                android.widget.Toast.makeText(this, "잠금을 풀고 업무 기록에서 해 주세요", android.widget.Toast.LENGTH_LONG).show();
                return;
            }
            super.startActivityForResult(i, code, opts);
        }

        // 적기 줄의 📷 칸과 그 앞 나눔 줄을 숨긴다(원본 적기 창은 그대로)
        private static boolean hidePhoto(android.view.View v) {
            if ("사진 넣기".contentEquals(String.valueOf(v.getContentDescription()))) {
                v.setVisibility(android.view.View.GONE);
                if (v.getParent() instanceof android.view.ViewGroup) {
                    android.view.ViewGroup p = (android.view.ViewGroup) v.getParent();
                    int at = p.indexOfChild(v);
                    if (at > 0) p.getChildAt(at - 1).setVisibility(android.view.View.GONE);
                }
                return true;
            }
            if (v instanceof android.view.ViewGroup) {
                android.view.ViewGroup g = (android.view.ViewGroup) v;
                for (int n = 0; n < g.getChildCount(); n++) if (hidePhoto(g.getChildAt(n))) return true;
            }
            return false;
        }
    }

    /** 잠금 위 적기 창: 마을과 같은 묶음에 쌓아(새 묶음 아님) 닫으면 바로 잠금 위 마을로 돌아온다 */
    static android.content.Intent lockQuick(Context c) {
        return new android.content.Intent(c, LockQuick.class).addFlags(android.content.Intent.FLAG_ACTIVITY_NO_ANIMATION);
    }
    public static class Confirm extends app.recordkit.work.ConfirmDoneActivity {}
    /** 「내 할 일」 위젯 */
    public static class TaskWidget extends app.recordkit.work.TaskWidget {}
    public static class TaskList extends app.recordkit.work.TaskWidgetService {}
    /** 「오늘 일정·할 일」 위젯 */
    public static class CalWidget extends app.recordkit.work.CalWidget {}
    public static class CalList extends app.recordkit.work.CalWidgetService {}
    public static class CalOpen extends app.recordkit.work.CalOpenActivity {}
    /** 할 일 알림 시간·하루 알림(8:30·12:30·17:00·20:00)·재부팅 뒤 다시 걸기 */
    public static class Alarm extends app.recordkit.work.AlarmReceiver {}
    /** 15분 확인 */
    public static class Sync extends app.recordkit.work.SyncJob {}
    /** 위젯 🎙 녹음·통화 가져오기 */
    public static class RecordStart extends app.recordkit.work.RecordStartActivity {}
    public static class Record extends app.recordkit.work.RecordService {}
    public static class RecordAlert extends app.recordkit.work.RecordReceiver {}
    public static class RecordUpload extends app.recordkit.work.RecordUploadJob {}
    // 녹음할 미팅 알림(2026-11-18 룡)
    public static class MeetAlert extends app.recordkit.work.MeetReceiver {}
    public static class Meet extends app.recordkit.work.MeetActivity {}
    /** 사이트가 보낸 바로 오는 알림 */
    public static class Push extends app.recordkit.work.PushService {}

    static android.content.Intent screen(Context c, boolean assign) {
        android.content.Intent i = new android.content.Intent(c, Screen.class).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
        if (assign) i.putExtra(app.recordkit.work.WorkScreen.EXTRA_ASSIGN, true);
        return i;
    }

    static android.content.Intent quick(Context c) {
        return new android.content.Intent(c, Quick.class).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK | android.content.Intent.FLAG_ACTIVITY_NO_ANIMATION);
    }

    // RPG 로그인으로 업무 기록 로그인 쿠키 받기(사이트 /api/login?rpg=1, DB 함수 rpg_work_token이 RPG 열쇠를 확인한다)
    static final class SignIn {
        private SignIn() {}
        private static long last;

        /** 업무 기록 로그인이 없거나 끊겼으면 받아 둔다(위젯·알림용). 마을을 열 때 부른다 */
        static void ensure(Context c) {
            Context app = c.getApplicationContext();
            new Thread(() -> renew(app)).start();
        }

        // 화면이 열릴 때마다: 로그인이 없거나 끊겼으면(401) 다시 받아 새로 연다(룡 「로그인은 한 번만」).
        static void check(Activity a, WebView web) {
            if (web == null) return;
            Context c = a.getApplicationContext();
            new Thread(() -> {
                if (renew(c)) a.runOnUiThread(() -> web.loadUrl(app.recordkit.work.Work.BASE + "/"));
            }).start();
        }

        // 새로 받았으면 true. 20초에 한 번만 본다(화면·마을이 겹쳐 불러도)
        private static boolean renew(Context c) {
            if (Store.get(c).token().isEmpty()) return false;
            synchronized (SignIn.class) {
                if (System.currentTimeMillis() - last < 20_000) return false;
                last = System.currentTimeMillis();
            }
            try {
                if (app.recordkit.work.Work.signedIn(c) && app.recordkit.work.Api.get(c, "/api/me").status != 401) return false;
            } catch (Exception e) {
                return false; // 인터넷이 안 되면 다음에
            }
            return fetch(c);
        }

        private static boolean fetch(Context c) {
            String rpg = Store.get(c).token();
            if (rpg.isEmpty()) return false;
            HttpURLConnection h = null;
            try {
                h = (HttpURLConnection) new URL(app.recordkit.work.Work.BASE + "/api/login?rpg=1").openConnection();
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
                    if (s.startsWith("hs=")) {
                        app.recordkit.work.Work.useCookie(c, s.split(";")[0]);
                        app.recordkit.work.Work.refresh(c);
                        return true;
                    }
                }
            } catch (Exception ignored) {
            } finally {
                if (h != null) h.disconnect();
            }
            return false;
        }
    }
}
