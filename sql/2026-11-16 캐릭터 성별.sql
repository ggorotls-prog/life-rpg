-- 2026-11-16 캐릭터 성별 칸 추가: 마을 캐릭터를 🧍‍♂️ 남자 / 🧍‍♀️ 여자로 보여 준다.
-- 처음 열 때 앱이 한 번 묻고 여기에 남는다. 이미 쓰던 계정 하나는 미리 넣어 둔다.
alter table rpg_players add column if not exists gender text check (gender in ('남','여'));
update rpg_players set gender='남' where work_member_id=(select id from work_members where name='깐깐룡이') and gender is null;

-- p_set 없이 부르면 지금 값(없으면 null), p_set='남'/'여'면 바꾸고 그 값을 돌려준다.
create or replace function rpg_gender(p_token text, p_set text default null)
returns json language plpgsql security definer set search_path = public, extensions as $$
declare w work_members := rpg_member(p_token); g text;
begin
  insert into rpg_players(work_member_id) values (w.id) on conflict do nothing;
  if p_set is not null then
    if p_set not in ('남','여') then return json_build_object('error','남 또는 여'); end if;
    update rpg_players set gender=p_set, updated_at=now() where work_member_id=w.id;
  end if;
  select gender into g from rpg_players where work_member_id=w.id;
  return json_build_object('gender', g);
end $$;
revoke all on function rpg_gender(text, text) from public;
grant execute on function rpg_gender(text, text) to anon;
