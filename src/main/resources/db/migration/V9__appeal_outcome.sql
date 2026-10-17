-- Обращение решается ответом клиенту по существу с итогом рассмотрения
-- (Закон № 4015-1, ст. 6.2 п. 5–6; для претензии — 123-ФЗ, ст. 16 ч. 2).
alter table appeal
    add column outcome varchar(30)
        check (outcome in ('SATISFIED', 'PARTIALLY_SATISFIED', 'REJECTED', 'EXPLAINED')),
    add column resolved_at timestamp with time zone;
