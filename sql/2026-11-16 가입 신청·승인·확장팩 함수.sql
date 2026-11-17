-- 2026-11-16 가입 신청·승인·확장팩·탈퇴 함수 (앞 파일 「가입 신청·동의·확장팩·보고서 문·30일 삭제」의 표를 씀)
-- 흐름: 로그인 화면 「가입 신청」 → rpg_join_request(아무나) → 주인(룡) 앱에 「📮 신청 N」 → rpg_join_decide(주인만)
--       → 계정 만들기(로그인 계정 + 업무 기록 구성원 + 고른 확장팩의 건강·재정 계정) → 신청한 사람은 닉네임으로 로그인.
-- 기존 사용자(아내 등)는 로그인 뒤 동의 창 → rpg_consent_set + rpg_pack_request → 주인 승인 → 건물이 뜬다.
-- 관계 확장팩은 주인 말고는 신청할 수 없다(남의 이름·번호).
-- 동의 문구 판 번호 = 1 (시안\가입 동의 문구 초안.md). 문구를 바꾸면 판 번호를 올리고 다시 동의받는다.

create or replace function rpg_consent_version() returns int language sql immutable as $$ select 1 $$;

-- 확장팩에 필요한 동의 항목
create or replace function rpg_pack_needs(p_pack text) returns text[] language sql immutable as $$
  select case p_pack when '건강' then array['건강','건강_민감'] when '영성' then array['영성','영성_민감']
                     when '재정' then array['재정'] when '업무' then array['업무'] else array[p_pack] end
$$;

-- 이름 겹침 검사(구성원·로그인 아이디·건강 기록 닉네임·대기 중 신청)
create or replace function rpg_name_taken(p_nick text) returns boolean
language sql stable security definer set search_path = public as $$
  select exists (select 1 from work_members where lower(name)=lower(p_nick) or lower(login_id)=lower(p_nick))
      or exists (select 1 from life_members where lower(nickname)=lower(p_nick))
      or exists (select 1 from rpg_join_requests where lower(nickname)=lower(p_nick) and status='pending')
$$;

-- 1) 가입 신청 (로그인 전, 아무나)
create or replace function rpg_join_request(p_nick text, p_pw text, p_gender text, p_packs text[], p_consents text[])
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

-- 계정 안의 기록 계정 만들기(건강·재정). 비밀번호는 로그인 계정 해시를 그대로 쓴다(같은 비밀번호)
create or replace function rpg_make_pack_account(p_member uuid, p_pack text) returns void
language plpgsql security definer set search_path = public, extensions as $$
declare w work_members; h text; g bigint; b uuid; src uuid;
begin
  select * into w from work_members where id = p_member;
  if p_pack = '건강' and not exists (select 1 from life_members where work_member_id = p_member) then
    select encrypted_password into h from auth.users where id = w.owner_id;
    insert into life_groups(name) values ('RPG '||w.name) returning id into g;   -- 혼자 쓰는 방(남의 기록이 서로 안 보이게)
    insert into life_members(nickname, pw_hash, group_id, is_leader, joined_day, consent_at, work_member_id)
      values (w.name, h, g, false, life_today(), now(), p_member);
  elsif p_pack = '재정' and not exists (select 1 from fin_book_members where work_member_id = p_member) then
    insert into fin_books(name) values (w.name||' 장부') returning id into b;
    insert into fin_book_members(book_id, work_member_id) values (b, p_member);
    select id into src from fin_books where name='가정' limit 1;                  -- 항목(분류)만 본뜸. 금액·기록은 안 가져옴
    insert into fin_items(book_id, kind, bucket, middle, detail, rate, sort, active, area, expense)
      select b, kind, bucket, middle, detail, rate, sort, active, '', expense from fin_items where book_id = src and area = '' and active;
    insert into fin_scopes(work_member_id, book_id, area) values (p_member, b, '');
  end if;
end $$;

-- 2) 주인: 신청 목록(가입 + 확장팩)
create or replace function rpg_join_list(p_token text) returns json
language plpgsql stable security definer set search_path = public, extensions as $$
declare w work_members := rpg_member(p_token);
begin
  if not exists (select 1 from rpg_players where work_member_id=w.id and owner) then return json_build_object('error','주인만 볼 수 있어요'); end if;
  return json_build_object(
    'joins', coalesce((select json_agg(json_build_object('id',id,'nick',nickname,'gender',gender,'packs',packs,'consents',consents,'at',created_at) order by created_at)
              from rpg_join_requests where status='pending'), '[]'::json),
    'packs', coalesce((select json_agg(json_build_object('member',k.work_member_id,'nick',m.name,'pack',k.pack) order by m.name)
              from rpg_packs k join work_members m on m.id=k.work_member_id where k.status='requested'), '[]'::json));
end $$;

-- 3) 주인: 가입 승인·거절. p_packs = 열어 줄 확장팩(신청한 것 안에서)
create or replace function rpg_join_decide(p_token text, p_id uuid, p_ok boolean, p_packs text[])
returns json language plpgsql security definer set search_path = public, extensions as $$
declare w work_members := rpg_member(p_token); r rpg_join_requests; uid uuid; em text; mid uuid; pk text; it text;
begin
  if not exists (select 1 from rpg_players where work_member_id=w.id and owner) then return json_build_object('error','주인만 할 수 있어요'); end if;
  select * into r from rpg_join_requests where id = p_id and status = 'pending' for update;
  if r.id is null then return json_build_object('error','이미 처리된 신청이에요'); end if;
  if not coalesce(p_ok,false) then
    update rpg_join_requests set status='rejected', decided_at=now(), pw_hash='' where id=r.id;
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

-- 4) 내 상태: 확장팩·동의·주인 여부·대기 신청 수
create or replace function rpg_me(p_token text) returns json
language plpgsql security definer set search_path = public, extensions as $$
declare w work_members := rpg_member(p_token); pl rpg_players;
begin
  insert into rpg_players(work_member_id) values (w.id) on conflict do nothing;
  select * into pl from rpg_players where work_member_id = w.id;
  return json_build_object(
    'owner', pl.owner, 'rpg_only', pl.rpg_only, 'gender', pl.gender, 'version', rpg_consent_version(),
    'packs', coalesce((select json_object_agg(pack, status) from rpg_packs where work_member_id=w.id), '{}'::json),
    'consents', coalesce((select json_object_agg(item, agreed) from rpg_consent_now where work_member_id=w.id), '{}'::json),
    'need_consent', not rpg_agreed(w.id, '기본')
       or coalesce((select max(version) from rpg_consents where work_member_id=w.id and item='기본' and agreed), 0) < rpg_consent_version(),
    'pending', case when pl.owner then (select count(*) from rpg_join_requests where status='pending')
                                   + (select count(*) from rpg_packs where status='requested') else 0 end);
end $$;

-- 5) 동의 바꾸기(동의·철회). 철회해도 기록은 남고 쓰임만 멈춤. 확장팩 동의를 철회하면 그 확장팩도 닫음
create or replace function rpg_consent_set(p_token text, p_items text[], p_agreed boolean) returns json
language plpgsql security definer set search_path = public, extensions as $$
declare w work_members := rpg_member(p_token); it text;
begin
  if not p_items <@ array['기본','만14세','건강','건강_민감','재정','영성','영성_민감','업무','보고서_읽기','보고서_AI','보고서_민감'] then
    return json_build_object('error','동의 항목이 이상해요.'); end if;
  if not p_agreed and p_items && array['기본','만14세'] then return json_build_object('error','필수 동의를 끄려면 탈퇴를 눌러 주세요.'); end if;
  foreach it in array p_items loop
    insert into rpg_consents(work_member_id, item, agreed, version) values (w.id, it, p_agreed, rpg_consent_version());
  end loop;
  if not p_agreed then
    update rpg_packs set status='closed', closed_at=now()
      where work_member_id=w.id and status in ('open','requested')
        and exists (select 1 from unnest(rpg_pack_needs(pack)) n where n = any(p_items));
  end if;
  return json_build_object('ok', true);
end $$;

-- 6) 확장팩 더하기 신청(그 확장팩 동의를 같이 받음) → 주인 승인 대기
create or replace function rpg_pack_request(p_token text, p_pack text) returns json
language plpgsql security definer set search_path = public, extensions as $$
declare w work_members := rpg_member(p_token); pl rpg_players; it text;
begin
  select * into pl from rpg_players where work_member_id = w.id;
  if p_pack not in ('건강','재정','영성','업무','관계') then return json_build_object('error','없는 확장팩이에요'); end if;
  if p_pack = '관계' and not pl.owner then return json_build_object('error','관계 기록은 아직 열 수 없어요'); end if;
  foreach it in array rpg_pack_needs(p_pack) loop
    insert into rpg_consents(work_member_id, item, agreed, version) values (w.id, it, true, rpg_consent_version());
  end loop;
  insert into rpg_packs(work_member_id, pack, status) values (w.id, p_pack, case when pl.owner then 'open' else 'requested' end)
    on conflict (work_member_id, pack) do update set status = case when pl.owner then 'open' else 'requested' end, closed_at = null
    where rpg_packs.status in ('closed','purged');
  if pl.owner then update rpg_packs set opened_at = now() where work_member_id=w.id and pack=p_pack; end if;
  return json_build_object('ok', true);
end $$;

-- 7) 주인: 확장팩 신청 승인·거절
create or replace function rpg_pack_decide(p_token text, p_member uuid, p_pack text, p_ok boolean) returns json
language plpgsql security definer set search_path = public, extensions as $$
declare w work_members := rpg_member(p_token);
begin
  if not exists (select 1 from rpg_players where work_member_id=w.id and owner) then return json_build_object('error','주인만 할 수 있어요'); end if;
  if not exists (select 1 from rpg_packs where work_member_id=p_member and pack=p_pack and status='requested') then
    return json_build_object('error','이미 처리된 신청이에요'); end if;
  if coalesce(p_ok,false) then
    perform rpg_make_pack_account(p_member, p_pack);
    update rpg_packs set status='open', opened_at=now() where work_member_id=p_member and pack=p_pack;
  else
    delete from rpg_packs where work_member_id=p_member and pack=p_pack;
  end if;
  return json_build_object('ok', true);
end $$;

-- 8) 확장팩 닫기(30일 뒤 지움 — RPG로 가입한 사람만. 기존 사용자는 건물만 숨김)
create or replace function rpg_pack_close(p_token text, p_pack text) returns json
language plpgsql security definer set search_path = public, extensions as $$
declare w work_members := rpg_member(p_token);
begin
  update rpg_packs set status='closed', closed_at=now() where work_member_id=w.id and pack=p_pack and status in ('open','requested');
  return json_build_object('ok', true);
end $$;

-- 9) 탈퇴(바로 로그아웃, 30일 뒤 지움). 주인은 못 함
create or replace function rpg_leave(p_token text) returns json
language plpgsql security definer set search_path = public, extensions as $$
declare w work_members := rpg_member(p_token);
begin
  if exists (select 1 from rpg_players where work_member_id=w.id and owner) then return json_build_object('error','주인은 탈퇴할 수 없어요'); end if;
  update rpg_players set left_at = now() where work_member_id = w.id;
  update rpg_packs set status='closed', closed_at=now() where work_member_id=w.id and status in ('open','requested');
  delete from rpg_sessions where work_member_id = w.id;
  return json_build_object('ok', true);
end $$;

-- 10) 탈퇴한 사람은 RPG 로그인 막기(30일 안에도)
create or replace function rpg_login(p_id text, p_pw text) returns json
language plpgsql security definer set search_path to 'public','extensions' as $$
declare w work_members; h text; t text;
begin
  select * into w from work_members where lower(login_id)=lower(btrim(coalesce(p_id,'')));
  if w.id is null or not w.active then
    if exists (select 1 from rpg_join_requests where lower(nickname)=lower(btrim(coalesce(p_id,''))) and status='pending') then
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
end $$;

revoke all on function rpg_consent_version(), rpg_pack_needs(text), rpg_name_taken(text), rpg_make_pack_account(uuid,text) from public, anon, authenticated;
revoke all on function rpg_join_request(text,text,text,text[],text[]), rpg_join_list(text), rpg_join_decide(text,uuid,boolean,text[]),
  rpg_me(text), rpg_consent_set(text,text[],boolean), rpg_pack_request(text,text), rpg_pack_decide(text,uuid,text,boolean),
  rpg_pack_close(text,text), rpg_leave(text) from public;
grant execute on function rpg_join_request(text,text,text,text[],text[]), rpg_join_list(text), rpg_join_decide(text,uuid,boolean,text[]),
  rpg_me(text), rpg_consent_set(text,text[],boolean), rpg_pack_request(text,text), rpg_pack_decide(text,uuid,text,boolean),
  rpg_pack_close(text,text), rpg_leave(text) to anon;
