-- Каналы поступления (решение 38): обращение подаёт клиент в личном кабинете или оператор
-- контакт-центра со слов клиента, по телефону или в офисе. registered_by: кто из операторов
-- зарегистрировал обращение.
alter table appeal
    add column channel varchar(20) not null default 'CABINET' check (channel in ('CABINET', 'PHONE', 'OFFICE')),
    add column registered_by varchar(160);

-- канал нужен и аналитике: новая колонка добавляется в конец представления
create or replace view appeal_facts as
select a.id,
       a.demo,
       -- договор ещё не привязан (решение 29): вид страхования, который выбрал клиент
       coalesce(a.contract_product_code, a.requested_product_code) as product,
       a.category,
       a.subcategory,
       coalesce(a.final_category, a.category) as final_category,
       coalesce(a.final_subcategory, a.subcategory) as final_subcategory,
       a.final_category is not null
           and (a.final_category, a.final_subcategory) <> (a.category, a.subcategory) as type_corrected,
       a.status,
       a.outcome,
       d.code as department,
       (a.created_at at time zone 'Europe/Moscow')::date as registered_on,
       (a.resolved_at at time zone 'Europe/Moscow')::date as resolved_on,
       a.legal_deadline,
       a.resolved_at is not null
           and (a.resolved_at at time zone 'Europe/Moscow')::date <= a.legal_deadline as resolved_on_time,
       a.resolved_at is null and a.status not in ('RESOLVED', 'CLOSED')
           and a.legal_deadline < (now() at time zone 'Europe/Moscow')::date as overdue_open,
       extract(epoch from a.resolved_at - a.created_at) / 86400.0 as resolution_days,
       a.deadline_extended_days > 0 as extended,
       exists (select 1 from transfer_request t where t.appeal_id = a.id and t.status = 'APPROVED') as transferred,
       exists (select 1 from appeal_history h
               where h.appeal_id = a.id and h.action = 'CLASSIFICATION_REQUIRED') as manually_classified,
       exists (select 1 from appeal_history h
               where h.appeal_id = a.id and h.action = 'CONTRACT_PENDING') as accepted_without_contract,
       -- повторное: тот же клиент, договор и категория за 30 дней до этого обращения
       exists (select 1 from appeal p
               where p.counterparty_id = a.counterparty_id and p.id <> a.id
                 and p.contract_id = a.contract_id and p.category = a.category
                 and p.created_at < a.created_at
                 and p.created_at > a.created_at - interval '30 days') as repeated,
       a.channel
from appeal a
left join department d on d.id = a.department_id;
