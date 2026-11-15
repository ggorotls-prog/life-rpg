package app.liferpg;

// 👛 지갑 장부 = 재정 기록 화면 그 자체(2026-11-15 룡 2단계, 1단계 🏠 집과 같은 틀).
// 내용은 원본 한 벌: 재정 기록 저장소(faith-budget) android/finkit의 FinScreen(재정 기록 앱 MainActivity와 같은 것).
// 이 앱만의 것: RPG 로그인으로 재정 기록에도 바로 들어가기(rpg_fin_token).
public class WalletActivity extends app.recordkit.fin.FinScreen {
    @Override
    protected void onPageReady() {
        SignIn.once(this, web, "fin_session", "rpg_fin_token");
    }
}
