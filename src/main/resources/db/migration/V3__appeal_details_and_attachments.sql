alter table appeal
    add column subcategory varchar(60) not null default 'GENERAL',
    add column details_json text not null default '{}';

alter table routing_rule
    drop constraint routing_rule_category_key;

alter table routing_rule
    add column subcategory varchar(60) not null default 'GENERAL';

alter table routing_rule
    add constraint uq_routing_rule_category_subcategory unique (category, subcategory);

insert into routing_rule (category, subcategory, department_id, priority, sla_hours)
select 'MORTGAGE', 'DOCUMENTS', id, 'NORMAL', 24
from department where code = 'MORTGAGE';

insert into routing_rule (category, subcategory, department_id, priority, sla_hours)
select 'MORTGAGE', 'RENEWAL', id, 'NORMAL', 24
from department where code = 'MORTGAGE';

insert into routing_rule (category, subcategory, department_id, priority, sla_hours)
select 'MORTGAGE', 'PAYMENT', id, 'NORMAL', 12
from department where code = 'PAYMENTS';

insert into routing_rule (category, subcategory, department_id, priority, sla_hours)
select 'MORTGAGE', 'CLAIM_EVENT', id, 'HIGH', 8
from department where code = 'CLAIMS';

insert into routing_rule (category, subcategory, department_id, priority, sla_hours)
select 'MORTGAGE', 'DATA_CHANGE', id, 'NORMAL', 24
from department where code = 'POLICY';

create table appeal_attachment (
    id uuid primary key,
    appeal_id uuid not null references appeal(id) on delete cascade,
    original_name varchar(255) not null,
    storage_name varchar(255) not null unique,
    content_type varchar(120) not null,
    size_bytes bigint not null check (size_bytes > 0),
    uploaded_at timestamp with time zone not null
);

create index idx_attachment_appeal on appeal_attachment(appeal_id, uploaded_at);
