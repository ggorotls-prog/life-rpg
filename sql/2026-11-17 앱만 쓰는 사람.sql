-- 2026-11-17 앱만 쓰는 사람(Claude Code 없이 앱만 쓰는 사람)이 혼자 쓸 수 있게. 아직 적용 안 함.
-- 앱 화면은 이 함수들이 없어도 깨지지 않는다(없으면 「아직 준비 중」 안내 글).
-- 로그인 계정 = 업무 기록과 같은 계정(work_members + auth.users 비밀번호). 비밀번호 규칙은 life_pw_rule(6자 이상, 영어+숫자, 쉬운 것 막기).
--
-- 1) rpg_change_password  : 본인이 비밀번호 바꾸기(fin_change_password와 같은 꼴). 바꾸면 모든 기기에서 나가짐 → 새 비밀번호로 다시 로그인
-- 2) rpg_member_list      : 주인만. 라이프 RPG 구성원 목록(비밀번호 다시 정하기용)
-- 3) rpg_member_reset_pw  : 주인만. 구성원 비밀번호 다시 정하기(업무 기록 「비밀번호 다시 정하기」와 같게: 잠금 풀기 + 모든 기기에서 나가기 + 처음 비밀번호 표시)
-- 4) rpg_join_status      : 로그인 전. 가입 신청 상태(기다림·승인·거절)만. 앱 15분 확인이 「승인됐어요」 알림을 띄우는 데 씀
-- 5) rpg_spirit_edit      : 본인 것만. RPG 창에서 적은 감사·칭찬·순종·깨달음·SOC 고치기
-- 6) rpg_spirit_del       : 본인 것만. 같은 기록 지우기

-- 1) 본인 비밀번호 바꾸기
create or replace function rpg_change_password(p_token text, p_current text, p_next text) returns json
language plpgsql security definer set search_path = public, extensions as $$
declare w work_members := rpg_member(p_token); h text; bad text := life_pw_rule(p_next);
begin
  if w.lock_until > now() then return json_build_object('error','여러 번 틀려서 15분 뒤에 다시 해 주세요.'); end if;
  select encrypted_password into h from auth.users where id = w.owner_id;
  if h is null or h <> crypt(coalesce(p_current,''), h) then
    update work_members set login_fails = case when coalesce(login_fails,0) >= 4 then 0 else coalesce(login_fails,0) + 1 end,
      lock_until = case when coalesce(login_fails,0) >= 4 then now() + interval '15 minutes' else lock_until end where id = w.id;
    return json_build_object('error','지금 비밀번호가 맞지 않아요.');
  end if;
  if bad is not null then return json_build_object('error', bad); end if;
  if p_next = p_current then return json_build_object('error','지금 비밀번호와 다른 걸로 정해 주세요.'); end if;
  update auth.users set encrypted_password = crypt(p_next, gen_salt('bf', 10)), updated_at = now() where id = w.owner_id;
  update work_members set must_change_pw = false, session_after = now(), login_fails = 0, lock_until = null where id = w.id;
  delete from rpg_sessions where work_member_id = w.id;
  return json_build_object('ok', true);
end $$;

-- 2) 주인: 구성원 목록(탈퇴한 사람·주인은 뺌)
create or replace function rpg_member_list(p_token text) returns json
language plpgsql stable security definer set search_path = public, extensions as $$
declare w work_members := rpg_member(p_token);
begin
  if not exists (select 1 from rpg_players where work_member_id = w.id and owner) then return json_build_object('error','주인만 볼 수 있어요'); end if;
  return json_build_object('members', coalesce((select json_agg(json_build_object('id', m.id, 'nick', m.name, 'login', m.login_id,
      'locked', coalesce(m.lock_until > now(), false)) order by m.name)
    from rpg_players p join work_members m on m.id = p.work_member_id
    where not p.owner and p.left_at is null and m.active), '[]'::json));
end $$;

-- 3) 주인: 구성원 비밀번호 다시 정하기
create or replace function rpg_member_reset_pw(p_token text, p_member uuid, p_pw text) returns json
language plpgsql security definer set search_path = public, extensions as $$
declare w work_members := rpg_member(p_token); t work_members; bad text := life_pw_rule(p_pw);
begin
  if not exists (select 1 from rpg_players where work_member_id = w.id and owner) then return json_build_object('error','주인만 할 수 있어요'); end if;
  select m.* into t from work_members m join rpg_players p on p.work_member_id = m.id
    where m.id = p_member and m.active and not p.owner and p.left_at is null;
  if t.id is null then return json_build_object('error','그 사람을 찾지 못했어요'); end if;
  if bad is not null then return json_build_object('error', bad); end if;
  update auth.users set encrypted_password = crypt(p_pw, gen_salt('bf', 10)), updated_at = now() where id = t.owner_id;
  update work_members set must_change_pw = true, login_fails = 0, lock_until = null, session_after = now() where id = t.id;
  delete from rpg_sessions where work_member_id = t.id;
  delete from fin_sessions where work_member_id = t.id;
  delete from life_sessions where member_id in (select id from life_members where work_member_id = t.id);
  return json_build_object('ok', true);
end $$;

-- 4) 로그인 전: 가입 신청 상태. 닉네임의 마지막 신청 상태만 돌려준다(이름·비밀번호·기록은 안 줌)
create or replace function rpg_join_status(p_nick text) returns json
language sql stable security definer set search_path = public as $$
  select json_build_object('status', coalesce((select status from rpg_join_requests
    where lower(nickname) = lower(btrim(coalesce(p_nick,''))) order by created_at desc limit 1), 'none'))
$$;

-- 5) 본인 것만: 감사·칭찬·순종·깨달음·SOC 고치기
create or replace function rpg_spirit_edit(p_token text, p_id uuid, p_text text, p_target text default null, p_learned text default null)
returns json language plpgsql security definer set search_path = public, extensions as $$
declare w work_members := rpg_member(p_token); t text := btrim(coalesce(p_text,'')); r spirit_records;
begin
  perform rpg_need_pack(w.id, '{"영성"}'::text[]);
  select * into r from spirit_records where id = p_id and owner_id = w.owner_id and kind in ('감사','칭찬','SOC','순종','깨달음');
  if r.id is null then return json_build_object('error','기록을 찾지 못했어요'); end if;
  if t = '' then return json_build_object('error','적을 것을 넣어 주세요'); end if;
  if r.kind = 'SOC' then
    update spirit_records set situation = t, learned = coalesce(btrim(p_learned), learned),
      title = record_date||' SOC '||left(t,30), updated_at = now() where id = r.id;
  else
    update spirit_records set body = t,
      praise_target = case when r.kind = '칭찬' then nullif(btrim(coalesce(p_target,'')),'') else praise_target end,
      title = record_date||' '||r.kind||' '||left(t,30), updated_at = now() where id = r.id;
  end if;
  return json_build_object('ok', true);
end $$;

-- 6) 본인 것만: 같은 기록 지우기
create or replace function rpg_spirit_del(p_token text, p_id uuid) returns json
language plpgsql security definer set search_path = public, extensions as $$
declare w work_members := rpg_member(p_token); n int;
begin
  perform rpg_need_pack(w.id, '{"영성"}'::text[]);
  delete from spirit_records where id = p_id and owner_id = w.owner_id and kind in ('감사','칭찬','SOC','순종','깨달음');
  get diagnostics n = row_count;
  return case when n > 0 then json_build_object('ok', true) else json_build_object('error','기록을 찾지 못했어요') end;
end $$;

revoke all on function rpg_change_password(text,text,text), rpg_member_list(text), rpg_member_reset_pw(text,uuid,text),
  rpg_join_status(text), rpg_spirit_edit(text,uuid,text,text,text), rpg_spirit_del(text,uuid) from public;
grant execute on function rpg_change_password(text,text,text), rpg_member_list(text), rpg_member_reset_pw(text,uuid,text),
  rpg_join_status(text), rpg_spirit_edit(text,uuid,text,text,text), rpg_spirit_del(text,uuid) to anon;
