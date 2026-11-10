package com.cherunika.chernika.engine;

/** Parsed instruction from the top-right Plato HUD. */
public final class HudInstruction {
    public enum Type { HIT_BALL, POCKET_BALL, BALL_IN_HAND, NONE, UNKNOWN }

    public final Type type;
    public final int ballNumber;
    public final String rawText;

    public HudInstruction(Type type, int ballNumber, String rawText) {
        if (type == null || ballNumber < 0 || ballNumber > 9) {
            throw new IllegalArgumentException("Invalid HUD instruction");
        }
        this.type = type;
        this.ballNumber = ballNumber;
        this.rawText = rawText == null ? "" : rawText;
    }
}
