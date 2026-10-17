-- Учётная система договоров недоступна — обращение всё равно принимается (решение 29).
-- Клиент выбирает вид страхования, договор подсистема подбирает сама, когда система вернётся.

-- Виды страхования — справочник подсистемы: коды те же, что у продуктов учётной системы.
create table insurance_product (
    code varchar(30) primary key,
    label varchar(100) not null,
    sort_order integer not null
);

insert into insurance_product (code, label, sort_order) values
    ('CASCO', 'КАСКО', 1),
    ('OSAGO', 'ОСАГО', 2),
    ('MORTGAGE', 'Ипотечное страхование', 3),
    ('PROPERTY', 'Страхование имущества', 4),
    ('LIFE', 'Страхование жизни', 5);

-- снимок договора пуст, пока договор не привязан
alter table appeal
    alter column contract_id drop not null,
    alter column contract_policy_number drop not null,
    alter column contract_product_code drop not null,
    alter column contract_product_name drop not null,
    alter column contract_insured_object drop not null,
    alter column contract_valid_from drop not null,
    alter column contract_valid_to drop not null,
    alter column contract_status drop not null;

alter table appeal
    add column contract_link varchar(20) not null default 'LINKED'
        check (contract_link in ('LINKED', 'PENDING', 'NEEDS_CHOICE', 'NOT_FOUND')),
    -- что указал клиент: договор из списка (если список успел загрузиться), вид и номер
    add column requested_contract_id uuid,
    add column requested_product_code varchar(30) references insurance_product(code),
    add column requested_policy_number varchar(80),
    -- фоновые попытки: паузы растут, чтобы не добивать лежащую систему
    add column contract_link_attempts integer not null default 0,
    add column contract_link_next_at timestamp with time zone,
    add constraint chk_appeal_contract_linked
        check (contract_link <> 'LINKED' or contract_id is not null);

create index idx_appeal_contract_pending on appeal(contract_link_next_at) where contract_link = 'PENDING';
