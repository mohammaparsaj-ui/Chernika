package com.cherunika.chernika;

import org.junit.Test;
import static org.junit.Assert.*;

public class ShotPlannerTest {
    @Test public void plansClearDirectShotToOneOfSixPockets() {
        FrameState frame = new FrameState(1200, 700);
        frame.table = new TableGeometry(100, 100, 1100, 600);
        frame.cueBall = new Ball(new Vec2(280, 350), 12, 0, true);
        frame.balls.add(frame.cueBall);
        frame.balls.add(new Ball(new Vec2(620, 350), 12, 1, false));
        ShotPlan plan = new ShotPlanner().plan(frame, 1);
        assertNotNull(plan);
        assertEquals(1, plan.target.number);
        assertTrue(plan.powerPercent >= 20 && plan.powerPercent <= 100);
    }

    @Test public void returnsNoShotWhenTargetNumberIsUnknown() {
        FrameState frame = new FrameState(1200, 700);
        frame.table = new TableGeometry(100, 100, 1100, 600);
        frame.cueBall = new Ball(new Vec2(280, 350), 12, 0, true);
        frame.balls.add(frame.cueBall);
        frame.balls.add(new Ball(new Vec2(620, 350), 12, 0, false));
        assertNull(new ShotPlanner().plan(frame, 1));
    }
}
