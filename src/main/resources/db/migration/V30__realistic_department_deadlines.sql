-- Реалистичные целевые сроки отделов (решение 50). Прежние 8–48 часов были сроком реакции, а
-- не ответа по существу: страховой случай за 8 часов не урегулировать, и почти каждое открытое
-- обращение в очереди выглядело просроченным. Новые сроки в пределах закона (решение 34):
-- не больше 432 ч у страховых случаев и не больше 312 ч у остальных видов.
-- Изменение пишется в журнал настроек, как если бы его сделал администратор.
create temporary table new_sla on commit drop as
select r.id, r.category, r.subcategory, d.code as department, r.priority, r.sla_hours as before_hours,
       case when r.category = 'CLAIM' or r.subcategory = 'CLAIM_EVENT' then 240
            when r.category = 'TECHNICAL' then 72
            else 168 end as after_hours
from routing_rule r join department d on d.id = r.department_id;

insert into settings_audit (changed_at, actor_subject, actor_name, entity, entity_key, action,
                            before_value, after_value, reason)
select now(), 'migration', 'Миграция V30', 'ROUTING_RULE', category || '/' || subcategory, 'UPDATED',
       jsonb_build_object('department', department, 'priority', priority, 'slaHours', before_hours),
       jsonb_build_object('department', department, 'priority', priority, 'slaHours', after_hours),
       'Срок отдела приведён к сроку ответа по существу: урегулирование 10 дней, техническая поддержка 3 дня, остальные отделы 7 дней'
from new_sla where before_hours <> after_hours;

update routing_rule r set sla_hours = n.after_hours from new_sla n where n.id = r.id;
