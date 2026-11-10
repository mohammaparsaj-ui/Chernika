package com.cherunika.chernika.engine;

import org.junit.Test;
import static org.junit.Assert.*;

public class HudInstructionParserTest {
    private final HudInstructionParser parser = new HudInstructionParser();

    @Test public void parsesHitBallAnnouncement() {
        HudInstruction instruction = parser.parse("Hit the 4 ball");
        assertEquals(HudInstruction.Type.HIT_BALL, instruction.type);
        assertEquals(4, instruction.ballNumber);
    }

    @Test public void parsesPocketNineAnnouncement() {
        HudInstruction instruction = parser.parse("Pocket the 9 ball");
        assertEquals(HudInstruction.Type.POCKET_BALL, instruction.type);
        assertEquals(9, instruction.ballNumber);
    }

    @Test public void recognizesBallInHand() {
        assertEquals(HudInstruction.Type.BALL_IN_HAND,
                parser.parse("Cue ball in hand").type);
    }

    @Test public void rejectsConflictingOcr() {
        assertEquals(HudInstruction.Type.UNKNOWN,
                parser.parse("Hit the 2 ball, Pocket the 9 ball").type);
    }

    @Test public void emptyTextIsNone() {
        assertEquals(HudInstruction.Type.NONE, parser.parse("").type);
    }
}
