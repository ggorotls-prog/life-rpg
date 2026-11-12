-- 라이프 RPG 2단계 「마을」(2026-11-12 룡 결정): 게임 화면 아이콘으로 바로 기록. 기록은 기존 앱과 같은 표에 쌓는다.
-- 집 = 건강 기록 life_days(일어났어요·잘게요) / 지갑 = 재정 기록 fin_entries / 퀘스트 = 업무 기록 work_tasks(내가 나에게 준 할 일만 완료)
-- 아빠 집 = 영성 기록 앱을 연다(쓰기는 그 앱에서). 여기서는 오늘 무엇을 했는지만 읽는다.
-- 규칙은 각 앱 함수(life_wake·life_bed·fin_add·업무 api/task-status.js)와 같게 맞췄다.

create or replace function rpg_home(p_token text) returns json
language plpgsql stable security definer set search_path to 'public','extensions' as $$
declare w work_members := rpg_member(p_token); m life_members; kst date := (now() at time zone 'Asia/Seoul')::date; d life_days;
begin
  select * into m from life_members where work_member_id=w.id limit 1;
  if m.id is not null then select * into d from life_days where member_id=m.id and day=life_today(); end if;
  return json_build_object(
    'today', kst,
    'health', case when m.id is null then null else json_build_object(
      'day', life_today(), 'wake', to_char(d.wake_time,'HH24:MI'), 'bed', to_char(d.bed_time,'HH24:MI'),
      'condition', d.condition, 'need', life_need(m)) end,
    'tasks', coalesce((select json_agg(json_build_object('id',t.id,'title',t.title,'due',t.due,'company',t.company,'important',t.important)
        order by t.important desc nulls last, t.due nulls last, t.created_at)
      from work_tasks t where t.assignee=w.name and t.assigned_by=w.name and t.status='할 일'
        and (t.due<=kst or (t.remind_at at time zone 'Asia/Seoul')::date<=kst)), '[]'::json),
    'tasks_done_today', (select count(*) from work_tasks where assignee=w.name and status='완료' and done_at=kst
        and coalesce(memo,'') not like '%⚡%' and coalesce(memo,'') not like '%🛠%'),
    'books', coalesce((select json_agg(json_build_object('id',b.id,'name',b.name) order by b.created_at)
      from fin_books b join fin_book_members bm on bm.book_id=b.id where bm.work_member_id=w.id), '[]'::json),
    'items', coalesce((select json_agg(json_build_object('id',i.id,'book',i.book_id,'kind',i.kind,'bucket',i.bucket,'middle',i.middle,
        'detail',i.detail,'area',i.area,'n',(select count(*) from fin_entries e where e.item_id=i.id and e.deleted_at is null and e.entry_date>kst-90))
        order by i.sort)
      from fin_items i where i.active and exists (select 1 from fin_scopes s where s.work_member_id=w.id and s.book_id=i.book_id and s.area=i.area)), '[]'::json),
    'fin_today', (select count(*) from fin_entries where created_by=w.id and deleted_at is null and (created_at at time zone 'Asia/Seoul')::date=kst),
    'spirit_today', coalesce((select json_agg(distinct kind) from spirit_records where written_by=w.name and record_date=kst), '[]'::json)
  );
end $$;

-- 집: 일어났어요(컨디션 1~3). 어젯밤 잔 시각이 없으면 같이 받는다(건강 기록 life_wake와 같은 규칙).
create or replace function rpg_wake(p_token text, p_condition int, p_yesterday_bed text default null) returns json
language plpgsql security definer set search_path to 'public','extensions' as $$
declare w work_members := rpg_member(p_token); m life_members; t date := life_today();
begin
  select * into m from life_members where work_member_id=w.id limit 1;
  if m.id is null then return json_build_object('error','건강 기록 계정이 이어져 있지 않아요'); end if;
  if p_condition not between 1 and 3 then return json_build_object('error','컨디션을 골라 주세요'); end if;
  if life_need(m)='bed_yesterday' then
    if p_yesterday_bed is null or p_yesterday_bed !~ '^\d{1,2}:\d{2}$' then return json_build_object('need','bed_yesterday'); end if;
    insert into life_days(member_id, day, bed_time) values (m.id, t-1, p_yesterday_bed::time)
      on conflict (member_id, day) do update set bed_time=excluded.bed_time;
  end if;
  insert into life_days(member_id, day, wake_time, condition) values (m.id, t, (now() at time zone 'Asia/Seoul')::time(0), p_condition)
    on conflict (member_id, day) do update set wake_time=excluded.wake_time, condition=excluded.condition;
  return json_build_object('ok',true);
end $$;

-- 집: 잘게요(지금 시각). 오늘 일어난 기록이 먼저 있어야 한다(life_bed와 같은 규칙).
create or replace function rpg_bed(p_token text) returns json
language plpgsql security definer set search_path to 'public','extensions' as $$
declare w work_members := rpg_member(p_token); m life_members; n int;
begin
  select * into m from life_members where work_member_id=w.id limit 1;
  if m.id is null then return json_build_object('error','건강 기록 계정이 이어져 있지 않아요'); end if;
  if life_need(m) is not null then return json_build_object('error','오늘 일어난 기록이 먼저 있어야 해요'); end if;
  update life_days set bed_time=(now() at time zone 'Asia/Seoul')::time(0), last_check_at=now() where member_id=m.id and day=life_today();
  get diagnostics n = row_count;
  return json_build_object('ok', n>0);
end $$;

-- 퀘스트: 내가 나에게 준 할 일만 완료(업무 기록 api/task-status.js: 맡긴 사람 = 나 → 완료, done_at = 오늘)
create or replace function rpg_task_done(p_token text, p_id uuid) returns json
language plpgsql security definer set search_path to 'public','extensions' as $$
declare w work_members := rpg_member(p_token); n int;
begin
  update work_tasks set status='완료', done_at=(now() at time zone 'Asia/Seoul')::date, updated_at=now()
   where id=p_id and assignee=w.name and assigned_by=w.name and status='할 일';
  get diagnostics n = row_count;
  if n=0 then return json_build_object('error','이미 바뀌었거나 내 할 일이 아니에요'); end if;
  return json_build_object('ok',true);
end $$;

-- 지갑: 오늘 날짜로 한 줄(재정 기록 fin_add와 같은 권한 검사: 장부 구성원 + 그 영역 기록 권한)
create or replace function rpg_fin_add(p_token text, p_item uuid, p_amount bigint, p_memo text default '') returns json
language plpgsql security definer set search_path to 'public','extensions' as $$
declare w work_members := rpg_member(p_token); i fin_items; v uuid;
begin
  select * into i from fin_items where id=p_item and active;
  if i.id is null then return json_build_object('error','없는 항목이에요'); end if;
  if not exists (select 1 from fin_book_members where work_member_id=w.id and book_id=i.book_id)
     or not exists (select 1 from fin_scopes s where s.work_member_id=w.id and s.book_id=i.book_id and s.area=i.area)
    then return json_build_object('error','이 항목은 기록 권한이 없어요'); end if;
  if p_amount is null or p_amount<=0 then return json_build_object('error','금액을 넣어 주세요'); end if;
  insert into fin_entries(book_id, entry_date, item_id, amount, memo, created_by)
  values (i.book_id, (now() at time zone 'Asia/Seoul')::date, i.id, p_amount, coalesce(p_memo,''), w.id) returning id into v;
  return json_build_object('ok',true,'id',v);
end $$;

revoke all on function rpg_home(text), rpg_wake(text,int,text), rpg_bed(text), rpg_task_done(text,uuid), rpg_fin_add(text,uuid,bigint,text) from public;
grant execute on function rpg_home(text), rpg_wake(text,int,text), rpg_bed(text), rpg_task_done(text,uuid), rpg_fin_add(text,uuid,bigint,text) to anon;
