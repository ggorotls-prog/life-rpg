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

    // 로그아웃·탈퇴(2026-11-18): 앞사람 것을 이 폰에서 모두 지운다. UI 줄에서 부른다(쿠키·웹 저장소).
    // 지우는 것: RPG 열쇠·위젯 요약·지난 확인 기록, 위젯 할 일 목록(rpg-widget),
    //   앱 안 업무 기록(todohub* = 로그인 쿠키·내 정보·위젯 목록·안 보낸 할 일)·관계 기록(rel = 열쇠·통화 확인 위치) 저장,
    //   모든 쿠키(업무 hs= · 영성 pr=), 건강·재정 기록 화면의 웹 저장소(ggorotls-prog.github.io = 로그인 열쇠 life_token·fin_session).
    // 남기는 것: 가입 승인 기다리는 닉네임(wait) = 로그인 전 상태. 묵상 알림 시각·잠금 등 이 폰 설정.
    // 둘로 나눈다: wipePrefs는 부른 줄에서 바로(뒤따르는 새 로그인 열쇠를 지우지 않게), wipeWeb은 UI 줄에서.
    // 아직 못 보낸 할 일(todohub-outbox)은 all일 때만 지운다 = 탈퇴했거나 다른 사람이 로그인했을 때.
    //   같은 사람이 로그아웃·로그인 풀림 뒤 다시 들어오면 못 보낸 것이 그대로 보내진다(2026-11-18 프로그래머 점검).
    static void wipePrefs(Context c, boolean all) {
        Context ac = c.getApplicationContext();   // 이름 app은 쓰지 않는다(패키지 app.recordkit과 겹침)
        get(ac).p.edit().putString("token", "").remove("summary").remove("seenPending").remove("seenPacks").apply();
        for (String f : new String[]{"rpg-widget", "todohub", "todohub-notify", "todohub-widget", "todohub-cal", "rel"})
            ac.getSharedPreferences(f, Context.MODE_PRIVATE).edit().clear().apply();
        if (all) ac.getSharedPreferences("todohub-outbox", Context.MODE_PRIVATE).edit().clear().apply();
    }
    static void wipeWeb(Context c) {
        Context ac = c.getApplicationContext();
        try {
            android.webkit.CookieManager cm = android.webkit.CookieManager.getInstance();
            cm.removeAllCookies(null);
            cm.flush();
            android.webkit.WebStorage.getInstance().deleteOrigin("https://ggorotls-prog.github.io");
        } catch (Exception ignored) {}
        Widget.draw(ac);
        try { app.recordkit.work.Work.refresh(ac); } catch (Exception ignored) {}   // 업무 기록 위젯도 비운 상태로 다시 그림
    }

    // {lv, pct, today, on:[1/0/-1 × 6], names:[...], xp, packs:{home,wallet,father,work}}
    String summary() { return p.getString("summary", ""); }
    void setSummary(String json) { p.edit().putString("summary", json == null ? "" : json).apply(); }
}
