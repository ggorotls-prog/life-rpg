package app.liferpg;

// 👛 지갑 빠른 기록 창 = 재정 기록 위젯 − 지출과 똑같은 창(2026-11-12 룡 「재정기록 위젯이랑 동일하게」, 2026-11-15 원본 한 벌).
// 내용은 원본 한 벌 finkit의 FinQuick(재정 기록 앱 QuickActivity와 같은 것). 이 앱만의 것: RPG 로그인으로 바로 들어가기.
public class WalletQuickActivity extends app.recordkit.fin.FinQuick {
    @Override
    protected void onPageReady() {
        SignIn.once(this, web, "fin_session", "rpg_fin_token");
    }
}
