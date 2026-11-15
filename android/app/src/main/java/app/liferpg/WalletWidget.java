package app.liferpg;

// 홈 화면 위젯 「+ 수입 | − 지출」 = 재정 기록 앱 위젯과 같은 것(원본 한 벌 finkit의 FinWidget). 재정 기록 앱 없이도 쓰게.
public class WalletWidget extends app.recordkit.fin.FinWidget {
    @Override
    protected Class<? extends app.recordkit.fin.FinQuick> quick() { return WalletQuickActivity.class; }
}
