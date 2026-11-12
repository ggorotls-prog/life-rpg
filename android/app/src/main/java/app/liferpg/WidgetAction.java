package app.liferpg;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.CalendarContract;
import android.widget.Toast;

import org.json.JSONObject;

// 위젯 목록을 눌렀을 때(투명 창): 일정 → 구글 캘린더 / 내 할 일 동그라미 → 「완료할까요?」 → 완료 +EXP /
// 요청받은 일 → 업무 기록 앱(상대에게 「다 했어요」 알림이 가야 해서) / 그 밖 → 라이프 RPG 앱.
public class WidgetAction extends Activity {
    static final String EXTRA_EVENT = "event", EXTRA_BEGIN = "begin", EXTRA_END = "end",
            EXTRA_TASK = "task", EXTRA_TITLE = "title", EXTRA_MINE = "mine", EXTRA_OPEN = "openApp";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Intent i = getIntent();
        if (i.hasExtra(EXTRA_EVENT)) {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, i.getLongExtra(EXTRA_EVENT, 0)))
                        .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, i.getLongExtra(EXTRA_BEGIN, 0))
                        .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, i.getLongExtra(EXTRA_END, 0))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception ignored) {}
            finish();
            return;
        }
        if (i.hasExtra(EXTRA_TASK) && !i.getBooleanExtra(EXTRA_MINE, true)) {
            Intent t = getPackageManager().getLaunchIntentForPackage("app.todohub");
            if (t == null) t = new Intent(Intent.ACTION_VIEW, Uri.parse("https://todo--hub.vercel.app"));
            try { startActivity(t.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); } catch (Exception ignored) {}
            finish();
            return;
        }
        if (i.hasExtra(EXTRA_TASK)) {
            String id = i.getStringExtra(EXTRA_TASK);
            new AlertDialog.Builder(this)
                    .setTitle("완료할까요?")
                    .setMessage(i.getStringExtra(EXTRA_TITLE))
                    .setPositiveButton("완료 +EXP", (d, w) -> done(id))
                    .setNegativeButton("아니요", (d, w) -> finish())
                    .setOnCancelListener(d -> finish())
                    .show();
            return;
        }
        startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        finish();
    }

    private void done(String id) {
        Context app = getApplicationContext();
        new Thread(() -> {
            String msg;
            try {
                Net.Result r = Net.rpc("rpg_task_done", new JSONObject().put("p_token", Store.get(app).token()).put("p_id", id).toString());
                JSONObject o = r.status == 200 ? new JSONObject(r.body) : null;
                msg = o != null && o.optBoolean("ok") ? "퀘스트 완료 · 일 +EXP" : o != null ? o.optString("error", "완료하지 못했어요") : "완료하지 못했어요";
            } catch (Exception e) {
                msg = "인터넷을 확인해 주세요";
            }
            String m = msg;
            runOnUiThread(() -> {
                Toast.makeText(app, m, Toast.LENGTH_SHORT).show();
                finish();
            });
            Daily.fetchToday(app); // 오늘 칸·목록 새로
        }).start();
    }
}
