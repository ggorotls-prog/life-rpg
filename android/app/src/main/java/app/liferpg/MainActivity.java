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
            @Override public void onPageFinished(WebView v, String url) { loaded = true; }
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
        if (android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
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
