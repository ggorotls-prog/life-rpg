package app.liferpg;

// 취침 잠금. 내용은 원본 한 벌: 건강 기록 저장소(life-room) android/healthkit의 SleepLock(빌드 때 받아 씀, 2026-11-15 룡 「잠금 코드는 하나로 모으자」).
// 이 클래스 이름(app.liferpg.LockService)은 바꾸지 않는다: 폰 접근성 설정이 이 이름으로 켜져 있다.
// 이 앱만의 것 둘: 앞으로 끌어올 화면 = 🏠 집(HealthActivity), 건강 기록 단독 앱은 막지 않음(둘 다 깔려 같이 잠겨도 서로 싸우지 않게).
public class LockService extends app.recordkit.health.SleepLock {
    @Override
    protected Class<? extends android.app.Activity> screen() { return HealthActivity.class; }

    @Override
    protected String[] partners() { return new String[]{"app.liferoom"}; }
}
