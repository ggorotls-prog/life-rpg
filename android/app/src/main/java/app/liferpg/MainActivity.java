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
    // 잠금화면 앞에 마을(LockFront): 잠금을 안 푼 동안 화면을 덮어 누름을 받는 판, 잠금이 풀렸는지 듣는 귀
    private android.widget.FrameLayout root;
    private View lockCover;
    private volatile boolean lockMode;   // 웹 다리 줄(todoAdd·open)에서도 읽는다
    // 📱 한 번에 켜기(Perms): 아직 열어 볼 것들 · 지금 설정 화면에 가 있는 것
    private final java.util.ArrayList<String> permQueue = new java.util.ArrayList<>();
    private String permWaiting;
    private boolean permLeft;   // 그 설정 화면으로 정말 나갔다(onPause를 지났다). 묻는 창이 닫히며 오는 onResume과 가르려고
    private android.app.AlertDialog dialog;   // 떠 있는 「새 버전」 창(잠금 위로 올라갈 때 닫는다)
    private final android.content.BroadcastReceiver unlocked = new android.content.BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { exitLock(); }
    };

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
        root = new android.widget.FrameLayout(this);
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
        HealthReminder.schedule(this);
        // 업무 기록: 알림 칸·15분 확인·바로 오는 알림, RPG 로그인으로 업무 기록 로그인 받아 두기(위젯·알림용)
        app.recordkit.work.Work.start(this);
        WorkApp.SignIn.ensure(this);
        // 영성 기록: 묵상 알림 다시 맞추기, RPG 로그인으로 영성 기록 로그인 받아 두기(작은 적기 창·위젯용)
        app.recordkit.spirit.Spirit.start(this);
        SpiritApp.SignIn.ensure(this);
        // 관계 기록: 15분 통화 확인, RPG 로그인으로 관계 기록 열쇠 받기
        RelApp.start(this);
        // 가상 폰 시험에서만: 이 표시로 기능을 켠다(진짜 폰에서는 다른 앱이 몰래 켜지 못하게 받지 않는다)
        if (getIntent() != null && getIntent().getBooleanExtra(LockFront.EXTRA_ON, false) && LockFront.emulator()) LockFront.set(this, true);
        LockFront.start(this);
        syncLock();
        take(getIntent());
        if (!lockMode && android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
    }

    // 위젯 버튼(🏠 집 · 👛 지갑 · ⛪ 아빠 집)으로 열렸으면 그 창을 연다
    static final String EXTRA_OPEN = "open";
    private String openWhat;

    private void take(Intent i) {
        if (i != null && i.getBooleanExtra(LockFront.EXTRA, false)) { syncLock(); return; }
        if (i == null || i.getStringExtra(EXTRA_OPEN) == null) return;
        // 2026-11-18: 바로 열지 않고 화면(openFrom)에 넘긴다. 화면이 로그인·동의·연 기록인지 본 뒤에 연다
        // (home = 건강 기록 화면, wallet = 지갑 빠른 기록, ledger = 재정 기록 화면, work = 업무 기록 화면, rel = 관계 기록 화면,
        //  spirit = 영성 기록 묵상 탭, father = 아빠 집. 가상 폰 시험도 이 길로 연다)
        openWhat = i.getStringExtra(EXTRA_OPEN);
    }

    // ── 잠금화면 앞에 마을(2026-11-18) ──
    // 잠금 위에 이 화면을 올리고, 투명한 판으로 덮어 보기만 하게 한다. 판을 누르면 잠금 풀기 → 풀리면 판을 걷는다.
    // 판을 둘지는 늘 「지금 폰 상태」로 정한다(여는 표시에 기대지 않는다): 기능이 켜져 있고, 잠겨 있거나 화면이 꺼져 있으면 판.
    // 화면이 다시 만들어져도(회전·어두운 화면 전환·웹 화면 죽음) onCreate·onResume이 다시 맞춘다.
    private void syncLock() {
        android.app.KeyguardManager km = getSystemService(android.app.KeyguardManager.class);
        PowerManager pm = getSystemService(PowerManager.class);
        boolean want = LockFront.on(this) && km != null && (km.isKeyguardLocked() || (pm != null && !pm.isInteractive()));
        if (want) enterLock(); else exitLock();
    }

    private void enterLock() {
        setShowWhenLocked(true);
        if (dialog != null) { try { dialog.dismiss(); } catch (Exception ignored) {} dialog = null; }   // 「새 버전」 창은 판이 못 덮는다
        if (loaded) js("window.onNative && onNative('lock')");   // 열려 있던 창을 닫고 마을로(적던 글·보고서가 잠금 위에 보이지 않게)
        if (lockMode) return;
        lockMode = true;
        if (web != null) {   // 키보드·화면 읽어 주기로도 아래 화면에 닿지 않게
            web.setFocusable(false);
            web.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        }
        // ＋ 수집 말풍선 자리만 구멍(2026-11-19 룡 「잠금 안 풀고 적기」): 그 자리를 누르면 판이 받지 않아 아래 마을의 말풍선이 눌린다
        android.widget.FrameLayout cover = new android.widget.FrameLayout(this) {
            @Override
            public boolean dispatchTouchEvent(android.view.MotionEvent e) {
                if (e.getActionMasked() == android.view.MotionEvent.ACTION_DOWN && inHole(e.getX() + getLeft(), e.getY() + getTop())) return false;
                return super.dispatchTouchEvent(e);
            }
        };
        android.widget.TextView hint = new android.widget.TextView(this);
        hint.setText("🔒 누르면 잠금 풀기");
        hint.setTextSize(13);
        hint.setTextColor(Color.WHITE);
        hint.setPadding(28, 12, 28, 12);
        android.graphics.drawable.GradientDrawable pill = new android.graphics.drawable.GradientDrawable();
        pill.setColor(0x99000000);
        pill.setCornerRadius(40);
        hint.setBackground(pill);
        android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(-2, -2, android.view.Gravity.CENTER_HORIZONTAL | android.view.Gravity.BOTTOM);
        lp.bottomMargin = Math.round(96 * getResources().getDisplayMetrics().density);
        cover.addView(hint, lp);
        cover.setClickable(true);
        cover.setFocusable(true);
        cover.setOnClickListener(v -> {
            android.app.KeyguardManager k = getSystemService(android.app.KeyguardManager.class);
            if (k == null || !k.isKeyguardLocked()) { exitLock(); return; }
            k.requestDismissKeyguard(this, new android.app.KeyguardManager.KeyguardDismissCallback() {
                @Override public void onDismissSucceeded() { exitLock(); }
            });
        });
        lockCover = cover;
        root.addView(cover, new android.widget.FrameLayout.LayoutParams(-1, -1));
        try { registerReceiver(unlocked, new android.content.IntentFilter(Intent.ACTION_USER_PRESENT)); } catch (Exception ignored) {}
    }

    private volatile double[] lockHole;   // ＋ 수집 말풍선 자리 {왼, 위, 오른, 아래, 웹 화면 폭} (CSS 좌표)
    private volatile double[] lockHole2;  // 아빠 집 ＋ 말풍선 자리(2026-11-26, 같은 꼴)

    // root 안의 (x, y)가 말풍선 자리인가. 판과 웹 화면은 같은 root에 겹쳐 있다. 여유는 두지 않는다(옆 사무실·🎙이 눌리지 않게)
    private boolean inHole(float x, float y) { return inHole(lockHole, x, y) || inHole(lockHole2, x, y); }

    private boolean inHole(double[] h, float x, float y) {
        if (h == null || web == null || web.getWidth() == 0 || h[2] <= h[0] || h[3] <= h[1]) return false;
        double k = web.getWidth() / h[4], pad = 0;
        double wx = x - web.getLeft(), wy = y - web.getTop();
        return wx >= h[0] * k - pad && wx <= h[2] * k + pad && wy >= h[1] * k - pad && wy <= h[3] * k + pad;
    }

    private void exitLock() {
        runOnUiThread(() -> {
            setShowWhenLocked(false);
            if (!lockMode) return;
            lockMode = false;
            if (lockCover != null) { root.removeView(lockCover); lockCover = null; }
            if (web != null) {
                web.setFocusable(true);
                web.setFocusableInTouchMode(true);
                web.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
            }
            try { unregisterReceiver(unlocked); } catch (Exception ignored) {}
            if (visible) resumeChecks();   // 잠금 위에서 바로 풀고 들어온 길: 취침 확인·새 버전 확인을 여기서
        });
    }

    @Override
    protected void onDestroy() {
        if (lockMode) { try { unregisterReceiver(unlocked); } catch (Exception ignored) {} }
        super.onDestroy();
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration c) {
        super.onConfigurationChanged(c);
        Diag.log(this, "MainActivity 화면 바뀜 " + c.screenWidthDp + "x" + c.screenHeightDp + "dp");
    }

    void openLedger() {
        runOnUiThread(() -> { try { startActivity(new Intent(this, WalletActivity.class)); } catch (Exception ignored) {} });
    }

    void openHealth() {
        runOnUiThread(() -> { try { startActivity(new Intent(this, HealthActivity.class)); } catch (Exception ignored) {} });
    }

    private void openFromIntent() {
        if (openWhat == null || !loaded) return;
        js("window.openFrom && openFrom(" + JSONObject.quote(openWhat) + ")");
        openWhat = null;
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        Diag.log(this, "MainActivity 다시 불림 " + Diag.how(i));
        take(i);
        openFromIntent();
    }

    @Override
    protected void onResume() {
        super.onResume();
        visible = true;
        if (loaded) js("window.onNative && onNative('resume')");
        // 한 번에 켜기: 설정 화면에 갔다 왔으면 다음 것을 연다(새 버전 창 같은 것은 다 끝난 뒤에)
        if (permWaiting != null && permLeft) {
            permWaiting = null;
            permLeft = false;
            if (web != null) web.postDelayed(this::permStep, 400);
            return;
        }
        // 잠금화면 위에 떠 있는 동안에는 보기만: 업데이트·알림을 묻지 않는다. 잠금이 풀렸으면 syncLock이 판을 걷고 아래 확인을 부른다
        LockFront.start(this);
        boolean was = lockMode;
        syncLock();
        if (lockMode || was) return;
        resumeChecks();
    }

    private void resumeChecks() {
        // 자는 중(잘게요 뒤)에는 마을 대신 건강 기록 화면으로(건강 기록 앱이 그 화면에 고정되는 것과 같게). 업데이트도 묻지 않는다
        if (LockService.isLocked(this)) { openHealth(); return; }
        Context app = getApplicationContext();
        boolean retry = backFromSecurity;
        backFromSecurity = false;
        Updater.askNotifications(this); // 알림이 꺼져 있으면 열 때마다 다시 묻기(2026-11-16)
        new Thread(() -> {
            int latest = Updater.latest(app);
            if (latest > 0 && latest <= BuildConfig.VERSION_CODE) Updater.clearNotice(app);
            if (latest <= BuildConfig.VERSION_CODE) return;
            if (!Updater.canInstall(this)) { runOnUiThread(this::askInstallPermission); return; }
            if (retry) { Updater.checkAndInstall(app, true, true); return; }
            if (!Updater.askedRecently(app, latest)) runOnUiThread(this::askUpdate);
        }).start();
    }

    @Override
    protected void onPause() {
        if (permWaiting != null) permLeft = true;
        visible = false;
        super.onPause();
    }

    @Override
    public void onBackPressed() {
        if (lockMode) { moveTaskToBack(true); return; }   // 잠금화면 위에서는 뒤로 = 마을을 내리고 잠금화면
        if (!loaded || web == null) { super.onBackPressed(); return; }
        web.evaluateJavascript("window.appBack ? appBack() : false", v -> {
            if (!"true".equals(v)) finish();
        });
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        js("window.onNative && onNative('resume')");
        if (code == 77) {
            // 두 번 거절해 묻는 창이 더는 안 뜨는 허락이 있으면 앱 정보 → 권한을 한 번 연다(갔다 오면 다음 것으로)
            boolean blocked = false;
            for (int i = 0; i < perms.length && i < res.length; i++)
                if (res[i] != android.content.pm.PackageManager.PERMISSION_GRANTED && !shouldShowRequestPermissionRationale(perms[i])) blocked = true;
            if (blocked) openForPerm(""); else permStep();
            return;
        }
        // 하나만 켜기(78): 묻는 창이 더는 안 뜨는 허락(두 번 거절)이면 앱 정보 → 권한으로 보낸다
        if (code == 78 && perms.length > 0 && res.length > 0 && res[0] != android.content.pm.PackageManager.PERMISSION_GRANTED && !shouldShowRequestPermissionRationale(perms[0])) {
            android.widget.Toast.makeText(this, Perms.hint(""), android.widget.Toast.LENGTH_LONG).show();
            try { startActivity(Perms.intent(this, "")); } catch (Exception ignored) {}
        }
    }

    // ── 📱 한 번에 켜기(2026-11-18 룡 「한 방에」) ──
    // 꺼진 것을 차례로 연다: 묻는 창으로 받는 것은 한꺼번에, 설정 화면에서 켜는 것은 하나씩(갔다 오면 onResume이 다음 것을 연다).
    // 한 번 열어 본 것은 켰든 안 켰든 줄에서 뺀다(같은 화면을 끝없이 다시 열지 않게). 안 켠 것은 「켜야 할 것」에 꺼짐으로 남는다.
    private void permsGo(String csv) {
        permQueue.clear();
        permWaiting = null;
        permLeft = false;
        boolean restricted = false;
        for (String id : (csv == null ? "" : csv).split(",")) {
            id = id.trim();
            if ("restricted".equals(id)) { restricted = true; continue; }
            if (Perms.find(id) != null) permQueue.add(id);
        }
        // 「제한된 설정 허용」은 알림 읽기·접근성 스위치를 한 번 눌러 본 뒤에야 앱 정보 ⋮에 생긴다.
        // 그래서 순서 = 그 화면(눌러 보기) → 앱 정보(허용) → 그 화면 다시(켜기). 첫 화면에서 켜졌으면 뒤의 둘은 저절로 빠진다
        if (restricted) {
            for (String id : new String[]{"finNotice", "sleepLock"}) {
                int at = permQueue.indexOf(id);
                if (at < 0) continue;
                permQueue.add(at + 1, "restricted");
                permQueue.add(at + 2, id);
                break;
            }
        }
        permStep();
    }

    // 설정 화면 하나를 열고, 갔다 오면 다음 것으로 넘어가게 표시한다. 그 화면이 없는 폰이면 앱 정보로
    private void openForPerm(String id) {
        permWaiting = id;
        permLeft = false;
        android.widget.Toast.makeText(this, Perms.hint(id), android.widget.Toast.LENGTH_LONG).show();
        try { startActivity(Perms.intent(this, id)); }
        catch (Exception e) {
            try { startActivity(Perms.intent(this, "")); }
            catch (Exception e2) { permWaiting = null; permStep(); }
        }
    }

    private void permStep() {
        if (lockMode || isFinishing()) { permQueue.clear(); return; }
        permQueue.removeIf(id -> Perms.on(this, id));
        if (permQueue.isEmpty()) { js("window.onNative && onNative('perms')"); return; }
        java.util.ArrayList<String> ask = new java.util.ArrayList<>();
        for (String id : permQueue) { Perms.Item it = Perms.find(id); if (it != null && it.perm != null) ask.add(it.perm); }
        if (!ask.isEmpty()) {
            permQueue.removeIf(id -> { Perms.Item it = Perms.find(id); return it != null && it.perm != null; });
            requestPermissions(ask.toArray(new String[0]), 77);
            return;
        }
        openForPerm(permQueue.remove(0));
    }

    private void permOpen(String id) {
        Perms.Item it = Perms.find(id);
        if (it == null) return;
        permQueue.clear();
        if (it.perm != null) { requestPermissions(new String[]{it.perm}, 78); return; }
        try {
            android.widget.Toast.makeText(this, Perms.hint(id), android.widget.Toast.LENGTH_LONG).show();
            startActivity(Perms.intent(this, id));
        } catch (Exception ignored) {}
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
        // 로그아웃·탈퇴(2026-11-18): 앞사람 것을 이 폰에서 모두 지운다(Store.wipeUser). 쿠키·웹 저장소는 화면 줄(UI)에서
        @JavascriptInterface public void wipe() { Store.wipePrefs(getApplicationContext(), false); runOnUiThread(() -> Store.wipeWeb(getApplicationContext())); }
        // 탈퇴했거나 다른 사람이 로그인했을 때: 아직 못 보낸 할 일까지 지운다
        @JavascriptInterface public void wipeAll() { Store.wipePrefs(getApplicationContext(), true); runOnUiThread(() -> Store.wipeWeb(getApplicationContext())); }

        // 📱 켜야 할 것(2026-11-17): 알림이 켜져 있는지 · 이 앱 알림 설정 화면
        @JavascriptInterface public boolean notifyOn() { return Updater.notificationsOn(MainActivity.this); }
        @JavascriptInterface public void openNotify() {
            open(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()));
        }
        // 📱 켜야 할 것 한 벌(Perms): 그 기록들에 필요한 것 목록 · 한 번에 켜기 · 하나만 켜기
        @JavascriptInterface public String perms(String packs) { return Perms.json(MainActivity.this, packs); }
        @JavascriptInterface public void permsGo(String ids) { runOnUiThread(() -> MainActivity.this.permsGo(ids)); }
        @JavascriptInterface public void permOpen(String id) { runOnUiThread(() -> MainActivity.this.permOpen(id)); }
        // 🏘 잠금화면 앞에 마을(2026-11-18): 켜짐 · 켜고 끄기 · 「다른 앱 위에 표시」 허용됐나 · 그 설정 화면
        @JavascriptInterface public boolean lockFront() { return LockFront.on(MainActivity.this); }
        @JavascriptInterface public void setLockFront(boolean v) { LockFront.set(getApplicationContext(), v); }
        // 늘 떠 있는 「잠금화면 앞에 마을 켜짐」 알림을 숨기는 곳(그 알림 칸만 끄면 기능은 그대로 돈다)
        @JavascriptInterface public void lockNote() {
            runOnUiThread(() -> android.widget.Toast.makeText(MainActivity.this, "이 알림 칸을 끄면 알림만 사라지고 기능은 그대로예요", android.widget.Toast.LENGTH_LONG).show());
            open(new Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()).putExtra(Settings.EXTRA_CHANNEL_ID, "lock_front"));
        }
        @JavascriptInterface public boolean overlayOn() { return LockFront.allowed(MainActivity.this); }
        @JavascriptInterface public void openOverlay() {
            open(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
        }
        // 가입 신청한 닉네임(승인 기다림). 15분 확인(Daily.notices)이 승인·거절되면 알림을 띄운다
        @JavascriptInterface public void setWait(String nick) { Store.get(MainActivity.this).setWait(nick); }

        // 🏠 집: 건강 기록 화면 그 자체를 이 앱 안에서 연다(HealthActivity). 돌아오면 onNative('resume')로 다시 불러와 +EXP
        @JavascriptInterface public void openHealth() { MainActivity.this.openHealth(); }

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

        // 지갑: 재정 기록 빠른 기록 창(재정 위젯 − 지출과 똑같은 창). 2026-11-15부터 이 앱 안의 같은 창(원본 한 벌 finkit)이라 늘 true
        // 👛 금고 말풍선 ＋/−: 그 종류(수입·지출)로 빠른 기록 창 · 은행·카드 알림에서 온 「안 적은 것」 수
        @JavascriptInterface public void openWalletKind(String kind) {
            open(app.recordkit.fin.FinWidget.quickIntent(MainActivity.this, WalletQuickActivity.class, "수입".equals(kind) ? "수입" : "지출"));
        }
        @JavascriptInterface public int finPending() {
            try { return app.recordkit.fin.FinPending.count(getApplicationContext()); } catch (Throwable t) { return 0; }
        }
        @JavascriptInterface public boolean openWallet() {
            open(Widget.walletIntent(MainActivity.this));
            return true;
        }

        // 👛 장부 전체 = 재정 기록 화면 그 자체(WalletActivity, 2026-11-15)
        @JavascriptInterface public void openLedger() { MainActivity.this.openLedger(); }

        // 아빠 집: 영성 기록의 그 탭(묵상·일기·가정예배)을 오늘 날짜로. 2026-11-16부터 이 앱 안의 영성 기록 화면 그 자체(원본 한 벌 spiritkit)
        @JavascriptInterface public void openSpirit(String tab) { open(SpiritApp.screen(MainActivity.this, tab)); }

        // 🩺 진단 보내기(2026-11-24): 진단 글을 공유 창으로(카톡 등). 기록 내용은 들어 있지 않다
        @JavascriptInterface public void sendDiag() {
            new Thread(() -> {
                String t = Diag.report(getApplicationContext());
                runOnUiThread(() -> {
                    try { startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, t), "진단 보내기")); }
                    catch (Exception e) { js("toast('공유 창을 열지 못했어요')"); }
                });
            }).start();
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
        // 업무 기록의 ＋ 할 일 적기 창 / 맡기기 창 = 업무 기록 위젯과 같은 창. 2026-11-16부터 이 앱 안의 같은 창(원본 한 벌 workkit)
        // 잠금화면 앞 마을이면 잠금 위 적기 창(사진 없이 적기만, 2026-11-19 룡)
        @JavascriptInterface public void todoAdd() { open(lockMode ? WorkApp.lockQuick(MainActivity.this) : WorkApp.quick(MainActivity.this)); }
        // ＋ 수집 말풍선 자리(웹 화면 기준 CSS 좌표). 잠금 판은 이 자리만 눌림을 아래 마을로 흘려보낸다
        @JavascriptInterface public void lockHole(double l, double t, double r, double b, double vw) {
            lockHole = vw > 0 ? new double[]{l, t, r, b, vw} : null;
        }
        // 🎤 아빠 집 ＋ = 말로 적는 나눌거리(2026-11-26 룡). 잠금 위에서는 적기만 하는 창
        @JavascriptInterface public void spiritAdd() { open(SpiritApp.voice(MainActivity.this, lockMode)); }
        @JavascriptInterface public void lockHole2(double l, double t, double r, double b, double vw) {
            lockHole2 = vw > 0 ? new double[]{l, t, r, b, vw} : null;
        }
        @JavascriptInterface public void todoAssign() { open(WorkApp.screen(MainActivity.this, true)); }

        // 👥 이웃 = 관계 기록 앱 그 자체(RelApp.Screen, 2026-11-16)
        @JavascriptInterface public void openRel() { open(RelApp.screen(MainActivity.this)); }

        // 업무 기록 화면(요청받은 일·확인·새 할 일) = 업무 기록 앱 그 자체(WorkApp.Screen)
        @JavascriptInterface public void openTodo() { open(WorkApp.screen(MainActivity.this, false)); }

        // 사무실 🎙 말풍선(2026-11-19 룡 「홈 화면 위젯은 안 쓴다, 라이프 RPG 하나만」): 갑자기 하는 미팅 녹음.
        // 진행 중인 녹음이 없으면 「지금 녹음 시작」(15분 단위), 있으면 「녹음 끝내고 글 넣기」. 내용은 workkit MeetActivity
        @JavascriptInterface public String meetStage() {
            try {
                String a = getSharedPreferences("todohub-meet", MODE_PRIVATE).getString("active", null);
                return a == null ? "" : new org.json.JSONObject(a).optString("stage", "");
            } catch (Exception e) { return ""; }
        }
        // 일정 줄 🎙: 녹음 알림 걸기·끄기(폰 안에만, 내용은 workkit Meet)
        @JavascriptInterface public String meetPlans() { return app.recordkit.work.MeetReceiver.plans(getApplicationContext()); }
        @JavascriptInterface public void planMeet(String key, String title, String start, String end) {
            try { app.recordkit.work.MeetReceiver.plan(getApplicationContext(), key, title, Long.parseLong(start), Long.parseLong(end)); } catch (Exception ignored) {}
        }
        @JavascriptInterface public void unplanMeet(String key) { app.recordkit.work.MeetReceiver.unplan(getApplicationContext(), key); }
        @JavascriptInterface public void openMeet() {
            open(new Intent(MainActivity.this, WorkApp.Meet.class).putExtra("mode", meetStage().isEmpty() ? "now" : "done"));
        }

        // 아빠 집: 영성 기록을 연다(2026-11-16부터 이 앱 안의 영성 기록 화면). 다른 앱은 그 앱(없으면 사이트)
        @JavascriptInterface public void openApp(String pkg, String url) {
            if (app.recordkit.spirit.Spirit.STANDALONE.equals(pkg)) { open(SpiritApp.screen(MainActivity.this, null)); return; }
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
        // 잠금 위 마을에서는 ＋ 수집 적기 창만 연다(구멍 가장자리·잠금 중 뜬 창으로 다른 화면이 잠금 뒤에 열리지 않게, 2026-11-19 검토)
        if (lockMode && (i.getComponent() == null || !(WorkApp.LockQuick.class.getName().equals(i.getComponent().getClassName())
                || SpiritApp.LockVoice.class.getName().equals(i.getComponent().getClassName())))) return;
        runOnUiThread(() -> {
            try { startActivity(i); } catch (Exception e) { try { startActivity(new Intent(Settings.ACTION_SETTINGS)); } catch (Exception ignored) {} }
        });
    }

    private void askUpdate() {
        if (isFinishing()) return;
        dialog = new android.app.AlertDialog.Builder(this)
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
        dialog = new android.app.AlertDialog.Builder(this)
                .setTitle("새 버전이 있어요")
                .setMessage("앞으로 저절로 업데이트되게, 다음 화면에서 「이 출처 허용」을 켜 주세요. 한 번만 하면 돼요.")
                .setPositiveButton("켜러 가기", (d, w) -> {
                    try { startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName()))); } catch (Exception ignored) {}
                })
                .setNegativeButton("나중에", null)
                .show();
    }
}
