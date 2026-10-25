package ru.mtuci.appeals.assistant;

import org.junit.jupiter.api.Test;
import ru.mtuci.appeals.assistant.AssistantEngine.AppealText;
import ru.mtuci.appeals.assistant.AssistantEngine.TypeGuess;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** Правила помощника без базы и модели: подсказка «похоже на претензию» и скрытие имён. */
class RulesEngineTest {

    private final RulesEngine rules = new RulesEngine();

    @Test
    void explicitPretensionIsEnough() {
        Optional<TypeGuess> guess = rules.guessType(text("COMPLAINT", "SERVICE_QUALITY",
                "Направляю претензию: ответа по выплате нет уже месяц."));
        assertThat(guess).isPresent();
        assertThat(guess.get().subcategory()).isEqualTo("PRETENSION");
        assertThat(guess.get().reasons().get(0)).contains("претензию");
    }

    @Test
    void twoIndirectSignsMakeAHint() {
        Optional<TypeGuess> guess = rules.guessType(text("PAYMENT", "INCORRECT_AMOUNT",
                "Не согласен с перерасчётом, требую пересчитать премию по договору."));
        assertThat(guess).isPresent();
        // оба признака в одной фразе: одна цитата, а не две пересекающиеся
        assertThat(guess.get().reasons()).singleElement().asString()
                .contains("Не согласен", "требую пересчитать");
    }

    @Test
    void quotesComeFromTheTextNotTheRepeatedSubject() {
        Optional<TypeGuess> guess = rules.guessType(new AppealText("PAYMENT", "INCORRECT_AMOUNT", "Расчёты · Неверная сумма",
                "Не согласен с перерасчётом премии",
                "Не согласен с перерасчётом: к оплате 56 500 ₽ вместо прежней суммы. Требую пересчитать премию.", List.of()));
        assertThat(guess).isPresent();
        assertThat(guess.get().reasons()).noneMatch(reason -> reason.contains("премии Не согласен"));
    }

    @Test
    void oneIndirectSignOrAQuestionIsNot() {
        assertThat(rules.guessType(text("PAYMENT", "INCORRECT_AMOUNT", "Прошу проверить, сумма к оплате 12 000 ₽."))).isEmpty();
        assertThat(rules.guessType(text("OTHER", "GENERAL", "Не согласен с расчётом франшизы, требую пересчитать."))).isEmpty();
        assertThat(rules.guessType(text("COMPLAINT", "PRETENSION", "Это претензия."))).isEmpty();
    }

    @Test
    void rulesDraftIsTheFilledTemplate() {
        assertThat(rules.draft(new AssistantEngine.DraftInput(text("OTHER", "GENERAL", "Вопрос"), null,
                "Здравствуйте, Анна!", List.of()))).isEqualTo("Здравствуйте, Анна!");
    }

    @Test
    void otherClientsNamesAreHiddenInAnyCase() {
        String masked = AssistantService.mask("Анна, ответ направлен Анне Смирновой. Договор аннулирован не будет.",
                "Анна Смирнова");
        assertThat(masked).isEqualTo("[клиент], ответ направлен [клиент] [клиент]. Договор аннулирован не будет.");
    }

    private static AppealText text(String category, String type, String description) {
        return new AppealText(category, type, category + " · " + type, "Тема обращения", description, List.of());
    }
}
