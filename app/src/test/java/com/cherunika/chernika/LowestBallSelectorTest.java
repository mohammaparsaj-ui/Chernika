package com.cherunika.chernika;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LowestBallSelectorTest {
    private final LowestBallSelector selector = new LowestBallSelector();

    @Test public void selectsLowestNumberedBallWhenEveryBallIsKnown() {
        LowestBallSelector.Selection result = selector.select(Arrays.asList(
                ball(4), ball(2), ball(7), ball(1)), 0);
        assertTrue(result.isConfident());
        assertEquals(1, result.number);
    }

    @Test public void waitsWhenAnyBallNumberIsUncertain() {
        LowestBallSelector.Selection result = selector.select(Arrays.asList(
                ball(2), new Ball(new Vec2(20, 20), 10, 0, false), ball(3)), 0);
        assertFalse(result.isConfident());
    }

    @Test public void rejectsDuplicateNumbers() {
        LowestBallSelector.Selection result = selector.select(Arrays.asList(
                ball(2), ball(2), ball(3)), 0);
        assertFalse(result.isConfident());
    }

    @Test public void hudTargetTakesPriorityOverLowestColorRead() {
        LowestBallSelector.Selection result = selector.select(Arrays.asList(
                ball(1), ball(4), ball(7)), 4);
        assertTrue(result.isConfident());
        assertEquals(4, result.number);
    }

    private Ball ball(int number) {
        return new Ball(new Vec2(number * 20, number * 30), 10, number, false);
    }
}
