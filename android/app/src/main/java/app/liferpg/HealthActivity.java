package app.liferpg;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.provider.Settings;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import org.json.JSONObject;

import java.io.File;

// 🏠 집 = 건강 기록 화면 그 자체(2026-11-15 룡 「기록 앱은 동일하고, 그걸 비추는 창인 라이프 알피지만 있고 없고」).
// 건강 기록 앱(app.liferoom) MainActivity와 같은 일을 한다: 같은 사이트(life-room)를 띄우고, 사이트가 부르는 폰 다리 LifeRoom
// (lock·unlock·isLocked·openPinSettings), 사진 고르기·찍기, 취침 잠금(LockService) 안내, 배터리 「제한 없음」 안내, 뒤로 가기.
// 화면 코드는 사이트 한 벌뿐이라 건강 기록을 고치면 여기도 같이 바뀐다.
// 더한 것 하나: RPG 로그인(업무 기록 아이디)으로 건강 기록에도 바로 들어간다(rpg_life_token). 안 되면 사이트 로그인 화면이 한 번 뜬다.
public class HealthActivity extends Activity {
    static final String BASE = "https://ggorotls-prog.github.io/life-room/";
    private static final int PICK = 7;

    private WebView web;
    private ValueCallback<Uri[]> pickCallback;
    private File cameraFile;
    private boolean triedToken;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.parseColor("#f6f5f1"));
        web = new WebView(this);
        web.setImportantForAutofill(android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        root.addView(web);
        setContentView(root);

        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            getWindow().getInsetsController().setSystemBarsAppearance(
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
            root.setOnApplyWindowInsetsListener((v, insets) -> {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                android.graphics.Insets ime = insets.getInsets(WindowInsets.Type.ime());
                v.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, ime.bottom));
                return WindowInsets.CONSUMED;
            });
        }

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                if (Uri.parse(BASE).getHost().equals(req.getUrl().getHost())) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, req.getUrl())); } catch (Exception ignored) {}
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) { signInOnce(); }

            @Override
            public boolean onRenderProcessGone(WebView view, android.webkit.RenderProcessGoneDetail detail) {
                root.removeView(view);
                view.destroy();
                recreate();
                return true;
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> cb, FileChooserParams params) {
                if (pickCallback != null) pickCallback.onReceiveValue(null);
                pickCallback = cb;
                Intent chooser = Intent.createChooser(
                        new Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*"), "사진 올리기");
                Intent camera = cameraIntent();
                if (camera != null) chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, new Intent[]{camera});
                try {
                    startActivityForResult(chooser, PICK);
                } catch (Exception e) {
                    pickCallback.onReceiveValue(null);
                    pickCallback = null;
                }
                return true;
            }
        });

        web.addJavascriptInterface(new Bridge(), "LifeRoom");
        web.loadUrl(BASE);
        HealthReminder.schedule(this);
        askAccessibility(false);
    }

    // RPG 로그인 → 건강 기록 로그인. 사이트에 열쇠(life_token)가 없을 때 한 번만 서버에서 받아 넣고 새로 연다.
    private void signInOnce() {
        if (triedToken || web == null) return;
        triedToken = true;
        String rpg = Store.get(this).token();
        if (rpg.isEmpty()) return;
        web.evaluateJavascript("(function(){try{return localStorage.getItem('life_token')||''}catch(e){return ''}})()", have -> {
            if (have != null && have.length() > 2) return; // 이미 들어가 있음("" 는 길이 2)
            new Thread(() -> {
                try {
                    Net.Result r = Net.rpc("rpg_life_token", new JSONObject().put("p_token", rpg).toString());
                    if (r.status != 200) return;
                    String t = new JSONObject(r.body).optString("token", "");
                    if (t.isEmpty()) return;
                    runOnUiThread(() -> { if (web != null) web.evaluateJavascript(
                            "try{localStorage.setItem('life_token'," + JSONObject.quote(t) + ");location.reload()}catch(e){}", null); });
                } catch (Exception ignored) {}
            }).start();
        });
    }

    private boolean accessibilityOn() {
        try {
            String on = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            return on != null && on.contains(getPackageName() + "/");
        } catch (Exception e) { return false; }
    }

    private boolean lockReady() { return accessibilityOn() && LockService.running(); }

    private AlertDialog accDialog;

    private void askAccessibility(boolean force) {
        if (lockReady()) return;
        if (accDialog != null && accDialog.isShowing()) return;
        if (!force && getSharedPreferences(LockService.PREFS, MODE_PRIVATE).getBoolean("acc_asked", false)) return;
        getSharedPreferences(LockService.PREFS, MODE_PRIVATE).edit().putBoolean("acc_asked", true).apply();
        accDialog = new AlertDialog.Builder(this)
                .setTitle("취침 잠금을 켜 주세요 (한 번만)")
                .setMessage("「잘게요」 뒤 일어날 때까지 다른 앱을 못 쓰게 하려면 이 순서로 한 번만 해 주세요.\n\n① 접근성 → 설치된 앱 → 라이프 RPG 눌러 보기 (「제한된 설정」 창이 뜨면 확인)\n② 앱 정보 → 오른쪽 위 ⋮ → 「제한된 설정 허용」 (①을 해야 ⋮에 나와요)\n③ 다시 접근성 → 라이프 RPG → 켜기\n\n이미 켜져 있는데 안 잠기면: 접근성 → 라이프 RPG를 껐다가 다시 켜 주세요.")
                .setPositiveButton("② 앱 정보", (d, w) -> {
                    try { startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))); } catch (Exception ignored) {}
                })
                .setNeutralButton("①③ 접근성", (d, w) -> {
                    try { startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); } catch (Exception ignored) {}
                })
                .setNegativeButton("나중에", null)
                .show();
    }

    private void js(String code) { runOnUiThread(() -> { if (web != null) web.evaluateJavascript(code, null); }); }

    private class Bridge {
        @JavascriptInterface
        public void lock() {
            runOnUiThread(() -> {
                if (!lockReady()) {
                    js("window.lifeLockFailed && lifeLockFailed()");
                    askAccessibility(true);
                    return;
                }
                LockService.setLocked(HealthActivity.this, true);
            });
        }

        @JavascriptInterface
        public void unlock() { LockService.setLocked(HealthActivity.this, false); }

        @JavascriptInterface
        public boolean isLocked() { return lockReady() && LockService.isLocked(HealthActivity.this); }

        @JavascriptInterface
        public void openPinSettings() { runOnUiThread(() -> askAccessibility(true)); }

        // 사이트 안내 글에 쓸 앱 이름(건강 기록 앱에는 이 칸이 없어 사이트가 「건강 기록」으로 쓴다)
        @JavascriptInterface
        public String appName() { return "라이프 RPG"; }
    }

    private Intent cameraIntent() {
        try {
            File dir = new File(getCacheDir(), "cam");
            dir.mkdirs();
            cameraFile = new File(dir, "photo.jpg");
            if (cameraFile.exists()) cameraFile.delete();
            Uri uri = androidx.core.content.FileProvider.getUriForFile(this, getPackageName() + ".files", cameraFile);
            return new Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                    .putExtra(MediaStore.EXTRA_OUTPUT, uri)
                    .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception e) {
            return null;
        }
    }

    private long pausedAt = 0;

    @Override
    protected void onPause() { super.onPause(); pausedAt = System.currentTimeMillis(); }

    @Override
    protected void onResume() {
        super.onResume();
        if (pausedAt > 0 && System.currentTimeMillis() - pausedAt > 10 * 60 * 1000 && web != null) web.reload();
        if (!LockService.isLocked(this)) askBattery();
    }

    // 배터리 「제한 없음」: 건강 기록 앱과 같은 안내(폰이 잠금 서비스를 재우면 「잘게요」가 풀린다). 열 때마다 한 번
    private static boolean batteryAsked = false;

    private void askBattery() {
        android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
        if (isFinishing() || batteryAsked || LockService.isLocked(this)) return;
        if (accDialog != null && accDialog.isShowing()) return;
        if (pm == null || pm.isIgnoringBatteryOptimizations(getPackageName())) return;
        batteryAsked = true;
        new AlertDialog.Builder(this)
                .setTitle("배터리 「제한 없음」으로 해 주세요")
                .setMessage("배터리 제한이 걸려 있으면 폰이 밤에 취침 잠금을 꺼 버려요.\n\n「켜기」를 누르고 다음 창에서 「허용」을 눌러 주세요.\n(창이 안 뜨면: 앱 정보 → 배터리 → 「제한 없음」)")
                .setPositiveButton("켜기", (d, w) -> {
                    try {
                        startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName())));
                    } catch (Exception e) {
                        try { startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))); } catch (Exception ignored) {}
                    }
                })
                .setNegativeButton("나중에", null)
                .show();
        android.util.Log.i("LifeRPG", "battery dialog shown");
    }

    // 뒤로 가기: 사이트의 appBack 먼저, 더 갈 데가 없으면 마을로. 자는 중에는 안 나간다(건강 기록 앱과 같음)
    @Override
    public void onBackPressed() {
        if (LockService.isLocked(this)) return;
        if (web == null) { finish(); return; }
        web.evaluateJavascript("(function(){try{return window.appBack?appBack():false}catch(e){return false}})()", r -> {
            if ("true".equals(r)) return;
            finish();
        });
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != PICK || pickCallback == null) return;
        Uri[] result = null;
        if (res == RESULT_OK) {
            if (data != null && data.getData() != null) {
                result = new Uri[]{data.getData()};
            } else if (cameraFile != null && cameraFile.length() > 0) {
                result = new Uri[]{androidx.core.content.FileProvider.getUriForFile(this, getPackageName() + ".files", cameraFile)};
            }
        }
        pickCallback.onReceiveValue(result);
        pickCallback = null;
    }
}
