package ru.mtuci.appeals.assistant;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Цитата вокруг найденного фрагмента — сотрудник сразу видит, почему сработала подсказка. */
final class Excerpts {

    static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.UNICODE_CHARACTER_CLASS;
    private static final int CONTEXT = 40;
    private static final int MAX_LENGTH = 300;

    private Excerpts() {
    }

    static String around(String text, Matcher match) {
        return around(text, match.start(), match.end());
    }

    /** Не выходит за строку — тема и текст обращения не склеиваются — и не режет слова пополам. */
    static String around(String text, int matchStart, int matchEnd) {
        int lineStart = text.lastIndexOf('\n', Math.max(0, matchStart - 1)) + 1;
        int lineEnd = text.indexOf('\n', matchEnd);
        if (lineEnd < 0) {
            lineEnd = text.length();
        }
        int start = Math.max(lineStart, matchStart - CONTEXT);
        int end = Math.min(lineEnd, matchEnd + CONTEXT);
        while (start > lineStart && !Character.isWhitespace(text.charAt(start - 1))) {
            start--;
        }
        while (end < lineEnd && !Character.isWhitespace(text.charAt(end))) {
            end++;
        }
        String excerpt = text.substring(start, end).replaceAll("\\s+", " ").trim();
        if (excerpt.length() > MAX_LENGTH - 2) {
            excerpt = excerpt.substring(0, MAX_LENGTH - 2);
        }
        return (start > lineStart ? "…" : "") + excerpt + (end < lineEnd ? "…" : "");
    }
}
