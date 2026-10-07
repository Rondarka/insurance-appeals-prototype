-- Схема формы обращения — справочник подсистемы (решение 24): какие типы обращений
-- допустимы для продукта договора и какие поля у каждого типа. Интерфейс строит форму
-- по схеме, сервер проверяет обращение по ней же.

create table appeal_category (
    code varchar(50) primary key,
    label varchar(160) not null,
    sort_order integer not null
);

create table appeal_type (
    category varchar(50) not null references appeal_category(code),
    code varchar(60) not null,
    label varchar(160) not null,
    sort_order integer not null,
    primary key (category, code)
);

-- contract_attribute: значение подставляет сервер из договора, клиент его не вводит.
-- Допустимые атрибуты заданы контрактом интеграции с учётной системой.
create table form_field (
    code varchar(60) primary key,
    label varchar(160) not null,
    input_type varchar(20) not null check (input_type in ('TEXT', 'DATE', 'NUMBER')),
    hint varchar(200),
    contract_attribute varchar(40) check (contract_attribute in ('policyNumber', 'insuredObject'))
);

create table appeal_type_field (
    category varchar(50) not null,
    type_code varchar(60) not null,
    field_code varchar(60) not null references form_field(code),
    required boolean not null default true,
    sort_order integer not null,
    primary key (category, type_code, field_code),
    foreign key (category, type_code) references appeal_type(category, code)
);

-- product_code — код продукта из учётной системы договоров (docs/contracts-api.yaml)
create table appeal_type_product (
    category varchar(50) not null,
    type_code varchar(60) not null,
    product_code varchar(30) not null,
    primary key (category, type_code, product_code),
    foreign key (category, type_code) references appeal_type(category, code)
);

insert into appeal_category (code, label, sort_order) values
    ('MORTGAGE', 'Ипотечное страхование', 1),
    ('CLAIM', 'Страховой случай', 2),
    ('POLICY', 'Вопрос по полису', 3),
    ('PAYMENT', 'Оплата или возврат', 4),
    ('COMPLAINT', 'Жалоба', 5),
    ('TECHNICAL', 'Техническая проблема', 6),
    ('OTHER', 'Другое', 7);

insert into appeal_type (category, code, label, sort_order) values
    ('MORTGAGE', 'DOCUMENTS', 'Получение или загрузка документов', 1),
    ('MORTGAGE', 'RENEWAL', 'Продление договора', 2),
    ('MORTGAGE', 'PAYMENT', 'Оплата ипотечного полиса', 3),
    ('MORTGAGE', 'CLAIM_EVENT', 'Страховой случай по объекту', 4),
    ('MORTGAGE', 'DATA_CHANGE', 'Изменение данных', 5),
    ('CLAIM', 'AUTO', 'Автомобиль', 1),
    ('CLAIM', 'PROPERTY', 'Имущество', 2),
    ('CLAIM', 'HEALTH', 'Жизнь и здоровье', 3),
    ('POLICY', 'COPY', 'Получить копию полиса', 1),
    ('POLICY', 'DATA_CHANGE', 'Изменить данные в полисе', 2),
    ('POLICY', 'TERMINATION', 'Расторгнуть договор', 3),
    ('PAYMENT', 'PAYMENT_STATUS', 'Проверить статус платежа', 1),
    ('PAYMENT', 'REFUND', 'Возврат денежных средств', 2),
    ('PAYMENT', 'INCORRECT_AMOUNT', 'Неверная сумма', 3),
    ('COMPLAINT', 'SERVICE_QUALITY', 'Качество обслуживания', 1),
    ('COMPLAINT', 'DEADLINE', 'Нарушение срока ответа', 2),
    ('COMPLAINT', 'EMPLOYEE', 'Действия сотрудника', 3),
    ('TECHNICAL', 'LOGIN', 'Не удаётся войти', 1),
    ('TECHNICAL', 'PERSONAL_ACCOUNT', 'Ошибка личного кабинета', 2),
    ('TECHNICAL', 'DOCUMENT_UPLOAD', 'Не загружается документ', 3),
    ('OTHER', 'GENERAL', 'Другой вопрос', 1);

insert into form_field (code, label, input_type, hint, contract_attribute) values
    ('policyNumber', 'Номер полиса', 'TEXT', null, 'policyNumber'),
    ('objectAddress', 'Адрес объекта страхования', 'TEXT', null, 'insuredObject'),
    ('paymentDate', 'Дата платежа', 'DATE', null, null),
    ('paymentAmount', 'Сумма платежа, ₽', 'NUMBER', 'Например, 15000', null),
    ('incidentDate', 'Дата происшествия', 'DATE', null, null),
    ('incidentPlace', 'Место происшествия', 'TEXT', 'Адрес или описание места', null),
    ('relatedAppealNumber', 'Номер связанного обращения', 'TEXT', 'Например, APP-20260612-ABC123', null),
    ('desiredOutcome', 'Какой результат вы ожидаете', 'TEXT', 'Опишите желаемый результат', null),
    ('systemSection', 'Раздел системы', 'TEXT', 'Личный кабинет, оплата или документы', null),
    ('device', 'Устройство и браузер', 'TEXT', 'Например, Windows 11, Chrome', null),
    ('errorText', 'Текст ошибки', 'TEXT', 'Скопируйте сообщение об ошибке', null);

insert into appeal_type_field (category, type_code, field_code, sort_order) values
    ('MORTGAGE', 'DOCUMENTS', 'policyNumber', 1),
    ('MORTGAGE', 'DOCUMENTS', 'objectAddress', 2),
    ('MORTGAGE', 'RENEWAL', 'policyNumber', 1),
    ('MORTGAGE', 'RENEWAL', 'objectAddress', 2),
    ('MORTGAGE', 'PAYMENT', 'policyNumber', 1),
    ('MORTGAGE', 'PAYMENT', 'paymentDate', 2),
    ('MORTGAGE', 'PAYMENT', 'paymentAmount', 3),
    ('MORTGAGE', 'CLAIM_EVENT', 'policyNumber', 1),
    ('MORTGAGE', 'CLAIM_EVENT', 'incidentDate', 2),
    ('MORTGAGE', 'CLAIM_EVENT', 'objectAddress', 3),
    ('MORTGAGE', 'DATA_CHANGE', 'policyNumber', 1),
    ('MORTGAGE', 'DATA_CHANGE', 'objectAddress', 2),
    ('CLAIM', 'AUTO', 'policyNumber', 1),
    ('CLAIM', 'AUTO', 'incidentDate', 2),
    ('CLAIM', 'AUTO', 'incidentPlace', 3),
    ('CLAIM', 'PROPERTY', 'policyNumber', 1),
    ('CLAIM', 'PROPERTY', 'incidentDate', 2),
    ('CLAIM', 'PROPERTY', 'incidentPlace', 3),
    ('CLAIM', 'HEALTH', 'policyNumber', 1),
    ('CLAIM', 'HEALTH', 'incidentDate', 2),
    ('CLAIM', 'HEALTH', 'incidentPlace', 3),
    ('POLICY', 'COPY', 'policyNumber', 1),
    ('POLICY', 'DATA_CHANGE', 'policyNumber', 1),
    ('POLICY', 'TERMINATION', 'policyNumber', 1),
    ('PAYMENT', 'PAYMENT_STATUS', 'policyNumber', 1),
    ('PAYMENT', 'PAYMENT_STATUS', 'paymentDate', 2),
    ('PAYMENT', 'PAYMENT_STATUS', 'paymentAmount', 3),
    ('PAYMENT', 'REFUND', 'policyNumber', 1),
    ('PAYMENT', 'REFUND', 'paymentDate', 2),
    ('PAYMENT', 'REFUND', 'paymentAmount', 3),
    ('PAYMENT', 'INCORRECT_AMOUNT', 'policyNumber', 1),
    ('PAYMENT', 'INCORRECT_AMOUNT', 'paymentDate', 2),
    ('PAYMENT', 'INCORRECT_AMOUNT', 'paymentAmount', 3),
    ('COMPLAINT', 'SERVICE_QUALITY', 'desiredOutcome', 1),
    ('COMPLAINT', 'DEADLINE', 'relatedAppealNumber', 1),
    ('COMPLAINT', 'DEADLINE', 'desiredOutcome', 2),
    ('COMPLAINT', 'EMPLOYEE', 'desiredOutcome', 1),
    ('TECHNICAL', 'LOGIN', 'systemSection', 1),
    ('TECHNICAL', 'LOGIN', 'device', 2),
    ('TECHNICAL', 'LOGIN', 'errorText', 3),
    ('TECHNICAL', 'PERSONAL_ACCOUNT', 'systemSection', 1),
    ('TECHNICAL', 'PERSONAL_ACCOUNT', 'device', 2),
    ('TECHNICAL', 'PERSONAL_ACCOUNT', 'errorText', 3),
    ('TECHNICAL', 'DOCUMENT_UPLOAD', 'systemSection', 1),
    ('TECHNICAL', 'DOCUMENT_UPLOAD', 'device', 2),
    ('TECHNICAL', 'DOCUMENT_UPLOAD', 'errorText', 3);

-- Ипотека: свои типы вместо общих вопросов по полису.
insert into appeal_type_product (category, type_code, product_code)
select category, code, 'MORTGAGE' from appeal_type where category = 'MORTGAGE';

-- Страховые случаи: тип события соответствует продукту.
insert into appeal_type_product (category, type_code, product_code) values
    ('CLAIM', 'AUTO', 'CASCO'),
    ('CLAIM', 'AUTO', 'OSAGO'),
    ('CLAIM', 'PROPERTY', 'PROPERTY'),
    ('CLAIM', 'HEALTH', 'LIFE');

insert into appeal_type_product (category, type_code, product_code)
select t.category, t.code, p.product_code
from appeal_type t
cross join (values ('CASCO'), ('OSAGO'), ('PROPERTY'), ('LIFE')) as p(product_code)
where t.category = 'POLICY';

-- Оплата, жалобы, технические и прочие вопросы — по любому договору.
insert into appeal_type_product (category, type_code, product_code)
select t.category, t.code, p.product_code
from appeal_type t
cross join (values ('CASCO'), ('OSAGO'), ('MORTGAGE'), ('PROPERTY'), ('LIFE')) as p(product_code)
where t.category in ('PAYMENT', 'COMPLAINT', 'TECHNICAL', 'OTHER');

-- Категория перестала быть перечислением в коде: целостность держит справочник.
alter table appeal
    add constraint fk_appeal_category foreign key (category) references appeal_category(code);

alter table routing_rule
    add constraint fk_routing_rule_category foreign key (category) references appeal_category(code);
