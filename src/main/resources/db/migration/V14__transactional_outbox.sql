-- Transactional outbox (решение 3): событие записывается в той же транзакции, что и
-- изменение обращения, а в RabbitMQ его доставляет отдельный процесс. Если брокер недоступен
-- или приложение упало сразу после коммита, событие не теряется — оно ждёт здесь.
create table outbox_event (
    -- идентификатор события: по нему потребители отсекают повторную доставку (решение 4)
    id uuid primary key,
    appeal_id uuid,
    event_type varchar(60) not null,
    routing_key varchar(200) not null,
    details jsonb not null,
    occurred_at timestamp with time zone not null,
    published_at timestamp with time zone,
    attempts integer not null default 0,
    last_error varchar(500)
);

-- ретранслятор берёт неотправленные по порядку, чистка — отправленные по возрасту
create index idx_outbox_unpublished on outbox_event(occurred_at) where published_at is null;
create index idx_outbox_published on outbox_event(published_at) where published_at is not null;
