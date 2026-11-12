package app.liferpg;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

// 화면은 앱 안의 index.html(assets). 서버는 Native.rpc로만 부른다(rpg_ 함수).
// 뒤로 가기: 화면의 appBack()이 열린 창을 닫으면 true, 아니면 앱 닫힘(새 앱 규칙, 2026-11-11).
public class MainActivity extends Activity {
    static volatile boolean visible;

    private WebView web;
    private boolean loaded, backFromSecurity;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        int bg = Color.parseColor("#f6f5f1");
        getWindow().setStatusBarColor(bg);
        getWindow().setNavigationBarColor(bg);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);

        web = new WebView(this);
        web.setBackgroundColor(bg);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setTextZoom(100);
        web.addJavascriptInterface(new Bridge(), "Native");
        web.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView v, String url) { loaded = true; openFromIntent(); }
            @Override public boolean shouldOverrideUrlLoading(WebView v, String url) { return true; }
            @Override public boolean onRenderProcessGone(WebView v, android.webkit.RenderProcessGoneDetail d) {
                try {
                    android.view.ViewGroup parent = (android.view.ViewGroup) v.getParent();
                    if (parent != null) parent.removeView(v);
                    v.destroy();
                } catch (Exception ignored) {}
                web = null;
                loaded = false;
                recreate();
                return true;
            }
        });
        web.loadUrl("file:///android_asset/index.html");
        android.widget.FrameLayout root = new android.widget.FrameLayout(this);
        root.setBackgroundColor(bg);
        root.addView(web);
        setContentView(root);
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            root.setOnApplyWindowInsetsListener((v, insets) -> {
                android.graphics.Insets bars = insets.getInsets(android.view.WindowInsets.Type.systemBars());
                android.graphics.Insets ime = insets.getInsets(android.view.WindowInsets.Type.ime());
                v.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, ime.bottom));
                return android.view.WindowInsets.CONSUMED;
            });
        } else {
            root.setFitsSystemWindows(true);
        }
        Daily.schedule(this);
        Daily.Job.schedule(this);
        take(getIntent());
        if (android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
    }

    // 위젯 버튼(🏠 집 · 👛 지갑 · ⛪ 아빠 집)으로 열렸으면 그 창을 연다
    static final String EXTRA_OPEN = "open";
    private String openWhat;

    private void take(Intent i) { if (i != null && i.getStringExtra(EXTRA_OPEN) != null) openWhat = i.getStringExtra(EXTRA_OPEN); }

    private void openFromIntent() {
        if (openWhat == null || !loaded) return;
        js("window.openFrom && openFrom(" + JSONObject.quote(openWhat) + ")");
        openWhat = null;
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        take(i);
        openFromIntent();
    }

    @Override
    protected void onResume() {
        super.onResume();
        visible = true;
        if (loaded) js("window.onNative && onNative('resume')");
        Context app = getApplicationContext();
        boolean retry = backFromSecurity;
        backFromSecurity = false;
        new Thread(() -> {
            int latest = Updater.latest(app);
            if (latest <= BuildConfig.VERSION_CODE) return;
            if (!Updater.canInstall(this)) { runOnUiThread(this::askInstallPermission); return; }
            if (retry) { Updater.checkAndInstall(app, true, true); return; }
            if (!Updater.askedRecently(app, latest)) runOnUiThread(this::askUpdate);
        }).start();
    }

    @Override
    protected void onPause() {
        visible = false;
        super.onPause();
    }

    @Override
    public void onBackPressed() {
        if (!loaded || web == null) { super.onBackPressed(); return; }
        web.evaluateJavascript("window.appBack ? appBack() : false", v -> {
            if (!"true".equals(v)) finish();
        });
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        js("window.onNative && onNative('resume')");
    }

    private void js(String code) {
        runOnUiThread(() -> { if (web != null) web.evaluateJavascript(code, null); });
    }

    private class Bridge {
        @JavascriptInterface public String token() { return Store.get(MainActivity.this).token(); }
        @JavascriptInterface public void setToken(String t) {
            Store.get(MainActivity.this).setToken(t);
            Widget.draw(getApplicationContext());
        }
        @JavascriptInterface public String version() { return BuildConfig.VERSION_NAME; }

        // 화면이 계산한 요약 → 위젯
        @JavascriptInterface public void setSummary(String json) {
            Store.get(MainActivity.this).setSummary(json);
            Widget.draw(getApplicationContext());
        }

        @JavascriptInterface public void rpc(String fn, String json, String cb) {
            new Thread(() -> {
                int status;
                String body;
                try {
                    Net.Result r = Net.rpc(fn, json);
                    status = r.status;
                    body = r.body;
                    if (Net.authFailed(r)) Store.get(MainActivity.this).setToken("");
                } catch (Exception e) {
                    status = 0;
                    body = "";
                }
                js("__rpc(" + JSONObject.quote(cb) + "," + status + "," + JSONObject.quote(body) + ")");
            }).start();
        }

        @JavascriptInterface public boolean battery() {
            PowerManager pm = getSystemService(PowerManager.class);
            return pm == null || pm.isIgnoringBatteryOptimizations(getPackageName());
        }
        @JavascriptInterface public void openBattery() {
            open(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName())));
        }
        // 오늘 일정: 폰 캘린더(구글 캘린더가 이미 들어와 있음). 공휴일·생일·취소는 뺀다. 권한 없으면 null
        @JavascriptInterface public String calendar(String day) {
            if (checkSelfPermission(android.Manifest.permission.READ_CALENDAR) != android.content.pm.PackageManager.PERMISSION_GRANTED) return null;
            org.json.JSONArray out = new org.json.JSONArray();
            try {
                java.time.ZoneId z = java.time.ZoneId.of("Asia/Seoul");
                java.time.LocalDate d = java.time.LocalDate.parse(day);
                long from = d.atStartOfDay(z).toInstant().toEpochMilli(), to = d.plusDays(1).atStartOfDay(z).toInstant().toEpochMilli();
                Uri.Builder b = android.provider.CalendarContract.Instances.CONTENT_URI.buildUpon();
                android.content.ContentUris.appendId(b, from);
                android.content.ContentUris.appendId(b, to - 1);
                String[] cols = {android.provider.CalendarContract.Instances.EVENT_ID, android.provider.CalendarContract.Instances.TITLE,
                        android.provider.CalendarContract.Instances.BEGIN, android.provider.CalendarContract.Instances.END,
                        android.provider.CalendarContract.Instances.ALL_DAY, android.provider.CalendarContract.Instances.CALENDAR_DISPLAY_NAME,
                        android.provider.CalendarContract.Instances.STATUS};
                try (android.database.Cursor c = getContentResolver().query(b.build(), cols,
                        android.provider.CalendarContract.Instances.VISIBLE + "=1", null, android.provider.CalendarContract.Instances.BEGIN + " ASC")) {
                    while (c != null && c.moveToNext()) {
                        String cal = c.getString(5) == null ? "" : c.getString(5);
                        if (cal.contains("휴일") || cal.contains("Holiday") || cal.contains("생일") || cal.contains("Birthday")) continue;
                        if (c.getInt(6) == android.provider.CalendarContract.Events.STATUS_CANCELED) continue;
                        long bg = c.getLong(2), en = c.getLong(3);
                        boolean allDay = c.getInt(4) == 1;
                        if (allDay) {
                            // 하루 종일 일정은 UTC 자정 기준(업무 기록 위젯과 같은 처리)
                            java.time.LocalDate bd = java.time.Instant.ofEpochMilli(bg).atZone(java.time.ZoneOffset.UTC).toLocalDate();
                            java.time.LocalDate ed = java.time.Instant.ofEpochMilli(en).atZone(java.time.ZoneOffset.UTC).toLocalDate();
                            if (d.isBefore(bd) || !d.isBefore(ed)) continue;
                        } else if (en <= from) continue;
                        out.put(new JSONObject().put("id", c.getLong(0)).put("title", c.getString(1) == null ? "" : c.getString(1))
                                .put("begin", bg).put("end", en).put("allDay", allDay));
                    }
                }
            } catch (Exception e) { return "[]"; }
            return out.toString();
        }

        @JavascriptInterface public void askCalendar() {
            runOnUiThread(() -> requestPermissions(new String[]{android.Manifest.permission.READ_CALENDAR}, 3));
        }

        // 일정을 누르면 구글 캘린더 앱에서 그 일정
        @JavascriptInterface public void openEvent(String id, String begin, String end) {
            try {
                Intent i = new Intent(Intent.ACTION_VIEW, android.content.ContentUris.withAppendedId(android.provider.CalendarContract.Events.CONTENT_URI, Long.parseLong(id)))
                        .putExtra(android.provider.CalendarContract.EXTRA_EVENT_BEGIN_TIME, Long.parseLong(begin))
                        .putExtra(android.provider.CalendarContract.EXTRA_EVENT_END_TIME, Long.parseLong(end));
                open(i);
            } catch (Exception ignored) {}
        }

        // 지갑: 재정 기록 앱의 빠른 기록 창(재정 위젯과 똑같은 창)을 연다. 재정 기록 앱이 없으면 false → 화면이 자기 창을 띄움
        @JavascriptInterface public boolean openWallet() {
            Intent i = Widget.walletIntent(MainActivity.this);
            if (i == null) return false;
            open(i);
            return true;
        }

        // 아빠 집: 영성 기록 앱의 그 탭(묵상·일기·가정예배)으로 연다. 오늘 날짜로 열린다(영성 기록 앱 EXTRA_TAB)
        @JavascriptInterface public void openSpirit(String tab) {
            runOnUiThread(() -> {
                Intent i = new Intent().setClassName("app.personalrecord", "app.personalrecord.MainActivity")
                        .putExtra("tab", tab).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try { startActivity(i); }
                catch (Exception e) {
                    try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://personal-record-alpha.vercel.app/?tab=" + Uri.encode(tab)))); } catch (Exception ignored) {}
                }
            });
        }

        // 묵상 이미지 공유: 사진(Pictures/라이프 RPG)에 저장하고 그 사진을 공유 창으로(카톡 등)
        @JavascriptInterface public void shareImage(String dataUrl, String name) {
            try {
                byte[] png = android.util.Base64.decode(dataUrl.substring(dataUrl.indexOf(',') + 1), android.util.Base64.DEFAULT);
                android.content.ContentValues v = new android.content.ContentValues();
                v.put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, name);
                v.put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png");
                v.put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/라이프 RPG");
                Uri uri = getContentResolver().insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
                if (uri == null) throw new IllegalStateException();
                try (java.io.OutputStream o = getContentResolver().openOutputStream(uri)) { o.write(png); }
                Intent send = new Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                open(Intent.createChooser(send, "묵상 보내기"));
            } catch (Exception e) {
                js("toast('공유 창을 열지 못했어요')");
            }
        }

        // 업무 기록의 ＋ 할 일 적기 창 / 맡기기 창(업무 기록 위젯과 같은 창)
        @JavascriptInterface public void todoAdd() { Intent i = Widget.todoIntent(MainActivity.this, "QuickAddActivity", null); if (i != null) open(i); else openTodo(); }
        @JavascriptInterface public void todoAssign() { Intent i = Widget.todoIntent(MainActivity.this, "MainActivity", "assign"); if (i != null) open(i); else openTodo(); }

        // 업무 기록 앱을 연다(요청받은 일·새 할 일)
        @JavascriptInterface public void openTodo() {
            runOnUiThread(() -> {
                Intent i = getPackageManager().getLaunchIntentForPackage("app.todohub");
                if (i == null) i = new Intent(Intent.ACTION_VIEW, Uri.parse("https://todo--hub.vercel.app"));
                try { startActivity(i); } catch (Exception ignored) {}
            });
        }

        // 아빠 집: 영성 기록 앱을 연다(없으면 사이트)
        @JavascriptInterface public void openApp(String pkg, String url) {
            runOnUiThread(() -> {
                Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
                if (i == null) i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                try { startActivity(i); } catch (Exception e) { js("toast('영성 기록을 열 수 없어요')"); }
            });
        }

        @JavascriptInterface public void testDaily() {
            new Thread(() -> Daily.report(getApplicationContext())).start();
        }
    }

    private void open(Intent i) {
        runOnUiThread(() -> {
            try { startActivity(i); } catch (Exception e) { try { startActivity(new Intent(Settings.ACTION_SETTINGS)); } catch (Exception ignored) {} }
        });
    }

    private void askUpdate() {
        if (isFinishing()) return;
        new android.app.AlertDialog.Builder(this)
                .setTitle("새 버전이 있어요")
                .setMessage("업데이트하려면 삼성 「자동 차단」을 잠깐 꺼야 해요.\n\n「업데이트」를 누르면 보안 화면이 열려요. 「자동 차단」을 끄고 뒤로 오면 업데이트가 시작돼요.\n(자동 차단은 30분 뒤 저절로 다시 켜져요)")
                .setPositiveButton("업데이트", (d, w) -> {
                    backFromSecurity = true;
                    for (String action : new String[]{Settings.ACTION_SECURITY_SETTINGS, Settings.ACTION_SETTINGS}) {
                        try { startActivity(new Intent(action)); return; } catch (Exception ignored) {}
                    }
                })
                .setNegativeButton("나중에", null)
                .show();
    }

    private void askInstallPermission() {
        if (isFinishing()) return;
        new android.app.AlertDialog.Builder(this)
                .setTitle("새 버전이 있어요")
                .setMessage("앞으로 저절로 업데이트되게, 다음 화면에서 「이 출처 허용」을 켜 주세요. 한 번만 하면 돼요.")
                .setPositiveButton("켜러 가기", (d, w) -> {
                    try { startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName()))); } catch (Exception ignored) {}
                })
                .setNegativeButton("나중에", null)
                .show();
    }
}
