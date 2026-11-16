package com.cherunika.chernika;

import org.junit.Test;

import static org.junit.Assert.*;

public class ShotPlannerNeuralActionTest {
    @Test
    public void normalizedAngleOffsetChangesAimDirectionAndPowerUsesUiScale() {
        Ball target = new Ball(new Vec2(100.0, 0.0), 11.0, 1, false);
        ShotPlan geometric = new ShotPlan(
                target, new Vec2(90.0, 0.0), null,
                65.0, 1.0, false, false, false);

        ShotPlan learned = ShotPlanner.withNeuralAction(
                geometric, new Vec2(0.0, 0.0), 0.5f, 0.0f);

        assertNotNull(learned);
        assertEquals(0.0, learned.aimPoint.x, 1e-5);
        assertEquals(90.0, learned.aimPoint.y, 1e-5);
        assertEquals(ShotPlanner.MAX_UI_POWER_PERCENT / 2.0,
                learned.powerPercent, 1e-5);
        assertSame(target, learned.target);
        assertSame(geometric.pocket, learned.pocket);
    }

    @Test
    public void normalizedPowerEndpointsMapToMinimumSafeAndMaximumUiPower() {
        Ball target = new Ball(new Vec2(100.0, 0.0), 11.0, 1, false);
        ShotPlan geometric = new ShotPlan(
                target, new Vec2(90.0, 0.0), null,
                65.0, 1.0, false, false, false);

        ShotPlan maximum = ShotPlanner.withNeuralAction(
                geometric, new Vec2(0.0, 0.0), 0.0f, 1.0f);
        ShotPlan minimum = ShotPlanner.withNeuralAction(
                geometric, new Vec2(0.0, 0.0), 0.0f, -1.0f);

        assertEquals(ShotPlanner.MAX_UI_POWER_PERCENT, maximum.powerPercent, 1e-5);
        assertEquals(20.0, minimum.powerPercent, 1e-5);
        assertEquals(90.0, maximum.aimPoint.x, 1e-5);
        assertEquals(0.0, maximum.aimPoint.y, 1e-5);
    }

    @Test
    public void invalidNeuralActionCannotCreateAShotPlan() {
        Ball target = new Ball(new Vec2(100.0, 0.0), 11.0, 1, false);
        ShotPlan geometric = new ShotPlan(
                target, new Vec2(90.0, 0.0), null,
                65.0, 1.0, false, false, false);

        assertNull(ShotPlanner.withNeuralAction(
                geometric, new Vec2(0.0, 0.0), Float.NaN, 0.0f));
        assertNull(ShotPlanner.withNeuralAction(
                geometric, new Vec2(0.0, 0.0), 0.0f, Float.POSITIVE_INFINITY));
        assertNull(ShotPlanner.withNeuralAction(
                null, new Vec2(0.0, 0.0), 0.0f, 0.0f));
    }
}
