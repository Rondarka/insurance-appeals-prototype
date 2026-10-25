package ru.mtuci.appeals.domain;

/**
 * Кем клиент приходится договору (решение 43). Договор заключает страхователь: только он
 * меняет условия, расторгает договор и получает назад премию. Застрахованное лицо и
 * выгодоприобретатель сообщают о страховом случае и задают вопросы.
 */
public enum ContractRole {
    POLICYHOLDER("страхователь"),
    INSURED("застрахованное лицо"),
    BENEFICIARY("выгодоприобретатель");

    private final String label;

    ContractRole(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** Роль из ответа учётной системы; незнакомая роль не ломает разбор договора. */
    public static ContractRole of(String code) {
        for (ContractRole role : values()) {
            if (role.name().equals(code)) {
                return role;
            }
        }
        return null;
    }
}
