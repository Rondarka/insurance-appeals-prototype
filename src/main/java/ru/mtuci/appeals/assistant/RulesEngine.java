package ru.mtuci.appeals.assistant;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Подсказки на правилах — работают всегда, без модели: и как основной режим, и как резервный,
 * когда модель недоступна. Правила объяснимы: у каждой подсказки есть цитата, на которой она держится.
 */
@Component
public class RulesEngine implements AssistantEngine {

    private static final String PRETENSION_CATEGORY = "COMPLAINT";
    private static final String PRETENSION = "PRETENSION";
    /** Где спор о решении страховщика возможен: жалоба, расчёты, страховой случай. */
    private static final Set<String> DISPUTE_CATEGORIES = Set.of("COMPLAINT", "PAYMENT", "CLAIM");
    /** Явный признак: клиент сам называет обращение претензией или говорит о досудебном порядке. */
    private static final Pattern EXPLICIT = Pattern.compile("претензи\\w*|досудебн\\w*", Excerpts.FLAGS);
    /** Косвенные признаки: несогласие, требование выплаты или пересмотра, сумма. Нужно два разных. */
    private static final List<Pattern> INDIRECT = List.of(
            Pattern.compile("не\\s+соглас\\w*", Excerpts.FLAGS),
            Pattern.compile("(требую|прошу)\\s+(\\w+\\s+){0,2}(выплат|доплат|возмест|пересмотр|пересчит)\\w*", Excerpts.FLAGS),
            Pattern.compile("(заниж|занизи|незаконн|необоснованн)\\w*", Excerpts.FLAGS),
            Pattern.compile("\\d[\\d \\u00a0]*\\d\\s*(₽|руб\\w*)", Excerpts.FLAGS));
    private static final int MAX_REASONS = 3;
    /** Признаки ближе этого числа символов в одной строке цитируются вместе. */
    private static final int NEAR = 80;

    @Override
    public String source() {
        return RULES;
    }

    @Override
    public Optional<TypeGuess> guessType(AppealText appeal) {
        if (PRETENSION.equals(appeal.subcategory()) || !DISPUTE_CATEGORIES.contains(appeal.category())) {
            return Optional.empty();
        }
        // тема часто повторяет текст — цитаты берём из текста, тема последней
        String text = appeal.description()
                + (appeal.clientMessages().isEmpty() ? "" : "\n" + String.join("\n", appeal.clientMessages()))
                + "\n" + appeal.subject();
        List<int[]> found = new ArrayList<>();
        Matcher explicit = EXPLICIT.matcher(text);
        boolean isExplicit = explicit.find();
        if (isExplicit) {
            found.add(new int[]{explicit.start(), explicit.end()});
        }
        int indirect = 0;
        for (Pattern pattern : INDIRECT) {
            Matcher match = pattern.matcher(text);
            if (match.find()) {
                indirect++;
                found.add(new int[]{match.start(), match.end()});
            }
        }
        if (!isExplicit && indirect < 2) {
            return Optional.empty();
        }
        // по порядку текста; близкие признаки в одной строке — одна цитата, а не две половины фразы
        found.sort(Comparator.comparingInt(position -> position[0]));
        List<int[]> merged = new ArrayList<>();
        for (int[] position : found) {
            int[] last = merged.isEmpty() ? null : merged.get(merged.size() - 1);
            boolean near = last != null && position[0] - last[1] <= NEAR
                    && text.substring(Math.min(last[1], position[0]), position[0]).indexOf('\n') < 0;
            if (near) {
                last[1] = Math.max(last[1], position[1]);
            } else {
                merged.add(position.clone());
            }
        }
        List<String> quotes = merged.stream().limit(MAX_REASONS)
                .map(position -> Excerpts.around(text, position[0], position[1]))
                .toList();
        return Optional.of(new TypeGuess(PRETENSION_CATEGORY, PRETENSION, quotes));
    }

    @Override
    public String draft(DraftInput input) {
        return input.filledTemplate();
    }
}
