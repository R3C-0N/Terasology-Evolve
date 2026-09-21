// Copyright 2021 The Terasology Foundation
// SPDX-License-Identifier: Apache-2.0

package org.terasology.engine.world.liquid;

import org.joml.Vector3i;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.terasology.engine.world.block.Block;
import org.terasology.engine.world.block.BlockRegion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The flow solver on a world of two hash maps. No engine, no assets, no fixtures: the solver was written
 * against {@link LiquidWorldView} precisely so these could run instantly.
 */
public class LiquidFlowSolverTest {

    private static final int WATER_RANGE = 8;
    private static final int LAVA_RANGE = 4;

    private Block air;
    private Block stone;
    private Block water;
    private Block lava;
    private FakeLiquidWorldView view;
    private LiquidFlowSolver solver;

    @BeforeEach
    public void setUp() {
        air = new Block();
        air.setReplacementAllowed(true);

        stone = new Block();

        water = new Block();
        water.setLiquid(true);
        water.setReplacementAllowed(true);
        water.setFlowRange(WATER_RANGE);
        water.setViscosity((byte) 4);

        lava = new Block();
        lava.setLiquid(true);
        lava.setReplacementAllowed(true);
        lava.setFlowRange(LAVA_RANGE);
        lava.setViscosity((byte) 10);

        view = new FakeLiquidWorldView(new BlockRegion(-40, -40, -40, 40, 40, 40), air);
        solver = new LiquidFlowSolver(view);
    }

    /**
     * Runs until nothing is left to do, so the per-pass budget does not decide the outcome.
     */
    private void settle() {
        for (int pass = 0; pass < 2000 && !solver.isIdle(); pass++) {
            solver.update();
            solver.clearSelfWrites();
        }
        assertTrue(solver.isIdle(), "the solver never settled");
    }

    private void floor(int y, int radius) {
        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                view.put(x, y, z, stone);
            }
        }
    }

    @Test
    @DisplayName("A column drained out of order leaves no orphan puddle")
    public void columnDrainedOutOfOrderLeavesNothing() {
        // This is the test the whole two-phase drain exists for. Falling costs nothing, so the five blocks
        // of the column all carry one; a local "has a neighbour a smaller value than mine?" check lets the
        // bottom of the column declare itself fed by the top and survive the top's removal.
        floor(4, 2);
        view.put(0, 10, 0, water, 0);
        for (int y = 5; y <= 9; y++) {
            view.put(0, y, 0, water, 1);
        }

        // Take the source away, and seed the check at the bottom so it is looked at before the top.
        view.put(0, 10, 0, air, 0);
        for (int y = 5; y <= 9; y++) {
            solver.enqueueCheck(new Vector3i(0, y, 0));
        }
        settle();

        for (int y = 5; y <= 9; y++) {
            assertEquals(air, view.at(0, y, 0), "water left standing at y=" + y);
        }
    }

    @Test
    @DisplayName("A source on a plain spreads exactly its range and no further")
    public void spreadsExactlyItsRange() {
        floor(0, 20);
        view.put(0, 1, 0, water, 0);
        solver.enqueueFlow(new Vector3i(0, 1, 0), 0);
        settle();

        // A Manhattan disc of radius eight, less the source itself.
        assertEquals(2 * WATER_RANGE * (WATER_RANGE + 1), view.count(water) - 1);
        assertEquals(WATER_RANGE + 1, view.flowAt(WATER_RANGE, 1, 0), "the far edge should carry range+1");
        assertEquals(air, view.at(WATER_RANGE + 1, 1, 0), "one block past the range must stay dry");
    }

    @Test
    @DisplayName("Falling costs no range")
    public void fallingCostsNoRange() {
        floor(-20, 20);
        for (int y = -19; y <= 1; y++) {
            view.put(0, y, 0, air);
        }
        view.put(0, 1, 0, water, 0);
        solver.enqueueFlow(new Vector3i(0, 1, 0), 0);
        settle();

        assertEquals(water, view.at(WATER_RANGE, -19, 0), "the full range should spread again at the foot");
        assertEquals(air, view.at(WATER_RANGE + 1, -19, 0));
        assertEquals(1, view.flowAt(0, -19, 0), "a column of fall carries one the whole way down");
    }

    @Test
    @DisplayName("A drop hands the whole range back")
    public void aDropRestoresTheWholeRange() {
        // A ledge seven steps wide, then a cliff down to a floor. Whatever the water spent walking the
        // ledge, it lands at the foot with a clean slate.
        for (int x = 0; x <= 7; x++) {
            view.put(x, 0, 0, stone);
        }
        floor(-6, 20);
        view.put(0, 1, 0, water, 0);
        solver.enqueueFlow(new Vector3i(0, 1, 0), 0);
        settle();

        assertEquals(1, view.flowAt(8, -5, 0), "the fall should reset the count to one");
        assertEquals(water, view.at(8 + WATER_RANGE, -5, 0), "and the full range run again at the foot");
        assertEquals(air, view.at(8 + WATER_RANGE + 1, -5, 0), "but no further than the full range");
    }

    @Test
    @DisplayName("A single step down is enough to start the reach over")
    public void oneStepDownStartsTheReachOver() {
        // A shelf three wide on a floor one block lower - the shape of a terrace. The lava walks the shelf,
        // steps off, drops a single block, and must then have its whole reach back. The shelf runs the
        // width of the region on purpose: a shelf one block wide in z lets the lava walk round the end
        // instead of over the lip, and the test then proves nothing.
        for (int x = 0; x <= 2; x++) {
            for (int z = -20; z <= 20; z++) {
                view.put(x, 0, z, stone);
            }
        }
        floor(-1, 20);
        view.put(0, 1, 0, lava, 0);
        solver.enqueueFlow(new Vector3i(0, 1, 0), 0);
        settle();

        assertEquals(lava, view.at(3, 0, 0), "it should drop off the step");
        assertEquals(1, view.flowAt(3, 0, 0), "landing with its reach restored");
        assertEquals(lava, view.at(3 + LAVA_RANGE, 0, 0), "and run its whole range again");
        assertEquals(air, view.at(3 + LAVA_RANGE + 1, 0, 0), "but no further");
    }

    @Test
    @DisplayName("Water with somewhere to fall does not spread sideways")
    public void fallingWaterDoesNotSpread() {
        floor(-5, 20);
        view.put(0, 1, 0, water, 0);
        solver.enqueueFlow(new Vector3i(0, 1, 0), 0);
        settle();

        assertEquals(air, view.at(1, 1, 0), "it should have fallen rather than spread");
        assertEquals(water, view.at(0, 0, 0));
    }

    @Test
    @DisplayName("A source never dries up")
    public void aSourceNeverDriesUp() {
        floor(0, 4);
        view.put(0, 1, 0, water, 0);
        solver.enqueueCheck(new Vector3i(0, 1, 0));
        settle();

        assertEquals(water, view.at(0, 1, 0));
        assertEquals(0, view.flowAt(0, 1, 0));
    }

    @Test
    @DisplayName("Drying a position puts its stored distance back to nought")
    public void dryingClearsTheStoredDistance() {
        floor(0, 20);
        view.put(0, 1, 0, water, 0);
        solver.enqueueFlow(new Vector3i(0, 1, 0), 0);
        settle();
        assertNotEquals(0, view.flowAt(3, 1, 0));

        view.put(0, 1, 0, air, 0);
        solver.enqueueCheck(new Vector3i(1, 1, 0));
        solver.enqueueCheck(new Vector3i(-1, 1, 0));
        solver.enqueueCheck(new Vector3i(0, 1, 1));
        solver.enqueueCheck(new Vector3i(0, 1, -1));
        settle();

        assertEquals(air, view.at(3, 1, 0));
        assertEquals(0, view.flowAt(3, 1, 0), "a dried position must not keep a stale distance");
    }

    @Test
    @DisplayName("Water and lava stop against each other instead of trading places")
    public void waterAndLavaDoNotOscillate() {
        floor(0, 20);
        view.put(0, 1, 0, water, 0);
        view.put(3, 1, 0, lava, 0);
        solver.enqueueFlow(new Vector3i(0, 1, 0), 0);
        solver.enqueueFlow(new Vector3i(3, 1, 0), 0);
        settle();

        assertEquals(water, view.at(0, 1, 0));
        assertEquals(lava, view.at(3, 1, 0));

        // The state settling is not proof: an oscillation settles too, once the queue drains. What proves
        // it is that another round writes nothing at all.
        int before = view.getWrites();
        solver.enqueueFlow(new Vector3i(0, 1, 0), 0);
        solver.enqueueFlow(new Vector3i(3, 1, 0), 0);
        settle();
        assertEquals(before, view.getWrites(), "the two liquids kept rewriting each other");
    }

    @Test
    @DisplayName("Lava runs half as far as water")
    public void lavaRunsItsShorterRange() {
        floor(0, 20);
        view.put(0, 1, 0, lava, 0);
        solver.enqueueFlow(new Vector3i(0, 1, 0), 0);
        settle();

        assertEquals(lava, view.at(LAVA_RANGE, 1, 0));
        assertEquals(air, view.at(LAVA_RANGE + 1, 1, 0));
    }

    @Test
    @DisplayName("Nothing is written outside the loaded world")
    public void nothingIsWrittenOutsideTheLoadedWorld() {
        // The fake view throws on a write outside its region, so reaching the edge is the assertion.
        view = new FakeLiquidWorldView(new BlockRegion(-3, -3, -3, 3, 3, 3), air);
        solver = new LiquidFlowSolver(view);
        for (int x = -3; x <= 3; x++) {
            for (int z = -3; z <= 3; z++) {
                view.put(x, 0, z, stone);
            }
        }
        view.put(0, 1, 0, water, 0);
        solver.enqueueFlow(new Vector3i(0, 1, 0), 0);
        settle();

        assertEquals(water, view.at(3, 1, 0), "it should fill right up to the edge");
    }

    @Test
    @DisplayName("One pass writes no more than its budget allows")
    public void onePassKeepsToItsBudget() {
        floor(0, 30);
        view.put(0, 1, 0, water, 0);
        solver.enqueueFlow(new Vector3i(0, 1, 0), 0);

        solver.update();
        assertTrue(view.getWrites() <= LiquidFlowSolver.MAX_PLACEMENTS_PER_PASS,
                "one pass wrote " + view.getWrites() + " blocks");
        assertTrue(view.getWrites() > 0, "one pass wrote nothing at all");
    }
}
