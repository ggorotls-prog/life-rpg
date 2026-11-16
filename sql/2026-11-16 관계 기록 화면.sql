-- 2026-11-16 룡: 관계 기록도 RPG 안 창으로(RPG = 비추는 창, 설계안 11절). 표는 안 바꾸고 함수 하나만 더한다.
-- RPG 로그인(업무 기록 아이디)으로 안쪽 관계 기록 화면에도 바로 들어가게: 관계 기록 열쇠(rel_sessions)를 하나 만들어 준다.
-- rel_login이 업무 기록 비밀번호로 열쇠를 주는 것과 같은 세기(RPG 열쇠 = 업무 기록 비밀번호로 받은 것). 기간도 rel_login과 같은 365일.
create or replace function rpg_rel_token(p_token text) returns json
language plpgsql security definer set search_path = public, extensions as $$
declare w work_members := rpg_member(p_token); t text;
begin
  t := encode(gen_random_bytes(32), 'hex');
  insert into rel_sessions(token_hash, work_member_id, expires_at) values (encode(digest(t, 'sha256'), 'hex'), w.id, now() + interval '365 days');
  return json_build_object('token', t, 'name', w.name);
end $$;
revoke all on function rpg_rel_token(text) from public;
grant execute on function rpg_rel_token(text) to anon;
