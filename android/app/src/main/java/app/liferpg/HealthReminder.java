package app.liferpg;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import java.util.Calendar;

// 건강 기록 앱(app.liferoom) Reminder와 같은 아침 8시·밤 10시 알림(2026-11-15 룡 「RPG만 깔아도 다른 기록 앱 안 깔아도 되게」).
// 건강 기록 앱이 같이 깔려 있으면 그 앱이 울리므로 여기서는 울리지 않는다(같은 알림 두 번 안 오게).
public class HealthReminder extends BroadcastReceiver {
    private static final int MORNING = 8, NIGHT = 22;
    private static final String CHANNEL = "health";

    static void schedule(Context c) {
        set(c, MORNING);
        set(c, NIGHT);
    }

    private static void set(Context c, int hour) {
        Calendar at = Calendar.getInstance();
        at.set(Calendar.HOUR_OF_DAY, hour);
        at.set(Calendar.MINUTE, 0);
        at.set(Calendar.SECOND, 0);
        if (at.getTimeInMillis() <= System.currentTimeMillis()) at.add(Calendar.DAY_OF_YEAR, 1);
        Intent i = new Intent(c, HealthReminder.class).putExtra("hour", hour);
        PendingIntent p = PendingIntent.getBroadcast(c, 100 + hour, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        c.getSystemService(AlarmManager.class).setInexactRepeating(AlarmManager.RTC_WAKEUP, at.getTimeInMillis(), AlarmManager.INTERVAL_DAY, p);
    }

    static boolean standaloneInstalled(Context c) {
        try { c.getPackageManager().getPackageInfo("app.liferoom", 0); return true; } catch (Exception e) { return false; }
    }

    @Override
    public void onReceive(Context c, Intent intent) {
        String a = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(a) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)) { schedule(c); return; }
        if (standaloneInstalled(c)) return;
        boolean morning = intent.getIntExtra("hour", MORNING) == MORNING;
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "건강 기록 아침·밤 알림", NotificationManager.IMPORTANCE_DEFAULT));
        PendingIntent open = PendingIntent.getActivity(c, 108, new Intent(c, HealthActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE);
        try {
            nm.notify(morning ? 801 : 802, new Notification.Builder(c, CHANNEL)
                    .setSmallIcon(R.drawable.ic_notify)
                    .setContentTitle(morning ? "좋은 아침이에요 ☀️" : "오늘 하루 수고하셨어요 🌙")
                    .setContentText(morning ? "일어난 시간을 적어 주세요" : "주무시기 전에 오늘을 마감해 주세요")
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .build());
        } catch (SecurityException ignored) {}
    }
}
