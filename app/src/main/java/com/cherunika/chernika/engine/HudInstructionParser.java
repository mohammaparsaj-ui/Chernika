package com.cherunika.chernika.engine;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Conservative parser: only recognized, explicit HUD phrases produce instructions. */
public final class HudInstructionParser {
    private static final Pattern HIT = Pattern.compile(
            "\\bhit\\s+(?:the\\s+)?([1-9])\\s*ball\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern POCKET = Pattern.compile(
            "\\bpocket\\s+(?:the\\s+)?([1-9])\\s*ball\\b", Pattern.CASE_INSENSITIVE);

    public HudInstruction parse(String text) {
        String raw = text == null ? "" : text;
        String normalized = raw.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", " ").replaceAll("\\s+", " ").trim();

        if (normalized.contains("ball in hand")
                || normalized.contains("cue ball foul")
                || normalized.contains("cue ball in hand")
                || normalized.contains("scratch")) {
            return new HudInstruction(HudInstruction.Type.BALL_IN_HAND, 0, raw);
        }

        int hit = find(HIT, normalized);
        int pocket = find(POCKET, normalized);
        if (hit != 0 && pocket != 0) {
            // Conflicting OCR text is not safe to interpret.
            return new HudInstruction(HudInstruction.Type.UNKNOWN, 0, raw);
        }
        if (hit != 0) return new HudInstruction(HudInstruction.Type.HIT_BALL, hit, raw);
        if (pocket != 0) return new HudInstruction(HudInstruction.Type.POCKET_BALL, pocket, raw);
        return new HudInstruction(normalized.isEmpty()
                ? HudInstruction.Type.NONE : HudInstruction.Type.UNKNOWN, 0, raw);
    }

    private int find(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        if (!matcher.find()) return 0;
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }
}
