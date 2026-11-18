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

    // ---- 신청·승인 알림(2026-11-17): 새 서버 없이 아래 15분 확인에서 본다 ----
    // 주인: 기다리는 신청이 늘면 「새 신청 N건」. 구성원: 신청한 기록이 열리면 「○○ 기록이 열렸어요」.
    // 로그인 전(가입 신청함): 승인·거절되면 알림. 서버 함수가 아직 없거나 인터넷이 안 되면 조용히 넘어간다.
    private static final String CH_NOTE = "notice";
    static void notices(Context ctx) {
        Store st = Store.get(ctx);
        try {
            String t = st.token();
            if (!t.isEmpty()) {
                Net.Result r = Net.rpc("rpg_me", new JSONObject().put("p_token", t).toString());
                if (r.status != 200) return;
                JSONObject o = new JSONObject(r.body);
                if (o.optBoolean("owner")) {
                    int n = o.optInt("pending");
                    if (n > st.seenPending()) notice(ctx, 2140, "새 신청 " + n + "건", "눌러서 승인하거나 거절해 주세요");
                    st.setSeenPending(n);
                } else {
                    JSONObject packs = o.optJSONObject("packs"), before = new JSONObject(st.seenPacks());
                    StringBuilder opened = new StringBuilder();
                    if (packs != null) for (java.util.Iterator<String> it = packs.keys(); it.hasNext(); ) {
                        String k = it.next();
                        if (!"open".equals(packs.optString(k)) || !"requested".equals(before.optString(k))) continue;
                        if (opened.length() > 0) opened.append(" · ");
                        opened.append(k);
                    }
                    if (opened.length() > 0) notice(ctx, 2141, opened + " 기록이 열렸어요", "눌러서 마을에서 보세요");
                    st.setSeenPacks(packs == null ? "{}" : packs.toString());
                }
                return;
            }
            String nick = st.waitNick();
            if (nick.isEmpty()) return;
            Net.Result r = Net.rpc("rpg_join_status", new JSONObject().put("p_nick", nick).toString());
            if (r.status != 200) return;
            String s = new JSONObject(r.body).optString("status");
            if ("approved".equals(s)) { notice(ctx, 2142, "가입이 승인됐어요", nick + "(으)로 로그인해 주세요"); st.setWait(""); }
            else if ("rejected".equals(s)) { notice(ctx, 2142, "가입이 승인되지 않았어요", "궁금하면 초대한 사람(관리자)에게 물어봐 주세요"); st.setWait(""); }
            // 거절하면 신청 줄을 바로 지워서 'none'이 온다(2026-11-18 룡)
            else if ("none".equals(s)) { notice(ctx, 2142, "가입 신청이 승인되지 않았어요", "승인되지 않았거나 지워졌어요. 궁금하면 초대한 사람(관리자)에게 물어봐 주세요"); st.setWait(""); }
        } catch (Exception ignored) {}
    }

    private static void notice(Context ctx, int id, String title, String text) {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CH_NOTE, "신청·승인 알림", NotificationManager.IMPORTANCE_DEFAULT));
        Notification note = new Notification.Builder(ctx, CH_NOTE)
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(Updater.openApp(ctx))
                .setAutoCancel(true)
                .build();
        try { nm.notify(id, note); } catch (SecurityException ignored) {}
    }

    // ---- 15분마다: 위젯 새로 그리기 + 새 버전 알림 (2026-11-17 룡: 다른 기록 앱과 같게 1시간 → 15분) ----
    public static class Job extends android.app.job.JobService {
        static final long EVERY = 15 * 60_000L;
        static void schedule(Context ctx) {
            JobScheduler js = ctx.getSystemService(JobScheduler.class);
            JobInfo old = js.getPendingJob(31);
            if (old != null && old.getIntervalMillis() == EVERY) return; // 옛 1시간 예약이면 같은 번호로 다시 건다(덮어씀)
            js.schedule(new JobInfo.Builder(31, new ComponentName(ctx, Job.class))
                    .setPeriodic(EVERY)
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
                    notices(app);
                } finally { jobFinished(p, false); }
            }).start();
            return true;
        }

        @Override
        public boolean onStopJob(android.app.job.JobParameters p) { return true; }
    }
}
