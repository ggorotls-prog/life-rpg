-- 2026-11-15 룡: 🏠 집 = 건강 기록 화면 그 자체(RPG = 비추는 창). 표는 안 바꾸고 함수 하나만 더한다.
-- RPG 로그인(업무 기록 아이디)으로 안쪽 건강 기록 화면도 바로 들어가게: 이어진 건강 기록 계정(life_members.work_member_id)의 열쇠를 하나 만들어 준다.
-- life_login이 업무 기록 비밀번호로 열쇠를 주는 것과 같은 세기(RPG 열쇠 = 업무 기록 비밀번호로 받은 것).
create or replace function rpg_life_token(p_token text) returns json
language plpgsql security definer set search_path = public, extensions as $$
declare w work_members := rpg_member(p_token); m life_members;
begin
  select * into m from life_members where work_member_id = w.id limit 1;
  if m.id is null then return json_build_object('error', '건강 기록 계정이 이어져 있지 않아요'); end if;
  return json_build_object('token', life_new_session(m.id));
end $$;
revoke all on function rpg_life_token(text) from public;
grant execute on function rpg_life_token(text) to anon;
