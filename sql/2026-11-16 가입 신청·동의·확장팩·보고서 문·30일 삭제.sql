-- 2026-11-16 가입 신청 + 개인정보 안전장치 (법무사 검토 3번 반영, 동의 문구 = 시안\가입 동의 문구 초안.md)
-- 1) 동의 기록: 지우지 않고 쌓기만 한다(언제 무엇에 동의·철회했는지 증거). 지금 상태 = 항목마다 마지막 줄.
-- 2) 확장팩: 기록마다 열림/닫힘. 열린 것만 마을에 건물이 뜬다.
-- 3) 가입 신청: 승인 전까지 따로 둔다. 비밀번호는 bcrypt 해시만.
-- 4) 보고서 문: 남의 기록은 rpg_report_data로만 읽는다. 동의 안 한 기록은 아예 안 나온다. (anon에 안 엶)
-- 5) 30일 삭제: 확장팩을 닫거나 탈퇴하고 30일 지나면 매일 새벽 3시 30분(KST)에 지운다. 지운 기록은 내용 없이 rpg_purge_log에만.
-- 관계 확장팩은 다른 사람에게 열지 않는다(남의 이름·번호). 보고서에도 주인(owner) 것만.

create table if not exists rpg_consents (
  id bigserial primary key,
  work_member_id uuid not null references work_members(id) on delete cascade,
  item text not null check (item in ('기본','만14세','건강','건강_민감','재정','영성','영성_민감','업무','관계',
                                     '보고서_읽기','보고서_AI','보고서_민감')),
  agreed boolean not null,
  version int not null,
  at timestamptz not null default now()
);
create index if not exists rpg_consents_m on rpg_consents(work_member_id, item, at desc);

create or replace view rpg_consent_now with (security_invoker = true) as
  select distinct on (work_member_id, item) work_member_id, item, agreed, version, at
  from rpg_consents order by work_member_id, item, at desc, id desc;

create table if not exists rpg_packs (
  work_member_id uuid not null references work_members(id) on delete cascade,
  pack text not null check (pack in ('건강','재정','영성','업무','관계')),
  status text not null default 'requested' check (status in ('requested','open','closed','purged')),
  opened_at timestamptz, closed_at timestamptz,
  primary key (work_member_id, pack),
  check (status <> 'closed' or closed_at is not null)
);

create table if not exists rpg_join_requests (
  id uuid primary key default gen_random_uuid(),
  nickname text not null,
  pw_hash text not null,
  gender text not null check (gender in ('남','여')),
  packs text[] not null,
  consents jsonb not null,
  version int not null,
  status text not null default 'pending' check (status in ('pending','approved','rejected')),
  created_at timestamptz not null default now(),
  decided_at timestamptz,
  work_member_id uuid
);

create table if not exists rpg_purge_log (
  id bigserial primary key, work_member_id uuid, what text not null, at timestamptz not null default now()
);

alter table rpg_players add column if not exists owner boolean not null default false;
alter table rpg_players add column if not exists left_at timestamptz;
-- rpg_only = 가입 신청으로 RPG에서만 쓰는 사람. 이 사람만 확장팩 닫기·탈퇴 때 기록을 지운다.
-- 기존 사용자(업무·건강·재정 기록 앱을 따로 쓰던 사람)는 RPG에서 닫아도 건물만 숨고, 원래 앱 기록은 그대로(그 앱의 기록이라서).
alter table rpg_players add column if not exists rpg_only boolean not null default false;

alter table rpg_consents enable row level security;
alter table rpg_packs enable row level security;
alter table rpg_join_requests enable row level security;
alter table rpg_purge_log enable row level security;
revoke all on rpg_consents, rpg_packs, rpg_join_requests, rpg_purge_log, rpg_consent_now from anon, authenticated;

-- 주인(만든 사람)은 처음부터 모두 열림·동의(본인 기록). 다른 기존 사용자는 다음에 앱을 열 때 동의 창으로 받는다.
update rpg_players set owner = true where work_member_id = (select id from work_members where name='깐깐룡이');
insert into rpg_packs(work_member_id, pack, status, opened_at)
  select id, p, 'open', now() from work_members, unnest(array['건강','재정','영성','업무','관계']) p where name='깐깐룡이'
  on conflict do nothing;
insert into rpg_consents(work_member_id, item, agreed, version)
  select w.id, i, true, 1 from work_members w,
    unnest(array['기본','만14세','건강','건강_민감','재정','영성','영성_민감','업무','관계','보고서_읽기','보고서_AI','보고서_민감']) i
  where w.name='깐깐룡이' and not exists (select 1 from rpg_consents c where c.work_member_id=w.id);

-- 동의 하나 확인
create or replace function rpg_agreed(p_member uuid, p_item text) returns boolean
language sql stable security definer set search_path = public as $$
  select coalesce((select agreed from rpg_consent_now where work_member_id=p_member and item=p_item), false)
$$;

-- 보고서에 쓸 수 있는 범위(남의 기록 보고서는 이것부터 본다)
create or replace function rpg_report_scope(p_member uuid) returns json
language plpgsql stable security definer set search_path = public as $$
declare pl rpg_players; areas text[] := '{}'; a text;
begin
  select * into pl from rpg_players where work_member_id = p_member;
  if pl.work_member_id is null or pl.left_at is not null or not rpg_agreed(p_member, '보고서_읽기') then
    return json_build_object('ok', false, 'ai', false, 'areas', '[]'::json);
  end if;
  for a in select pack from rpg_packs where work_member_id=p_member and status='open' loop
    if a = '관계' and not pl.owner then continue; end if;                              -- 남의 정보
    if a in ('건강','영성') and not rpg_agreed(p_member, '보고서_민감') then continue; end if;
    if not rpg_agreed(p_member, a) then continue; end if;
    if a in ('건강','영성') and not rpg_agreed(p_member, a||'_민감') then continue; end if;
    areas := areas || a;
  end loop;
  return json_build_object('ok', coalesce(array_length(areas,1),0) > 0, 'ai', rpg_agreed(p_member, '보고서_AI'), 'areas', to_json(areas));
end $$;

-- 보고서용 기록 꺼내기: 범위 밖 기록은 아예 안 나온다
create or replace function rpg_report_data(p_member uuid, p_from date, p_to date) returns json
language plpgsql stable security definer set search_path = public as $$
declare sc json := rpg_report_scope(p_member); w work_members; ar text[]; out jsonb := '{}'::jsonb;
begin
  if not coalesce((sc->>'ok')::boolean, false) then return json_build_object('scope', sc, 'error', '보고서 동의가 없어요'); end if;
  select * into w from work_members where id = p_member;
  if (select count(*) from work_members where name = w.name) > 1 then raise exception '같은 이름이 둘 이상이라 멈춤: %', w.name; end if;
  ar := array(select json_array_elements_text(sc->'areas'));
  if '건강' = any(ar) then out := out || jsonb_build_object('건강', (
    select coalesce(jsonb_agg(to_jsonb(x) order by x.day), '[]') from (
      select d.day, d.wake_time, d.bed_time, d.condition,
        (select jsonb_agg(jsonb_build_object('kind',p.kind,'slot',p.slot,'note',p.note)) from life_posts p where p.member_id=m.id and p.day=d.day) posts
      from life_members m join life_days d on d.member_id=m.id
      where m.work_member_id=p_member and d.day between p_from and p_to) x)); end if;
  if '재정' = any(ar) then out := out || jsonb_build_object('재정', (
    select coalesce(jsonb_agg(jsonb_build_object('date',e.entry_date,'amount',e.amount,'item',btrim(coalesce(i.middle,'')||' '||coalesce(i.detail,'')),'memo',e.memo) order by e.entry_date), '[]')
    from fin_entries e left join fin_items i on i.id=e.item_id
    where e.created_by=p_member and e.deleted_at is null and e.entry_date between p_from and p_to)); end if;
  if '영성' = any(ar) then out := out || jsonb_build_object('영성', (
    select coalesce(jsonb_agg(jsonb_build_object('date',s.record_date,'kind',s.kind,'title',s.title,'body',s.body,
      'passage',s.passage,'obey',s.obey,'prayer',s.prayer,'learned',s.learned) order by s.record_date), '[]')
    from spirit_records s where s.written_by=w.name and s.record_date between p_from and p_to)); end if;
  if '업무' = any(ar) then out := out || jsonb_build_object('업무', (
    select coalesce(jsonb_agg(jsonb_build_object('title',t.title,'status',t.status,'due',t.due,'done_at',t.done_at) order by t.due), '[]')
    from work_tasks t where t.assignee=w.name and (t.due between p_from and p_to or t.done_at::date between p_from and p_to))); end if;
  if '관계' = any(ar) then out := out || jsonb_build_object('관계', (
    select coalesce(jsonb_agg(jsonb_build_object('at',r.happened_at,'kind',r.kind,'area',r.area,'body',r.body) order by r.happened_at), '[]')
    from rel_records r where r.created_by=p_member and r.happened_at::date between p_from and p_to)); end if;
  return json_build_object('scope', sc, 'data', out);
end $$;

-- 기록 하나 지우기(내용은 로그에 안 남김)
create or replace function rpg_purge_area(p_member uuid, p_pack text) returns void
language plpgsql security definer set search_path = public as $$
declare w work_members; solo uuid[];
begin
  select * into w from work_members where id = p_member;
  if w.id is null then return; end if;
  -- 안전: 주인 것, RPG 전용이 아닌 사람 것, 이름이 겹치는 사람 것은 절대 안 지운다
  if exists (select 1 from rpg_players where work_member_id = p_member and (owner or not rpg_only)) then
    raise exception '지우면 안 되는 사람: %', w.name; end if;
  if not exists (select 1 from rpg_players where work_member_id = p_member) then raise exception 'RPG 사용자 아님'; end if;
  if (select count(*) from work_members where name = w.name) > 1 then raise exception '같은 이름이 둘 이상: %', w.name; end if;
  if p_pack = '건강' then
    delete from life_members where work_member_id = p_member;                         -- 하루 기록·사진 글·세션은 함께 지워짐
  elsif p_pack = '재정' then
    solo := array(select bm.book_id from fin_book_members bm where bm.work_member_id = p_member
                  and not exists (select 1 from fin_book_members o where o.book_id = bm.book_id and o.work_member_id <> p_member));
    delete from fin_entries where book_id = any(solo);                              -- 순서: 기록 → 고정 → 묶음 → 범위 → 장부(항목은 장부와 함께)
    delete from fin_scopes where book_id = any(solo) or work_member_id = p_member;
    delete from fin_fixed where book_id = any(solo);
    delete from fin_funds where book_id = any(solo);
    delete from fin_books where id = any(solo);                                     -- 혼자 쓰던 장부째
    update fin_entries set created_by = null where created_by = p_member;          -- 같이 쓰는 장부: 줄은 가족 기록이라 두고 이 사람과의 연결만 끊음
    delete from fin_book_members where work_member_id = p_member;                  -- 같이 쓰는 장부에서는 빠지기만
  elsif p_pack = '영성' then
    delete from spirit_records where written_by = w.name;
  elsif p_pack = '업무' then
    delete from work_tasks where assignee = w.name and assigned_by = w.name;        -- 남과 주고받은 일은 남의 기록이라 둠
  elsif p_pack = '관계' then
    delete from rel_records where created_by = p_member;
    delete from rel_people where created_by = p_member;
    delete from rel_my_areas where work_member_id = p_member;
  end if;
  insert into rpg_purge_log(work_member_id, what) values (p_member, p_pack);
end $$;

-- 매일 도는 30일 삭제
create or replace function rpg_purge() returns int
language plpgsql security definer set search_path = public as $$
declare r record; n int := 0; p text;
begin
  -- 확장팩 닫고 30일: RPG 전용 사람만 지움. 기존 사용자는 건물만 숨고 기록은 원래 앱에 그대로(purged로만 표시)
  for r in select k.work_member_id, k.pack, pl.rpg_only, pl.owner from rpg_packs k join rpg_players pl using (work_member_id)
           where k.status='closed' and k.closed_at < now() - interval '30 days' loop
    begin
      if r.rpg_only and not r.owner then perform rpg_purge_area(r.work_member_id, r.pack); end if;
      update rpg_packs set status='purged' where work_member_id=r.work_member_id and pack=r.pack;
      n := n + 1;
    exception when others then insert into rpg_purge_log(work_member_id, what) values (r.work_member_id, '실패 '||r.pack||': '||left(sqlerrm,80));
    end;
  end loop;
  -- 탈퇴 30일: RPG 전용 사람은 연 확장팩 기록·계정을 지움. 기존 사용자는 RPG 쪽 줄만 지우고 원래 앱 계정·기록은 그대로
  for r in select work_member_id, rpg_only from rpg_players where left_at is not null and left_at < now() - interval '30 days' and not owner loop
    begin
      if r.rpg_only then
        for p in select pack from rpg_packs where work_member_id = r.work_member_id and status <> 'purged' loop
          perform rpg_purge_area(r.work_member_id, p);
        end loop;
        update work_members set active=false, login_id=null, login_email=null, push_token=null where id = r.work_member_id;
      end if;
      delete from rpg_join_requests where work_member_id = r.work_member_id;
      delete from rpg_sessions where work_member_id = r.work_member_id;
      delete from rpg_packs where work_member_id = r.work_member_id;
      delete from rpg_consents where work_member_id = r.work_member_id;
      delete from rpg_players where work_member_id = r.work_member_id;
      insert into rpg_purge_log(work_member_id, what) values (r.work_member_id, '탈퇴');
      n := n + 1;
    exception when others then insert into rpg_purge_log(work_member_id, what) values (r.work_member_id, '실패 탈퇴: '||left(sqlerrm,80));
    end;
  end loop;
  -- 거절된 가입 신청은 30일 뒤 지움
  delete from rpg_join_requests where status='rejected' and decided_at < now() - interval '30 days';
  return n;
end $$;

revoke all on function rpg_agreed(uuid,text), rpg_report_scope(uuid), rpg_report_data(uuid,date,date),
  rpg_purge_area(uuid,text), rpg_purge() from public, anon, authenticated;

select cron.schedule('rpg-purge-daily', '30 18 * * *', 'select rpg_purge()');   -- UTC 18:30 = KST 03:30
