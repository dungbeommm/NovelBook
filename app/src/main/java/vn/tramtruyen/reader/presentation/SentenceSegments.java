package vn.tramtruyen.reader.presentation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Divides original prose into complete sentences, with word boundaries for oversized requests. */
final class SentenceSegments {
    private static final String ENDS = ".!?…。！？";
    private static final String CLOSERS = "\"'”’»)]}」』】";
    private static final String SENTENCE_TAIL = ENDS + CLOSERS;
    private static final List<String> ABBREVIATIONS = Arrays.asList(
            "tp", "q", "p", "ts", "ths", "pgs", "gs", "bs", "mr", "mrs", "ms", "dr", "prof", "st", "vs", "v.v");

    private SentenceSegments() {}

    static List<String> split(String text, int limit, boolean firstOnly) {
        List<String> result = new ArrayList<>();
        int start = 0;
        for (int index = 0; index < text.length(); index++) {
            char current = text.charAt(index);
            if (current == '\n' || current == '\r') {
                add(result, text, start, index, limit, firstOnly);
                start = index + 1;
            } else if (ENDS.indexOf(current) >= 0) {
                int end = index + 1;
                while (end < text.length() && SENTENCE_TAIL.indexOf(text.charAt(end)) >= 0) end++;
                boolean followedBySpace = end == text.length() || space(text.charAt(end));
                boolean wideEnd = "。！？".indexOf(current) >= 0;
                if ((followedBySpace || wideEnd) && !abbreviation(text, start, index, end)) {
                    add(result, text, start, end, limit, firstOnly);
                    start = end;
                    index = end - 1;
                }
            }
            if (firstOnly && !result.isEmpty()) return result;
        }
        add(result, text, start, text.length(), limit, firstOnly);
        return result;
    }

    private static boolean abbreviation(String text, int start, int stop, int end) {
        if (text.charAt(stop) != '.' || end != stop + 1 || end == text.length()) return false;
        int word = stop;
        while (word > start && (Character.isLetter(text.charAt(word - 1)) || text.charAt(word - 1) == '.')) word--;
        String token = text.substring(word, stop);
        return ABBREVIATIONS.contains(token.toLowerCase(Locale.ROOT)) ||
                token.length() == 1 && Character.isUpperCase(token.charAt(0));
    }

    private static void add(List<String> result, String text, int start, int end, int limit, boolean firstOnly) {
        while (start < end && space(text.charAt(start))) start++;
        while (start < end && space(text.charAt(end - 1))) end--;
        while (end - start > limit) {
            int lastSpace = -1;
            int pause = -1;
            for (int cursor = start + 1; cursor <= start + limit; cursor++) {
                if (!space(text.charAt(cursor))) continue;
                lastSpace = cursor;
                if (cursor - start >= limit / 2 && ",;:，；：".indexOf(text.charAt(cursor - 1)) >= 0) pause = cursor;
            }
            int cut = pause >= 0 ? pause : lastSpace;
            if (cut < 0) {
                cut = start + limit;
                while (cut < end && !space(text.charAt(cut))) cut++;
            }
            result.add(text.substring(start, cut));
            if (firstOnly) return;
            start = cut;
            while (start < end && space(text.charAt(start))) start++;
        }
        if (start < end) result.add(text.substring(start, end));
    }

    private static boolean space(char value) {
        return Character.isWhitespace(value) || Character.isSpaceChar(value);
    }
}
