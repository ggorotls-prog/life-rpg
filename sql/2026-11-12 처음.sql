-- 라이프 RPG (2026-11-12). 6대 기록을 날짜별로 모아 주기만 한다(읽기 전용).
-- 새로 만드는 것: rpg_sessions(로그인 열쇠 해시), rpg_players(히어로 이름·닿은 최고 레벨). 기존 표는 읽기만.
-- 표는 잠금(RLS, 정책 없음). 앱은 rpg_ 함수로만.

create table if not exists rpg_sessions (
  token_hash text primary key,
  work_member_id uuid not null references work_members(id) on delete cascade,
  created_at timestamptz not null default now(),
  expires_at timestamptz not null
);
create table if not exists rpg_players (
  work_member_id uuid primary key references work_members(id) on delete cascade,
  hero_name text,                 -- 히어로 오운완의 이름(실명)
  peaks jsonb not null default '{}', -- 한 번 닿은 레벨(안 내려가게)
  updated_at timestamptz not null default now()
);
alter table rpg_sessions enable row level security;
alter table rpg_players enable row level security;
revoke all on rpg_sessions, rpg_players from anon, authenticated;

insert into rpg_players(work_member_id, hero_name)
select id, '김귀룡' from work_members where name='깐깐룡이'
on conflict (work_member_id) do nothing;

create or replace function rpg_login(p_id text, p_pw text) returns json
language plpgsql security definer set search_path to 'public','extensions' as $$
declare w work_members; h text; t text;
begin
  select * into w from work_members where lower(login_id)=lower(btrim(coalesce(p_id,'')));
  if w.id is null or not w.active then return json_build_object('error','아이디나 비밀번호가 맞지 않아요.'); end if;
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

create or replace function rpg_logout(p_token text) returns json
language plpgsql security definer set search_path to 'public','extensions' as $$
begin
  delete from rpg_sessions where token_hash=encode(digest(coalesce(p_token,''),'sha256'),'hex');
  return json_build_object('ok',true);
end $$;

create or replace function rpg_member(p_token text) returns work_members
language plpgsql stable security definer set search_path to 'public','extensions' as $$
declare w work_members;
begin
  select m.* into w from rpg_sessions s join work_members m on m.id=s.work_member_id
   where s.token_hash=encode(digest(coalesce(p_token,''),'sha256'),'hex') and s.expires_at>now()
     and m.active and s.created_at>coalesce(m.session_after,'-infinity');
  if w.id is null then raise exception 'auth'; end if;
  return w;
end $$;

-- 날짜(한국)·영역·항목별 개수. 계산(EXP·레벨)은 앱이 한다 → 공식을 바꿔도 DB는 그대로.
create or replace function rpg_days(p_token text) returns json
language plpgsql stable security definer set search_path to 'public','extensions' as $$
declare w work_members; hero text; r json; today date := (now() at time zone 'Asia/Seoul')::date;
begin
  w := rpg_member(p_token);
  select hero_name into hero from rpg_players where work_member_id=w.id;
  with x as (
    -- 일: 내가 끝낸 할 일(Claude가 한 ⚡·🛠 줄은 뺌)
    select done_at d, '일' a, '할 일 끝냄' i from work_tasks
     where assignee=w.name and status='완료' and done_at is not null
       and coalesce(memo,'') not like '%⚡%' and coalesce(memo,'') not like '%🛠%'
    -- 건강: 기상·취침·운동·식단·오운완
    union all select day, '건강', '기상' from life_days ld join life_members lm on lm.id=ld.member_id where lm.work_member_id=w.id and ld.wake_time is not null
    union all select day, '건강', '취침' from life_days ld join life_members lm on lm.id=ld.member_id where lm.work_member_id=w.id and ld.bed_time is not null
    union all select day, '건강', case when kind='diet' then '식단' else '운동' end from life_posts lp join life_members lm on lm.id=lp.member_id where lm.work_member_id=w.id
    union all select date, '건강', '오운완' from hero_records where hero is not null and name=hero
    -- 영성: 묵상·SOC·감사·칭찬·일기
    union all select record_date, '영성', kind from spirit_records where written_by=w.name and record_date is not null
    -- 재정: 내가 볼 수 있는 장부에 적힌 것(아내가 적은 가정 장부 포함)
    union all select e.entry_date, '재정', b.name||' 장부' from fin_entries e join fin_books b on b.id=e.book_id
     where e.deleted_at is null and e.entry_date<=today and e.book_id in (select book_id from fin_book_members where work_member_id=w.id)
    -- 관계: 통화·메모, 카톡 1:1
    union all select (happened_at at time zone 'Asia/Seoul')::date, '관계', case when kind='memo' then '메모' else '통화' end from rel_records where created_by=w.id
    union all select (posted_at at time zone 'Asia/Seoul')::date, '관계', '카톡 1:1' from kakao_messages where owner_id=w.owner_id and not is_group
  )
  select json_build_object(
    'name', w.name, 'today', today,
    'peaks', (select peaks from rpg_players where work_member_id=w.id),
    'rows', coalesce((select json_agg(json_build_array(d, a, i, n) order by d) from (
      select d, a, i, count(*) n from x where d is not null and d<=today group by 1,2,3) z), '[]'::json)
  ) into r;
  return r;
end $$;

-- 한 번 닿은 레벨을 남긴다(더 큰 값만).
create or replace function rpg_save_peaks(p_token text, p_peaks jsonb) returns json
language plpgsql security definer set search_path to 'public','extensions' as $$
declare w work_members; cur jsonb; k text; v numeric;
begin
  w := rpg_member(p_token);
  insert into rpg_players(work_member_id) values (w.id) on conflict do nothing;
  select peaks into cur from rpg_players where work_member_id=w.id;
  for k, v in select key, value::numeric from jsonb_each_text(coalesce(p_peaks,'{}')) loop
    if v > coalesce((cur->>k)::numeric, 0) then cur := cur || jsonb_build_object(k, v); end if;
  end loop;
  update rpg_players set peaks=cur, updated_at=now() where work_member_id=w.id;
  return cur::json;
end $$;

revoke all on function rpg_member(text) from public, anon, authenticated;
grant execute on function rpg_login(text,text), rpg_logout(text), rpg_days(text), rpg_save_peaks(text,jsonb) to anon;
