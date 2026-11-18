-- 2026-11-18 탈퇴 이름·닉네임 다시 쓰기 (룡 결정). 아직 DB에 올리지 않았다. 주인이 보고 올린다.
-- 바탕: DB 지금 정의(pg_get_functiondef, 2026-11-18 「가입 전 정리」를 넣은 뒤)를 그대로 가져와 아래만 고쳤다.
--
-- ■ 무엇이 바뀌나
-- | 무엇                      | 지금                                                        | 바뀐 뒤                                                                                   |
-- |---------------------------|-------------------------------------------------------------|-------------------------------------------------------------------------------------------|
-- | rpg_used_names (새 표)     | 없음                                                        | 지워진 닉네임의 바탕(끝 #숫자를 뗀 것, 소문자)과 마지막 번호. RLS 켬·정책 없음 = 함수만 읽음 |
-- | rpg_purge_account          | 남과 주고받은 업무 줄의 owner_id 만 비움                      | + 그 줄들의 이름 칸(그 사람 이름과 정확히 같은 값만)을 「탈퇴한 사람」으로                   |
-- |                           |                                                             |   work_tasks.assignee·assigned_by · work_chats.sender · work_notes.written_by ·            |
-- |                           |                                                             |   work_goals.representative·edited_by                                                       |
-- |                           |                                                             | + 지운 닉네임의 바탕을 rpg_used_names 에 넣거나 번호를 올림(「철수」=1, 「철수#2」=2)        |
-- | rpg_name_taken             | 살아 있는 구성원 이름·login_id 와 똑같으면 막음               | 끝의 #숫자를 뗀 바탕으로 비교(「철수#2」가 살아 있으면 「철수」 신청도 막음). 나머지 그대로  |
-- | rpg_join_decide            | 승인하면 이름·login_id = 신청 닉네임                          | 바탕이 rpg_used_names 에 있으면 「닉네임#(last_n+1)」. 「그새 같은 이름」 확인도 바탕으로    |
-- | rpg_login                  | login_id 가 적은 아이디와 같은 계정                           | + login_id 가 「적은 아이디#숫자」인 계정도 찾음(살아 있는 것 먼저, 번호 큰 것 먼저)         |
-- | rpg_consent_version        | 1                                                           | 2 (동의 문구가 바뀌어 이미 동의한 사람에게 다시 묻는다. 주인은 rpg_me 의 owner 로 예외)       |
-- | rpg_join_status            | 그대로(신청 닉네임은 번호가 없어서 그대로 돈다). 이 파일에 없음 |                                                                                           |
--
-- ■ 지우는 것은 늘지 않았다. 혼자 쓴 할 일(담당·맡긴 사람 모두 본인)은 지금처럼 지운다.
--   노트·목표는 영역 사람들이 함께 보는 것이라 혼자 쓴 것인지 가릴 수 없어 지우지 않고 이름만 바꾼다.
-- ■ 권한: 지우는 함수(rpg_purge_account)·rpg_name_taken·rpg_consent_version 은 anon 실행 권한 없음(지금과 같음).
--   rpg_join_decide·rpg_login 은 지금처럼 anon 실행(열쇠·비밀번호를 함수 안에서 확인).

-- 1) 지워진 닉네임 표
create table if not exists rpg_used_names (
  base   text primary key,          -- 닉네임에서 끝의 #숫자를 뗀 것, 소문자
  last_n int  not null default 1    -- 지금까지 쓴 가장 큰 번호(처음 사람 = 1)
);
alter table rpg_used_names enable row level security;   -- 정책 없음 = 함수만 읽고 쓴다
revoke all on rpg_used_names from anon, authenticated;

-- 2) 동의 문구 판 올림(2026-11-18 법 문구)
create or replace function public.rpg_consent_version() returns integer language sql immutable as $$ select 2 $$;

-- 3) 닉네임이 이미 있나: 살아 있는 구성원은 바탕(끝 #숫자 뗀 것)으로 비교
create or replace function public.rpg_name_taken(p_nick text)
 returns boolean language sql stable security definer set search_path to 'public' as $function$
  select exists (select 1 from work_members
                 where lower(regexp_replace(name, '#\d+$', '')) = lower(p_nick)
                    or lower(regexp_replace(login_id, '#\d+$', '')) = lower(p_nick))
      or exists (select 1 from life_members where lower(nickname)=lower(p_nick))
      or exists (select 1 from rpg_join_requests where lower(nickname)=lower(p_nick) and status='pending')
$function$;

-- 4) 가입 승인: 지워진 닉네임이면 「닉네임#번호」로 만든다
create or replace function public.rpg_join_decide(p_token text, p_id uuid, p_ok boolean, p_packs text[])
 returns json language plpgsql security definer set search_path to 'public', 'extensions' as $function$
declare w work_members := rpg_member(p_token); r rpg_join_requests; uid uuid; em text; mid uuid; pk text; it text; nm text; n int;
begin
  if not exists (select 1 from rpg_players where work_member_id=w.id and owner) then return json_build_object('error','주인만 할 수 있어요'); end if;
  select * into r from rpg_join_requests where id = p_id and status = 'pending' for update;
  if r.id is null then return json_build_object('error','이미 처리된 신청이에요'); end if;
  if not coalesce(p_ok,false) then
    delete from rpg_join_requests where id = r.id;
    return json_build_object('ok', true, 'rejected', true);
  end if;
  if exists (select 1 from work_members where lower(regexp_replace(name, '#\d+$', ''))=lower(r.nickname)
                                         or lower(regexp_replace(login_id, '#\d+$', ''))=lower(r.nickname))
     or exists (select 1 from life_members where lower(nickname)=lower(r.nickname)) then
    return json_build_object('error','그새 같은 이름이 생겼어요. 거절하고 다른 이름으로 다시 신청받아 주세요.'); end if;
  -- 앞사람이 쓰다 지워진 닉네임이면 안에서는 번호를 붙인다(앞사람이 남긴 줄과 이름으로 이어지지 않게). 로그인은 번호 없이
  select last_n into n from rpg_used_names where base = lower(r.nickname);
  nm := case when n is null then r.nickname else r.nickname || '#' || (n + 1) end;
  uid := gen_random_uuid(); em := 'rpg-'||left(replace(uid::text,'-',''),12)||'@login.ryong.local';
  insert into auth.users(instance_id, id, aud, role, email, encrypted_password, email_confirmed_at,
                         raw_app_meta_data, raw_user_meta_data, created_at, updated_at,
                         confirmation_token, recovery_token, email_change_token_new, email_change)
    values ('00000000-0000-0000-0000-000000000000', uid, 'authenticated', 'authenticated', em, r.pw_hash, now(),
            '{"provider":"email","providers":["email"]}', '{}', now(), now(), '', '', '', '');
  insert into auth.identities(provider_id, user_id, identity_data, provider, last_sign_in_at, created_at, updated_at)
    values (uid::text, uid, jsonb_build_object('sub', uid::text, 'email', em, 'email_verified', true), 'email', now(), now(), now());
  insert into work_members(owner_id, name, login_id, login_email, active, must_change_pw, uses, visible_companies)
    values (uid, nm, nm, em, true, false, '{}', '{}') returning id into mid;
  insert into rpg_players(work_member_id, gender, rpg_only) values (mid, r.gender, true);
  for it in select jsonb_array_elements_text(r.consents) loop
    insert into rpg_consents(work_member_id, item, agreed, version) values (mid, it, true, r.version);
  end loop;
  foreach pk in array r.packs loop
    if pk = any(coalesce(p_packs,'{}')) then
      perform rpg_make_pack_account(mid, pk);
      insert into rpg_packs(work_member_id, pack, status, opened_at) values (mid, pk, 'open', now());
    end if;
  end loop;
  update rpg_join_requests set status='approved', decided_at=now(), pw_hash='', work_member_id=mid where id=r.id;
  return json_build_object('ok', true, 'member', mid);
end $function$;

-- 5) 로그인: 번호 없이 적어도 「닉네임#번호」 계정을 찾는다
create or replace function public.rpg_login(p_id text, p_pw text)
 returns json language plpgsql security definer set search_path to 'public', 'extensions' as $function$
declare w work_members; h text; t text; x text := lower(btrim(coalesce(p_id,'')));
begin
  select m.* into w from work_members m
   where lower(m.login_id) = x or lower(regexp_replace(m.login_id, '#\d+$', '')) = x
   order by (m.active and not exists (select 1 from rpg_players p where p.work_member_id=m.id and p.left_at is not null)) desc,
            (lower(m.login_id) = x) desc,
            coalesce(substring(m.login_id from '#(\d+)$')::int, 1) desc
   limit 1;
  if w.id is null or not w.active then
    if exists (select 1 from rpg_join_requests where lower(nickname)=x and status='pending') then
      return json_build_object('error','아직 승인을 기다리고 있어요. 승인되면 다시 로그인해 주세요.'); end if;
    return json_build_object('error','아이디나 비밀번호가 맞지 않아요.'); end if;
  if exists (select 1 from rpg_players where work_member_id=w.id and left_at is not null) then
    return json_build_object('error','탈퇴한 계정이에요.'); end if;
  if w.lock_until>now() then return json_build_object('error','여러 번 틀려서 15분 뒤에 다시 해 주세요.'); end if;
  select encrypted_password into h from auth.users where id=w.owner_id;
  if h is null or h<>crypt(coalesce(p_pw,''),h) then
    update work_members set login_fails=case when coalesce(login_fails,0)>=4 then 0 else coalesce(login_fails,0)+1 end,
      lock_until=case when coalesce(login_fails,0)>=4 then now()+interval '15 minutes' else lock_until end where id=w.id;
    return json_build_object('error','아이디나 비밀번호가 맞지 않아요.');
  end if;
  update work_members set login_fails=0,lock_until=null where id=w.id;
  t:=encode(gen_random_bytes(32),'hex');
  insert into rpg_sessions(token_hash,work_member_id,expires_at) values (encode(digest(t,'sha256'),'hex'),w.id,now()+interval '365 days');
  insert into rpg_players(work_member_id) values (w.id) on conflict do nothing;
  return json_build_object('token',t,'name',w.name);
end $function$;

-- 6) 탈퇴 30일 뒤 계정 지우기: 남과 주고받은 줄의 이름을 「탈퇴한 사람」으로, 닉네임 바탕을 남긴다
create or replace function public.rpg_purge_account(p_member uuid)
 returns void language plpgsql security definer set search_path to 'public' as $function$
declare w work_members; pl rpg_players; uid uuid; gone constant text := '탈퇴한 사람';
begin
  select * into w from work_members where id = p_member;
  if w.id is null then return; end if;
  select * into pl from rpg_players where work_member_id = p_member;
  if pl.work_member_id is null or not pl.rpg_only or pl.owner or pl.left_at is null or pl.left_at >= now() - interval '30 days' then
    raise exception '지우면 안 되는 사람: %', w.name; end if;
  if coalesce(w.login_email, '') not like 'rpg-%@login.ryong.local' then raise exception 'RPG 가입 계정이 아님: %', w.name; end if;
  if (select count(*) from work_members where name = w.name) > 1 then raise exception '같은 이름이 둘 이상: %', w.name; end if;
  uid := w.owner_id;
  if uid is not null then
    if (select count(*) from work_members where owner_id = uid) > 1 then raise exception '같은 로그인을 쓰는 구성원이 또 있음: %', w.name; end if;
    if coalesce((select email from auth.users where id = uid), '') not like 'rpg-%@login.ryong.local' then raise exception 'RPG 가입 로그인이 아님: %', w.name; end if;
  end if;
  if exists (select 1 from hero_members where work_member_id = p_member) or exists (select 1 from hero_rooms where admin_id = p_member)
     or exists (select 1 from life_groups where admin_id = p_member) then
    raise exception '다른 모임에 걸려 있음: %', w.name; end if;

  delete from life_members where work_member_id = p_member;
  delete from life_groups g where g.name = 'RPG '||w.name and g.admin_id is null
    and not exists (select 1 from life_members m where m.group_id = g.id);
  delete from fin_scopes where work_member_id = p_member;
  delete from rel_records where created_by = p_member;
  delete from rel_people where created_by = p_member;
  delete from rpg_bible_rounds where work_member_id = p_member;
  delete from rpg_join_requests where work_member_id = p_member;

  if uid is not null then
    delete from spirit_records where owner_id = uid;
    delete from spirit_accounts where owner_id = uid;
    delete from work_tasks where owner_id = uid and assignee = w.name and assigned_by = w.name;
    update work_tasks set owner_id = null where owner_id = uid;
    update work_notes set owner_id = null where owner_id = uid;
    update work_chats set owner_id = null where owner_id = uid;
    update work_goals set owner_id = null where owner_id = uid;
    update work_greenjuui_orgs set owner_id = null where owner_id = uid;
    update work_greenjuui_calls set owner_id = null where owner_id = uid;
    update work_greenjuui_purchases set owner_id = null where owner_id = uid;
  end if;

  -- 남과 주고받아 남는 줄: 그 사람 이름과 정확히 같은 값만 「탈퇴한 사람」으로(누가 적은 줄이든. 상대가 맡긴 일의 담당도 포함)
  update work_tasks set assignee = gone where assignee = w.name;
  update work_tasks set assigned_by = gone where assigned_by = w.name;
  update work_chats set sender = gone where sender = w.name;
  update work_notes set written_by = gone where written_by = w.name;
  update work_goals set representative = gone where representative = w.name;
  update work_goals set edited_by = gone where edited_by = w.name;

  -- 닉네임 바탕을 남긴다: 다음에 같은 닉네임으로 가입하면 안에서는 「닉네임#(last_n+1)」
  insert into rpg_used_names(base, last_n)
    values (lower(regexp_replace(w.name, '#\d+$', '')), coalesce(substring(w.name from '#(\d+)$')::int, 1))
    on conflict (base) do update set last_n = greatest(rpg_used_names.last_n, excluded.last_n);

  insert into rpg_purge_log(work_member_id, what) values (p_member, '탈퇴 계정');
  delete from work_members where id = p_member;
  if uid is not null then delete from auth.users where id = uid; end if;
end $function$;

-- 7) 권한(지금과 같게)
revoke all on function public.rpg_consent_version(), public.rpg_name_taken(text), public.rpg_purge_account(uuid) from public, anon, authenticated;
revoke all on function public.rpg_join_decide(text,uuid,boolean,text[]), public.rpg_login(text,text) from public, authenticated;
grant execute on function public.rpg_join_decide(text,uuid,boolean,text[]), public.rpg_login(text,text) to anon;
