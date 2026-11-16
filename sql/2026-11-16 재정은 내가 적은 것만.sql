-- 2026-11-16 룡: 「와이프가 등록한 게 내 경험치로 올라갔다. 내 건 내 거, 와이프 건 와이프 거」
-- 재정 EXP = 내가 적은 장부 기록만(created_by = 나). 적은 사람이 비어 있는 옛 기록(11-10 이전)은 나만 들어 있는 장부일 때만 센다.
CREATE OR REPLACE FUNCTION public.rpg_days(p_token text)
 RETURNS json
 LANGUAGE plpgsql
 STABLE SECURITY DEFINER
 SET search_path TO 'public', 'extensions'
AS $function$
declare w work_members; hero text; r json; today date := (now() at time zone 'Asia/Seoul')::date;
begin
  w := rpg_member(p_token);
  select hero_name into hero from rpg_players where work_member_id=w.id;
  with x as (
    select done_at d, '일' a, '할 일 끝냄' i from work_tasks
     where assignee=w.name and status='완료' and done_at is not null
       and coalesce(memo,'') not like '%⚡%' and coalesce(memo,'') not like '%🛠%'
    union all select day, '건강', '기상' from life_days ld join life_members lm on lm.id=ld.member_id where lm.work_member_id=w.id and ld.wake_time is not null
    union all select day, '건강', '취침' from life_days ld join life_members lm on lm.id=ld.member_id where lm.work_member_id=w.id and ld.bed_time is not null
    union all select day, '건강', case when kind='diet' then '식단' else '운동' end from life_posts lp join life_members lm on lm.id=lp.member_id where lm.work_member_id=w.id
    union all select date, '건강', '오운완' from hero_records where hero is not null and name=hero
    union all select record_date, '영성', kind from spirit_records where written_by=w.name and record_date is not null
    union all select e.entry_date, '재정', b.name||' 장부' from fin_entries e join fin_books b on b.id=e.book_id
     where e.deleted_at is null and e.entry_date<=today
       and e.book_id in (select book_id from fin_book_members where work_member_id=w.id)
       and (e.created_by = w.id
            or (e.created_by is null and not exists (select 1 from fin_book_members o where o.book_id=e.book_id and o.work_member_id<>w.id)))
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
end $function$;
