-- Сотрудники — из поставщика идентификации, а не из справочника подсистемы (решение 22).
-- В передаче хранится, кто действовал: идентификатор из токена и имя на момент действия —
-- как имя клиента в обращении. Справочник сотрудников больше не нужен.
alter table transfer_request
    add column requested_by_subject varchar(100),
    add column requested_by_name varchar(160),
    add column reviewed_by_subject varchar(100),
    add column reviewed_by_name varchar(160);

update transfer_request t
set requested_by_subject = e.id::text,
    requested_by_name = e.full_name
from employee e
where e.id = t.requested_by_id;

update transfer_request t
set reviewed_by_subject = e.id::text,
    reviewed_by_name = e.full_name
from employee e
where e.id = t.reviewed_by_id;

alter table transfer_request
    alter column requested_by_subject set not null,
    alter column requested_by_name set not null,
    drop column requested_by_id,
    drop column reviewed_by_id;

drop table employee;
