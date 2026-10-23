-- Итоговая классификация (решение 23): при ответе по существу сотрудник подтверждает или
-- уточняет тип обращения. Показатели строятся по итоговому типу, а расхождение с исходным —
-- сигнал, что клиенты не понимают категории формы.
alter table appeal
    add column final_category varchar(50),
    add column final_subcategory varchar(60),
    add constraint fk_appeal_final_type
        foreign key (final_category, final_subcategory) references appeal_type(category, code);

-- уже решённые обращения: итоговый тип — исходный
update appeal set final_category = category, final_subcategory = subcategory
where outcome is not null
  and exists (select 1 from appeal_type t where t.category = appeal.category and t.code = appeal.subcategory);

-- Факты для аналитики (решение 23): одна строка на обращение, все признаки, по которым
-- принимаются решения. Даты — по часовому поясу страховой.
create view appeal_facts as
select a.id,
       a.demo,
       -- договор ещё не привязан (решение 29) — вид страхования, который выбрал клиент
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
                 and p.created_at > a.created_at - interval '30 days') as repeated
from appeal a
left join department d on d.id = a.department_id;
