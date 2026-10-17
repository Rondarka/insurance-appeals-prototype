package ru.mtuci.appeals.domain;

/** Как считается нормативный срок — выписка способов из актов: docs/normative-deadlines.md. */
public enum DeadlineCounting {
    /** Рабочие дни по производственному календарю — 4015-1 ст. 6.2, 123-ФЗ ст. 16 ч. 2 п. 1. */
    WORKING_DAYS,
    /** Календарные дни — 123-ФЗ ст. 16 ч. 2 п. 2. */
    CALENDAR_DAYS,
    /** Календарные дни, за исключением нерабочих праздничных — 40-ФЗ ст. 12 п. 21. */
    CALENDAR_EXCEPT_HOLIDAYS
}
