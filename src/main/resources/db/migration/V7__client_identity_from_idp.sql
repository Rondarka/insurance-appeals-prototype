-- Клиент — контрагент учётной системы, его личность подтверждает поставщик идентификации
-- страховой (решение 22). Подсистема больше не ведёт свою таблицу клиентов: идентификатор
-- клиента приходит в токене (counterparty_id), имя и почта — оттуда же и сохраняются
-- в обращении на момент регистрации.

alter table appeal drop constraint appeal_client_id_fkey;
alter table appeal rename column client_id to counterparty_id;
alter index idx_appeal_client rename to idx_appeal_counterparty;

drop table client;
