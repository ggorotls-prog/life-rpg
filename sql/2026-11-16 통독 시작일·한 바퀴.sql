-- 2026-11-16 룡: 「통독 시작일도 넣어 주고 대략 하루 10장 기준해서 역산으로 넣어 줘. 다 하면 체크하고 다시 창세기로 돌아가고 기록이 남는 걸로」
-- ① 지난 통독 696장(창세기 1 ~ 요엘 1)의 날짜를 하루 10장으로 거꾸로 셈: 요엘 1이 있는 마지막 10장 = 2026-11-16 … 창세기 1~6 = 2026-09-08(시작일).
-- ② 이번 바퀴 시작일 rpg_players.bible_started(+ 다음 바퀴부터는 시작 순간 bible_started_at). 통독 창 맨 위에 「19독째 · 9/8 시작」.
-- ③ 한 바퀴(1,008장)를 다 체크하면: 끝낸 바퀴를 rpg_bible_rounds에 남기고(몇 번째·시작일·끝난 날) 몇 독 +1, 새 바퀴는 오늘부터 창세기 1장.
--    지난 바퀴의 통독 줄은 지우지 않고 남긴다(체크를 풀 때도 이번 바퀴 줄만 지운다).
-- ④ EXP: 옮겨 적은 지난 통독(제목 끝 「(이전 통독 옮겨 적음)」)은 EXP로 세지 않는다(그날 RPG로 기록한 게 아니라서. 70일치 영성 EXP가 한꺼번에 오르지 않게).

-- ① 날짜 거꾸로
with b(ord, book, n) as (values (1,'창세기',50),(2,'출애굽기',40),(3,'레위기',27),(4,'민수기',36),(5,'신명기',34),(6,'여호수아',24),(7,'사사기',21),(8,'룻기',4),(9,'사무엘상',31),(10,'사무엘하',24),(11,'열왕기상',22),(12,'열왕기하',25),(13,'역대상',29),(14,'역대하',36),(15,'에스라',10),(16,'느헤미야',13),(17,'에스더',10),(18,'욥기',42),(19,'전도서',12),(20,'아가',8),(21,'이사야',66),(22,'예레미야',52),(23,'예레미야애가',5),(24,'에스겔',48),(25,'다니엘',12),(26,'호세아',14),(27,'요엘',1)),
refs as (select book||' '||c as r, row_number() over (order by ord, c) - 1 as i, count(*) over () as total from b, generate_series(1,b.n) c)
update spirit_records s
   set record_date = date '2026-11-16' - ((refs.total - 1 - refs.i) / 10)::int,
       title = (date '2026-11-16' - ((refs.total - 1 - refs.i) / 10)::int)::text || ' 통독 ' || refs.r || ' (이전 통독 옮겨 적음)'
  from refs
 where s.kind = '통독' and s.passage = refs.r and s.title like '%(이전 통독 옮겨 적음)'
   and s.owner_id = (select owner_id from work_members where login_id = '깐깐룡이');

-- ② 시작일
alter table rpg_players add column if not exists bible_started date;
alter table rpg_players add column if not exists bible_started_at timestamptz;
update rpg_players p set bible_started = (select min(record_date) from spirit_records s join work_members m on m.owner_id = s.owner_id
  where m.id = p.work_member_id and s.kind = '통독') where p.work_member_id = (select id from work_members where login_id = '깐깐룡이');

-- ③ 끝낸 바퀴 기록
create table if not exists rpg_bible_rounds (
  id uuid primary key default gen_random_uuid(),
  work_member_id uuid not null references work_members(id),
  round int not null,
  started date,
  finished date not null,
  created_at timestamptz not null default now()
);
alter table rpg_bible_rounds enable row level security;  -- 함수로만(정책 없음 = 브라우저·앱은 직접 못 봄)

-- 이번 바퀴의 통독 줄인가
create or replace function rpg_bible_cur(p rpg_players, s spirit_records) returns boolean
language sql immutable as $$
  select s.record_date >= coalesce(p.bible_started, '-infinity'::date)
     and (p.bible_started_at is null or s.created_at > p.bible_started_at)  -- 끝낸 순간에 만든 줄(마지막 장)은 지난 바퀴
$$;

create or replace function rpg_bible(p_token text) returns json
language plpgsql stable security definer set search_path = public, extensions as $$
declare w work_members := rpg_member(p_token); kst date := (now() at time zone 'Asia/Seoul')::date; p rpg_players;
begin
  select * into p from rpg_players where work_member_id = w.id;
  return json_build_object(
    'read', coalesce((select json_agg(distinct s.passage) from spirit_records s where s.owner_id=w.owner_id and s.kind='통독' and rpg_bible_cur(p, s)), '[]'::json),
    'today', (select count(*) from spirit_records where owner_id=w.owner_id and kind='통독' and record_date=kst and title not like '%(이전 통독 옮겨 적음)'),
    'rounds', coalesce(p.bible_rounds, 0),
    'started', p.bible_started,
    'done', coalesce((select json_agg(json_build_object('round', round, 'started', started, 'finished', finished) order by round desc)
                        from rpg_bible_rounds where work_member_id = w.id), '[]'::json));
end $$;

create or replace function rpg_bible_toggle(p_token text, p_ref text, p_on boolean) returns json
language plpgsql security definer set search_path = public, extensions as $$
declare w work_members := rpg_member(p_token); kst date := (now() at time zone 'Asia/Seoul')::date; r text := btrim(coalesce(p_ref,''));
        p rpg_players; n int;
begin
  if r !~ '^\S+ \d{1,3}$' then return json_build_object('error','장 이름이 이상해요'); end if;
  insert into rpg_players(work_member_id) values (w.id) on conflict do nothing;
  select * into p from rpg_players where work_member_id = w.id;
  if p_on then
    if not exists (select 1 from spirit_records s where s.owner_id=w.owner_id and s.kind='통독' and s.passage=r and rpg_bible_cur(p, s)) then
      insert into spirit_records(title,kind,record_date,written_by,owner_id,status,passage)
      values (kst||' 통독 '||r,'통독',kst,w.name,w.owner_id,'완료',r);
    end if;
  else
    delete from spirit_records s where s.owner_id=w.owner_id and s.kind='통독' and s.passage=r and rpg_bible_cur(p, s);
    return json_build_object('ok',true);
  end if;
  if p.bible_started is null then update rpg_players set bible_started = kst where work_member_id = w.id; end if;
  -- 한 바퀴(시편·잠언 뺀 1,008장)를 다 채웠으면: 기록 남기고 몇 독 +1, 새 바퀴는 지금부터
  select count(distinct s.passage) into n from spirit_records s where s.owner_id=w.owner_id and s.kind='통독' and rpg_bible_cur(p, s);
  if n >= 1008 then
    insert into rpg_bible_rounds(work_member_id, round, started, finished) values (w.id, coalesce(p.bible_rounds,0) + 1, p.bible_started, kst);
    update rpg_players set bible_rounds = coalesce(bible_rounds,0) + 1, bible_started = kst, bible_started_at = now() where work_member_id = w.id;
    return json_build_object('ok', true, 'finished', coalesce(p.bible_rounds,0) + 1);
  end if;
  return json_build_object('ok',true);
end $$;
revoke all on function rpg_bible_cur(rpg_players, spirit_records) from public, anon, authenticated;

-- ④ rpg_days 영성 줄: 옮겨 적은 지난 통독은 빼고 센다
do $$
declare d text := pg_get_functiondef('rpg_days(text)'::regprocedure);
begin
  d := replace(d, $q$from spirit_records where written_by=w.name and record_date is not null$q$,
                  $q$from spirit_records where written_by=w.name and record_date is not null and coalesce(title,'') not like '%(이전 통독 옮겨 적음)'$q$);
  execute d;
end $$;
