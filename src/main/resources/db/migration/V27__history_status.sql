-- Лента статусов для клиента: каждая запись журнала помнит, в каком статусе осталось
-- обращение после действия. Из этого берутся даты шагов «принято», «в работе», «ответ дан».
alter table appeal_history add column status_after varchar(40);

-- прежние записи: статус восстанавливается по действию и по подписи статуса в тексте записи
update appeal_history set status_after = case
    when action = 'CREATED' then 'PENDING_ROUTING'
    when action in ('ROUTED', 'CLASSIFIED', 'TRANSFERRED', 'TRANSFER_APPROVED') then 'ROUTED'
    when action = 'RESOLVED' then 'RESOLVED'
    when action = 'CLASSIFICATION_REQUIRED' then 'NEEDS_CLASSIFICATION'
    when description like '%→ «В работе»%' or description like 'Клиент ответил%' then 'IN_PROGRESS'
    when description like '%→ «Ждём ответа клиента»%' then 'WAITING_CUSTOMER'
    when description like '%→ «Решено»%' then 'RESOLVED'
    when description like '%→ «Закрыто»%' then 'CLOSED'
    when description like '%→ «Назначено отделу»%' then 'ROUTED'
    -- самые ранние записи писались кодами статусов: «ROUTED -> IN_PROGRESS»
    when description ~ '-> [A-Z_]+$' then substring(description from '-> ([A-Z_]+)$')
end;

-- записи без смены статуса (сообщение, вложение, срок) наследуют статус предыдущей записи
update appeal_history h set status_after = (
    select p.status_after from appeal_history p
    where p.appeal_id = h.appeal_id and p.status_after is not null
      and (p.created_at, p.id) < (h.created_at, h.id)
    order by p.created_at desc, p.id desc
    limit 1)
where h.status_after is null;
