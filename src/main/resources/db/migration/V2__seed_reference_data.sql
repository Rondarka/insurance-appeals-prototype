insert into department (code, name, description) values
    ('MORTGAGE', 'Ипотечное страхование', 'Вопросы по договорам ипотечного страхования'),
    ('CLAIMS', 'Урегулирование убытков', 'Обращения по страховым случаям'),
    ('POLICY', 'Сопровождение договоров', 'Изменение данных и вопросы по полисам'),
    ('PAYMENTS', 'Расчёты и возвраты', 'Оплата, возвраты и финансовые вопросы'),
    ('QUALITY', 'Контроль качества', 'Жалобы и претензии клиентов'),
    ('SUPPORT', 'Техническая поддержка', 'Технические проблемы и прочие обращения');

insert into routing_rule (category, department_id, priority, sla_hours)
select 'MORTGAGE', id, 'NORMAL', 24 from department where code = 'MORTGAGE';

insert into routing_rule (category, department_id, priority, sla_hours)
select 'CLAIM', id, 'HIGH', 8 from department where code = 'CLAIMS';

insert into routing_rule (category, department_id, priority, sla_hours)
select 'POLICY', id, 'NORMAL', 24 from department where code = 'POLICY';

insert into routing_rule (category, department_id, priority, sla_hours)
select 'PAYMENT', id, 'NORMAL', 12 from department where code = 'PAYMENTS';

insert into routing_rule (category, department_id, priority, sla_hours)
select 'COMPLAINT', id, 'HIGH', 8 from department where code = 'QUALITY';

insert into routing_rule (category, department_id, priority, sla_hours)
select 'TECHNICAL', id, 'NORMAL', 12 from department where code = 'SUPPORT';

insert into routing_rule (category, department_id, priority, sla_hours)
select 'OTHER', id, 'LOW', 48 from department where code = 'SUPPORT';
