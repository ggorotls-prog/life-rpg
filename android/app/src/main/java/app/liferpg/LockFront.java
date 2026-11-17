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
            if (Intent.ACTION_SCREEN_OFF.equals(i.getAction())) show(c);
        }
    };

    static void show(Context c) {
        try {
            if (!on(c) || !allowed(c)) return;
            if (LockService.isLocked(c)) return;   // 자는 중에는 건강 기록 취침 잠금이 화면을 맡는다
            AudioManager am = c.getSystemService(AudioManager.class);
            int mode = am == null ? AudioManager.MODE_NORMAL : am.getMode();
            if (mode == AudioManager.MODE_RINGTONE || mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION) return;
            c.startActivity(new Intent(c, MainActivity.class).putExtra(EXTRA, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION));
        } catch (Exception ignored) {}
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
    }

    @Override
    public int onStartCommand(Intent i, int flags, int id) {
        if (!on(this)) { stopSelf(); return START_NOT_STICKY; }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        try { unregisterReceiver(off); } catch (Exception ignored) {}
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
