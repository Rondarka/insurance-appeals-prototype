-- Журнал доступа (решение 40): кто из сотрудников открывал обращение и его файлы. В обращении
-- персональные данные клиента, а нередко и сведения о здоровье; при подозрении на утечку
-- служба безопасности должна видеть, у кого был доступ. Клиент, открывающий своё обращение,
-- сюда не пишется.
create table appeal_access_log (
    id bigserial primary key,
    appeal_id uuid not null references appeal(id) on delete cascade,
    action varchar(20) not null check (action in ('VIEWED', 'FILE_OPENED')),
    attachment_id uuid,
    viewer_subject varchar(100) not null,
    viewer_name varchar(160) not null,
    viewer_role varchar(40) not null,
    viewer_department varchar(50),
    accessed_at timestamp with time zone not null
);

create index idx_access_log_appeal on appeal_access_log(appeal_id, accessed_at desc);
