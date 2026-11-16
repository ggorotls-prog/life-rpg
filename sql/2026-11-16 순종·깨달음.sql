-- 2026-11-16 룡: 「묵상은 아침에 앉아서 시작하지만 순종한 것은 하루 중에 수집되는 거야. 깨달음도 수집되는 거고 그걸 알아야 해. 그러니 라이프 RPG로.」
-- ⛪ 아빠 집에서 감사·칭찬처럼 한 줄로 바로 적는다: 🙌 순종(오늘 묵상 「순종할 것」을 한 것) · 💡 깨달음(그날 주신 통찰).
-- 표는 안 바꾸고 rpg_spirit_add가 받는 종류만 늘린다(영성 기록 표 spirit_records, body 칸). 통독·기도처럼 영성 기록 사이트 화면에는 아직 안 보인다.
create or replace function rpg_spirit_add(p_token text, p_kind text, p_text text, p_target text default null, p_learned text default null)
returns json language plpgsql security definer set search_path = public, extensions as $$
declare w work_members := rpg_member(p_token); kst date := (now() at time zone 'Asia/Seoul')::date; t text := btrim(coalesce(p_text,'')); v uuid;
begin
  if p_kind not in ('감사','칭찬','SOC','순종','깨달음') then return json_build_object('error','종류가 이상해요'); end if;
  if t='' then return json_build_object('error','적을 것을 넣어 주세요'); end if;
  if p_kind='SOC' then
    insert into spirit_records(title,kind,record_date,written_by,owner_id,status,situation,learned)
    values (kst||' SOC '||left(t,30), 'SOC', kst, w.name, w.owner_id, '작성중', t, coalesce(btrim(p_learned),'')) returning id into v;
  else
    insert into spirit_records(title,kind,record_date,written_by,owner_id,status,body,praise_target)
    values (kst||' '||p_kind||' '||left(t,30), p_kind, kst, w.name, w.owner_id, '작성중', t,
            case when p_kind='칭찬' then nullif(btrim(coalesce(p_target,'')),'') end) returning id into v;
  end if;
  return json_build_object('ok',true,'id',v);
end $$;
