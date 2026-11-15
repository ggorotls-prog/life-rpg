package app.liferpg;

// 🏠 집 = 건강 기록 화면 그 자체(2026-11-15 룡 「기록 앱은 동일하고, 그걸 비추는 창인 라이프 알피지만 있고 없고」).
// 내용은 원본 한 벌: 건강 기록 저장소(life-room) android/healthkit의 HealthScreen(건강 기록 앱 MainActivity와 같은 것).
// 이 앱만의 것: RPG 로그인으로 건강 기록에도 바로 들어가기(rpg_life_token), 아침·밤 알림 걸기, 배터리 안내(열 때마다).
public class HealthActivity extends app.recordkit.health.HealthScreen {
    @Override
    protected void onCreate(android.os.Bundle saved) {
        super.onCreate(saved);
        HealthReminder.schedule(this);
    }

    @Override
    protected void onPageReady() {
        SignIn.once(this, web, "life_token", "rpg_life_token");
    }

    @Override
    protected void onResume() {
        super.onResume();
        askBattery();
    }
}
