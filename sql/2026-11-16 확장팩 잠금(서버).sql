-- 2026-11-16 프로그래머 점검 🔴3: 확장팩 잠금이 화면에만 있었다 → 기록 쓰기·열쇠 함수마다 서버에서도 막는다.
-- 주인은 늘 통과. 다른 사람은 그 확장팩이 open일 때만. 업무·영성 화면 열쇠(rpg_work_token)는 둘 중 하나만 열려 있어도 됨.
create or replace function rpg_need_pack(p_member uuid, p_packs text[]) returns void
language plpgsql stable security definer set search_path = public as $$
begin
  if exists (select 1 from rpg_players where work_member_id = p_member and owner) then return; end if;
  if not rpg_agreed(p_member, '기본') then raise exception 'pack: 동의가 필요해요'; end if;
  if not exists (select 1 from rpg_packs where work_member_id = p_member and pack = any(p_packs) and status = 'open') then
    raise exception 'pack: 아직 열리지 않은 기록이에요'; end if;
end $$;
revoke all on function rpg_need_pack(uuid, text[]) from public, anon, authenticated;

-- 각 함수의 첫 begin 바로 뒤에 한 줄을 넣어 다시 만든다(권한은 그대로 남음)
do $$
declare r record; d text;
begin
  for r in select * from (values
    ('rpg_spirit_add','영성'),('rpg_diary_save','영성'),('rpg_bible_toggle','영성'),('rpg_bible','영성'),
    ('rpg_prayer_save','영성'),('rpg_prayer_week','영성'),('rpg_spirit_learned','영성'),('rpg_spirit_day','영성'),
    ('rpg_fin_add','재정'),('rpg_fin_token','재정'),
    ('rpg_life_token','건강'),('rpg_wake','건강'),('rpg_bed','건강'),
    ('rpg_rel_token','관계'),
    ('rpg_task_done','업무'),('rpg_work_token','업무","영성')) v(fn, packs)
  loop
    select pg_get_functiondef(p.oid) into d from pg_proc p where p.pronamespace='public'::regnamespace and p.proname = r.fn;
    if d is null or position('rpg_need_pack' in d) > 0 then continue; end if;
    d := regexp_replace(d, E'\nbegin\n', E'\nbegin\n  perform rpg_need_pack(w.id, ''{"' || r.packs || E'"}''::text[]);\n');
    execute d;
  end loop;
end $$;
