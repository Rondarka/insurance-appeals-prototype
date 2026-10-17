-- Файл приходит не только с обращением, но и с сообщением в переписке: клиент досылает
-- запрошенный документ, сотрудник прикладывает расчёт или письмо. У файлов из формы
-- обращения сообщения нет.
alter table appeal_attachment
    add column message_id bigint references appeal_message(id) on delete cascade;

create index idx_attachment_message on appeal_attachment(message_id);
