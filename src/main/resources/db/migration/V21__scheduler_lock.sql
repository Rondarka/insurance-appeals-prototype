-- Несколько экземпляров подсистемы (решение 36): фоновую задачу — доставку событий из outbox,
-- эскалацию, привязку договоров — в один момент выполняет один экземпляр. Таблица ShedLock.
create table shedlock (
    name varchar(64) primary key,
    lock_until timestamp not null,
    locked_at timestamp not null,
    locked_by varchar(255) not null
);
