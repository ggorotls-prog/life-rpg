package app.liferpg;

import android.content.Context;
import android.content.Intent;

import org.json.JSONObject;

// 👥 이웃 = 관계 기록 앱 그 자체(2026-11-16 룡 「기록 앱은 늘 내용물이 똑같다」, 설계안 11절).
// 내용은 원본 한 벌: 관계 기록 저장소(relation-record) android/relkit(관계 기록 앱과 같이 씀, 빌드 때 받아 씀). 화면 글도 그 모듈 안 한 벌.
// 여기는 manifest에 올릴 이어받기 클래스만(manifest 이름 .RelApp$Screen 처럼). 이름은 바꾸지 않는다(통화 받기·권한 설정이 이 이름에 붙는다).
// 이 앱만의 것: RPG 로그인으로 관계 기록에도 바로 들어가기(rpg_rel_token), 새 버전 확인 없음.
// 관계 기록 앱이 같이 깔려 있으면 통화 모으기·묻기는 그 앱만(원본 Rel.quiet): 같은 통화가 두 번 올라가지 않게.
public final class RelApp {
    private RelApp() {}

    /** 관계 기록 화면(사람·통화·메모·권한 안내). 관계 기록 앱 MainActivity와 같은 것 */
    public static class Screen extends app.recordkit.rel.RelScreen {
        @Override
        protected void onPageReady() {
            if (app.recordkit.rel.Rel.signedIn(this)) return;
            Context c = getApplicationContext();
            new Thread(() -> { if (signIn(c)) reloadScreen(); }).start();
        }
    }

    /** 통화 끝나면 뜨는 작은 창 */
    public static class Ask extends app.recordkit.rel.AskActivity {}
    /** 알림 [아님] · 업데이트·재부팅 뒤 다시 걸기 */
    public static class AskAlert extends app.recordkit.rel.AskReceiver {}
    /** 통화가 끝났다(폰이 알려 줌) */
    public static class Call extends app.recordkit.rel.CallReceiver {}
    /** 통화 기록 읽기·보내기(통화 끝·15분·다시 보내기) */
    public static class CallWork extends app.recordkit.rel.CallJob {}

    static Intent screen(Context c) {
        return new Intent(c, Screen.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    /** 마을을 열 때: 15분 통화 확인 걸기, 관계 기록 열쇠가 없으면 RPG 로그인으로 받기 */
    static void start(Context c) {
        app.recordkit.rel.Rel.start(c);
        if (app.recordkit.rel.Rel.signedIn(c)) return;
        Context app = c.getApplicationContext();
        new Thread(() -> signIn(app)).start();
    }

    // RPG 열쇠 → 관계 기록 열쇠(rpg_rel_token). 함수가 없거나 계정이 안 이어져 있으면 그 화면 로그인 창
    private static synchronized boolean signIn(Context c) {
        if (app.recordkit.rel.Rel.signedIn(c)) return false;
        String rpg = Store.get(c).token();
        if (rpg.isEmpty()) return false;
        try {
            Net.Result r = Net.rpc("rpg_rel_token", new JSONObject().put("p_token", rpg).toString());
            if (r.status != 200) return false;
            String t = new JSONObject(r.body).optString("token", "");
            if (t.isEmpty()) return false;
            app.recordkit.rel.Rel.useToken(c, t);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
