package app.liferpg;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.RemoteViews;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Set;

// 홈 화면 위젯 = 마을 첫 화면을 줄인 것(2026-11-12 룡 「위젯으로 먼저 써 보자」).
// 맨 위: Lv · 막대 · 오늘 n칸 · 🏠 👛 ⛪ 버튼(누르면 앱에서 그 기록 창).
// 그 아래: 오늘 일정 + 할 일(업무 기록 캘린더 위젯과 같은 나누기, TodayService). 동그라미 = 완료 확인 창(WidgetAction).
public class Widget extends AppWidgetProvider {

    @Override
    public void onUpdate(Context ctx, AppWidgetManager m, int[] ids) {
        draw(ctx);
        new Thread(() -> Daily.fetchToday(ctx.getApplicationContext())).start();
    }

    // 서버에서 받은 오늘 기록 영역으로 요약의 「on」을 고친다(날이 바뀌면 모두 꺼짐)
    static void markToday(Context ctx, String day, Set<String> areas) {
        try {
            String s = Store.get(ctx).summary();
            if (!s.isEmpty()) {
                JSONObject o = new JSONObject(s);
                JSONArray on = o.optJSONArray("on");
                for (int i = 0; i < Daily.AREAS.length && on != null; i++) {
                    if (on.optInt(i) < 0) continue; // 준비 중은 그대로
                    on.put(i, areas.contains(Daily.AREAS[i]) ? 1 : 0);
                }
                o.put("today", day);
                Store.get(ctx).setSummary(o.toString());
            }
        } catch (Exception ignored) {}
        draw(ctx);
    }

    static void draw(Context ctx) {
        AppWidgetManager m = AppWidgetManager.getInstance(ctx);
        int[] ids = m.getAppWidgetIds(new ComponentName(ctx, Widget.class));
        if (ids.length == 0) return;
        for (int id : ids) {
            RemoteViews v = new RemoteViews(ctx.getPackageName(), R.layout.widget);
            String s = Store.get(ctx).summary();
            if (s.isEmpty()) {
                v.setTextViewText(R.id.lv, "라이프 RPG");
                v.setTextViewText(R.id.sub, "눌러서 로그인");
                v.setProgressBar(R.id.bar, 100, 0, false);
            } else {
                try {
                    JSONObject o = new JSONObject(s);
                    v.setTextViewText(R.id.lv, "Lv " + o.optInt("lv"));
                    v.setProgressBar(R.id.bar, 100, o.optInt("pct"), false);
                    JSONArray on = o.optJSONArray("on");
                    int n = 0, act = 0;
                    for (int i = 0; on != null && i < on.length(); i++) {
                        if (on.optInt(i) >= 0) act++;
                        if (on.optInt(i) > 0) n++;
                    }
                    v.setTextViewText(R.id.sub, "오늘 " + n + "/" + act + "칸");
                } catch (Exception ignored) {}
            }
            // 버튼: 앱을 열고 그 창
            v.setOnClickPendingIntent(R.id.head, openApp(ctx, null, 70));
            v.setOnClickPendingIntent(R.id.bHome, openApp(ctx, "home", 71));
            Intent wallet = walletIntent(ctx);
            v.setOnClickPendingIntent(R.id.bWallet, wallet != null
                    ? PendingIntent.getActivity(ctx, 72, wallet, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE)
                    : openApp(ctx, "wallet", 72));
            v.setOnClickPendingIntent(R.id.bFather, openApp(ctx, "father", 73));
            // 목록
            Intent svc = new Intent(ctx, TodayService.class).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id);
            svc.setData(Uri.parse(svc.toUri(Intent.URI_INTENT_SCHEME)));
            v.setRemoteAdapter(R.id.list, svc);
            v.setEmptyView(R.id.list, R.id.empty);
            PendingIntent tpl = PendingIntent.getActivity(ctx, 74, new Intent(ctx, WidgetAction.class),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
            v.setPendingIntentTemplate(R.id.list, tpl);
            v.setOnClickPendingIntent(R.id.empty, openApp(ctx, null, 75));
            m.updateAppWidget(id, v);
        }
        m.notifyAppWidgetViewDataChanged(ids, R.id.list);
    }

    // 지갑 = 재정 기록 앱의 빠른 기록 창(재정 위젯 − 지출 버튼과 같은 창: 금액 → 가정/기업 → 항목 → 저장, 2026-11-12 룡 「재정기록 위젯이랑 동일하게」).
    // 재정 기록 앱이 깔려 있지 않으면 null.
    static Intent walletIntent(Context ctx) {
        try { ctx.getPackageManager().getPackageInfo("app.faithbudget", 0); } catch (Exception e) { return null; }
        return new Intent().setClassName("app.faithbudget", "app.faithbudget.QuickActivity")
                .putExtra("kind", "지출").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    static PendingIntent openApp(Context ctx, String what, int code) {
        Intent i = new Intent(ctx, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (what != null) i.putExtra(MainActivity.EXTRA_OPEN, what);
        return PendingIntent.getActivity(ctx, code, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
