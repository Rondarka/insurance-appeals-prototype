-- Нормативные сроки и производственный календарь (решения 13, 20, 21).
-- Значения сроков — из текстов актов, см. docs/normative-deadlines.md.

-- Только отступления от обычной недели (пн–пт рабочие, сб–вс выходные). Подсистема
-- календарь не ведёт: строки загружаются из файлов утверждённого календаря при старте.
create table production_calendar (
    day date primary key,
    kind varchar(10) not null check (kind in ('WORKDAY', 'WEEKEND', 'HOLIDAY'))
);

-- Норматив — версия срока: изменение закона — новая строка с новой датой начала,
-- обращения, зарегистрированные раньше, сохраняют прежний срок.
create table deadline_norm (
    id bigserial primary key,
    code varchar(60) not null,
    title varchar(200) not null,
    term_days integer not null check (term_days > 0),
    counting varchar(30) not null
        check (counting in ('WORKING_DAYS', 'CALENDAR_DAYS', 'CALENDAR_EXCEPT_HOLIDAYS')),
    max_extension_days integer not null default 0 check (max_extension_days >= 0),
    -- условие применения, которое проверяет подсистема по данным обращения
    condition varchar(40) check (condition in ('VIOLATION_WITHIN_180_DAYS')),
    legal_basis varchar(300) not null,
    valid_from date not null,
    valid_to date,
    unique (code, valid_from)
);

-- Какой норматив к какому типу обращения. product_code пустой — для любого продукта.
-- Несколько строк на тип перебираются по priority: применяется первая с выполненным
-- условием. Тип без строк получает общий норматив GENERAL.
create table appeal_type_norm (
    category varchar(50) not null,
    type_code varchar(60) not null,
    product_code varchar(30),
    norm_code varchar(60) not null,
    priority integer not null default 1,
    foreign key (category, type_code) references appeal_type(category, code)
);

-- valid_from: дата вступления нормы в силу; для норм старше подсистемы — 01.01.2024,
-- начало ведения справочника
insert into deadline_norm (code, title, term_days, counting, max_extension_days, condition, legal_basis, valid_from) values
    ('GENERAL', 'Ответ на обращение', 15, 'WORKING_DAYS', 10, null,
     'Закон РФ № 4015-1, ст. 6.2 п. 5', '2024-07-01'),
    ('PRETENSION_FAST', 'Претензия по стандартной форме, нарушение не старше 180 дней', 15, 'WORKING_DAYS', 0,
     'VIOLATION_WITHIN_180_DAYS', '123-ФЗ, ст. 16 ч. 2 п. 1', '2023-12-11'),
    ('PRETENSION', 'Претензия', 30, 'CALENDAR_DAYS', 0, null,
     '123-ФЗ, ст. 16 ч. 2 п. 2', '2023-12-11'),
    ('OSAGO_CLAIM', 'Заявление о страховом возмещении по ОСАГО', 20, 'CALENDAR_EXCEPT_HOLIDAYS', 0, null,
     '40-ФЗ, ст. 12 п. 21', '2024-01-01'),
    -- статья 6.2 не распространяется на выплаты, изменение условий и отказ от договора:
    -- срок задают правила страхования компании, в стенде значение демонстрационное
    ('INSURANCE_RULES', 'По правилам страхования (демонстрационное значение)', 20, 'WORKING_DAYS', 0, null,
     'Правила страхования компании; Закон РФ № 4015-1, ст. 6.2 п. 1 абз. 2', '2024-01-01');

-- Претензия — отдельный тип обращения со сроком по 123-ФЗ
insert into appeal_type (category, code, label, sort_order) values
    ('COMPLAINT', 'PRETENSION', 'Претензия: не согласен с решением или выплатой', 4);

insert into form_field (code, label, input_type, hint, contract_attribute) values
    ('violationDate', 'Дата нарушения', 'DATE', 'Например, дата отказа в выплате', null),
    ('claimAmount', 'Сумма требования, ₽', 'NUMBER', 'Сколько вы требуете выплатить', null);

insert into appeal_type_field (category, type_code, field_code, sort_order) values
    ('COMPLAINT', 'PRETENSION', 'violationDate', 1),
    ('COMPLAINT', 'PRETENSION', 'claimAmount', 2),
    ('COMPLAINT', 'PRETENSION', 'desiredOutcome', 3);

insert into appeal_type_product (category, type_code, product_code)
select 'COMPLAINT', 'PRETENSION', p.product_code
from (values ('CASCO'), ('OSAGO'), ('MORTGAGE'), ('PROPERTY'), ('LIFE')) as p(product_code);

insert into appeal_type_norm (category, type_code, product_code, norm_code, priority) values
    ('COMPLAINT', 'PRETENSION', null, 'PRETENSION_FAST', 1),
    ('COMPLAINT', 'PRETENSION', null, 'PRETENSION', 2),
    ('CLAIM', 'AUTO', 'OSAGO', 'OSAGO_CLAIM', 1),
    ('CLAIM', 'AUTO', null, 'INSURANCE_RULES', 2),
    ('CLAIM', 'PROPERTY', null, 'INSURANCE_RULES', 1),
    ('CLAIM', 'HEALTH', null, 'INSURANCE_RULES', 1),
    ('MORTGAGE', 'CLAIM_EVENT', null, 'INSURANCE_RULES', 1),
    ('MORTGAGE', 'RENEWAL', null, 'INSURANCE_RULES', 1),
    ('MORTGAGE', 'DATA_CHANGE', null, 'INSURANCE_RULES', 1),
    ('POLICY', 'DATA_CHANGE', null, 'INSURANCE_RULES', 1),
    ('POLICY', 'TERMINATION', null, 'INSURANCE_RULES', 1);

-- В обращении — применённый норматив и срок по нему. Часы из правила маршрутизации
-- остаются целевым сроком отдела (deadline_at), нормативный срок — отдельно.
alter table appeal
    add column norm_id bigint references deadline_norm(id),
    add column legal_deadline date,
    add column deadline_extended_days integer not null default 0;
