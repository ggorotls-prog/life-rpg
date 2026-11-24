package app.liferpg;

import android.app.Application;

// 앱이 켜질 때: 업무 기록 바로 오는 알림(Firebase, 업무 기록 앱과 같은 프로젝트)을 준비한다. 원본 workkit Work.initPush.
// 준비가 안 돼도(열쇠 없음 등) 앱은 열리고, 업무 기록 알림은 15분 확인으로 온다.
public class App extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        Diag.watch(this);   // 🩺 진단 기록(2026-11-24): 화면이 열리고 닫힐 때마다 한 줄
        app.recordkit.work.Work.initPush(this, BuildConfig.FIREBASE_API_KEY);
    }
}
