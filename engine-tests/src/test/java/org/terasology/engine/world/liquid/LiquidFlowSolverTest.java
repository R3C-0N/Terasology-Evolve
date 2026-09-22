// Copyright 2021 The Terasology Foundation
// SPDX-License-Identifier: Apache-2.0

package org.terasology.engine.world.liquid;

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import org.joml.Vector3i;
import org.joml.Vector3ic;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.terasology.engine.math.Side;
import org.terasology.engine.world.block.Block;
import org.terasology.engine.world.block.BlockRegion;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The flow solver on a world of two hash maps. No engine, no assets, no fixtures: the solver was written
 * against {@link LiquidWorldView} precisely so these could run instantly.
 */
public class LiquidFlowSolverTest {

    private static final int WATER_RANGE = 8;
    private static final int LAVA_RANGE = 4;

    private static final String BASALT_URI = "Test:Basalt";
    private static final String OBSIDIAN_URI = "Test:Obsidian";

    /**
     * A clock the test winds on by hand, in milliseconds. The solver is paced by this and by nothing else,
     * so a test that never winds it sees a liquid that never moves - which is itself an assertion, below.
     */
    private static final class FakeClock {
        private final AtomicLong millis = new AtomicLong();

        long get() {
            return millis.get();
        }

        void advance(long ms) {
            millis.addAndGet(ms);
        }
    }

    /** A whole block's dwell for the thickest liquid there can be, so no test settles at anyone's pace. */
    private static final long SETTLE_STEP_MS =
            (long) LiquidFlowSolver.FLOW_DELAY_PER_VISCOSITY_MS * Block.MAX_VISCOSITY;

    private static final long WATER_STEP_MS = (long) LiquidFlowSolver.FLOW_DELAY_PER_VISCOSITY_MS * 4;

    private Block air;
    private Block stone;
    private Block water;
    private Block lava;
    /** A liquid with no thickness at all, for the tests that are about the budget and not about the pace. */
    private Block ether;
    /**
     * Lava that has been told what it sets into, which the plain {@link #lava} deliberately has not.
     * <p>
     * The two are kept apart on purpose. What stops water and lava trading places for ever is a predicate that
     * knows nothing of either, and the test that holds it needs a hot liquid the setting rule never touches -
     * otherwise the water would simply turn the lava to stone and the invariant would go untested.
     */
    private Block molten;
    private Block basalt;
    private Block obsidian;
    private FakeLiquidWorldView view;
    private FakeClock clock;
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

        ether = new Block();
        ether.setLiquid(true);
        ether.setReplacementAllowed(true);
        ether.setFlowRange(WATER_RANGE);
        ether.setViscosity((byte) 0);

        basalt = new Block();
        obsidian = new Block();

        molten = new Block();
        molten.setLiquid(true);
        molten.setReplacementAllowed(true);
        molten.setFlowRange(LAVA_RANGE);
        molten.setViscosity((byte) 10);
        molten.setWarmth((byte) 15);
        molten.setCoolsInto(BASALT_URI);
        molten.setSourceCoolsInto(OBSIDIAN_URI);
        molten.setCooledBelow((byte) 7);

        view = new FakeLiquidWorldView(new BlockRegion(-40, -40, -40, 40, 40, 40), air);
        view.declare(BASALT_URI, basalt);
        view.declare(OBSIDIAN_URI, obsidian);
        clock = new FakeClock();
        solver = new LiquidFlowSolver(view, clock::get);
    }

    /**
     * Runs until nothing is left to do, winding the clock a full block's dwell between passes so that
     * neither the per-pass budget nor the viscosity decides the outcome. What the clock is worth is
     * asserted on its own, in the tests that wind it a step at a time.
     */
    private void settle() {
        for (int pass = 0; pass < 2000 && !solver.isIdle(); pass++) {
            clock.advance(SETTLE_STEP_MS);
            solver.update();
            solver.clearSelfWrites();
        }
        assertTrue(solver.isIdle(), "the solver never settled");
    }

    /**
     * One step of the clock, and as many passes as it takes to spend what that step made due.
     */
    private void tick(long ms) {
        clock.advance(ms);
        for (int pass = 0; pass < 200 && solver.hasWorkDue(); pass++) {
            solver.update();
            solver.clearSelfWrites();
        }
    }

    /**
     * The whole of what the setting rule promises, said once: when everything has come to rest, there is
     * nowhere left in the world where the hot liquid touches the cold one.
     */
    private void assertNoLavaAgainstWater() {
        for (int x = -20; x <= 20; x++) {
            for (int z = -20; z <= 20; z++) {
                if (!molten.equals(view.at(x, 1, z))) {
                    continue;
                }
                for (Side side : Side.allSides()) {
                    Vector3i neighbour = side.getAdjacentPos(new Vector3i(x, 1, z), new Vector3i());
                    assertNotEquals(water, view.getBlock(neighbour),
                            "lava left standing against water at " + x + ", 1, " + z);
                }
            }
        }
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
        solver = new LiquidFlowSolver(view, clock::get);
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
    @DisplayName("Water never stands at a source's distance while it is still running")
    public void aRunningLiquidIsNeverCaughtCallingItselfASource() {
        // The defect this test exists for was never visible in the finished state: the solver put its blocks
        // down first and their distances a moment later, and in between the world held living water whose
        // distance still read nought - which is what a source reads. Nothing in memory stayed wrong, so
        // nothing looked wrong. But a chunk is snapshotted for saving from another thread, and a snapshot
        // taken in that window wrote living water down as a spring. It came back from the save immortal, a
        // whole batch of it at a time, and no amount of taking the real source away would shift it.
        //
        // So the assertion is not about the state the solver reaches. It is about every state it passes
        // through, watched from where the save thread watches.
        floor(0, 20);
        Set<Vector3ic> sources = Sets.newHashSet(new Vector3i(0, 1, 0));
        view.put(0, 1, 0, water, 0);

        List<Vector3ic> caught = Lists.newArrayList();
        view.onBlocksWritten(() -> caught.addAll(view.livingLiquidsCallingThemselvesSources(sources)));

        solver.enqueueFlow(new Vector3i(0, 1, 0), 0);
        settle();

        assertTrue(caught.isEmpty(), () -> caught.size() + " cells were water at a source's distance while "
                + "the solver was mid-write, the first at " + caught.get(0));
    }

    @Test
    @DisplayName("Taking the source away takes the whole pool with it")
    public void aPoolDoesNotOutliveItsSource() {
        // The plain statement of what a source is for, and the shape of the bug as it was reported: a pool
        // spread, the source replaced by something solid, and the water simply stayed.
        floor(0, 20);
        view.put(0, 1, 0, water, 0);
        solver.enqueueFlow(new Vector3i(0, 1, 0), 0);
        settle();
        assertTrue(view.count(water) > 1, "the pool never formed, so its draining proves nothing");

        view.put(0, 1, 0, stone, 0);
        for (Side side : Side.horizontalSides()) {
            solver.enqueueCheck(side.getAdjacentPos(new Vector3i(0, 1, 0), new Vector3i()));
        }
        settle();

        assertEquals(0, view.count(water), "water outlived the source that made it");
    }

    @Test
    @DisplayName("A frozen clock leaves the water where it stands")
    public void aFrozenClockLeavesTheWaterWhereItStands() {
        floor(0, 20);
        view.put(0, 1, 0, water, 0);
        solver.enqueueFlow(new Vector3i(0, 1, 0), 0);

        for (int pass = 0; pass < 200; pass++) {
            solver.update();
            solver.clearSelfWrites();
        }

        assertEquals(0, view.getWrites(), "water ran without a single millisecond passing");
        assertFalse(solver.isIdle(), "the solver gave up instead of waiting");
    }

    @Test
    @DisplayName("Water crosses one block per dwell, and no more")
    public void waterAdvancesOneRingPerDwell() {
        floor(0, 20);
        view.put(0, 1, 0, water, 0);
        solver.enqueueFlow(new Vector3i(0, 1, 0), 0);

        for (int ring = 1; ring <= 4; ring++) {
            tick(WATER_STEP_MS);
            assertEquals(water, view.at(ring, 1, 0), "the front should have reached ring " + ring);
            assertEquals(air, view.at(ring + 1, 1, 0), "but it should not have reached past it yet");
        }
    }

    @Test
    @DisplayName("Lava creeps where water runs, in the very same scene")
    public void twoLiquidsEachKeepTheirOwnPace() {
        // One clock, two paces, side by side. Over five of water's dwells - 2400 ms - water takes five
        // blocks because its dwell is 480, while lava, owing 1200 each time, only ever comes due once.
        floor(0, 30);
        view.put(-20, 1, 0, water, 0);
        view.put(20, 1, 0, lava, 0);
        solver.enqueueFlow(new Vector3i(-20, 1, 0), 0);
        solver.enqueueFlow(new Vector3i(20, 1, 0), 0);

        for (int step = 0; step < 5; step++) {
            tick(WATER_STEP_MS);
        }

        assertEquals(water, view.at(-15, 1, 0), "water should have run five blocks");
        assertEquals(air, view.at(-14, 1, 0), "and no further");
        assertEquals(lava, view.at(21, 1, 0), "lava should have crept one");
        assertEquals(air, view.at(22, 1, 0), "and no further, in the same elapsed time");
    }

    @Test
    @DisplayName("A liquid with no thickness runs as fast as the budget allows")
    public void aThinLiquidIgnoresTheClock() {
        floor(0, 20);
        view.put(0, 1, 0, ether, 0);
        solver.enqueueFlow(new Vector3i(0, 1, 0), 0);

        for (int pass = 0; pass < 200 && !solver.isIdle(); pass++) {
            solver.update();
            solver.clearSelfWrites();
        }

        assertTrue(solver.isIdle(), "a liquid owing no dwell should settle on a frozen clock");
        assertEquals(2 * WATER_RANGE * (WATER_RANGE + 1), view.count(ether) - 1);
    }

    @Test
    @DisplayName("A tide goes out at the pace it came in, and from the far edge first")
    public void aTideGoesOutAtThePaceItCameIn() {
        floor(0, 20);
        view.put(0, 1, 0, water, 0);
        solver.enqueueFlow(new Vector3i(0, 1, 0), 0);
        settle();
        long full = view.count(water);

        view.put(0, 1, 0, stone, 0);
        for (Side side : Side.horizontalSides()) {
            solver.enqueueCheck(side.getAdjacentPos(new Vector3i(0, 1, 0), new Vector3i()));
        }

        tick(WATER_STEP_MS);
        assertEquals(air, view.at(WATER_RANGE, 1, 0), "the far edge should go first");
        assertEquals(water, view.at(1, 1, 0), "and the near ring should still be standing");
        assertTrue(view.count(water) < full && view.count(water) > 1,
                "the pool should be partly gone, not all gone and not untouched");

        settle();
        assertEquals(0, view.count(water), "and in the end the whole pool goes");
    }

    @Test
    @DisplayName("A source put back mid-ebb stops the tide where it stands")
    public void aSourcePutBackMidEbbStopsTheTide() {
        // The case that defeats a naive design. Condemned cells are excluded from feeding one another -
        // without that, a draining column declares itself fed by its own top and survives as an orphan
        // puddle - but that same exclusion means a reprieve arriving by the ordinary route would land one
        // deadline too late, and a hole would be dug through a pool that was already refilling.
        floor(0, 20);
        view.put(0, 1, 0, water, 0);
        solver.enqueueFlow(new Vector3i(0, 1, 0), 0);
        settle();

        view.put(0, 1, 0, stone, 0);
        for (Side side : Side.horizontalSides()) {
            solver.enqueueCheck(side.getAdjacentPos(new Vector3i(0, 1, 0), new Vector3i()));
        }
        tick(WATER_STEP_MS);
        tick(WATER_STEP_MS);

        // The tide is going out. Put the spring back.
        view.put(0, 1, 0, water, 0);
        solver.enqueueFlow(new Vector3i(0, 1, 0), 0);
        long standing = view.count(water);

        // The tide does not stop dead, and it should not: the water coming back travels at the water's own
        // pace, so for a moment the far rings are still going out while the near ones are already filling.
        // What matters is that the two fronts meet and the pool closes up, rather than the ebb carrying on
        // through ground the spring had already retaken. Dipping below what was standing is expected;
        // finishing short of whole is not.
        for (int step = 0; step < 12; step++) {
            tick(WATER_STEP_MS);
        }
        settle();

        assertTrue(view.count(water) > standing, "the spring came back and the pool did not");
        assertEquals(2 * WATER_RANGE * (WATER_RANGE + 1), view.count(water) - 1,
                "the pool should end up whole again, with no hole left in it");
    }

    @Test
    @DisplayName("A source poured from a height takes its whole waterfall with it")
    public void aWaterfallDoesNotOutliveItsSource() {
        // The shape a player actually builds: a spring up on a ledge, a fall, and a pool spreading at the
        // foot of it. A flat pool is the easy case - every ring carries a different distance, so the drain
        // can walk them in order. A fall carries one distance from top to bottom, because falling hands the
        // whole reach back, and that is the case where a cell can be told it is fed by the very cell above
        // that is itself on its way out.
        floor(0, 20);
        view.put(0, 10, 0, water, 0);
        solver.enqueueFlow(new Vector3i(0, 10, 0), 0);
        settle();

        assertEquals(water, view.at(0, 5, 0), "the fall should have formed");
        assertEquals(water, view.at(WATER_RANGE, 1, 0), "and pooled across the floor");
        long poured = view.count(water);

        view.put(0, 10, 0, stone, 0);
        solver.enqueueCheck(new Vector3i(0, 9, 0));
        for (Side side : Side.horizontalSides()) {
            solver.enqueueCheck(side.getAdjacentPos(new Vector3i(0, 10, 0), new Vector3i()));
        }
        settle();

        assertEquals(0, view.count(water),
                "the spring was taken away and " + view.count(water) + " of " + poured
                        + " blocks of water stayed behind");
    }

    @Test
    @DisplayName("One pass writes no more than its budget allows")
    public void onePassKeepsToItsBudget() {
        // Deliberately a liquid with no viscosity: with a thick one, a single pass would write the four
        // neighbours of the source and stop for want of time rather than for want of budget, and the test
        // would quietly stop being about the budget at all.
        floor(0, 30);
        view.put(0, 1, 0, ether, 0);
        solver.enqueueFlow(new Vector3i(0, 1, 0), 0);

        solver.update();
        assertTrue(view.getWrites() <= LiquidFlowSolver.MAX_PLACEMENTS_PER_PASS,
                "one pass wrote " + view.getWrites() + " blocks");
        assertTrue(view.getWrites() > 0, "one pass wrote nothing at all");
    }

    @Test
    @DisplayName("Lava that has run sets into stone where the water touches it")
    public void flowingLavaMeetingWaterTurnsToBasalt() {
        floor(0, 20);
        view.put(-6, 1, 0, water, 0);
        view.put(0, 1, 0, molten, 0);
        solver.enqueueFlow(new Vector3i(-6, 1, 0), 0);
        solver.enqueueFlow(new Vector3i(0, 1, 0), 0);
        settle();

        // Where the fronts meet is a race between two paces and is not worth asserting. What the rule
        // promises is the state it leaves: stone was made, the far side of the disc the water never touched
        // is still lava, and nowhere in the world does lava stand against water.
        assertEquals(water, view.at(-6, 1, 0), "the cold liquid is never the one that changes");
        assertTrue(view.count(basalt) > 0, "the two fronts met and nothing set");
        assertTrue(view.count(molten) > 0, "the rule took lava the water never reached");
        assertNoLavaAgainstWater();
    }

    @Test
    @DisplayName("A source and its run set into blocks of their own")
    public void aSourceAndItsRunSetIntoDifferentBlocks() {
        floor(0, 20);
        view.put(0, 1, 0, molten, 0);
        solver.enqueueFlow(new Vector3i(0, 1, 0), 0);
        settle();
        assertEquals(molten, view.at(LAVA_RANGE, 1, 0), "the lava should have run its range first");

        // A single cell of water, laid against the far end of the run and nowhere near the source.
        view.put(LAVA_RANGE + 1, 1, 0, water, 0);
        solver.enqueueFlow(new Vector3i(LAVA_RANGE, 1, 0), LAVA_RANGE + 1);
        settle();

        assertEquals(basalt, view.at(LAVA_RANGE, 1, 0), "a run sets into the block a run is given");
        assertEquals(molten, view.at(0, 1, 0), "the source was never touched by anything cold");
    }

    @Test
    @DisplayName("Only the hot liquid changes; the cold one is left alone")
    public void theColdLiquidIsNeverTheOneThatChanges() {
        floor(0, 20);
        view.put(0, 1, 0, water, 0);
        view.put(1, 1, 0, molten, 0);
        solver.enqueueFlow(new Vector3i(0, 1, 0), 0);
        solver.enqueueFlow(new Vector3i(1, 1, 0), 0);
        settle();

        assertEquals(water, view.at(0, 1, 0));
        assertEquals(obsidian, view.at(1, 1, 0), "a source sets into the block a source is given");
    }

    @Test
    @DisplayName("A liquid is never set by its own kind")
    public void aLiquidIsNeverSetByItsOwnKind() {
        floor(0, 20);
        view.put(0, 1, 0, molten, 0);
        view.put(1, 1, 0, molten, 0);
        solver.enqueueFlow(new Vector3i(0, 1, 0), 0);
        solver.enqueueFlow(new Vector3i(1, 1, 0), 0);
        settle();

        assertEquals(molten, view.at(0, 1, 0), "two cells of the same liquid must not set each other");
        assertEquals(molten, view.at(1, 1, 0));
    }

    @Test
    @DisplayName("A liquid warmer than the threshold does not set it")
    public void aLiquidWarmerThanTheThresholdDoesNotSetIt() {
        Block warmSpring = new Block();
        warmSpring.setLiquid(true);
        warmSpring.setReplacementAllowed(true);
        warmSpring.setFlowRange(WATER_RANGE);
        warmSpring.setWarmth((byte) 8); // One above what the molten rock declares it is set by.

        floor(0, 20);
        view.put(0, 1, 0, warmSpring, 0);
        view.put(1, 1, 0, molten, 0);
        solver.enqueueFlow(new Vector3i(0, 1, 0), 0);
        solver.enqueueFlow(new Vector3i(1, 1, 0), 0);
        settle();

        assertEquals(molten, view.at(1, 1, 0), "cooledBelow should have kept this one liquid");
    }

    @Test
    @DisplayName("A liquid that names no block never sets, whatever touches it")
    public void aLiquidThatNamesNoBlockNeverSets() {
        // This is the plain lava, and it is what makes the rule free for every liquid that does not take part.
        floor(0, 20);
        view.put(0, 1, 0, water, 0);
        view.put(1, 1, 0, lava, 0);
        solver.enqueueFlow(new Vector3i(0, 1, 0), 0);
        solver.enqueueFlow(new Vector3i(1, 1, 0), 0);
        settle();

        assertEquals(lava, view.at(1, 1, 0));
    }

    @Test
    @DisplayName("A liquid naming a block nobody knows stays liquid rather than vanishing")
    public void anUnknownBlockLeavesTheLiquidAlone() {
        // A block manager hands back air for a name it does not know. Believed, that would turn the lava into
        // nothing at all - a rule that looks like it works and quietly deletes the world.
        Block mistyped = new Block();
        mistyped.setLiquid(true);
        mistyped.setReplacementAllowed(true);
        mistyped.setFlowRange(LAVA_RANGE);
        mistyped.setWarmth((byte) 15);
        mistyped.setCoolsInto("Test:NoSuchBlock");
        mistyped.setCooledBelow((byte) 7);

        floor(0, 20);
        view.put(0, 1, 0, water, 0);
        view.put(1, 1, 0, mistyped, 0);
        solver.enqueueFlow(new Vector3i(0, 1, 0), 0);
        solver.enqueueFlow(new Vector3i(1, 1, 0), 0);
        settle();

        assertEquals(mistyped, view.at(1, 1, 0), "an unknown name must leave the liquid exactly as it was");
    }

    @Test
    @DisplayName("A tongue of lava meeting the sea seals itself and stops")
    public void aTongueOfLavaMeetingTheSeaSealsItselfAndStops() {
        // The rule has to come to rest on its own. What the lava leaves behind is stone, not a space, so the
        // water cannot come on and the front seals at one block thick. Settling proves nothing by itself -
        // an oscillation settles too - so the assertion is that a second round writes nothing at all.
        floor(0, 20);
        for (int x = 3; x <= 12; x++) {
            for (int z = -12; z <= 12; z++) {
                view.put(x, 1, z, water, 0);
            }
        }
        view.put(0, 1, 0, molten, 0);
        solver.enqueueFlow(new Vector3i(0, 1, 0), 0);
        settle();

        int before = view.getWrites();
        solver.enqueueFlow(new Vector3i(0, 1, 0), 0);
        settle();
        assertEquals(before, view.getWrites(), "the front kept rewriting itself");
    }

    @Test
    @DisplayName("What fed a source that set dries up behind it")
    public void whatFedASourceThatSetDriesUp() {
        // A source that sets is a source taken away, and the tongue it was feeding has to go with it. Nothing
        // else would ever notice: the cells further out are perfectly good liquid carrying perfectly good
        // distances to a source that is no longer there.
        floor(0, 20);
        view.put(0, 1, 0, molten, 0);
        solver.enqueueFlow(new Vector3i(0, 1, 0), 0);
        settle();
        assertEquals(molten, view.at(0, 1, -LAVA_RANGE), "the run should be there to lose");

        view.put(1, 1, 0, water, 0);
        solver.enqueueFlow(new Vector3i(0, 1, 0), 0);
        settle();

        assertEquals(obsidian, view.at(0, 1, 0), "the source should have set");
        assertEquals(air, view.at(0, 1, -LAVA_RANGE), "the far end of the run should have dried up");
    }

    @Test
    @DisplayName("A cell that has set is never seen as living liquid at a source's distance")
    public void aCellThatSetIsNeverCaughtCallingItselfASource() {
        // The same window as aRunningLiquidIsNeverCaughtCallingItselfASource, and the reason the setting
        // writes go out blocks first and distances after: turned round, a snapshot taken between the two
        // would catch living lava at nought - which is what a source reads - and the save would come back
        // with a spring that never dries up.
        floor(0, 20);
        Set<Vector3ic> sources = Sets.newHashSet(new Vector3i(0, 1, 0), new Vector3i(6, 1, 0));
        view.put(0, 1, 0, molten, 0);
        view.put(6, 1, 0, water, 0);

        List<Vector3ic> caught = Lists.newArrayList();
        view.onBlocksWritten(() -> caught.addAll(view.livingLiquidsCallingThemselvesSources(sources)));

        solver.enqueueFlow(new Vector3i(0, 1, 0), 0);
        solver.enqueueFlow(new Vector3i(6, 1, 0), 0);
        settle();

        assertTrue(caught.isEmpty(), () -> caught.size() + " cells were living liquid at a source's distance "
                + "while the solver was mid-write, the first at " + caught.get(0));
    }

    @Test
    @DisplayName("Water arriving against lava that is already standing sets it")
    public void waterArrivingAgainstStandingLavaSetsIt() {
        // The cold side of the rule, and the one that looks after itself least. Lava running up against water
        // is caught by the lava's own turn, because a cell just placed is queued. Water running up against
        // lava that has long since come to rest is caught by nothing: the change the solver's own write raises
        // is marked as its own and skipped, so the lava would stand there for good, liquid, against water.
        floor(0, 20);
        view.put(0, 1, 0, molten, 0);
        solver.enqueueFlow(new Vector3i(0, 1, 0), 0);
        settle();
        assertEquals(molten, view.at(2, 1, 0), "the lava should have come to rest first");

        // Now, and only now, a spring opens far enough away that the water arrives second.
        view.put(6, 1, 0, water, 0);
        solver.enqueueFlow(new Vector3i(6, 1, 0), 0);
        settle();

        assertTrue(view.count(basalt) > 0, "the water arrived and nothing set");
        assertNoLavaAgainstWater();
    }
}
