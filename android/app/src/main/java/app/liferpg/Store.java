package app.liferpg;

import android.content.Context;
import android.content.SharedPreferences;

// 폰 안에만 두는 것: 로그인 열쇠, 위젯에 보일 요약(화면이 계산해 넘겨 준 것).
final class Store {
    private static Store one;
    private final SharedPreferences p;

    private Store(Context c) { p = c.getApplicationContext().getSharedPreferences("rpg", Context.MODE_PRIVATE); }

    static synchronized Store get(Context c) {
        if (one == null) one = new Store(c);
        return one;
    }

    String token() { return p.getString("token", ""); }
    void setToken(String t) {
        SharedPreferences.Editor e = p.edit().putString("token", t == null ? "" : t);
        if (t == null || t.isEmpty()) e.remove("summary");
        e.apply();
    }

    // {lv, pct, today, on:[1/0/-1 × 6], names:[...], xp}
    String summary() { return p.getString("summary", ""); }
    void setSummary(String json) { p.edit().putString("summary", json == null ? "" : json).apply(); }
}
