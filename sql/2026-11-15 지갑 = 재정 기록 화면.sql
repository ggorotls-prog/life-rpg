-- 2026-11-15 룡 2단계: 👛 지갑 = 재정 기록 화면 그 자체(RPG = 비추는 창). 표는 안 바꾸고 함수 하나만 더한다. ※ 아직 DB에 안 만듦(룡 OK 뒤 만든다)
-- RPG 로그인(업무 기록 아이디)으로 안쪽 재정 기록 화면에도 바로 들어가게: 재정 기록 열쇠(fin_sessions)를 하나 만들어 준다.
-- fin_login이 업무 기록 비밀번호로 열쇠를 주는 것과 같은 세기(RPG 열쇠 = 업무 기록 비밀번호로 받은 것). 장부 사람(fin_book_members)만.
create or replace function rpg_fin_token(p_token text) returns json
language plpgsql security definer set search_path = public, extensions as $$
declare w work_members := rpg_member(p_token); t text;
begin
  if not exists (select 1 from fin_book_members where work_member_id = w.id) then
    return json_build_object('error', '재정 기록 장부가 이어져 있지 않아요');
  end if;
  t := encode(gen_random_bytes(32), 'hex');
  insert into fin_sessions(token_hash, work_member_id, expires_at) values (encode(digest(t, 'sha256'), 'hex'), w.id, now() + interval '180 days');
  return json_build_object('token', t);
end $$;
revoke all on function rpg_fin_token(text) from public;
grant execute on function rpg_fin_token(text) to anon;
