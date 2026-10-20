-- Исполнитель обращения: кроме имени — идентификатор сотрудника в IdP, чтобы «мои обращения»
-- не зависели от совпадения имён (решение 32).
alter table appeal
    add column assigned_subject varchar(100),
    add column assigned_at timestamp with time zone;

create index idx_appeal_assigned_subject on appeal(assigned_subject);

-- Кого из сотрудников можно назначить исполнителем. Это не справочник: данные о сотрудниках
-- по-прежнему приходят только из токенов, здесь — последнее, что IdP сообщил о каждом при входе.
create table staff_member (
    subject varchar(100) primary key,
    full_name varchar(160) not null,
    department_code varchar(30) not null,
    supervisor boolean not null,
    last_seen_at timestamp with time zone not null
);

create index idx_staff_member_department on staff_member(department_code);
