package app.liferpg;

import android.app.Activity;
import android.webkit.WebView;

import org.json.JSONObject;

// 라이프 RPG 로그인(업무 기록 아이디)으로 안쪽 기록 화면에도 바로 들어가기(2026-11-15).
// 그 화면에 로그인 열쇠가 없으면 서버 함수(rpg_life_token · rpg_fin_token)로 그 기록의 열쇠를 받아 넣고 새로 연다.
// 함수가 없거나 계정이 안 이어져 있으면 아무것도 안 한다 → 그 화면의 로그인 창이 한 번 뜬다.
final class SignIn {
    private SignIn() {}

    static void once(Activity a, WebView web, String storageKey, String rpcFn) {
        if (web == null) return;
        String rpg = Store.get(a).token();
        if (rpg.isEmpty()) return;
        web.evaluateJavascript("(function(){try{return localStorage.getItem(" + JSONObject.quote(storageKey) + ")||''}catch(e){return ''}})()", have -> {
            if (have != null && have.length() > 2) return; // 이미 들어가 있음("" 는 길이 2)
            new Thread(() -> {
                try {
                    Net.Result r = Net.rpc(rpcFn, new JSONObject().put("p_token", rpg).toString());
                    if (r.status != 200) return;
                    String t = new JSONObject(r.body).optString("token", "");
                    if (t.isEmpty()) return;
                    a.runOnUiThread(() -> web.evaluateJavascript("try{localStorage.setItem(" + JSONObject.quote(storageKey) + ","
                            + JSONObject.quote(t) + ");location.reload()}catch(e){}", null));
                } catch (Exception ignored) {}
            }).start();
        });
    }
}
