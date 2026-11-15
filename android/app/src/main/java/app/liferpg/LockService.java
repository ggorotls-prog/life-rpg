package app.liferpg;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

// 취침 잠금(2026-11-06 룡 결정: 「확실하게」 접근성 서비스로).
// 「잘게요」 뒤에는 다른 앱이 화면에 올라오는 순간 그 위를 「자는 중」 화면으로 덮고, 이 앱을 다시 앞으로 끌어온다.
// 「일어났어요」나 「긴급 해제」(사유 기록)로만 풀린다. 전화 받기·알람·시스템 화면은 그대로 둔다.
// 혹시 앱이 잘못돼도 12시간이 지나면 저절로 풀린다.
// 2026-11-08: 앞으로 끌어오기만으로는 아침에 다른 앱으로 넘어가진 일이 있어(룡), 접근성 덮개 화면을 더했다.
// 덮개는 다른 권한 없이 모든 앱 위에 뜨고, 폰이 백그라운드 화면 열기를 막아도 화면을 가린다.
// 라이프 RPG 판(2026-11-15 룡 「기록 앱은 동일, RPG는 비추는 창」): 건강 기록 앱(app.liferoom) LockService와 같은 코드.
// 다른 점 둘: 앞으로 끌어올 화면이 HealthActivity(🏠 집 = 건강 기록 화면), 건강 기록 앱은 막지 않는다(둘 다 깔려 같이 잠겨도 서로 끌어오며 싸우지 않게).
public class LockService extends AccessibilityService {
    static final String PREFS = "life";
    static final long MAX_LOCK_MS = 12L * 60 * 60 * 1000;

    // 지금 폰과 실제로 이어져 일하는 중인 서비스. 설정에 「켜짐」으로 보여도 이게 비어 있으면 잠금이 안 된다
    private static volatile LockService alive;

    private static final String[] ALLOW = {
            "com.samsung.android.incallui",         // 삼성 통화 화면
            "com.android.incallui",                 // 통화 화면
            "com.google.android.dialer",            // 구글 전화(통화 화면 포함)
            "com.samsung.android.dialer",           // 삼성 전화
            "com.android.server.telecom",           // 통화 연결
            "com.sec.android.app.clockpackage",     // 삼성 시계(알람)
            "com.google.android.deskclock",         // 구글 시계(알람)
            "com.android.deskclock",
            "com.google.android.permissioncontroller",
            "com.android.permissioncontroller",
            "android",
            "app.liferoom",                       // 건강 기록 단독 앱(같은 기록, 같은 화면)
    };

    private final Handler main = new Handler(Looper.getMainLooper());
    private View cover;

    static boolean running() { return alive != null; }

    static boolean isLocked(Context c) {
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long since = p.getLong("locked_at", 0);
        if (since == 0) return false;
        if (System.currentTimeMillis() - since > MAX_LOCK_MS) { setLocked(c, false); return false; }
        return true;
    }

    static void setLocked(Context c, boolean on) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putLong("locked_at", on ? System.currentTimeMillis() : 0).apply();
        LockService s = alive;
        if (!on && s != null) s.main.post(s::hideCover);
    }

    @Override
    protected void onServiceConnected() { alive = this; }

    @Override
    public boolean onUnbind(Intent intent) { alive = null; hideCover(); return super.onUnbind(intent); }

    @Override
    public void onDestroy() { alive = null; hideCover(); super.onDestroy(); }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        alive = this;
        if (e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || e.getPackageName() == null) return;
        if (!isLocked(this)) { hideCover(); return; }
        String pkg = e.getPackageName().toString();
        if (pkg.equals(getPackageName())) { hideCover(); return; }
        // 알림창·잠금 화면·키보드는 덮개를 그대로 둔다(긴급 해제 사유를 적을 때 키보드가 뜬다, 2026-11-07)
        if (pkg.equals("com.android.systemui") || isKeyboard(pkg)) return;
        for (String a : ALLOW) if (pkg.equals(a)) { hideCover(); return; }
        // 다른 앱이 올라왔다: 먼저 덮고, 이 앱을 다시 앞으로
        showCover();
        openApp();
    }

    private void openApp() {
        Intent i = new Intent(this, HealthActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        try { startActivity(i); } catch (Exception ignored) {}
    }

    private void showCover() {
        if (cover != null) return;
        try {
            LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setGravity(Gravity.CENTER);
            box.setBackgroundColor(Color.parseColor("#f6f5f1"));
            int pad = (int) (32 * getResources().getDisplayMetrics().density);
            box.setPadding(pad, pad, pad, pad);
            TextView t = new TextView(this);
            t.setText("🌙\n자는 중이에요\n\n일어나면 건강 기록에서\n「일어났어요」를 눌러 주세요");
            t.setTextColor(Color.parseColor("#1f1f1d"));
            t.setTextSize(20);
            t.setGravity(Gravity.CENTER);
            box.addView(t);
            Button b = new Button(this);
            b.setText("건강 기록 열기");
            b.setOnClickListener(v -> { hideCover(); openApp(); });
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            bp.topMargin = pad;
            box.addView(b, bp);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    PixelFormat.OPAQUE);
            getSystemService(WindowManager.class).addView(box, lp);
            cover = box;
        } catch (Exception ignored) { cover = null; }
    }

    private void hideCover() {
        if (cover == null) return;
        try { getSystemService(WindowManager.class).removeView(cover); } catch (Exception ignored) {}
        cover = null;
    }

    private boolean isKeyboard(String pkg) {
        try {
            InputMethodManager imm = getSystemService(InputMethodManager.class);
            for (InputMethodInfo i : imm.getEnabledInputMethodList()) if (pkg.equals(i.getPackageName())) return true;
        } catch (Exception ignored) {}
        return false;
    }

    @Override
    public void onInterrupt() {}
}
