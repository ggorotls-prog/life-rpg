-- 통독 한 벌(2026-11-19 룡: 「RPG에 있는 건 기록에도 있어야」). DB에는 마이그레이션 bible_core_for_spirit_site · rpg_bible_use_core 로 적용함.
-- 라이프 RPG(rpg_bible·rpg_bible_toggle)와 영성 기록 사이트(서버가 service_role로 /api/bible)가 같은 함수를 쓴다.
-- 읽은 장 = spirit_records(kind 통독, passage '책 장'). 몇 독째·시작일은 rpg_players(RPG를 안 쓰는 사람은 줄이 없어 1독째로만 센다).

create or replace function public.bible_state(p_member uuid)
 returns json language plpgsql stable security definer set search_path to 'public','extensions'
as $function$
declare w work_members; kst date := (now() at time zone 'Asia/Seoul')::date; p rpg_players;
begin
  select * into w from work_members where id = p_member;
  if w.id is null then return json_build_object('error','없는 사람이에요'); end if;
  select * into p from rpg_players where work_member_id = w.id;
  return json_build_object(
    'read', coalesce((select json_agg(distinct s.passage) from spirit_records s where s.owner_id=w.owner_id and s.kind='통독' and rpg_bible_cur(p, s)), '[]'::json),
    'today', (select count(*) from spirit_records where owner_id=w.owner_id and kind='통독' and record_date=kst and title not like '%(이전 통독 옮겨 적음)'),
    'rounds', coalesce(p.bible_rounds, 0),
    'started', p.bible_started,
    'done', coalesce((select json_agg(json_build_object('round', round, 'started', started, 'finished', finished) order by round desc)
                        from rpg_bible_rounds where work_member_id = w.id), '[]'::json));
end $function$;

-- p_player = RPG에서 부를 때 true(rpg_players 줄이 없으면 만든다). 영성 기록 사이트는 false(RPG 가입자가 아닌 사람을 RPG 명단에 넣지 않는다)
create or replace function public.bible_toggle(p_member uuid, p_ref text, p_on boolean, p_player boolean default false)
 returns json language plpgsql security definer set search_path to 'public','extensions'
as $function$
declare w work_members; kst date := (now() at time zone 'Asia/Seoul')::date; r text := btrim(coalesce(p_ref,'')); p rpg_players; n int;
begin
  select * into w from work_members where id = p_member;
  if w.id is null then return json_build_object('error','없는 사람이에요'); end if;
  if r !~ '^\S+ \d{1,3}$' then return json_build_object('error','장 이름이 이상해요'); end if;
  if p_player then insert into rpg_players(work_member_id) values (w.id) on conflict do nothing; end if;
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
  if p.work_member_id is null then return json_build_object('ok',true); end if;   -- RPG 줄이 없는 사람: 시작일·바퀴는 세지 않는다
  if p.bible_started is null then update rpg_players set bible_started = kst where work_member_id = w.id; end if;
  select count(distinct s.passage) into n from spirit_records s where s.owner_id=w.owner_id and s.kind='통독' and rpg_bible_cur(p, s);
  if n >= 1008 then
    insert into rpg_bible_rounds(work_member_id, round, started, finished) values (w.id, coalesce(p.bible_rounds,0) + 1, p.bible_started, kst);
    update rpg_players set bible_rounds = coalesce(bible_rounds,0) + 1, bible_started = kst, bible_started_at = now() where work_member_id = w.id;
    return json_build_object('ok', true, 'finished', coalesce(p.bible_rounds,0) + 1);
  end if;
  return json_build_object('ok',true);
end $function$;

revoke execute on function public.bible_state(uuid) from public, anon, authenticated;
revoke execute on function public.bible_toggle(uuid, text, boolean, boolean) from public, anon, authenticated;
grant execute on function public.bible_state(uuid) to service_role;
grant execute on function public.bible_toggle(uuid, text, boolean, boolean) to service_role;

-- 라이프 RPG 쪽: 열쇠·확장팩 확인 뒤 같은 한 벌을 부른다
create or replace function public.rpg_bible(p_token text)
 returns json language plpgsql stable security definer set search_path to 'public','extensions'
as $function$
declare w work_members := rpg_member(p_token);
begin
  perform rpg_need_pack(w.id, '{"영성"}'::text[]);
  return bible_state(w.id);
end $function$;

create or replace function public.rpg_bible_toggle(p_token text, p_ref text, p_on boolean)
 returns json language plpgsql security definer set search_path to 'public','extensions'
as $function$
declare w work_members := rpg_member(p_token);
begin
  perform rpg_need_pack(w.id, '{"영성"}'::text[]);
  return bible_toggle(w.id, p_ref, p_on, true);
end $function$;
