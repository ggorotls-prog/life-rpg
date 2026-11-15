package app.liferpg;

import android.content.Context;

// 건강 기록 아침 8시·밤 10시 알림. 내용은 원본 한 벌 healthkit의 DayReminder(건강 기록 앱과 같이 씀).
// 건강 기록 앱이 같이 깔려 있으면 그 앱이 울리므로 여기서는 울리지 않는다(2026-11-15 룡 OK).
public class HealthReminder extends app.recordkit.health.DayReminder {
    static void schedule(Context c) { schedule(c, HealthReminder.class); }

    @Override
    protected Class<? extends android.app.Activity> screen() { return HealthActivity.class; }

    @Override
    protected int smallIcon() { return R.drawable.ic_notify; }

    @Override
    protected String channel() { return "health"; }

    @Override
    protected boolean skip(Context c) {
        try { c.getPackageManager().getPackageInfo("app.liferoom", 0); return true; } catch (Exception e) { return false; }
    }
}
