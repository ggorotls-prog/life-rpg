package app.liferpg;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TimeZone;

// 밤 9시 30분 「오늘 나아간 것」 알림(2026-11-12 룡 결정) + 재부팅·업데이트 뒤 다시 걸기.
// 알람은 정확한 시각 권한 없이 21:30~21:45 사이에 울린다(배터리 아낌).
public class Daily extends BroadcastReceiver {
    static final String ACTION = "app.liferpg.DAILY";
    static final String[] AREAS = {"일", "관계", "재정", "건강", "지성", "영성"};
    private static final String CH = "daily";

    @Override
    public void onReceive(Context ctx, Intent i) {
        schedule(ctx);
        Job.schedule(ctx);
        if (!ACTION.equals(i.getAction())) return;
        PendingResult pr = goAsync();
        new Thread(() -> {
            try { report(ctx); } finally { pr.finish(); }
        }).start();
    }

    static void schedule(Context ctx) {
        AlarmManager am = ctx.getSystemService(AlarmManager.class);
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("Asia/Seoul"));
        c.set(Calendar.HOUR_OF_DAY, 21);
        c.set(Calendar.MINUTE, 30);
        c.set(Calendar.SECOND, 0);
        if (c.getTimeInMillis() <= System.currentTimeMillis() + 60_000) c.add(Calendar.DAY_OF_MONTH, 1);
        PendingIntent pi = PendingIntent.getBroadcast(ctx, 30, new Intent(ctx, Daily.class).setAction(ACTION),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        am.setWindow(AlarmManager.RTC_WAKEUP, c.getTimeInMillis(), 15 * 60_000L, pi);
    }

    // 오늘 기록된 영역을 서버에서 받아 위젯을 새로 그리고, 알림을 띄운다
    static void report(Context ctx) {
        Map<String, String> today = fetchToday(ctx);
        if (today == null) return;
        int n = 0;
        StringBuilder sb = new StringBuilder();
        for (String a : AREAS) {
            if (!today.containsKey(a)) continue;
            n++;
            if (sb.length() > 0) sb.append(" · ");
            sb.append(a);
        }
        String title = n == 0 ? "오늘 나아간 것" : "오늘 나아간 것 " + n + "칸";
        String text = n == 0 ? "아직 기록된 것이 없어요. 눌러서 봐요" : sb + " — 눌러서 일간 보고 보기";
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CH, "오늘 나아간 것(밤 9시 30분)", NotificationManager.IMPORTANCE_DEFAULT));
        Notification note = new Notification.Builder(ctx, CH)
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(Updater.openApp(ctx))
                .setAutoCancel(true)
                .build();
        try { nm.notify(2130, note); } catch (SecurityException ignored) {}
    }

    // 오늘 기록이 있는 영역 → 항목 글. 로그인 안 했거나 인터넷이 안 되면 null. 위젯 불도 같이 고친다.
    static Map<String, String> fetchToday(Context ctx) {
        String t = Store.get(ctx).token();
        if (t.isEmpty()) return null;
        try {
            Net.Result r = Net.rpc("rpg_days", new JSONObject().put("p_token", t).toString());
            if (Net.authFailed(r)) { Store.get(ctx).setToken(""); return null; }
            if (r.status != 200) return null;
            JSONObject o = new JSONObject(r.body);
            String day = o.optString("today");
            JSONArray rows = o.optJSONArray("rows");
            Map<String, String> m = new LinkedHashMap<>();
            for (int i = rows.length() - 1; i >= 0; i--) {
                JSONArray x = rows.getJSONArray(i);
                if (!day.equals(x.optString(0))) break; // 날짜 순이라 뒤에서부터 오늘만
                String a = x.optString(1), item = x.optString(2);
                m.put(a, m.containsKey(a) ? m.get(a) + " · " + item : item);
            }
            Widget.markToday(ctx, day, m.keySet());
            return m;
        } catch (Exception e) {
            return null;
        }
    }

    // ---- 1시간마다: 위젯 새로 그리기 + 새 버전 알림 ----
    public static class Job extends android.app.job.JobService {
        static void schedule(Context ctx) {
            JobScheduler js = ctx.getSystemService(JobScheduler.class);
            if (js.getPendingJob(31) != null) return;
            js.schedule(new JobInfo.Builder(31, new ComponentName(ctx, Job.class))
                    .setPeriodic(60 * 60_000L)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPersisted(true)
                    .build());
        }

        @Override
        public boolean onStartJob(android.app.job.JobParameters p) {
            Context app = getApplicationContext();
            new Thread(() -> {
                try {
                    fetchToday(app);
                    Updater.notifyIfNew(app);
                } finally { jobFinished(p, false); }
            }).start();
            return true;
        }

        @Override
        public boolean onStopJob(android.app.job.JobParameters p) { return true; }
    }
}
