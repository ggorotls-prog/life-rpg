package app.liferpg;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

// 🩺 진단 기록(2026-11-24 룡 「진단판 만들어」): 아내 플립7에서 실행중인 앱 창의 카드를 누르면 「앱을 열 수 없습니다」.
// 화면이 열리고 닫힐 때마다 한 줄씩 적어 두고(최근 200줄), ⚙ 설정 → 「🩺 진단 보내기」로 공유 창에 넘긴다.
// 적는 것: 시각·화면 이름·새로 만듦/되살림·「기록에서 열림」 표시·화면 번호. 기록 내용(할 일·건강·신앙·사람 이름)은 적지 않는다.
final class Diag {
    private static final int MAX = 200;
    private static final Object LOCK = new Object();
    private static final java.util.concurrent.ExecutorService IO = java.util.concurrent.Executors.newSingleThreadExecutor();   // 화면이 느려지지 않게 적기는 뒤에서

    private static File file(Context c) { return new File(c.getApplicationContext().getFilesDir(), "diag.txt"); }

    static void log(Context c, String line) {
        String t = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.KOREA).format(new Date());
        Context app = c.getApplicationContext();
        try { IO.execute(() -> write(app, t + " " + line)); } catch (Exception ignored) {}
    }

    // 앱이 고장 나 꺼지는 순간에는 뒤에서 적을 틈이 없어서 바로 적는다
    static void logNow(Context c, String line) { write(c.getApplicationContext(), new SimpleDateFormat("MM-dd HH:mm:ss", Locale.KOREA).format(new Date()) + " " + line); }

    private static void write(Context c, String line) {
        try {
            synchronized (LOCK) {
                File f = file(c);
                List<String> lines = f.exists() ? new ArrayList<>(Files.readAllLines(f.toPath(), StandardCharsets.UTF_8)) : new ArrayList<>();
                lines.add(line);
                while (lines.size() > MAX) lines.remove(0);
                Files.write(f.toPath(), lines, StandardCharsets.UTF_8);
            }
        } catch (Exception ignored) {}
    }

    // 화면이 어떻게 열렸나: 「기록에서 열림」 = 실행중인 앱 창에서 연 것(FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)
    static String how(Intent i) {
        try { return how0(i); } catch (Exception e) { return "표시 못 읽음"; }   // 깨진 인텐트로 앱이 꺼지지 않게
    }

    private static String how0(Intent i) {
        if (i == null) return "표시 없음";
        int f = i.getFlags();
        StringBuilder s = new StringBuilder("flags=0x" + Integer.toHexString(f));
        if ((f & Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0) s.append(" 기록에서열림");
        if ((f & Intent.FLAG_ACTIVITY_NEW_TASK) != 0) s.append(" 새작업");
        if (i.getAction() != null) s.append(" action=").append(i.getAction().replace("android.intent.action.", ""));
        if (i.getBooleanExtra(LockFront.EXTRA, false)) s.append(" 잠금앞마을");
        return s.toString();
    }

    private static String display(Activity a) {
        try {
            if (Build.VERSION.SDK_INT >= 30 && a.getDisplay() != null) return "화면" + a.getDisplay().getDisplayId();
        } catch (Exception ignored) {}
        return "화면?";
    }

    private static String name(Activity a) { return a.getClass().getName().replace("app.liferpg.", ""); }

    // 앱의 모든 화면(마을·건강·지갑·업무·영성·이웃)을 지켜본다
    static void watch(Application app) {
        log(app, "앱 시작(프로세스 새로 켜짐) 판" + BuildConfig.VERSION_CODE);
        app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityCreated(Activity a, Bundle b) {
                log(a, name(a) + " 만듦" + (b != null ? "(되살림)" : "(새로)") + " " + how(a.getIntent()) + " task" + a.getTaskId() + " " + display(a));
            }
            @Override public void onActivityStarted(Activity a) { log(a, name(a) + " 보임 " + display(a)); }
            @Override public void onActivityResumed(Activity a) { log(a, name(a) + " 맨앞"); }
            @Override public void onActivityPaused(Activity a) { log(a, name(a) + " 뒤로"); }
            @Override public void onActivityStopped(Activity a) { log(a, name(a) + " 안보임"); }
            @Override public void onActivitySaveInstanceState(Activity a, Bundle b) {}
            @Override public void onActivityDestroyed(Activity a) { log(a, name(a) + " 닫힘" + (a.isFinishing() ? "(끝냄)" : "(폰이 닫음)")); }
        });
        Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            StringBuilder s = new StringBuilder("앱 고장: ").append(e.getClass().getName());   // 오류 문장은 안 적는다(기록 조각이 섞일 수 있어서)
            StackTraceElement[] st = e.getStackTrace();
            for (int k = 0; k < Math.min(8, st.length); k++) s.append(" | ").append(st[k]);
            logNow(app, s.toString());
            if (prev != null) prev.uncaughtException(t, e);
        });
    }

    // 보낼 글: 폰·앱 정보 + 작업 목록 + 꺼진 이유 + 진단 기록 + 앱 고장 로그
    static String report(Context c) {
        StringBuilder s = new StringBuilder("🩺 라이프 RPG 진단\n");
        s.append("폰: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append(" (").append(Build.DEVICE).append(")\n");
        s.append("안드로이드: ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        s.append("빌드: ").append(Build.DISPLAY).append('\n');
        s.append("앱 판: ").append(BuildConfig.VERSION_CODE).append('\n');
        try {
            android.hardware.display.DisplayManager dm = c.getSystemService(android.hardware.display.DisplayManager.class);
            for (android.view.Display d : dm.getDisplays()) {
                android.graphics.Point p = new android.graphics.Point();
                d.getRealSize(p);
                s.append("화면").append(d.getDisplayId()).append(": ").append(p.x).append('x').append(p.y).append(" 상태").append(d.getState()).append('\n');
            }
        } catch (Exception e) { s.append("화면 정보 못 읽음\n"); }
        s.append("\n[작업 목록]\n");
        try {
            ActivityManager am = c.getSystemService(ActivityManager.class);
            for (ActivityManager.AppTask t : am.getAppTasks()) {
                ActivityManager.RecentTaskInfo i = t.getTaskInfo();
                s.append("task").append(i.taskId)
                        .append(" 바닥=").append(i.baseActivity == null ? "-" : i.baseActivity.getShortClassName())
                        .append(" 맨위=").append(i.topActivity == null ? "-" : i.topActivity.getShortClassName())
                        .append(" 화면수=").append(i.numActivities)
                        .append(" ").append(how(i.baseIntent)).append('\n');
            }
        } catch (Exception e) { s.append("못 읽음 ").append(e).append('\n'); }
        if (Build.VERSION.SDK_INT >= 30) {
            s.append("\n[앱이 최근에 꺼진 이유]\n");
            try {
                ActivityManager am = c.getSystemService(ActivityManager.class);
                SimpleDateFormat f = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.KOREA);
                for (android.app.ApplicationExitInfo x : am.getHistoricalProcessExitReasons(null, 0, 5))
                    s.append(f.format(new Date(x.getTimestamp()))).append(" 이유").append(x.getReason())
                            .append(" 중요도").append(x.getImportance()).append(" ").append(x.getDescription() == null ? "" : x.getDescription()).append('\n');
            } catch (Exception e) { s.append("못 읽음 ").append(e).append('\n'); }
        }
        s.append("\n[진단 기록]\n");
        try {
            synchronized (LOCK) {
                File f = file(c);
                if (f.exists()) for (String l : Files.readAllLines(f.toPath(), StandardCharsets.UTF_8)) s.append(l).append('\n');
                else s.append("없음\n");
            }
        } catch (Exception e) { s.append("못 읽음\n"); }
        // 이 앱이 남긴 로그 중 고장(AndroidRuntime)·잠금 앞 마을(LockFront)만. 다른 앱 로그는 원래 읽을 수 없다
        s.append("\n[앱 로그]\n");
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"logcat", "-d", "-t", "300", "-s", "AndroidRuntime:E", "LockFront:*", "ActivityThread:W"});
            // 고장 로그는 오류 문장을 빼고 종류·줄 위치만 남긴다(오류 문장에 기록 조각이 섞일 수 있어서)
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String l; int n = 0;
                while ((l = r.readLine()) != null && n < 120) {
                    if (l.contains("AndroidRuntime") && !l.contains("\tat ") && !l.contains(" at ")) l = l.replaceAll("((?:Exception|Error)[A-Za-z]*):.*", "$1");
                    s.append(l).append('\n'); n++;
                }
            } finally { p.destroy(); }
        } catch (Exception e) { s.append("못 읽음\n"); }
        return s.toString();
    }
}
