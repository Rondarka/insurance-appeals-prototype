-- Эскалация по сроку закона (решение 14): уровень поднимает планировщик, каждый уровень —
-- один раз, поэтому событие и запись в журнале не повторяются.
alter table appeal add column escalation_level varchar(20) not null default 'NONE'
    check (escalation_level in ('NONE', 'AT_RISK', 'BREACHED'));
alter table appeal add column escalated_at timestamp with time zone;

-- планировщик смотрит только нерешённые обращения с близким сроком
create index idx_appeal_open_legal_deadline on appeal(legal_deadline)
    where status not in ('RESOLVED', 'CLOSED');
