-- Договоры переезжают во внешнюю учётную систему страховой (decisions.md, решение 19).
-- В обращении остаётся снимок договора на момент регистрации, а contract_id становится
-- ссылкой на договор во внешней системе, а не внешним ключом.

alter table appeal
    add column contract_policy_number  varchar(80),
    add column contract_product_code   varchar(20),
    add column contract_product_name   varchar(100),
    add column contract_insured_object varchar(500),
    add column contract_valid_from     date,
    add column contract_valid_to       date,
    add column contract_terminated_on  date,
    add column contract_status         varchar(20);

-- уже зарегистрированные обращения получают снимок из таблицы договоров
update appeal a
set contract_policy_number  = c.policy_number,
    contract_product_code   = case c.insurance_type
                                  when 'КАСКО' then 'CASCO'
                                  when 'ОСАГО' then 'OSAGO'
                                  when 'Ипотечное страхование' then 'MORTGAGE'
                                  when 'Страхование жизни' then 'LIFE'
                                  else 'PROPERTY'
                              end,
    contract_product_name   = c.insurance_type,
    contract_insured_object = c.insured_object,
    contract_valid_from     = c.valid_from,
    contract_valid_to       = c.valid_to,
    contract_status         = c.status
from insurance_contract c
where c.id = a.contract_id;

alter table appeal
    alter column contract_id             set not null,
    alter column contract_policy_number  set not null,
    alter column contract_product_code   set not null,
    alter column contract_product_name   set not null,
    alter column contract_insured_object set not null,
    alter column contract_valid_from     set not null,
    alter column contract_valid_to       set not null,
    alter column contract_status         set not null,
    drop constraint appeal_contract_id_fkey;

drop table insurance_contract;
