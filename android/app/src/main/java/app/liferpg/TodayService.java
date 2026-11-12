package app.liferpg;

import android.Manifest;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.provider.CalendarContract;
import android.view.View;
import android.widget.RemoteViews;
import android.widget.RemoteViewsService;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

// 위젯 목록: 오늘 일정(폰 캘린더) + 내 할 일. 나누기는 업무 기록 캘린더 위젯(CalWidgetService)과 같다.
// 요청받은 일 → 오늘 줄(지난 할 일 · 하루 종일 · 시간 순, 지금 줄) → 적어 둔 일.
public class TodayService extends RemoteViewsService {
    @Override
    public RemoteViewsFactory onGetViewFactory(Intent intent) { return new Factory(getApplicationContext()); }

    static final int HEAD = 0, NOW = 1, EVENT = 2, TASK = 3, INFO = 4;
    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    static final class Row {
        final int kind;
        String time = "", title = "", sub = "", id = "";
        int sort = -1, color = 0;
        boolean mine = true, faded, loose, perm, late;
        long eventId = -1, begin, end;
        Row(int kind) { this.kind = kind; }
    }

    static final class Factory implements RemoteViewsFactory {
        private final Context ctx;
        private List<Row> rows = new ArrayList<>();

        Factory(Context ctx) { this.ctx = ctx; }
        @Override public void onCreate() {}
        @Override public void onDestroy() {}

        private SharedPreferences prefs() { return ctx.getSharedPreferences("rpg-widget", Context.MODE_PRIVATE); }

        @Override
        public void onDataSetChanged() {
            ZonedDateTime now = ZonedDateTime.now(SEOUL);
            LocalDate today = now.toLocalDate();
            String t0 = today.toString(), me = null;
            JSONArray tasks = null;
            String token = Store.get(ctx).token();
            if (!token.isEmpty()) {
                try {
                    Net.Result r = Net.rpc("rpg_home", new JSONObject().put("p_token", token).toString());
                    if (r.status == 200) {
                        JSONObject o = new JSONObject(r.body);
                        me = o.optString("name");
                        tasks = o.optJSONArray("tasks");
                        prefs().edit().putInt("check", o.optInt("to_check")).apply();
                        prefs().edit().putString("name", me).putString("tasks", tasks == null ? "[]" : tasks.toString()).apply();
                    }
                } catch (Exception ignored) {}
                if (tasks == null) { // 인터넷이 안 되면 마지막에 받아 둔 것
                    try { me = prefs().getString("name", null); tasks = new JSONArray(prefs().getString("tasks", "[]")); } catch (Exception ignored) {}
                }
            }

            List<Row> day = new ArrayList<>(), loose = new ArrayList<>(), asked = new ArrayList<>();
            boolean allowed = ctx.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED;
            if (allowed) try { day.addAll(events(today, tag(me))); } catch (Exception ignored) {}

            if (me != null && tasks != null) {
                for (int i = 0; i < tasks.length(); i++) {
                    JSONObject t = tasks.optJSONObject(i);
                    if (t == null) continue;
                    String due = t.optString("due", ""), remind = t.optString("remind", "");
                    if ("null".equals(due)) due = "";
                    if ("null".equals(remind)) remind = "";
                    String rday = remind.length() >= 10 ? remind.substring(0, 10) : "";
                    Row r = new Row(TASK);
                    r.id = t.optString("id");
                    r.title = (t.optBoolean("important") ? "★ " : "") + t.optString("title");
                    r.mine = me.equals(t.optString("requester"));
                    if (!r.mine) {
                        String when = !due.isEmpty() ? due : rday;
                        r.loose = true;
                        r.time = when;
                        r.sub = t.optString("requester") + " 요청" + (when.isEmpty() ? "" : " · " + shortDay(when) + "까지");
                        r.color = Color.parseColor(!when.isEmpty() && when.compareTo(t0) < 0 ? "#d62d1c" : "#b26a00");
                        asked.add(r);
                        continue;
                    }
                    if (due.isEmpty() && rday.isEmpty()) { r.loose = true; loose.add(r); continue; }
                    boolean late = !due.isEmpty() && due.compareTo(t0) < 0;
                    boolean hit = late || due.equals(t0) || rday.equals(t0) || (due.isEmpty() && rday.compareTo(t0) < 0);
                    if (!hit) continue;
                    if (rday.equals(t0) && remind.length() >= 16) {
                        r.time = remind.substring(11, 16);
                        r.sort = Integer.parseInt(r.time.substring(0, 2)) * 60 + Integer.parseInt(r.time.substring(3, 5));
                        r.sub = "🔔" + (due.compareTo(t0) > 0 ? " · " + shortDay(due) + "까지" : "");
                        r.color = Color.parseColor("#b26a00");
                    } else if (late) {
                        r.time = "지남"; r.sort = -2; r.late = true;
                        r.sub = shortDay(due) + "까지";
                        r.color = Color.parseColor("#d62d1c");
                    } else {
                        r.time = "하루";
                    }
                    day.add(r);
                }
            }
            day.sort((a, b) -> a.sort - b.sort);
            // 머리 줄 숫자(요청받은 일 · 확인할 일)를 남기고 머리만 다시 그린다
            prefs().edit().putInt("asked", asked.size()).apply();
            try {
                RemoteViews head = new RemoteViews(ctx.getPackageName(), R.layout.widget);
                Widget.todoBar(ctx, head);
                android.appwidget.AppWidgetManager m = android.appwidget.AppWidgetManager.getInstance(ctx);
                m.partiallyUpdateAppWidget(m.getAppWidgetIds(new android.content.ComponentName(ctx, Widget.class)), head);
            } catch (Exception ignored) {}

            List<Row> out = new ArrayList<>();
            if (!asked.isEmpty()) {
                asked.sort((a, b) -> a.time.isEmpty() != b.time.isEmpty() ? (a.time.isEmpty() ? 1 : -1) : a.time.compareTo(b.time));
                Row h = new Row(HEAD); h.title = "요청받은 일"; h.sub = String.valueOf(asked.size()); h.color = Color.parseColor("#b26a00");
                out.add(h); out.addAll(asked);
            }
            if (!allowed) {
                Row p = new Row(INFO); p.title = "눌러서 캘린더 보기를 허용해 주세요"; p.sub = "앱을 열면 허용 창이 떠요"; p.perm = true;
                out.add(p);
            }
            int nowMin = now.getHour() * 60 + now.getMinute();
            boolean timed = false, placed = false;
            for (Row r : day) if (r.sort >= 0) timed = true;
            for (Row r : day) {
                if (timed && !placed && r.sort > nowMin) { out.add(new Row(NOW)); placed = true; }
                out.add(r);
            }
            if (timed && !placed) out.add(new Row(NOW));
            if (day.isEmpty()) {
                Row e = new Row(INFO);
                e.title = token.isEmpty() ? "눌러서 앱을 열고 로그인해 주세요" : "오늘은 비어 있어요";
                out.add(e);
            }
            if (!loose.isEmpty()) {
                Row h = new Row(HEAD); h.title = "적어 둔 일"; h.sub = String.valueOf(loose.size()); h.color = Color.parseColor("#2f5d50");
                out.add(h); out.addAll(loose);
            }
            rows = out;
        }

        private List<Row> events(LocalDate today, String tag) {
            List<Row> out = new ArrayList<>();
            long from = today.atStartOfDay(SEOUL).toInstant().toEpochMilli();
            long to = today.plusDays(1).atStartOfDay(SEOUL).toInstant().toEpochMilli();
            Uri.Builder b = CalendarContract.Instances.CONTENT_URI.buildUpon();
            ContentUris.appendId(b, from);
            ContentUris.appendId(b, to - 1);
            String[] cols = {CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.TITLE,
                    CalendarContract.Instances.BEGIN, CalendarContract.Instances.END,
                    CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.CALENDAR_DISPLAY_NAME,
                    CalendarContract.Instances.STATUS};
            try (Cursor c = ctx.getContentResolver().query(b.build(), cols,
                    CalendarContract.Instances.VISIBLE + "=1", null, CalendarContract.Instances.BEGIN + " ASC")) {
                if (c == null) return out;
                while (c.moveToNext()) {
                    String cal = c.getString(5) == null ? "" : c.getString(5);
                    if (cal.contains("휴일") || cal.contains("Holiday") || cal.contains("생일") || cal.contains("Birthday")) continue;
                    if (c.getInt(6) == CalendarContract.Events.STATUS_CANCELED) continue;
                    long begin = c.getLong(2), end = c.getLong(3);
                    Row r = new Row(EVENT);
                    r.eventId = c.getLong(0); r.begin = begin; r.end = end;
                    r.title = c.getString(1) == null || c.getString(1).isEmpty() ? "(제목 없음)" : c.getString(1);
                    if (c.getInt(4) == 1) {
                        LocalDate bd = Instant.ofEpochMilli(begin).atZone(ZoneOffset.UTC).toLocalDate();
                        LocalDate ed = Instant.ofEpochMilli(end).atZone(ZoneOffset.UTC).toLocalDate();
                        if (today.isBefore(bd) || !today.isBefore(ed)) continue;
                        r.time = "하루";
                    } else {
                        if (end <= from) continue;
                        ZonedDateTime bs = Instant.ofEpochMilli(begin).atZone(SEOUL), es = Instant.ofEpochMilli(end).atZone(SEOUL);
                        if (begin < from) r.time = "이어짐";
                        else { r.time = hm(bs); r.sort = bs.getHour() * 60 + bs.getMinute(); }
                        if (end <= to) r.sub = "~" + hm(es);
                    }
                    r.faded = others(r.title, tag);
                    out.add(r);
                }
            }
            return out;
        }

        @Override public int getCount() { return rows.size(); }

        @Override
        public RemoteViews getViewAt(int position) {
            RemoteViews v = new RemoteViews(ctx.getPackageName(), R.layout.widget_item);
            if (position >= rows.size()) return v;
            Row r = rows.get(position);
            v.setViewVisibility(R.id.wHead, r.kind == HEAD ? View.VISIBLE : View.GONE);
            v.setViewVisibility(R.id.wNow, r.kind == NOW ? View.VISIBLE : View.GONE);
            v.setViewVisibility(R.id.wBody, r.kind >= EVENT ? View.VISIBLE : View.GONE);
            if (r.kind == HEAD) {
                v.setTextViewText(R.id.wHeadText, r.title);
                v.setTextViewText(R.id.wHeadCount, r.sub);
                v.setTextColor(R.id.wHeadText, r.color);
                v.setTextColor(R.id.wHeadCount, r.color);
                return v;
            }
            if (r.kind == NOW) return v;
            boolean task = r.kind == TASK, event = r.kind == EVENT;
            v.setViewVisibility(R.id.wTime, r.loose || r.kind == INFO ? View.GONE : View.VISIBLE);
            v.setTextViewText(R.id.wTime, r.time);
            v.setTextColor(R.id.wTime, Color.parseColor(r.late ? "#d62d1c" : "#6b6a64"));
            v.setViewVisibility(R.id.wBar, event ? View.VISIBLE : View.GONE);
            if (event) v.setInt(R.id.wBar, "setBackgroundColor", Color.parseColor(r.faded ? "#cfcdc5" : "#3b6fb6"));
            v.setViewVisibility(R.id.wCheck, task ? View.VISIBLE : View.GONE);
            v.setTextViewText(R.id.wTitle, r.title);
            v.setTextColor(R.id.wTitle, Color.parseColor(r.faded || (r.kind == INFO && !r.perm) ? "#9a9890" : r.perm ? "#2f5d50" : "#1f1f1d"));
            v.setViewVisibility(R.id.wSub, r.sub.isEmpty() ? View.GONE : View.VISIBLE);
            v.setTextViewText(R.id.wSub, r.sub);
            v.setTextColor(R.id.wSub, r.color != 0 ? r.color : Color.parseColor("#6b6a64"));

            Intent fill = new Intent();
            if (event) {
                fill.putExtra(WidgetAction.EXTRA_EVENT, r.eventId).putExtra(WidgetAction.EXTRA_BEGIN, r.begin).putExtra(WidgetAction.EXTRA_END, r.end);
                v.setOnClickFillInIntent(R.id.wBody, fill);
            } else if (task) {
                fill.putExtra(WidgetAction.EXTRA_TASK, r.id).putExtra(WidgetAction.EXTRA_TITLE, r.title).putExtra(WidgetAction.EXTRA_MINE, r.mine);
                v.setOnClickFillInIntent(R.id.wCheck, fill);
                v.setOnClickFillInIntent(R.id.wText, new Intent().putExtra(WidgetAction.EXTRA_OPEN, true));
            } else {
                v.setOnClickFillInIntent(R.id.wBody, new Intent().putExtra(WidgetAction.EXTRA_OPEN, true));
            }
            return v;
        }

        @Override public RemoteViews getLoadingView() { return null; }
        @Override public int getViewTypeCount() { return 1; }
        @Override public long getItemId(int position) { return position; }
        @Override public boolean hasStableIds() { return false; }
    }

    static String hm(ZonedDateTime t) { return String.format("%02d:%02d", t.getHour(), t.getMinute()); }

    static String shortDay(String day) {
        try {
            LocalDate d = LocalDate.parse(day);
            return d.getMonthValue() + "/" + d.getDayOfMonth() + " (" + "월화수목금토일".charAt(d.getDayOfWeek().getValue() - 1) + ")";
        } catch (Exception e) { return day; }
    }

    // 가족 캘린더 양식 「룡 - 출근」의 이름표(업무 기록 위젯과 같음)
    static String tag(String name) {
        if (name == null) return null;
        for (String t : new String[]{"룡", "월", "영"}) if (name.contains(t)) return t;
        return null;
    }

    static boolean others(String title, String tag) {
        if (tag == null) return false;
        int cut = title.indexOf(" - ");
        if (cut <= 0 || cut > 12) return false;
        String who = title.substring(0, cut);
        return !who.contains(tag) && !who.contains("가족") && !who.contains("모두");
    }
}
