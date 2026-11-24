package app.liferpg;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;

// 잠금화면 「앞」에 마을(2026-11-18 룡 「잠금화면으로 만들어서 한번 해보자」, 안내서 「다음」의 룡 구상).
// 화면이 꺼지는 순간 마을 화면(MainActivity)을 잠금화면 위에 올려 둔다 → 폰을 켜면 잠금 풀기 전에 마을이 먼저 보인다.
// 잠금을 안 푼 동안에는 보기만(마을·Lv·다음 일정). 화면을 누르면 잠금을 풀고, 푼 뒤에 건물을 눌러 기록한다.
// 화면 꺼짐은 앱이 켜져 있을 때만 받을 수 있어서 늘 떠 있는 작은 알림(조용한 칸)이 하나 생긴다.
// 전화·통화 중과 자는 중(건강 기록 취침 잠금)에는 띄우지 않는다. 기본은 꺼짐, ⚙ 설정 → 「켜야 할 것」에서 켠다.
public class LockFront extends Service {
    static final String EXTRA = "lock_front";      // 이 표시로 열린 마을 화면은 잠금화면 위에 뜬다
    static final String EXTRA_ON = "lock_front_on"; // 가상 폰 시험: 이 표시를 붙여 열면 기능을 켠다
    private static final String CH = "lock_front";

    private static SharedPreferences sp(Context c) { return c.getApplicationContext().getSharedPreferences("rpg", Context.MODE_PRIVATE); }

    static boolean on(Context c) { return sp(c).getBoolean("lockFront", false); }

    /** 「다른 앱 위에 표시」가 허용돼 있어야 화면이 꺼질 때 마을을 올릴 수 있다 */
    static boolean allowed(Context c) { return Settings.canDrawOverlays(c); }

    /** 가상 폰인가(시험용 표시는 가상 폰에서만 받는다) */
    static boolean emulator() {
        String f = String.valueOf(Build.FINGERPRINT), h = String.valueOf(Build.HARDWARE), mo = String.valueOf(Build.MODEL);
        return f.contains("generic") || f.contains("emulator") || h.contains("ranchu") || h.contains("goldfish") || mo.contains("sdk_gphone") || mo.contains("Emulator");
    }

    // 마을 화면(MainActivity) 위에 건강 기록·장부 같은 다른 화면이 떠 있나. 그럴 때 마을을 다시 띄우면 그 화면이 닫혀 적던 글이 사라진다
    private static boolean otherScreenOnTop(Context c) {
        try {
            android.app.ActivityManager am = c.getSystemService(android.app.ActivityManager.class);
            for (android.app.ActivityManager.AppTask t : am.getAppTasks()) {
                android.app.ActivityManager.RecentTaskInfo info = t.getTaskInfo();
                if (info.baseActivity == null || !MainActivity.class.getName().equals(info.baseActivity.getClassName())) continue;
                return info.topActivity != null && !MainActivity.class.getName().equals(info.topActivity.getClassName());
            }
        } catch (Exception ignored) {}
        return false;
    }

    static void set(Context c, boolean v) {
        sp(c).edit().putBoolean("lockFront", v).apply();
        if (v) start(c);
        else c.stopService(new Intent(c, LockFront.class));
    }

    static void start(Context c) {
        if (!on(c)) return;
        try { c.startForegroundService(new Intent(c, LockFront.class)); } catch (Exception ignored) {}
    }

    private final BroadcastReceiver off = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            // 0.5초 뒤에 띄운다: 플립을 접을 때 화면 꺼짐 신호가 경첩 각도보다 먼저 올 수 있어서(접힘을 놓치지 않게)
            if (Intent.ACTION_SCREEN_OFF.equals(i.getAction())) new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> show(c), 500);
        }
    };

    static void show(Context c) {
        try {
            if (!on(c) || !allowed(c)) return;
            if (LockService.isLocked(c)) return;   // 자는 중에는 건강 기록 취침 잠금이 화면을 맡는다
            if (otherScreenOnTop(c)) return;       // 적던 화면이 닫히지 않게 이번에는 안 띄운다
            AudioManager am = c.getSystemService(AudioManager.class);
            int mode = am == null ? AudioManager.MODE_NORMAL : am.getMode();
            if (mode == AudioManager.MODE_RINGTONE || mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION) return;
            // 접는 폰(플립)을 접어서 꺼진 것이면 안 띄운다(2026-11-24 룡 「닫힌 화면에선 안 뜨게」): 커버 화면에 마을이 뜨고,
            // 그 뒤 펼친 화면의 실행중인 앱 창에서 눌러도 안 들어가지는 일이 생겼다(아내 플립7, 룡 S26은 정상)
            if (folded()) return;
            Intent go = new Intent(c, MainActivity.class).putExtra(EXTRA, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
            // 늘 큰 화면(기본 화면)에 띄운다: 마을 창이 커버 화면에 묶이지 않게
            android.app.ActivityOptions o = android.app.ActivityOptions.makeBasic();
            o.setLaunchDisplayId(android.view.Display.DEFAULT_DISPLAY);
            try { c.startActivity(go, o.toBundle()); } catch (Exception e) { android.util.Log.w("LockFront", "큰 화면 지정 실패, 그냥 띄움", e); c.startActivity(go); }
        } catch (Exception e) { android.util.Log.w("LockFront", "마을 못 띄움", e); }
    }

    // ── 접힘 확인: 경첩 각도 센서(안드로이드 11+, 접는 폰에만 있음). 센서가 없는 폰은 늘 「안 접힘」 ──
    private static volatile float hinge = -1;   // 마지막 경첩 각도(도). -1 = 모름(센서 없음·아직 값 없음)
    static final float FOLDED_BELOW = 90f;      // 이보다 작으면 접힘으로 본다(활짝 = 180)

    static boolean folded() { return hinge >= 0 && hinge < FOLDED_BELOW; }

    private final android.hardware.SensorEventListener hingeListen = new android.hardware.SensorEventListener() {
        @Override public void onSensorChanged(android.hardware.SensorEvent e) { if (e.values != null && e.values.length > 0) hinge = e.values[0]; }
        @Override public void onAccuracyChanged(android.hardware.Sensor s, int a) {}
    };

    private void watchHinge() {
        try {
            android.hardware.SensorManager sm = getSystemService(android.hardware.SensorManager.class);
            if (sm == null) return;
            // 폰을 깨우지 않는 종류를 먼저(화면이 꺼진 동안 여닫을 때마다 깨지 않게), 없으면 기본
            android.hardware.Sensor s = sm.getDefaultSensor(android.hardware.Sensor.TYPE_HINGE_ANGLE, false);
            if (s == null) s = sm.getDefaultSensor(android.hardware.Sensor.TYPE_HINGE_ANGLE);
            android.util.Log.i("LockFront", "경첩 센서: " + (s == null ? "없음" : s.getName()));
            if (s != null) sm.registerListener(hingeListen, s, android.hardware.SensorManager.SENSOR_DELAY_NORMAL);
        } catch (Exception e) { android.util.Log.w("LockFront", "경첩 센서 못 씀", e); }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel ch = new NotificationChannel(CH, "잠금화면 앞에 마을", NotificationManager.IMPORTANCE_MIN);
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
        Notification n = new Notification.Builder(this, CH)
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle("잠금화면 앞에 마을 켜짐")
                .setContentText("끄려면 라이프 RPG ⚙ 설정 → 켜야 할 것")
                .setOngoing(true)
                .build();
        try {
            if (Build.VERSION.SDK_INT >= 34) startForeground(41, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            else startForeground(41, n);
        } catch (Exception e) { stopSelf(); return; }
        registerReceiver(off, new IntentFilter(Intent.ACTION_SCREEN_OFF));
        watchHinge();
    }

    @Override
    public int onStartCommand(Intent i, int flags, int id) {
        if (!on(this)) { stopSelf(); return START_NOT_STICKY; }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        try { unregisterReceiver(off); } catch (Exception ignored) {}
        try { getSystemService(android.hardware.SensorManager.class).unregisterListener(hingeListen); } catch (Exception ignored) {}
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent i) { return null; }

    // 폰을 다시 켜거나 앱이 업데이트된 뒤 다시 지켜보기
    public static class Boot extends BroadcastReceiver {
        @Override
        public void onReceive(Context c, Intent i) {
            String a = i.getAction();
            if (Intent.ACTION_BOOT_COMPLETED.equals(a) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)) start(c);
        }
    }
}
