package com.cherunika.chernika;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class TargetAnnouncementParser {
    private static final Pattern HIT = Pattern.compile("\\bhit\\s+(?:the\\s+)?([1-9])\\s+ball\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern POCKET = Pattern.compile("\\bpocket\\s+(?:the\\s+)?([1-9])\\s+ball\\b", Pattern.CASE_INSENSITIVE);

    TargetAnnouncement parse(String text) {
        String normalized = text == null ? "" : text.toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ").trim();
        int legal = find(HIT, normalized);
        int objective = find(POCKET, normalized);
        boolean scratch = normalized.contains("scratch") || normalized.contains("ball in hand")
                || normalized.contains("cue ball foul") || normalized.contains("cue ball in hand");
        boolean breakTurn = normalized.contains("turn to break")
                || normalized.contains("break shot")
                || normalized.contains("breaking now");
        return new TargetAnnouncement(legal, objective, scratch, breakTurn, normalized);
    }

    private int find(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        if (!matcher.find()) return 0;
        try { return Integer.parseInt(matcher.group(1)); }
        catch (NumberFormatException ignored) { return 0; }
    }
}
