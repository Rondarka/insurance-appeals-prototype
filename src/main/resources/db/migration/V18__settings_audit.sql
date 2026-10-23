-- Журнал изменений настроек (решение 34): правила маршрутизации и нормативы сроков меняет
-- администратор, и для регулируемой отрасли важно знать, кто, когда, что и почему изменил.
create table settings_audit (
    id bigserial primary key,
    changed_at timestamp with time zone not null,
    actor_subject varchar(100) not null,
    actor_name varchar(160) not null,
    -- ROUTING_RULE или DEADLINE_NORM
    entity varchar(40) not null,
    -- что именно: «CLAIM/GENERAL», «GENERAL с 01.01.2027»
    entity_key varchar(160) not null,
    action varchar(20) not null check (action in ('CREATED', 'UPDATED', 'DELETED')),
    before_value jsonb,
    after_value jsonb,
    reason varchar(500) not null
);

create index idx_settings_audit_changed on settings_audit(changed_at desc);
