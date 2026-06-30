create table client (
    id uuid primary key,
    full_name varchar(160) not null,
    email varchar(254) not null unique,
    phone varchar(40) not null
);

create table insurance_contract (
    id uuid primary key,
    client_id uuid not null references client(id),
    policy_number varchar(80) not null unique,
    insurance_type varchar(100) not null,
    insured_object varchar(500) not null,
    valid_from date not null,
    valid_to date not null,
    status varchar(30) not null
);

create index idx_contract_client on insurance_contract(client_id);

create table employee (
    id uuid primary key,
    full_name varchar(160) not null,
    role varchar(30) not null,
    department_id bigint not null references department(id)
);

create index idx_employee_department on employee(department_id);

alter table appeal
    add column client_id uuid references client(id),
    add column contract_id uuid references insurance_contract(id);

create index idx_appeal_client on appeal(client_id);
create index idx_appeal_contract on appeal(contract_id);

create table transfer_request (
    id uuid primary key,
    appeal_id uuid not null references appeal(id) on delete cascade,
    source_department_id bigint not null references department(id),
    target_department_id bigint not null references department(id),
    requested_by_id uuid not null references employee(id),
    reason varchar(500) not null,
    status varchar(30) not null,
    reviewed_by_id uuid references employee(id),
    review_comment varchar(500),
    created_at timestamp with time zone not null,
    reviewed_at timestamp with time zone
);

create index idx_transfer_target_status
    on transfer_request(target_department_id, status, created_at);

create unique index uq_pending_transfer_per_appeal
    on transfer_request(appeal_id)
    where status = 'PENDING';

insert into client (id, full_name, email, phone) values
    ('11111111-1111-1111-1111-111111111111', 'Анна Смирнова', 'anna@example.ru', '+7 999 123-45-67');

insert into insurance_contract (
    id, client_id, policy_number, insurance_type, insured_object, valid_from, valid_to, status
) values
    (
        '21111111-1111-1111-1111-111111111111',
        '11111111-1111-1111-1111-111111111111',
        'ИП-2026-001245',
        'Ипотечное страхование',
        'Квартира: г. Москва, ул. Примерная, д. 10, кв. 25',
        '2026-02-01',
        '2027-01-31',
        'ACTIVE'
    ),
    (
        '22222222-2222-2222-2222-222222222222',
        '11111111-1111-1111-1111-111111111111',
        'КАСКО-2026-004821',
        'КАСКО',
        'Автомобиль: Geely Monjaro, госномер А123АА77',
        '2026-03-15',
        '2027-03-14',
        'ACTIVE'
    );

insert into employee (id, full_name, role, department_id)
select '31111111-1111-1111-1111-111111111111', 'Елена Соколова', 'SPECIALIST', id
from department where code = 'SUPPORT';

insert into employee (id, full_name, role, department_id)
select '32222222-2222-2222-2222-222222222222', 'Андрей Волков', 'SUPERVISOR', id
from department where code = 'SUPPORT';

insert into employee (id, full_name, role, department_id)
select '33333333-3333-3333-3333-333333333333', 'Марина Орлова', 'SUPERVISOR', id
from department where code = 'CLAIMS';

insert into employee (id, full_name, role, department_id)
select '34444444-4444-4444-4444-444444444444', 'Сергей Лебедев', 'SPECIALIST', id
from department where code = 'CLAIMS';

insert into employee (id, full_name, role, department_id)
select '35555555-5555-5555-5555-555555555555', 'Павел Кузнецов', 'SUPERVISOR', id
from department where code = 'MORTGAGE';

insert into employee (id, full_name, role, department_id)
select '36666666-6666-6666-6666-666666666666', 'Ольга Морозова', 'SUPERVISOR', id
from department where code = 'PAYMENTS';

insert into employee (id, full_name, role, department_id)
select '37777777-7777-7777-7777-777777777777', 'Ирина Белова', 'SUPERVISOR', id
from department where code = 'POLICY';

insert into employee (id, full_name, role, department_id)
select '38888888-8888-8888-8888-888888888888', 'Дмитрий Фомин', 'SUPERVISOR', id
from department where code = 'QUALITY';

update appeal
set client_id = '11111111-1111-1111-1111-111111111111',
    contract_id = '21111111-1111-1111-1111-111111111111'
where client_id is null;

alter table appeal
    alter column client_id set not null,
    alter column contract_id set not null;
