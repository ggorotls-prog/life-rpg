-- 2026-11-16 룡: 「통독은 요엘서 1장까지 체크 부탁, 통독 수는 그냥 대략 18독 정도 해 줘」
-- ① 지난 통독 옮겨 적기: 창세기 1장 ~ 요엘 1장(시편·잠언 뺀 표 순서) 696장을 통독 줄로 넣음. 날짜는 RPG 시작 전날 2026-11-11(언제 읽었는지 몰라서 한 날로), 제목 끝에 「(이전 통독 옮겨 적음)」. 이미 있는 장은 건너뜀.
-- ② 몇 독: rpg_players.bible_rounds(끝낸 번 수) 칸을 더하고 룡 = 18. rpg_bible이 rounds를 같이 준다 → 통독 창 맨 위 「18독 · 19독째 n%」.
--    한 바퀴(1,008장)를 다 채운 뒤 몇 독을 올리고 체크를 비우는 것은 아직 없음(다 채우면 룡께 여쭤 만든다).
alter table rpg_players add column if not exists bible_rounds int not null default 0;
create or replace function rpg_bible(p_token text) returns json
language plpgsql stable security definer set search_path = public, extensions as $$
declare w work_members := rpg_member(p_token); kst date := (now() at time zone 'Asia/Seoul')::date;
begin
  return json_build_object(
    'read', coalesce((select json_agg(distinct passage) from spirit_records where owner_id=w.owner_id and kind='통독'), '[]'::json),
    'today', (select count(*) from spirit_records where owner_id=w.owner_id and kind='통독' and record_date=kst),
    'rounds', coalesce((select bible_rounds from rpg_players where work_member_id=w.id), 0));
end $$;
-- 룡 몫 자료(실행함): 지난 통독 696장 + bible_rounds = 18
-- with b(ord, book, n) as (values (1,'창세기',50), … ,(26,'호세아',14),(27,'요엘',1)), refs as (select book||' '||c as r from b, generate_series(1,b.n) c)
-- insert into spirit_records(title,kind,record_date,written_by,owner_id,status,passage) select '2026-11-11 통독 '||r||' (이전 통독 옮겨 적음)','통독','2026-11-11',<룡>,<룡 owner>,'완료',r from refs where not exists(…);
-- update rpg_players set bible_rounds = 18 where work_member_id = <룡>;
