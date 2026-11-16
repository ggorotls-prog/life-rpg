-- 2026-11-16 룡: 업무 기록도 RPG 안 창으로(RPG = 비추는 창, 설계안 11절). 표는 안 바꾸고 함수 하나만 더한다.
-- 업무 기록 로그인은 사이트(Vercel)가 비밀값으로 서명한 쿠키라 DB가 직접 못 만든다.
-- 그래서 이 함수는 RPG 열쇠가 누구 것인지만 알려 주고, 쿠키는 사이트 /api/login?rpg=1 이 만든다.
-- 세기: RPG 열쇠 = 업무 기록 비밀번호로 받은 것(rpg_login). rpg_member가 끊긴 열쇠·비활성·「모든 기기에서 나가기」 뒤 열쇠를 막는다.
-- 사이트 서버(service_role)만 부를 수 있다: 브라우저·앱은 이 함수를 직접 못 부른다.
create or replace function rpg_work_token(p_token text) returns json
language plpgsql stable security definer set search_path = public, extensions as $$
declare w work_members := rpg_member(p_token);
begin
  return json_build_object('member_id', w.id);
end $$;
revoke all on function rpg_work_token(text) from public;
revoke all on function rpg_work_token(text) from anon, authenticated;
grant execute on function rpg_work_token(text) to service_role;
