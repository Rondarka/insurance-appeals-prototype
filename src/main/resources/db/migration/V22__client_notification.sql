-- Уведомления клиенту (решение 37): когда сообщить клиенту, решает подсистема, как доставить,
-- решает сервис коммуникаций страховой. Таблица помнит, по какому событию уведомление уже
-- запрошено: повторная доставка события не даст клиенту второе сообщение.
create table notification_request (
    source_event_id uuid primary key,
    appeal_id uuid not null references appeal(id) on delete cascade,
    template varchar(40) not null,
    requested_at timestamp with time zone not null
);

create index idx_notification_request_appeal on notification_request(appeal_id);
