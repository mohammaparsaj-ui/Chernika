package com.cherunika.chernika;

import org.junit.Test;
import static org.junit.Assert.*;

public class ShotPlannerTest {
    private TableGeometry table() {
        return PlatoTableCalibration.fromDetectedFeltBounds(
                100, 100, 611, 1100, 1200, 1600);
    }

    @Test public void plansClearDirectShotToOneOfSixPockets() {
        FrameState frame = new FrameState(1200, 1600);
        frame.table = table();
        frame.cueBall = new Ball(new Vec2(220, 600), 12, 0, true);
        frame.balls.add(frame.cueBall);
        frame.balls.add(new Ball(new Vec2(450, 600), 12, 1, false));
        ShotPlan plan = new ShotPlanner().plan(frame, 1);
        assertNotNull(plan);
        assertEquals(1, plan.target.number);
        assertTrue(plan.powerPercent >= 20 && plan.powerPercent <= 100);
        assertNotNull(plan.pocket.id);
    }

    @Test public void returnsNoShotWhenTargetNumberIsUnknown() {
        FrameState frame = new FrameState(1200, 1600);
        frame.table = table();
        frame.cueBall = new Ball(new Vec2(220, 600), 12, 0, true);
        frame.balls.add(frame.cueBall);
        frame.balls.add(new Ball(new Vec2(450, 600), 12, 0, false));
        assertNull(new ShotPlanner().plan(frame, 1));
    }
}
