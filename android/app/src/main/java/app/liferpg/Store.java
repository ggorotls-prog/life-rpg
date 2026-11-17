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

    // 가입 신청한 닉네임(승인 기다림, 화면이 넘겨 줌). 15분 확인이 승인·거절을 알림으로 알린 뒤 비운다
    String waitNick() { return p.getString("wait", ""); }
    void setWait(String n) { p.edit().putString("wait", n == null ? "" : n).apply(); }
    // 지난 확인 때 본 것: 주인의 기다리는 신청 수, 구성원의 확장팩 상태({팩: 상태}). 늘어난 것만 알린다
    int seenPending() { return p.getInt("seenPending", 0); }
    void setSeenPending(int n) { p.edit().putInt("seenPending", n).apply(); }
    String seenPacks() { return p.getString("seenPacks", "{}"); }
    void setSeenPacks(String json) { p.edit().putString("seenPacks", json == null ? "{}" : json).apply(); }

    // {lv, pct, today, on:[1/0/-1 × 6], names:[...], xp, packs:{home,wallet,father,work}}
    String summary() { return p.getString("summary", ""); }
    void setSummary(String json) { p.edit().putString("summary", json == null ? "" : json).apply(); }
}
