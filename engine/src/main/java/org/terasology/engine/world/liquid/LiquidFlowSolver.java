// Copyright 2021 The Terasology Foundation
// SPDX-License-Identifier: Apache-2.0

package org.terasology.engine.world.liquid;

import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import org.joml.Vector3i;
import org.joml.Vector3ic;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.terasology.engine.math.Side;
import org.terasology.engine.world.block.Block;
import org.terasology.engine.world.chunks.Chunks;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Works out where a liquid runs to, and where it drains back from.
 * <p>
 * The liquid occupies exactly those positions within {@code flowRange} of a source, under a shortest-path
 * distance where falling costs nothing, stepping sideways costs one, and rising is impossible. A source
 * carries the distance nought, and because an untouched per-block data field reads nought everywhere, every
 * block of liquid the world generator laid down is a source without a single write.
 * <p>
 * The distance stored is one more than the number of sideways steps. That offset is what stops the sea
 * copying itself downwards forever: without it the block under a source would inherit nought, become a
 * source in its turn, and never dry up.
 * <p>
 * It holds no reference to the engine beyond {@link Block} and {@link Side}, so it can be driven by a plain
 * map in a test.
 */
public class LiquidFlowSolver {

    /**
     * Placements of an unlit liquid per pass. At sixty frames a second this fills a flooded cave of some six
     * hundred blocks in about a sixth of a second.
     */
    public static final int MAX_PLACEMENTS_PER_PASS = 64;

    /**
     * Placements of a lit liquid per pass. Lava shines at fifteen, so every block laid sets off a full light
     * flood of radius fifteen - laying eight is already like planting eight torches in one frame.
     */
    public static final int MAX_LIT_PLACEMENTS_PER_PASS = 8;

    /**
     * How many chunks one pass may dirty. Each write dirties up to eight, and each dirty chunk is re-meshed;
     * past this the meshing workers fall behind and the terrain shows holes.
     */
    public static final int MAX_DIRTY_CHUNKS_PER_PASS = 8;

    /**
     * How large a drained component may grow before the self-repairing fallback takes over.
     */
    public static final int MAX_REMOVAL_COMPONENT = 4096;

    /**
     * How many positions may wait to be looked at. A queue that runs away is a bug worth seeing, not a leak
     * worth suffering.
     */
    public static final int MAX_QUEUE_SIZE = 65536;

    /**
     * How many positions one pass may check for lost support.
     */
    public static final int MAX_CHECKS_PER_PASS = 256;

    /**
     * How many positions one pass may look at, placement or not. The placement budget alone does not bound
     * the pass: a queue full of positions with nowhere left to go writes nothing and would be walked whole.
     */
    public static final int MAX_VISITS_PER_PASS = 4096;

    private static final Logger logger = LoggerFactory.getLogger(LiquidFlowSolver.class);

    private static final int BUCKETS = 16;
    private static final int UNREACHED = Integer.MAX_VALUE;

    private final LiquidWorldView view;

    /**
     * Positions waiting to spread, bucketed by distance so the smallest always comes out first. That is a
     * Dijkstra whose extraction is constant time, the distances being small integers.
     */
    private final Deque<Vector3i>[] addBuckets;
    private final Set<Vector3i> queuedToAdd = Sets.newHashSet();

    /**
     * Positions whose support may have gone.
     */
    private final Set<Vector3i> checkQueue = new LinkedHashSet<>();

    /**
     * The positions this solver is about to write, so the change events it causes are not mistaken for
     * someone else's and queued all over again.
     */
    private final Set<Vector3ic> selfWrites = Sets.newHashSet();

    /**
     * What this pass has decided to write but has not written yet.
     * <p>
     * The writes are held back so they can go out in one batch, and every read inside a pass has to see
     * through them: without that, a cell placed early in the pass still reads as air when its own turn
     * comes round, is thrown out as "not a liquid", and the front dies after a single ring.
     */
    private final Map<Vector3ic, Block> staged = Maps.newLinkedHashMap();
    private final Map<Vector3ic, Integer> stagedValues = Maps.newLinkedHashMap();

    private boolean warnedFullQueue;

    @SuppressWarnings("unchecked")
    public LiquidFlowSolver(LiquidWorldView view) {
        this.view = view;
        this.addBuckets = new Deque[BUCKETS];
        for (int i = 0; i < BUCKETS; i++) {
            addBuckets[i] = new ArrayDeque<>();
        }
    }

    // ---------------------------------------------------------------- predicates

    /**
     * Whether this block is a liquid that runs. A liquid with no reach stays exactly where it was put.
     */
    public static boolean isFlowLiquid(Block block) {
        return block.isLiquid() && block.getFlowRange() > 0;
    }

    /**
     * Whether a liquid may take this position over.
     * <p>
     * The {@code !isLiquid} is the most fragile line in the whole design, and it is not redundant: water is
     * itself replaceable, so without it water overwrites water without end, and water and lava take turns
     * replacing each other forever - each turn of the lava costing a full light flood. Never drop it on the
     * grounds that water is replaceable.
     */
    public static boolean isFlowPassable(Block block) {
        return block.isReplacementAllowed() && !block.isLiquid();
    }

    private static int childValue(int value, boolean downwards) {
        int base = Math.max(1, value);
        return downwards ? base : base + 1;
    }

    private static Vector3i below(Vector3ic pos) {
        return new Vector3i(pos.x(), pos.y() - 1, pos.z());
    }

    private static Vector3i above(Vector3ic pos) {
        return new Vector3i(pos.x(), pos.y() + 1, pos.z());
    }

    /**
     * Whether this position is still falling. While it is, it does not spread sideways at all - which turns
     * a pancake into a waterfall and saves an order of magnitude of blocks on broken ground.
     * <p>
     * The second clause is not decoration. Asking only whether the space below is free makes the rule
     * undo itself: the moment the liquid has filled that space, the block above stops falling and spreads,
     * so a source over a drop ends up building a waterfall and a pancake on top of it. A position whose
     * own fall already stands below it is still falling.
     */
    private boolean canFall(Vector3ic pos, Block liquid, int value) {
        Vector3i down = below(pos);
        if (!view.isRelevant(down)) {
            return false;
        }
        Block standing = blockAt(down);
        if (isFlowPassable(standing)) {
            return true;
        }
        return standing.equals(liquid) && flowAt(down) == childValue(value, true);
    }

    /**
     * The block at this position as this pass has it, staged writes included.
     */
    private Block blockAt(Vector3ic pos) {
        Block pending = staged.get(pos);
        return pending != null ? pending : view.getBlock(pos);
    }

    /**
     * The distance at this position as this pass has it, staged writes included.
     */
    private int flowAt(Vector3ic pos) {
        Integer pending = stagedValues.get(pos);
        return pending != null ? pending : view.getFlow(pos);
    }

    // ---------------------------------------------------------------- the queues

    public void enqueueFlow(Vector3ic pos, int value) {
        if (queuedToAdd.size() >= MAX_QUEUE_SIZE) {
            warnFullQueue();
            return;
        }
        Vector3i key = new Vector3i(pos);
        if (queuedToAdd.add(key)) {
            addBuckets[Math.min(Math.max(value, 0), BUCKETS - 1)].addLast(key);
        }
    }

    public void enqueueCheck(Vector3ic pos) {
        if (checkQueue.size() >= MAX_QUEUE_SIZE) {
            warnFullQueue();
            return;
        }
        checkQueue.add(new Vector3i(pos));
    }

    private void warnFullQueue() {
        if (!warnedFullQueue) {
            warnedFullQueue = true;
            logger.warn("Liquid flow queue is full at {} entries; further positions are being dropped."
                    + " Something is feeding it faster than it can drain.", MAX_QUEUE_SIZE);
        }
    }

    public boolean wasSelfWrite(Vector3ic pos) {
        return selfWrites.contains(pos);
    }

    public void clearSelfWrites() {
        selfWrites.clear();
    }

    public boolean isIdle() {
        return queuedToAdd.isEmpty() && checkQueue.isEmpty();
    }

    private Vector3i popLowest() {
        for (Deque<Vector3i> bucket : addBuckets) {
            Vector3i pos = bucket.pollFirst();
            if (pos != null) {
                queuedToAdd.remove(pos);
                return pos;
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- one pass

    /**
     * Drains as much of both queues as the budget allows. Draining happens here and nowhere else: event
     * handlers only ever enqueue, because the event system dispatches synchronously and recursively with no
     * depth guard, so cascading from a handler blows the stack.
     */
    public void update() {
        Budget budget = new Budget();
        processChecks(budget);
        processAdds(budget);
    }

    private void processAdds(Budget budget) {
        int visits = 0;
        while (!budget.isSpent() && visits < MAX_VISITS_PER_PASS) {
            visits++;
            Vector3i q = popLowest();
            if (q == null) {
                break;
            }
            if (!view.isRelevant(q)) {
                continue;
            }
            Block liquid = blockAt(q);
            if (!isFlowLiquid(liquid)) {
                continue;
            }
            int value = flowAt(q);
            spreadTo(below(q), q, liquid, value, true, budget);
            if (!budget.isSpent() && !canFall(q, liquid, value)) {
                for (Side side : Side.horizontalSides()) {
                    spreadTo(side.getAdjacentPos(q, new Vector3i()), q, liquid, value, false, budget);
                    if (budget.isSpent()) {
                        break;
                    }
                }
            }
        }
        flush();
    }

    private void spreadTo(Vector3i target, Vector3i from, Block liquid, int value, boolean downwards,
                          Budget budget) {
        if (!view.isRelevant(target)) {
            return;
        }
        int candidate = childValue(value, downwards);
        if (candidate > liquid.getFlowRange() + 1) {
            // The reach is spent. Nothing is placed and nothing is marked: the frontier lives in the values.
            return;
        }
        Block standing = blockAt(target);
        if (standing.equals(liquid)) {
            int existing = flowAt(target);
            if (existing != 0 && existing > candidate) {
                if (staged.containsKey(target)) {
                    stagedValues.put(new Vector3i(target), candidate);
                } else {
                    view.setFlow(target, candidate);
                }
                enqueueFlow(target, candidate);
            }
            return;
        }
        if (!isFlowPassable(standing)) {
            return;
        }
        if (!budget.stage(liquid, target)) {
            // This pass has written enough. Put the position back and stop: carrying on would pop it
            // straight back off the queue, refuse it again, and spin the main thread forever.
            enqueueFlow(from, value);
            budget.spend();
            return;
        }
        Vector3i key = new Vector3i(target);
        staged.put(key, liquid);
        stagedValues.put(key, candidate);
        enqueueFlow(key, candidate);
    }

    private void flush() {
        if (staged.isEmpty()) {
            return;
        }
        selfWrites.addAll(staged.keySet());
        view.setBlocks(staged);
        for (Map.Entry<Vector3ic, Integer> entry : stagedValues.entrySet()) {
            view.setFlow(entry.getKey(), entry.getValue());
        }
        staged.clear();
        stagedValues.clear();
    }

    // ---------------------------------------------------------------- losing support

    private void processChecks(Budget budget) {
        int checked = 0;
        while (checked < MAX_CHECKS_PER_PASS && !checkQueue.isEmpty() && !budget.isSpent()) {
            Vector3i pos = checkQueue.iterator().next();
            checkQueue.remove(pos);
            checked++;
            if (!view.isRelevant(pos)) {
                continue;
            }
            Block liquid = view.getBlock(pos);
            if (!isFlowLiquid(liquid)) {
                continue;
            }
            int value = view.getFlow(pos);
            if (value == 0) {
                continue; // A source never dries up.
            }
            int best = bestFeederValue(pos, liquid);
            if (best == value) {
                continue;
            }
            if (best < value) {
                view.setFlow(pos, best);
                enqueueFlow(pos, best);
            } else {
                drain(pos, liquid);
            }
        }
    }

    /**
     * The smallest distance this position could take from a neighbour that still feeds it, or
     * {@link #UNREACHED} if nothing does.
     */
    private int bestFeederValue(Vector3ic pos, Block liquid) {
        return bestFeederValue(pos, liquid, null);
    }

    /**
     * As above, but a feeder inside {@code excluded} does not count. Ignoring the feeders that are
     * themselves being reconsidered is what breaks the circular reasoning when a whole column drains.
     */
    private int bestFeederValue(Vector3ic pos, Block liquid, Set<Vector3i> excluded) {
        int best = UNREACHED;
        Vector3i up = above(pos);
        if (feeds(up, liquid, excluded, true)) {
            best = Math.min(best, childValue(view.getFlow(up), true));
        }
        for (Side side : Side.horizontalSides()) {
            Vector3i neighbour = side.getAdjacentPos(pos, new Vector3i());
            if (feeds(neighbour, liquid, excluded, false)) {
                best = Math.min(best, childValue(view.getFlow(neighbour), false));
            }
        }
        return best;
    }

    private boolean feeds(Vector3i candidate, Block liquid, Set<Vector3i> excluded, boolean fromAbove) {
        if (!view.isRelevant(candidate) || !view.getBlock(candidate).equals(liquid)) {
            return false;
        }
        if (excluded != null && excluded.contains(candidate)) {
            return false;
        }
        // A sideways neighbour with somewhere to fall spends its whole flow downwards and feeds nobody.
        return fromAbove || !canFall(candidate, liquid, view.getFlow(candidate));
    }

    /**
     * Dries up everything that was living off {@code root}, and only that.
     * <p>
     * A local test - "has some neighbour a smaller value than mine?" - is wrong here, and quietly so.
     * Falling costs nothing, so a whole column of falling liquid carries the same value; if the queue
     * reaches the bottom of the column before the top, the bottom sees the top still liquid at an equal
     * value, declares itself fed, and survives as an orphan puddle once the top goes. The queue crosses tick
     * boundaries, so that order is the common case rather than a rare one. Hence two passes.
     */
    private void drain(Vector3i root, Block liquid) {
        // Phase A: close over everything whose value could have come from the root. Nothing is written.
        Set<Vector3i> pending = Sets.newLinkedHashSet();
        Deque<Vector3i> stack = new ArrayDeque<>();
        pending.add(root);
        stack.push(root);
        boolean overflowed = false;
        while (!stack.isEmpty()) {
            Vector3i q = stack.pop();
            int value = view.getFlow(q);
            if (!collectDependants(q, liquid, value, below(q), true, pending, stack)) {
                overflowed = true;
                break;
            }
            if (!canFall(q, liquid, value)) {
                for (Side side : Side.horizontalSides()) {
                    if (!collectDependants(q, liquid, value, side.getAdjacentPos(q, new Vector3i()), false,
                            pending, stack)) {
                        overflowed = true;
                        break;
                    }
                }
            }
            if (overflowed) {
                break;
            }
        }

        Map<Vector3ic, Integer> distances = Maps.newHashMap();
        if (!overflowed) {
            // Phase B: recompute the component, seeded only from feeders outside it.
            recompute(pending, liquid, distances);
        }

        // Phase C: apply. Beyond the reach the liquid goes; otherwise its distance is corrected.
        Map<Vector3ic, Block> removals = Maps.newLinkedHashMap();
        int limit = liquid.getFlowRange() + 1;
        for (Vector3i cell : pending) {
            int distance = overflowed ? UNREACHED : distances.getOrDefault(cell, UNREACHED);
            if (distance > limit) {
                removals.put(new Vector3i(cell), view.getAir());
            } else if (distance != view.getFlow(cell)) {
                view.setFlow(cell, distance);
            }
        }
        if (removals.isEmpty()) {
            return;
        }
        selfWrites.addAll(removals.keySet());
        view.setBlocks(removals);
        for (Vector3ic cell : removals.keySet()) {
            view.setFlow(cell, 0);
        }
        // Whatever still stands next to the freed space may now run into it.
        for (Vector3ic cell : removals.keySet()) {
            for (Side side : Side.allSides()) {
                Vector3i neighbour = side.getAdjacentPos(cell, new Vector3i());
                if (view.isRelevant(neighbour) && isFlowLiquid(view.getBlock(neighbour))) {
                    enqueueFlow(neighbour, view.getFlow(neighbour));
                }
            }
        }
        if (overflowed) {
            logger.debug("Liquid drain at {} outgrew {} cells; cleared the lot and let it flood back.",
                    root, MAX_REMOVAL_COMPONENT);
        }
    }

    private boolean collectDependants(Vector3i from, Block liquid, int fromValue, Vector3i child,
                                      boolean downwards, Set<Vector3i> pending, Deque<Vector3i> stack) {
        if (!view.isRelevant(child) || pending.contains(child) || !view.getBlock(child).equals(liquid)) {
            return true;
        }
        int childFlow = view.getFlow(child);
        if (childFlow == 0) {
            return true; // A source is never up for reconsideration.
        }
        if (childFlow >= childValue(fromValue, downwards)) {
            pending.add(child);
            stack.push(child);
        }
        return pending.size() <= MAX_REMOVAL_COMPONENT;
    }

    /**
     * A nought-one breadth-first search over the component, seeded from the feeders that survive outside it.
     */
    private void recompute(Set<Vector3i> pending, Block liquid, Map<Vector3ic, Integer> distances) {
        Deque<Vector3i> deque = new ArrayDeque<>();
        for (Vector3i cell : pending) {
            int seed = bestFeederValue(cell, liquid, pending);
            if (seed != UNREACHED) {
                distances.put(cell, seed);
                deque.addLast(cell);
            }
        }
        while (!deque.isEmpty()) {
            Vector3i q = deque.pollFirst();
            int value = distances.get(q);
            relax(q, below(q), true, value, pending, distances, deque);
            if (!canFall(q, liquid, value)) {
                for (Side side : Side.horizontalSides()) {
                    relax(q, side.getAdjacentPos(q, new Vector3i()), false, value, pending, distances, deque);
                }
            }
        }
    }

    private void relax(Vector3i from, Vector3i child, boolean downwards, int value, Set<Vector3i> pending,
                       Map<Vector3ic, Integer> distances, Deque<Vector3i> deque) {
        if (!pending.contains(child)) {
            return;
        }
        int candidate = childValue(value, downwards);
        if (candidate < distances.getOrDefault(child, UNREACHED)) {
            distances.put(child, candidate);
            if (candidate == value) {
                deque.addFirst(child);
            } else {
                deque.addLast(child);
            }
        }
    }

    // ---------------------------------------------------------------- the budget

    /**
     * What one pass is allowed to write. The costly unit is the block written, not the cell visited: each
     * write dirties up to eight chunks for re-meshing and queues a change for four batch propagators.
     */
    private static final class Budget {
        private int placements;
        private int litPlacements;
        private boolean spent;
        private final Set<Vector3ic> dirtyChunks = Sets.newHashSet();

        boolean isSpent() {
            return spent;
        }

        /**
         * Ends the pass. Called the moment a write is refused, because a refusal that merely skipped one
         * position would be retried without end.
         */
        void spend() {
            spent = true;
        }

        /**
         * Books one placement, or refuses it if this pass has written enough.
         */
        boolean stage(Block liquid, Vector3ic pos) {
            boolean lit = liquid.getLuminance() > 0;
            if (lit) {
                if (litPlacements >= MAX_LIT_PLACEMENTS_PER_PASS) {
                    return false;
                }
            } else if (placements >= MAX_PLACEMENTS_PER_PASS) {
                return false;
            }
            Vector3i chunk = Chunks.toChunkPos(pos, new Vector3i());
            if (!dirtyChunks.contains(chunk) && dirtyChunks.size() >= MAX_DIRTY_CHUNKS_PER_PASS) {
                return false;
            }
            dirtyChunks.add(chunk);
            if (lit) {
                litPlacements++;
            } else {
                placements++;
            }
            return true;
        }
    }
}
