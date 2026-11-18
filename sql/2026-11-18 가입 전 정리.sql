-- 2026-11-18 가입 전 정리 (첫 가입 승인 전에 고쳐 두는 것). 아직 DB에 올리지 않았다. 주인이 보고 올린다.
--
-- ■ 지우는 것
-- | 무엇을                                   | 어떤 조건으로                                                                 | 언제                                    |
-- |------------------------------------------|-------------------------------------------------------------------------------|-----------------------------------------|
-- | 거절한 가입 신청(rpg_join_requests 줄)    | 주인이 거절을 누름(rpg_join_decide, p_ok = false)                             | 거절하는 그 순간                        |
-- | 이미 거절로 남은 신청(status='rejected')  | 이 파일을 올릴 때 한 번(맨 끝 delete 한 줄)                                   | 올릴 때 한 번                           |
-- | 탈퇴한 사람의 로그인 계정                 | rpg_players.rpg_only = true, owner = false, left_at 30일 지남,                | 매일 03:30 KST(cron 「rpg-purge-daily」  |
-- |  (work_members 줄 · auth.users 줄 ·       |  work_members.login_email 이 'rpg-…@login.ryong.local'(가입 승인 때 만든 것)   |  '30 18 * * *' UTC → rpg_purge().       |
-- |   그 사람의 세션 줄 · 남은 개인 줄)        |  기존 구성원(rpg_only 아님)·주인은 절대 대상 아님                             |  예약은 새로 만들지 않는다)             |
--
-- ■ 지우지 않는 것: 기다리는 가입 신청(status='pending')은 날짜가 지나도 지우지 않는다(주인이 못 봤을 수 있다, 2026-11-18 룡).
--   그래서 「기다리는 신청 전체 30건 상한」도 두지 않는다(지우지 않으면 상한이 가입을 영영 막는다). 「최근 1시간 10건」 막기는 그대로.
--   rpg_join_cleanup(대기 7일·거절 4일 지우기)은 할 일이 없어져 만들지 않고, rpg_purge() 에서 부르는 줄도 뺐다.
--
-- ■ 이 파일을 쓴 날 그 조건에 걸리는 줄 (select count(*)로 셈, 2026-11-18)
-- | 거절로 남은 신청 0건 · 기다리는 신청 0건 · 탈퇴 30일 지난 RPG 전용 0건
--
-- ■ 지우는 순서(탈퇴 계정) — pg_constraint 로 확인한 외래키(2026-11-18) 기준
--   work_members 를 가리키는 키: 지울 때 같이 지워짐(CASCADE) = rpg_players · rpg_sessions · rpg_packs · rpg_consents · fin_book_members ·
--     fin_sessions · rel_sessions · rel_my_areas · lc_sessions · lc_profile
--   막는 키(NO ACTION/RESTRICT) = life_members(RESTRICT) · fin_scopes · rel_people · rel_records · rpg_bible_rounds · hero_members ·
--     hero_rooms.admin_id · life_groups.admin_id  → 먼저 지운다(hero·life_groups.admin_id 는 RPG 전용 사람에게 없어야 하므로 있으면 멈춤)
--   auth.users 를 가리키는 키: CASCADE = auth.identities · auth.sessions · auth.mfa_* · auth.one_time_tokens · auth.oauth_* · auth.webauthn_*
--     (auth.scim_users 는 SET NULL)
--   막는 키(NO ACTION) = work_members.owner_id · work_tasks · work_notes · work_chats · work_goals · work_greenjuui_orgs/calls/purchases ·
--     spirit_records · spirit_accounts → 그 사람 것은 지우고, 남과 주고받아 남는 줄은 owner_id 만 비운다(칸이 비어도 되는 것 확인함)
--   순서: 확장팩 기록(rpg_purge_area) → 막는 줄 → work_members(같이 지워지는 줄) → auth.users(로그인 세션 등 같이 지워짐)

-- 1) 가입 신청: 2026-11-16 원본(= DB 지금 정의)과 같다. 이 파일 앞판의 「기다리는 신청 30건 상한」은 뺐다(기다리는 신청은 지우지 않으므로).
--    DB 지금 정의와 같아서 다시 올려도 바뀌는 것이 없다
create or replace function public.rpg_join_request(p_nick text, p_pw text, p_gender text, p_packs text[], p_consents text[])
returns json language plpgsql security definer set search_path = public, extensions as $$
declare nick text := btrim(coalesce(p_nick,'')); bad text := life_pw_rule(p_pw); pk text; need text;
        packs text[] := array(select distinct x from unnest(coalesce(p_packs,'{}')) x);
        cons text[] := array(select distinct x from unnest(coalesce(p_consents,'{}')) x);
begin
  if char_length(nick) not between 2 and 12 then return json_build_object('error','닉네임은 2~12자로 적어 주세요.'); end if;
  if nick !~ '^[가-힣A-Za-z0-9]+$' then return json_build_object('error','닉네임은 한글·영어·숫자만 써 주세요.'); end if;
  if bad is not null then return json_build_object('error', bad); end if;
  if coalesce(p_gender,'') not in ('남','여') then return json_build_object('error','캐릭터(남자·여자)를 골라 주세요.'); end if;
  if coalesce(array_length(packs,1),0) = 0 then return json_build_object('error','쓸 기록을 하나 이상 골라 주세요.'); end if;
  if not packs <@ array['건강','재정','영성','업무'] then return json_build_object('error','고를 수 없는 기록이 있어요.'); end if;
  if not cons <@ array['기본','만14세','건강','건강_민감','재정','영성','영성_민감','업무','보고서_읽기','보고서_AI','보고서_민감'] then
    return json_build_object('error','동의 항목이 이상해요.'); end if;
  if not array['기본','만14세'] <@ cons then return json_build_object('error','필수 동의에 체크해 주세요.'); end if;
  foreach pk in array packs loop
    foreach need in array rpg_pack_needs(pk) loop
      if not need = any(cons) then return json_build_object('error', pk||' 기록 동의에 체크해 주세요.'); end if;
    end loop;
  end loop;
  if (select count(*) from rpg_join_requests where status='pending' and created_at > now() - interval '1 hour') >= 10 then
    return json_build_object('error','신청이 많아요. 잠시 뒤에 다시 해 주세요.'); end if;
  if rpg_name_taken(nick) then return json_build_object('error','이미 있는 닉네임이에요. 다른 이름으로 해 주세요.'); end if;
  insert into rpg_join_requests(nickname, pw_hash, gender, packs, consents, version)
    values (nick, crypt(p_pw, gen_salt('bf')), p_gender, packs, to_jsonb(cons), rpg_consent_version());
  return json_build_object('ok', true);
end $$;
revoke all on function public.rpg_join_request(text,text,text,text[],text[]) from public, authenticated;
grant execute on function public.rpg_join_request(text,text,text,text[],text[]) to anon;

-- 2) 가입 신청 승인·거절: 거절하면 그 신청 줄을 바로 지운다(2026-11-18 룡). 승인 갈래는 DB 지금 정의(pg_get_functiondef, 2026-11-18)와 같다
create or replace function public.rpg_join_decide(p_token text, p_id uuid, p_ok boolean, p_packs text[])
returns json language plpgsql security definer set search_path = public, extensions as $$
declare w work_members := rpg_member(p_token); r rpg_join_requests; uid uuid; em text; mid uuid; pk text; it text;
begin
  if not exists (select 1 from rpg_players where work_member_id=w.id and owner) then return json_build_object('error','주인만 할 수 있어요'); end if;
  select * into r from rpg_join_requests where id = p_id and status = 'pending' for update;
  if r.id is null then return json_build_object('error','이미 처리된 신청이에요'); end if;
  if not coalesce(p_ok,false) then
    delete from rpg_join_requests where id = r.id;   -- 2026-11-18: 거절 = 바로 지움(예전: status='rejected' 로 남김)
    return json_build_object('ok', true, 'rejected', true);
  end if;
  if exists (select 1 from work_members where lower(name)=lower(r.nickname) or lower(login_id)=lower(r.nickname))
     or exists (select 1 from life_members where lower(nickname)=lower(r.nickname)) then
    return json_build_object('error','그새 같은 이름이 생겼어요. 거절하고 다른 이름으로 다시 신청받아 주세요.'); end if;
  uid := gen_random_uuid(); em := 'rpg-'||left(replace(uid::text,'-',''),12)||'@login.ryong.local';
  insert into auth.users(instance_id, id, aud, role, email, encrypted_password, email_confirmed_at,
                         raw_app_meta_data, raw_user_meta_data, created_at, updated_at,
                         confirmation_token, recovery_token, email_change_token_new, email_change)
    values ('00000000-0000-0000-0000-000000000000', uid, 'authenticated', 'authenticated', em, r.pw_hash, now(),
            '{"provider":"email","providers":["email"]}', '{}', now(), now(), '', '', '', '');
  insert into auth.identities(provider_id, user_id, identity_data, provider, last_sign_in_at, created_at, updated_at)
    values (uid::text, uid, jsonb_build_object('sub', uid::text, 'email', em, 'email_verified', true), 'email', now(), now(), now());
  insert into work_members(owner_id, name, login_id, login_email, active, must_change_pw, uses, visible_companies)
    values (uid, r.nickname, r.nickname, em, true, false, '{}', '{}') returning id into mid;
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
end $$;
revoke all on function public.rpg_join_decide(text,uuid,boolean,text[]) from public, authenticated;
grant execute on function public.rpg_join_decide(text,uuid,boolean,text[]) to anon;

-- 2-2) 예전 정리 함수는 없앤다(대기 7일·거절 4일 지우기 → 이제 할 일 없음). DB에는 아직 없지만 혹시 있으면 지운다
drop function if exists public.rpg_join_cleanup();

-- 3) 탈퇴 30일 뒤 로그인 계정까지 지우기(8번). RPG로만 가입한 사람만. rpg_purge 가 확장팩 기록을 지운 뒤 부른다
create or replace function public.rpg_purge_account(p_member uuid) returns void
language plpgsql security definer set search_path = public as $$
declare w work_members; pl rpg_players; uid uuid;
begin
  select * into w from work_members where id = p_member;
  if w.id is null then return; end if;
  select * into pl from rpg_players where work_member_id = p_member;
  -- 안전: RPG 전용·주인 아님·탈퇴 30일·가입 승인 때 만든 계정(가짜 이메일 rpg-…)일 때만. 하나라도 아니면 멈춤
  if pl.work_member_id is null or not pl.rpg_only or pl.owner or pl.left_at is null or pl.left_at >= now() - interval '30 days' then
    raise exception '지우면 안 되는 사람: %', w.name; end if;
  if coalesce(w.login_email, '') not like 'rpg-%@login.ryong.local' then raise exception 'RPG 가입 계정이 아님: %', w.name; end if;
  if (select count(*) from work_members where name = w.name) > 1 then raise exception '같은 이름이 둘 이상: %', w.name; end if;
  uid := w.owner_id;
  if uid is not null then
    if (select count(*) from work_members where owner_id = uid) > 1 then raise exception '같은 로그인을 쓰는 구성원이 또 있음: %', w.name; end if;
    if coalesce((select email from auth.users where id = uid), '') not like 'rpg-%@login.ryong.local' then raise exception 'RPG 가입 로그인이 아님: %', w.name; end if;
  end if;
  -- RPG 전용 사람에게 있으면 안 되는 줄(남의 모임 관리자 등): 있으면 멈추고 주인이 본다
  if exists (select 1 from hero_members where work_member_id = p_member) or exists (select 1 from hero_rooms where admin_id = p_member)
     or exists (select 1 from life_groups where admin_id = p_member) then
    raise exception '다른 모임에 걸려 있음: %', w.name; end if;

  -- work_members 를 막는 줄
  delete from life_members where work_member_id = p_member;                         -- 건강 기록 계정(하루 기록·사진 글·세션은 같이 지워짐)
  delete from life_groups g where g.name = 'RPG '||w.name and g.admin_id is null
    and not exists (select 1 from life_members m where m.group_id = g.id);          -- 가입 때 만든 혼자 쓰는 모임
  delete from fin_scopes where work_member_id = p_member;
  delete from rel_records where created_by = p_member;
  delete from rel_people where created_by = p_member;
  delete from rpg_bible_rounds where work_member_id = p_member;
  delete from rpg_join_requests where work_member_id = p_member;

  -- auth.users 를 막는 줄
  if uid is not null then
    delete from spirit_records where owner_id = uid;   -- 계정으로만(이름이 같은 남의 기록이 걸리지 않게)
    delete from spirit_accounts where owner_id = uid;
    delete from work_tasks where owner_id = uid and assignee = w.name and assigned_by = w.name;
    update work_tasks set owner_id = null where owner_id = uid;                    -- 남과 주고받은 일은 남의 기록이라 두고 연결만 끊음
    update work_notes set owner_id = null where owner_id = uid;
    update work_chats set owner_id = null where owner_id = uid;
    update work_goals set owner_id = null where owner_id = uid;
    update work_greenjuui_orgs set owner_id = null where owner_id = uid;
    update work_greenjuui_calls set owner_id = null where owner_id = uid;
    update work_greenjuui_purchases set owner_id = null where owner_id = uid;
  end if;

  insert into rpg_purge_log(work_member_id, what) values (p_member, '탈퇴 계정');
  delete from work_members where id = p_member;     -- rpg_players·rpg_sessions·rpg_packs·rpg_consents·fin_sessions·rel_sessions·lc_* 같이 지워짐
  if uid is not null then delete from auth.users where id = uid; end if;   -- auth.identities·auth.sessions 등 같이 지워짐
end $$;
revoke all on function public.rpg_purge_account(uuid) from public, anon, authenticated;

-- 4) 매일 도는 정리(고침): 탈퇴한 RPG 전용 사람은 계정까지(rpg_purge_account). 가입 신청은 여기서 지우지 않는다(거절은 그 순간 지움, 기다리는 신청은 두기).
--    기존 구성원(rpg_only 아님)은 지금처럼 RPG 쪽 줄만 지우고 원래 앱 계정·기록은 그대로
create or replace function public.rpg_purge() returns int
language plpgsql security definer set search_path = public as $$
declare r record; n int := 0; p text;
begin
  for r in select k.work_member_id, k.pack, pl.rpg_only, pl.owner from rpg_packs k join rpg_players pl using (work_member_id)
           where k.status='closed' and k.closed_at < now() - interval '30 days' loop
    begin
      if r.rpg_only and not r.owner then perform rpg_purge_area(r.work_member_id, r.pack); end if;
      update rpg_packs set status='purged' where work_member_id=r.work_member_id and pack=r.pack;
      n := n + 1;
    exception when others then insert into rpg_purge_log(work_member_id, what) values (r.work_member_id, '실패 '||r.pack||': '||left(sqlerrm,80));
    end;
  end loop;
  for r in select work_member_id, rpg_only from rpg_players where left_at is not null and left_at < now() - interval '30 days' and not owner loop
    begin
      if r.rpg_only then
        for p in select pack from rpg_packs where work_member_id = r.work_member_id and status <> 'purged' loop
          perform rpg_purge_area(r.work_member_id, p);
        end loop;
        perform rpg_purge_account(r.work_member_id);   -- 2026-11-18: 로그인 계정 줄까지(work_members·auth.users·세션)
      else
        delete from rpg_join_requests where work_member_id = r.work_member_id;
        delete from rpg_sessions where work_member_id = r.work_member_id;
        delete from rpg_packs where work_member_id = r.work_member_id;
        delete from rpg_consents where work_member_id = r.work_member_id;
        delete from rpg_players where work_member_id = r.work_member_id;
        insert into rpg_purge_log(work_member_id, what) values (r.work_member_id, '탈퇴');
      end if;
      n := n + 1;
    exception when others then insert into rpg_purge_log(work_member_id, what) values (r.work_member_id, '실패 탈퇴: '||left(sqlerrm,80));
    end;
  end loop;
  return n;
end $$;
revoke all on function public.rpg_purge() from public, anon, authenticated;

-- 5) 업무 기록 사이트 로그인 막기(6번): RPG로만 가입했고 「업무」 확장팩이 열려 있지 않으면 true.
--    사이트 서버(service_role)만 부른다. rpg_players·rpg_packs 표는 service_role 에도 SELECT 가 없어서 함수로 읽는다(2026-11-18 확인)
create or replace function public.rpg_work_blocked(p_member uuid) returns boolean
language sql stable security definer set search_path = public as $$
  select exists (select 1 from rpg_players where work_member_id = p_member and rpg_only and not owner)
     and not exists (select 1 from rpg_packs where work_member_id = p_member and pack = '업무' and status = 'open')
$$;
revoke all on function public.rpg_work_blocked(uuid) from public, anon, authenticated;
grant execute on function public.rpg_work_blocked(uuid) to service_role;

-- 예약은 그대로(새로 만들지 않음): select jobname, schedule, command from cron.job where jobname = 'rpg-purge-daily';
--   → rpg-purge-daily · '30 18 * * *'(UTC 18:30 = KST 03:30) · select rpg_purge()

-- 6) 이미 거절로 남은 신청을 한 번 지운다(2026-11-18 셈: 0건)
delete from rpg_join_requests where status = 'rejected';
