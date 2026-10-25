-- Из чего складывается задержка маршрутизации в нагрузочном тесте (docs/experiments.md).
--   docker compose exec -T postgres psql -U appeals -d insurance_appeals -f - < load/routing-lag.sql
-- outbox: от записи события до подтверждения брокера; очередь: от отправки до начала
-- маршрутизации потребителем; всего: от регистрации до назначения отдела. Секунды.
with tested as (
    select id, created_at from appeal where subject like 'Нагрузочный тест%'
), sent as (
    select e.appeal_id, e.occurred_at, e.published_at
    from outbox_event e join tested t on t.id = e.appeal_id
    where e.event_type = 'APPEAL_CREATED'
), routed as (
    select h.appeal_id, min(h.created_at) as routed_at
    from appeal_history h join tested t on t.id = h.appeal_id
    where h.action = 'ROUTED'
    group by h.appeal_id
)
select count(*) as appeals,
       round(percentile_cont(0.5) within group (order by extract(epoch from s.published_at - s.occurred_at))::numeric, 2) as outbox_p50,
       round(percentile_cont(0.95) within group (order by extract(epoch from s.published_at - s.occurred_at))::numeric, 2) as outbox_p95,
       round(percentile_cont(0.5) within group (order by extract(epoch from r.routed_at - s.published_at))::numeric, 2) as queue_p50,
       round(percentile_cont(0.95) within group (order by extract(epoch from r.routed_at - s.published_at))::numeric, 2) as queue_p95,
       round(percentile_cont(0.5) within group (order by extract(epoch from r.routed_at - t.created_at))::numeric, 2) as total_p50,
       round(percentile_cont(0.95) within group (order by extract(epoch from r.routed_at - t.created_at))::numeric, 2) as total_p95
from tested t
join sent s on s.appeal_id = t.id
join routed r on r.appeal_id = t.id;
