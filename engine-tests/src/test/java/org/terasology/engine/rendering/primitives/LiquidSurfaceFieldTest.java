// Copyright 2021 The Terasology Foundation
// SPDX-License-Identifier: Apache-2.0

package org.terasology.engine.rendering.primitives;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The corner arithmetic on two plain arrays. Everything that can go wrong in it is arithmetic, and none of it needs a
 * chunk to go wrong in, which is why {@link LiquidSurfaceField#cornerHeight} takes no world.
 */
public class LiquidSurfaceFieldTest {

    private static final int WATER_RANGE = 8;
    private static final int LAVA_RANGE = 4;

    private static final int A = LiquidSurfaceField.ABSENT;

    private static float height(int[] flows, boolean[] above, int range) {
        return LiquidSurfaceField.cornerHeight(flows, above, range);
    }

    private static boolean[] nothingAbove() {
        return new boolean[4];
    }

    @Test
    @DisplayName("Four sources stand at the height they have always stood at")
    public void fourSourcesDoNotMove() {
        // The sea is made entirely of sources, so this is the test that says the generated world is untouched.
        assertEquals(LiquidSurfaceField.HIGH, height(new int[]{0, 0, 0, 0}, nothingAbove(), WATER_RANGE));
        assertEquals(LiquidSurfaceField.HIGH, height(new int[]{0, 0, 0, 0}, nothingAbove(), LAVA_RANGE));
    }

    @Test
    @DisplayName("The last step of a run is the thinnest the film ever gets")
    public void theEndOfTheReachIsTheThinnest() {
        // The last value still alive is range + 1, and it must land exactly on the bottom of the range: short of it
        // the slope is wasted, past it the film would poke through the floor.
        assertEquals(LiquidSurfaceField.LOW,
                height(new int[]{WATER_RANGE + 1, A, A, A}, nothingAbove(), WATER_RANGE));
        assertEquals(LiquidSurfaceField.LOW,
                height(new int[]{LAVA_RANGE + 1, A, A, A}, nothingAbove(), LAVA_RANGE));
    }

    @Test
    @DisplayName("Lava falls away twice as fast as water over one step")
    public void aShorterReachMakesASteeperSlope() {
        float water = LiquidSurfaceField.HIGH - height(new int[]{1, A, A, A}, nothingAbove(), WATER_RANGE);
        float lava = LiquidSurfaceField.HIGH - height(new int[]{1, A, A, A}, nothingAbove(), LAVA_RANGE);
        assertEquals((WATER_RANGE + 1) / (float) (LAVA_RANGE + 1), lava / water, 1e-6f);
    }

    @Test
    @DisplayName("Dry columns do not drag the corner down")
    public void absentColumnsAreNotCounted() {
        // Counting an absent column as nought or as the end of the reach gives two different wrong answers; it has to
        // be left out of the average entirely.
        float twoPresent = height(new int[]{0, 2, A, A}, nothingAbove(), WATER_RANGE);
        float sameTwoAlone = height(new int[]{2, 0, A, A}, nothingAbove(), WATER_RANGE);
        assertEquals(sameTwoAlone, twoPresent);

        float mean = (LiquidSurfaceField.HIGH + height(new int[]{2, A, A, A}, nothingAbove(), WATER_RANGE)) / 2f;
        assertEquals(mean, twoPresent, 1e-6f);
    }

    @Test
    @DisplayName("Anything overhead pulls the corner up to the full block")
    public void liquidAboveFillsTheCorner() {
        // The column that touches the corner only diagonally counts too: that is the corner a fall grazes, and it is
        // the one that closes the seam between the fall and the pool it lands in.
        boolean[] onlyTheDiagonal = {false, false, false, true};
        assertEquals(LiquidSurfaceField.FULL,
                height(new int[]{WATER_RANGE + 1, WATER_RANGE + 1, WATER_RANGE + 1, WATER_RANGE + 1},
                        onlyTheDiagonal, WATER_RANGE));
    }

    @Test
    @DisplayName("The four blocks sharing a corner agree on its height, to the bit")
    public void everyBlockRoundACornerAgrees() {
        // This is the test the whole design rests on. The four blocks that meet at a corner each present the same
        // four columns, but each starts with its own, so each sees a different order. If the heights were averaged
        // as floats the sums would not be associative and the last bit could differ - a hairline crack along every
        // block edge. Strict equality, no delta: a delta would hide exactly the defect being looked for.
        int[] asSeenFromEachOfTheFour0 = {0, 3, 7, 9};
        int[] asSeenFromEachOfTheFour1 = {3, 0, 9, 7};
        int[] asSeenFromEachOfTheFour2 = {7, 9, 0, 3};
        int[] asSeenFromEachOfTheFour3 = {9, 7, 3, 0};

        float first = height(asSeenFromEachOfTheFour0, nothingAbove(), WATER_RANGE);
        assertEquals(first, height(asSeenFromEachOfTheFour1, nothingAbove(), WATER_RANGE));
        assertEquals(first, height(asSeenFromEachOfTheFour2, nothingAbove(), WATER_RANGE));
        assertEquals(first, height(asSeenFromEachOfTheFour3, nothingAbove(), WATER_RANGE));

        // And the same where some columns are missing, which is the shape of a shoreline.
        int[] shore0 = {1, A, 6, A};
        int[] shore1 = {6, A, 1, A};
        assertEquals(height(shore0, nothingAbove(), WATER_RANGE), height(shore1, nothingAbove(), WATER_RANGE));
    }

    @Test
    @DisplayName("No corner ever leaves the block it belongs to")
    public void everyHeightStaysInsideTheBlock() {
        for (int a = -1; a <= WATER_RANGE + 1; a++) {
            for (int b = -1; b <= WATER_RANGE + 1; b++) {
                float h = height(new int[]{Math.max(a, 0), b, A, A}, nothingAbove(), WATER_RANGE);
                assertTrue(h >= LiquidSurfaceField.LOW && h <= LiquidSurfaceField.FULL,
                        "a corner left the block at " + h);
            }
        }
    }
}
