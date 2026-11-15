package app.liferpg;

import android.app.Activity;
import android.webkit.WebView;

import org.json.JSONObject;

// 라이프 RPG 로그인(업무 기록 아이디)으로 안쪽 기록 화면에도 바로 들어가기(2026-11-15).
// 그 화면에 로그인 열쇠가 없으면 서버 함수(rpg_life_token · rpg_fin_token)로 그 기록의 열쇠를 받아 넣고 새로 연다.
// 함수가 없거나 계정이 안 이어져 있으면 아무것도 안 한다 → 그 화면의 로그인 창이 뜬다.
// 화면이 열릴 때마다 본다: 그 기록 열쇠가 만료·로그아웃으로 지워져도 다음에 열면 다시 이어진다(룡 「로그인은 한 번만」).
// 같은 기록은 20초에 한 번만 묻는다(새로 여는 동안 겹치지 않게).
final class SignIn {
    private SignIn() {}
    private static final java.util.Map<String, Long> last = new java.util.HashMap<>();

    static void once(Activity a, WebView web, String storageKey, String rpcFn) {
        if (web == null) return;
        synchronized (last) {
            Long t0 = last.get(storageKey);
            if (t0 != null && System.currentTimeMillis() - t0 < 20_000) return;
        }
        String rpg = Store.get(a).token();
        if (rpg.isEmpty()) return;
        web.evaluateJavascript("(function(){try{return localStorage.getItem(" + JSONObject.quote(storageKey) + ")||''}catch(e){return ''}})()", have -> {
            if (have != null && have.length() > 2) return; // 이미 들어가 있음("" 는 길이 2)
            synchronized (last) { last.put(storageKey, System.currentTimeMillis()); }
            new Thread(() -> {
                try {
                    Net.Result r = Net.rpc(rpcFn, new JSONObject().put("p_token", rpg).toString());
                    if (r.status != 200) return;
                    String t = new JSONObject(r.body).optString("token", "");
                    if (t.isEmpty()) return;
                    a.runOnUiThread(() -> web.evaluateJavascript("try{localStorage.setItem(" + JSONObject.quote(storageKey) + ","
                            + JSONObject.quote(t) + ");if(localStorage.getItem(" + JSONObject.quote(storageKey) + ")===" + JSONObject.quote(t) + ")location.reload()}catch(e){}", null));
                } catch (Exception ignored) {}
            }).start();
        });
    }
}
