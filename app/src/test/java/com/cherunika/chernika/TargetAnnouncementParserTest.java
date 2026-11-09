package com.cherunika.chernika;

import org.junit.Test;
import static org.junit.Assert.*;

public class TargetAnnouncementParserTest {
    @Test public void readsHitBallAndPocketObjective() {
        TargetAnnouncement result = new TargetAnnouncementParser().parse("Hit the 3 ball  |  Pocket the 9 ball");
        assertEquals(3, result.legalBall);
        assertEquals(9, result.objectiveBall);
        assertFalse(result.scratch);
    }

    @Test public void recognizesBallInHand() {
        TargetAnnouncement result = new TargetAnnouncementParser().parse("Scratch - Ball in hand");
        assertTrue(result.scratch);
    }
}
