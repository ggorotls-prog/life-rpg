package app.liferpg;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Set;

// 홈 화면 위젯(본체, 설계안 2판): 캐릭터 레벨 · 진행 막대 · 오늘 6칸(기록됨 / 아직 / 준비 중).
// 누르면 앱이 열린다. 레벨은 앱을 열 때 화면이 계산해 넘겨 주고, 오늘 칸은 1시간마다 서버에서 고친다.
public class Widget extends AppWidgetProvider {
    static final int[] CELLS = {R.id.c0, R.id.c1, R.id.c2, R.id.c3, R.id.c4, R.id.c5};
    static final int ON = 0xFF3B5B8C, OFF = 0xFFC9C6BD, READY = 0xFFE4E2DB;

    @Override
    public void onUpdate(Context ctx, AppWidgetManager m, int[] ids) {
        draw(ctx);
        new Thread(() -> Daily.fetchToday(ctx.getApplicationContext())).start();
    }

    // 서버에서 받은 오늘 기록 영역으로 요약의 「on」을 고친다(날이 바뀌면 모두 꺼짐)
    static void markToday(Context ctx, String day, Set<String> areas) {
        try {
            String s = Store.get(ctx).summary();
            if (s.isEmpty()) return;
            JSONObject o = new JSONObject(s);
            JSONArray on = o.optJSONArray("on");
            for (int i = 0; i < Daily.AREAS.length && on != null; i++) {
                if (on.optInt(i) < 0) continue; // 준비 중은 그대로
                on.put(i, areas.contains(Daily.AREAS[i]) ? 1 : 0);
            }
            o.put("today", day);
            Store.get(ctx).setSummary(o.toString());
        } catch (Exception ignored) {}
        draw(ctx);
    }

    static void draw(Context ctx) {
        AppWidgetManager m = AppWidgetManager.getInstance(ctx);
        int[] ids = m.getAppWidgetIds(new ComponentName(ctx, Widget.class));
        if (ids.length == 0) return;
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
                for (int i = 0; i < CELLS.length; i++) {
                    int st = on == null ? 0 : on.optInt(i);
                    v.setTextViewText(CELLS[i], Daily.AREAS[i]);
                    v.setTextColor(CELLS[i], st > 0 ? ON : st < 0 ? READY : OFF);
                    if (st >= 0) act++;
                    if (st > 0) n++;
                }
                v.setTextViewText(R.id.sub, "오늘 " + n + "/" + act + "칸");
            } catch (Exception ignored) {}
        }
        PendingIntent open = PendingIntent.getActivity(ctx, 7, new Intent(ctx, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        v.setOnClickPendingIntent(R.id.root, open);
        m.updateAppWidget(ids, v);
    }
}
