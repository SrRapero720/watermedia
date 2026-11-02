package org.watermedia.bootstrap.app;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.watermedia.bootstrap.app.WaterMediaApp.FramePlan;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies how the main loop picks a frame: animating screens repaint whole, changes repaint the tree, and a moving
 * CRT band over a still tree only composites the cached layers, once per band step.
 */
@DisplayName("Frame plan")
public class FramePlanTest {

    @Nested
    @DisplayName("Repaints")
    class Repaints {

        @Test
        @DisplayName("an animating screen draws straight to the window whatever else holds")
        void continuous() {
            assertEquals(FramePlan.DIRECT, FramePlan.of(true, false, true, true, true, true, false));
            assertEquals(FramePlan.DIRECT, FramePlan.of(true, true, true, true, false, false, true));
        }

        @Test
        @DisplayName("a change repaints the tree into the cache only while a CRT band moves, between band steps too")
        void requested() {
            assertEquals(FramePlan.CACHE, FramePlan.of(false, true, true, true, true, true, false));
            assertEquals(FramePlan.DIRECT, FramePlan.of(false, true, true, false, true, false, true));
            assertEquals(FramePlan.DIRECT, FramePlan.of(false, true, false, true, true, true, true), "CRT just turned off");
        }
    }

    @Nested
    @DisplayName("Still tree")
    class StillTree {

        @Test
        @DisplayName("a moving band over a valid cache only composites, and rebuilds a dropped cache")
        void composites() {
            assertEquals(FramePlan.COMPOSITE, FramePlan.of(false, false, true, true, true, true, true));
            assertEquals(FramePlan.CACHE, FramePlan.of(false, false, true, true, true, false, true));
        }

        @Test
        @DisplayName("a band that has not stepped since the last frame skips a composite that would repeat it")
        void paced() {
            assertEquals(FramePlan.IDLE, FramePlan.of(false, false, true, true, true, true, false));
            assertEquals(FramePlan.CACHE, FramePlan.of(false, false, true, true, true, false, false), "a lost cache rebuilds at once");
        }

        @Test
        @DisplayName("nothing to show sleeps: no band, CRT off or a minimized window")
        void idle() {
            assertEquals(FramePlan.IDLE, FramePlan.of(false, false, true, false, true, true, true));
            assertEquals(FramePlan.IDLE, FramePlan.of(false, false, false, true, true, true, true));
            assertEquals(FramePlan.IDLE, FramePlan.of(false, false, true, true, false, true, true));
        }
    }

    @Nested
    @DisplayName("After beginFrame")
    class AfterBeginFrame {

        @Test
        @DisplayName("a composite whose layers the frame start dropped repaints the tree into new ones")
        void layersDropped() {
            assertEquals(FramePlan.CACHE, FramePlan.COMPOSITE.settle(false));
            assertEquals(FramePlan.COMPOSITE, FramePlan.COMPOSITE.settle(true));
        }

        @Test
        @DisplayName("every other plan stands whatever the cache holds")
        void othersStand() {
            for (final FramePlan plan: new FramePlan[] { FramePlan.IDLE, FramePlan.DIRECT, FramePlan.CACHE }) {
                assertEquals(plan, plan.settle(false));
                assertEquals(plan, plan.settle(true));
            }
        }
    }
}
