-- Демонстрационные обращения (решение 33): их создаёт генератор для стенда и скриншотов.
-- Отметка нужна, чтобы подписывать такие данные как тестовые и удалять их одним запросом.
alter table appeal add column demo boolean not null default false;

create index idx_appeal_demo on appeal(demo) where demo;
