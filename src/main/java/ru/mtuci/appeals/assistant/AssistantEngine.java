package ru.mtuci.appeals.assistant;

import ru.mtuci.appeals.domain.AppealOutcome;

import java.util.List;
import java.util.Optional;

/**
 * Точка подключения помощника сотрудника (решение 35): один контракт и для правил, и для
 * локальной модели. Какой движок работает — настройка администратора; модель недоступна —
 * отвечают правила. Подсказка только советует, решает сотрудник.
 */
public interface AssistantEngine {

    String RULES = "RULES";
    String LOCAL_MODEL = "LOCAL_MODEL";

    /** RULES или LOCAL_MODEL: сотрудник видит, откуда подсказка, учёт принятых ведётся раздельно. */
    String source();

    /** Похоже ли обращение на претензию, хотя клиент выбрал другой тип: от типа зависит срок по закону. */
    Optional<TypeGuess> guessType(AppealText appeal);

    /**
     * Черновик ответа по существу. Основа — шаблон, уже заполненный данными обращения и
     * договора; правила возвращают его как есть, модель дописывает по тексту обращения.
     */
    String draft(DraftInput input);

    /** Что написал клиент: тема, текст обращения и его сообщения в переписке. */
    record AppealText(String category, String subcategory, String typeLabel, String subject, String description,
                      List<String> clientMessages) {

        public String all() {
            return subject + "\n" + description + (clientMessages.isEmpty() ? "" : "\n" + String.join("\n", clientMessages));
        }
    }

    /** Предполагаемый тип и цитаты из текста клиента, на которых держится догадка. */
    record TypeGuess(String category, String subcategory, List<String> reasons) {
    }

    /** filledTemplate — шаблон с данными; similarAnswers — как отвечали на похожие, имена клиентов скрыты. */
    record DraftInput(AppealText appeal, AppealOutcome outcome, String filledTemplate, List<String> similarAnswers) {
    }
}
