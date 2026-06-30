create table department (
    id bigserial primary key,
    code varchar(50) not null unique,
    name varchar(160) not null,
    description varchar(500)
);

create table routing_rule (
    id bigserial primary key,
    category varchar(50) not null unique,
    department_id bigint not null references department(id),
    priority varchar(20) not null,
    sla_hours integer not null check (sla_hours > 0)
);

create table appeal (
    id uuid primary key,
    public_number varchar(32) not null unique,
    customer_name varchar(160) not null,
    customer_email varchar(254) not null,
    category varchar(50) not null,
    subject varchar(200) not null,
    description text not null,
    status varchar(40) not null,
    priority varchar(20) not null,
    department_id bigint references department(id),
    assigned_employee varchar(160),
    deadline_at timestamp with time zone,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    version bigint not null default 0
);

create index idx_appeal_department on appeal(department_id);
create index idx_appeal_status on appeal(status);
create index idx_appeal_created_at on appeal(created_at desc);

create table appeal_history (
    id bigserial primary key,
    appeal_id uuid not null references appeal(id) on delete cascade,
    action varchar(60) not null,
    description varchar(1000) not null,
    actor varchar(160) not null,
    created_at timestamp with time zone not null
);

create index idx_appeal_history_appeal on appeal_history(appeal_id, created_at);

create table appeal_message (
    id bigserial primary key,
    appeal_id uuid not null references appeal(id) on delete cascade,
    author_type varchar(30) not null,
    author_name varchar(160) not null,
    body text not null,
    created_at timestamp with time zone not null
);

create index idx_appeal_message_appeal on appeal_message(appeal_id, created_at);

create table event_audit (
    id bigserial primary key,
    event_id uuid not null unique,
    appeal_id uuid,
    event_type varchar(80) not null,
    routing_key varchar(160) not null,
    payload text not null,
    received_at timestamp with time zone not null
);

create index idx_event_audit_received on event_audit(received_at desc);
