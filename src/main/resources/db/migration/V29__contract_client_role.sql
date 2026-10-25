-- Роль клиента в договоре (решение 43): страхователь, застрахованное лицо или
-- выгодоприобретатель. Берётся из учётной системы и хранится в снимке договора.
alter table appeal add column contract_client_role varchar(20)
    check (contract_client_role in ('POLICYHOLDER', 'INSURED', 'BENEFICIARY'));

-- до этой версии учётная система отдавала клиенту только договоры, где он страхователь
update appeal set contract_client_role = 'POLICYHOLDER' where contract_id is not null;

-- менять условия, расторгать договор и получать назад премию может только страхователь
alter table appeal_type add column policyholder_only boolean not null default false;

update appeal_type set policyholder_only = true
where (category, code) in (('POLICY', 'TERMINATION'), ('POLICY', 'DATA_CHANGE'),
                           ('MORTGAGE', 'RENEWAL'), ('MORTGAGE', 'DATA_CHANGE'),
                           ('PAYMENT', 'REFUND'));
