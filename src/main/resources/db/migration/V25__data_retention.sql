-- Сроки хранения персональных данных (решение 41): через заданное число лет после ответа
-- по существу персональные данные в обращении стираются, а тип, продукт, отдел, даты и итог
-- остаются для аналитики. Срок задаёт администратор, изменение попадает в журнал настроек.
create table data_retention (
    id integer primary key check (id = 1),
    years integer not null check (years between 1 and 75)
);
insert into data_retention (id, years) values (1, 5);

alter table appeal add column anonymized_at timestamp with time zone;

-- после обезличивания связь с договором разорвана намеренно
alter table appeal
    drop constraint chk_appeal_contract_linked,
    add constraint chk_appeal_contract_linked
        check (contract_link <> 'LINKED' or contract_id is not null or anonymized_at is not null);

create index idx_appeal_retention on appeal(resolved_at) where anonymized_at is null and resolved_at is not null;
