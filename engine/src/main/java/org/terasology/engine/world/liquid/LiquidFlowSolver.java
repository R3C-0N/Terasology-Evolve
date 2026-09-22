// Copyright 2021 The Terasology Foundation
// SPDX-License-Identifier: Apache-2.0

package org.terasology.engine.world.liquid;

import com.google.common.collect.Lists;
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
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * Works out where a liquid runs to, and where it drains back from.
 * <p>
 * What each position holds is the number of sideways steps taken <em>since the last fall</em>: stepping
 * sideways costs one, falling hands the whole reach back, and rising is impossible. A source carries
 * nought, and because an untouched per-block data field reads nought everywhere, every block of liquid the
 * world generator laid down is a source without a single write.
 * <p>
 * The distance stored is one more than that count. The offset is what stops the sea copying itself
 * downwards forever: without it the block under a source would inherit nought, become a source in its turn,
 * and never dry up.
 * <p>
 * Because a fall resets the count, the reach no longer bounds how far a liquid travels - only how far it
 * travels on the level. Down a slope of single steps it runs as long as the slope does, which is the point:
 * lava that stops dead at the lip of a step is not lava.
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

    /**
     * How long a liquid dwells on a block before running on, per point of viscosity.
     * <p>
     * Water declares four and so takes 480 ms a block, near four seconds to run its eight; lava declares ten
     * and takes a second and a fifth, so it crawls where water runs. The pace is read off the block and
     * never off a constant here, so a module that adds tar gets a tar that creeps without touching this
     * class. A liquid declaring no viscosity at all runs as fast as the budget allows, which is what every
     * liquid did before there was a clock in here.
     */
    public static final int FLOW_DELAY_PER_VISCOSITY_MS = 120;

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

    /**
     * Positions that have somewhere to go but have not yet dwelt long enough to go there, soonest first.
     * <p>
     * The buckets are untouched by this: a position waits here, then moves there, and the ordering that
     * makes the field correct - the smallest distance out first - still holds over everything that is due.
     * The clock decides what is admitted, never what comes out.
     * <p>
     * Two liquids of different thickness share this queue without interfering, because each entry carries
     * its own absolute moment: water due in half a second and lava due in a second simply interleave.
     */
    private final PriorityQueue<Waiting> waiting = new PriorityQueue<>(Comparator.comparingLong(w -> w.dueAt));

    /**
     * Cells a drain has condemned and the moment each is to go, soonest first, deepest first among equals.
     * <p>
     * The drain works out the whole truth in one pass - which is what keeps a column from leaving an orphan
     * puddle behind - and then lets the answer out a ring at a time, so a tide is seen to go out rather than
     * found already gone. A falling column carries one distance from top to bottom, so without the tiebreak
     * on height it would vanish all at once instead of emptying from the top.
     */
    private final PriorityQueue<Removal> removalSchedule = new PriorityQueue<>(
            Comparator.<Removal>comparingLong(r -> r.dueAt).thenComparing(r -> -r.pos.y()));

    /**
     * The same condemned cells, by position. Two things need this and not merely the queue: a second drain
     * must not schedule a cell twice, and a cell on its way out must not be counted as feeding the one
     * behind it.
     */
    private final Set<Vector3i> condemned = Sets.newHashSet();

    private final LongSupplier clock;

    private boolean warnedFullQueue;

    public LiquidFlowSolver(LiquidWorldView view) {
        this(view, System::currentTimeMillis);
    }

    /**
     * @param clock the clock this solver paces itself by, in milliseconds. Injected rather than fetched from
     *         a registry, because the solver is driven by two hash maps in its tests and would stop being
     *         testable the moment it reached into the engine to ask the time. The no-argument constructor
     *         hands it real time rather than a stopped clock: a solver built with a stopped clock would
     *         simply never flow, and would do it silently.
     */
    @SuppressWarnings("unchecked")
    public LiquidFlowSolver(LiquidWorldView view, LongSupplier clock) {
        this.view = view;
        this.clock = clock;
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

    /**
     * The distance a neighbour takes from this one.
     * <p>
     * A fall does not merely cost nothing, it hands the whole reach back: whatever the liquid had spent
     * getting to the lip, it lands with a clean slate and runs its full range again. Keeping the spent
     * distance instead made a liquid that had already walked its range freeze on the edge of a one block
     * step, close enough to fall and unable to, because reaching the drop needed one sideways move it could
     * no longer afford. On broken ground that stopped almost everything.
     */
    private static int childValue(int value, boolean downwards) {
        return downwards ? 1 : Math.max(1, value) + 1;
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
     * own fall already stands below it - that is, a column of the same liquid freshly reset to one - is
     * still falling.
     */
    private boolean canFall(Vector3ic pos, Block liquid) {
        Vector3i down = below(pos);
        if (!view.isRelevant(down)) {
            return false;
        }
        Block standing = blockAt(down);
        if (isFlowPassable(standing)) {
            return true;
        }
        return standing.equals(liquid) && flowAt(down) == childValue(0, true);
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

    /**
     * The dwell this liquid owes on one block.
     */
    public static long flowDelayMillis(Block liquid) {
        return (long) FLOW_DELAY_PER_VISCOSITY_MS * liquid.getViscosity();
    }

    public void enqueueFlow(Vector3ic pos, int value) {
        enqueueFlow(pos, value, blockAt(pos));
    }

    /**
     * Queues a position to spread once it has dwelt as long as its liquid is thick.
     * <p>
     * Membership is {@link #queuedToAdd} whether a position is waiting or ready, so the waiting room and the
     * buckets are disjoint and their union is that set. That is what keeps {@link #isIdle()} honest and the
     * queue cap meaningful, and it makes "are the buckets empty" a size comparison rather than a scan.
     * <p>
     * There is no decrease-key here and none is needed, which is worth stating because the absence looks
     * like an oversight: the dwell is a property of the block, and a position holds one block, so a second
     * queueing can only ever land later than the one already booked. Ignoring it is exactly right.
     */
    private void enqueueFlow(Vector3ic pos, int value, Block liquid) {
        if (queuedToAdd.size() >= MAX_QUEUE_SIZE) {
            warnFullQueue();
            return;
        }
        Vector3i key = new Vector3i(pos);
        if (!queuedToAdd.add(key)) {
            return;
        }
        long dwell = flowDelayMillis(liquid);
        if (dwell <= 0) {
            addBuckets[bucketOf(value)].addLast(key);
        } else {
            waiting.add(new Waiting(key, clock.getAsLong() + dwell, value));
        }
    }

    /**
     * Puts a position straight back among the ready, owing no fresh dwell.
     * <p>
     * A write the budget refused is the solver's own doing and not the liquid's thickness. Charging another
     * block's worth of waiting for it would make a liquid run slower on a busy frame than on a quiet one,
     * which is a pace nobody asked for and nobody could predict.
     */
    private void enqueueReady(Vector3ic pos, int value) {
        if (queuedToAdd.size() >= MAX_QUEUE_SIZE) {
            warnFullQueue();
            return;
        }
        Vector3i key = new Vector3i(pos);
        if (queuedToAdd.add(key)) {
            addBuckets[bucketOf(value)].addLast(key);
        }
    }

    private static int bucketOf(int value) {
        return Math.min(Math.max(value, 0), BUCKETS - 1);
    }

    /**
     * Moves everything whose moment has come out of the waiting room and into the buckets.
     */
    private void admitDue(long now) {
        Waiting head;
        while ((head = waiting.peek()) != null && head.dueAt <= now) {
            waiting.poll();
            addBuckets[bucketOf(head.value)].addLast(head.pos);
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

    /**
     * Whether there is nothing left to do at all, waiting included. This is what a test settles against.
     */
    public boolean isIdle() {
        return queuedToAdd.isEmpty() && checkQueue.isEmpty() && condemned.isEmpty();
    }

    /**
     * Whether there is anything to do at this very moment.
     * <p>
     * The driving system asks this rather than {@link #isIdle()}, because a thick liquid spends most of its
     * life waiting: lava dwells a second and a fifth on every block it takes, and a second and a fifth of
     * monitored, empty passes is a second and a fifth of frames paid for nothing.
     */
    public boolean hasWorkDue() {
        if (!checkQueue.isEmpty()) {
            return true;
        }
        if (queuedToAdd.size() > waiting.size()) {
            return true; // Something sits in the buckets, and a bucket holds only what is due.
        }
        long now = clock.getAsLong();
        Waiting head = waiting.peek();
        if (head != null && head.dueAt <= now) {
            return true;
        }
        Removal due = removalSchedule.peek();
        return due != null && due.dueAt <= now;
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
        // Read once. Two readings in one pass buy nothing and make a test's outcome depend on how long the
        // pass took.
        long now = clock.getAsLong();
        Budget budget = new Budget();
        processChecks(budget);
        applyDueRemovals(now, budget);
        admitDue(now);
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
            if (condemned.contains(q)) {
                // A cell on its way out feeds nothing, least of all the hole it is about to leave behind.
                // This is what makes every spread below start from a cell that is certainly alive, and it is
                // the pair of the reprieve in spreadTo: together they let water coming back overtake a tide
                // still going out, instead of trailing one deadline behind it and being dug through.
                continue;
            }
            Block liquid = blockAt(q);
            if (!isFlowLiquid(liquid)) {
                continue;
            }
            int value = flowAt(q);
            spreadTo(below(q), q, liquid, value, true, budget);
            if (!budget.isSpent() && !canFall(q, liquid)) {
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
                // No reprieve is granted here, and that is measured rather than assumed: letting a smaller
                // distance strike a cell off the condemned list early was worth four blocks out of sixty on
                // a pool refilling mid-ebb, because the water coming back travels at its own pace anyway and
                // arrives about when the deadline does. The re-check in applyDueRemovals is what actually
                // saves a cell that is fed again, and it is enough.
                if (staged.containsKey(target)) {
                    stagedValues.put(new Vector3i(target), candidate);
                } else {
                    view.setFlow(target, candidate);
                }
                enqueueFlow(target, candidate, liquid);
            }
            return;
        }
        if (!isFlowPassable(standing)) {
            return;
        }
        if (!budget.stage(liquid, target)) {
            // This pass has written enough. Put the position back and stop: carrying on would pop it
            // straight back off the queue, refuse it again, and spin the main thread forever.
            enqueueReady(from, value);
            budget.spend();
            return;
        }
        Vector3i key = new Vector3i(target);
        staged.put(key, liquid);
        stagedValues.put(key, candidate);
        enqueueFlow(key, candidate, liquid);
    }

    /**
     * Writes the pass's decisions out: the distances first, then the blocks.
     * <p>
     * The order is the whole point, and it is not tidiness. A chunk is snapshotted for saving from another
     * thread, and the snapshot takes the block array and the data arrays one after the other. Put the blocks
     * down first and there is a window - as long as a full synchronous dispatch of {@code OnChangedBlock} for
     * the whole batch - in which the chunk holds living liquid whose distance is still nought. A snapshot
     * taken there writes that to disk, and nought means source: the flow comes back from the save as a
     * spring that never dries up, a whole batch of it at a time. Copy-on-write hands the live array the right
     * value a moment later, so nothing looks wrong until the world is reloaded.
     * <p>
     * Writing the distance first tears the other way, into a value standing over air, and that is harmless:
     * nothing reads the field except through a liquid, and the mesher checks the block before the distance.
     * It is also what {@link org.terasology.engine.world.chunks.internal.ChunkImpl#setBlock} asks for in so
     * many words - it does not clear the extra data, and leaves initialising it to whoever placed the block.
     */
    private void flush() {
        if (staged.isEmpty()) {
            return;
        }
        for (Map.Entry<Vector3ic, Integer> entry : stagedValues.entrySet()) {
            view.setFlow(entry.getKey(), entry.getValue());
        }
        selfWrites.addAll(staged.keySet());
        view.setBlocks(staged);
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
            if (condemned.contains(pos)) {
                // Already judged, and waiting its turn to go. Looking again here would look with no
                // exclusion set at all, so it would find a neighbour that is itself on its way out, call
                // itself fed by it, and both would survive each other - the very circular reasoning the
                // drain's three phases exist to break. A condemned cell is reprieved by being fed afresh,
                // never by being asked a second time.
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
        if (condemned.contains(candidate)) {
            // A cell already sentenced feeds nothing, whoever is asking and whichever drain sentenced it.
            // Taking a source away sets off one drain per neighbour, one after another, and each used to
            // see the cells the previous ones had condemned as perfectly good feeders - because they are
            // still standing, now that the taking away waits its turn. Whole rings were reprieved by water
            // that was itself on its way out.
            return false;
        }
        // A sideways neighbour with somewhere to fall spends its whole flow downwards and feeds nobody.
        return fromAbove || !canFall(candidate, liquid);
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
            if (!canFall(q, liquid)) {
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

        // Phase C: apply. Beyond the reach the liquid is condemned; otherwise its distance is corrected.
        //
        // Only the condemning happens now. The whole truth is worked out in one pass - that is what keeps a
        // column from leaving an orphan puddle behind, and it is exactly what the drain's three phases are
        // for - but the taking away is let out a ring at a time, so a tide is seen to go out rather than
        // found already gone.
        //
        // The rank is the distance already stored, not the one phase B recomputed: with the source gone
        // every condemned cell recomputes to UNREACHED, whereas the stored value is literally how many
        // steps from the source this cell is. The far edge carries the largest, so it goes first, and the
        // pool empties inwards over the same time it took to fill.
        List<Vector3i> doomed = Lists.newArrayList();
        int limit = liquid.getFlowRange() + 1;
        int furthest = 0;
        for (Vector3i cell : pending) {
            int distance = overflowed ? UNREACHED : distances.getOrDefault(cell, UNREACHED);
            if (distance > limit) {
                doomed.add(cell);
                furthest = Math.max(furthest, view.getFlow(cell));
            } else if (distance != view.getFlow(cell)) {
                // A distance is not something anyone can see, so correcting one waits for nothing.
                view.setFlow(cell, distance);
            }
        }
        long now = clock.getAsLong();
        long step = flowDelayMillis(liquid);
        for (Vector3i cell : doomed) {
            long dueAt = now + (furthest - view.getFlow(cell)) * step;
            if (condemned.add(new Vector3i(cell))) {
                removalSchedule.add(new Removal(new Vector3i(cell), liquid, dueAt));
            }
        }
        if (overflowed) {
            logger.debug("Liquid drain at {} outgrew {} cells; cleared the lot and let it flood back.",
                    root, MAX_REMOVAL_COMPONENT);
        }
    }

    /**
     * Takes away the condemned cells whose moment has come.
     * <p>
     * The world moves while a tide goes out - a source put back, a space filled in - so what was condemned a
     * moment ago is only taken away if it is still unfed now. That is why nothing here hunts down and
     * cancels a schedule when a block changes: the schedule is an intention, and this check is the truth.
     * <p>
     * Feeders still condemned do not count. Without that the bottom of a draining column would see the top -
     * same liquid, same distance, since falling costs nothing - declare itself fed and survive the top's
     * going, as an orphan puddle. It is phase B's reasoning, held open across passes now that the removals
     * are spread across passes.
     */
    private void applyDueRemovals(long now, Budget budget) {
        Map<Vector3ic, Block> removals = Maps.newLinkedHashMap();
        Removal head;
        while ((head = removalSchedule.peek()) != null && head.dueAt <= now) {
            if (!budget.stage(head.liquid, head.pos)) {
                break; // Nothing is dropped: it keeps its place and goes next pass.
            }
            removalSchedule.poll();
            if (!condemned.contains(head.pos)) {
                continue; // Reprieved while it waited, and this entry is the husk it left behind.
            }
            if (!view.isRelevant(head.pos) || !view.getBlock(head.pos).equals(head.liquid)
                    || view.getFlow(head.pos) == 0) {
                condemned.remove(head.pos);
                continue;
            }
            int best = bestFeederValue(head.pos, head.liquid, condemned);
            if (best <= head.liquid.getFlowRange() + 1) {
                condemned.remove(head.pos);
                view.setFlow(head.pos, best);
                enqueueFlow(head.pos, best, head.liquid);
                continue; // Fed again, and so reprieved.
            }
            // Stays condemned until the blocks actually land. The batch is written in one go at the end, so
            // a cell struck off here would still be standing when the next one asks who feeds it - and one
            // doomed cell would reprieve the next, all the way back along the column.
            removals.put(new Vector3i(head.pos), view.getAir());
        }
        if (removals.isEmpty()) {
            return;
        }
        // Here the blocks go first, and unlike in flush() that is the safe order: the tear this leaves is a
        // stale distance standing over air, which nothing reads. Turning it round to match flush() would
        // leave living liquid at nought instead - a source, saved as such the moment a snapshot caught it.
        // The two orders look inconsistent and are both deliberate.
        selfWrites.addAll(removals.keySet());
        view.setBlocks(removals);
        for (Vector3ic cell : removals.keySet()) {
            view.setFlow(cell, 0);
            condemned.remove(cell);
        }
        // Whatever still stands next to the freed space may now run into it - but not a cell on its way out,
        // which would run straight back in and the tide would never go out at all.
        //
        // And it is asked both questions, not one. Spreading is the hopeful half; the other half is whether
        // the cell that just vanished was the only thing feeding this one. Asking only the first is how a
        // pool at the foot of a waterfall outlives the waterfall: the fall drains, the pool is invited to
        // run into the space it left, and nobody ever puts it to the question - so it stands there for good,
        // carrying a perfectly good distance to a source that is no longer there. A source is not asked: it
        // does not dry up, and that is what keeps a generated sea free.
        for (Vector3ic cell : removals.keySet()) {
            for (Side side : Side.allSides()) {
                Vector3i neighbour = side.getAdjacentPos(cell, new Vector3i());
                if (view.isRelevant(neighbour) && !condemned.contains(neighbour)
                        && isFlowLiquid(view.getBlock(neighbour))) {
                    enqueueFlow(neighbour, view.getFlow(neighbour));
                    if (view.getFlow(neighbour) != 0) {
                        enqueueCheck(neighbour);
                    }
                }
            }
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
            if (!canFall(q, liquid)) {
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
    /**
     * A position waiting out its dwell before it may run. The distance is only the bucket it will land in
     * once due; what it actually spreads is read back from the world when its turn comes.
     */
    private static final class Waiting {
        private final Vector3i pos;
        private final long dueAt;
        private final int value;

        Waiting(Vector3i pos, long dueAt, int value) {
            this.pos = pos;
            this.dueAt = dueAt;
            this.value = value;
        }
    }

    /**
     * A cell a drain has condemned, and the moment it is to go.
     */
    private static final class Removal {
        private final Vector3i pos;
        private final Block liquid;
        private final long dueAt;

        Removal(Vector3i pos, Block liquid, long dueAt) {
            this.pos = pos;
            this.liquid = liquid;
            this.dueAt = dueAt;
        }
    }

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
