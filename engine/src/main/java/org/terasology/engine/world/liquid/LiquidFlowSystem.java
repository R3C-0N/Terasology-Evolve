// Copyright 2021 The Terasology Foundation
// SPDX-License-Identifier: Apache-2.0

package org.terasology.engine.world.liquid;

import org.joml.Vector3i;
import org.joml.Vector3ic;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.terasology.engine.core.Time;
import org.terasology.engine.entitySystem.entity.EntityRef;
import org.terasology.engine.entitySystem.systems.BaseComponentSystem;
import org.terasology.engine.entitySystem.systems.RegisterMode;
import org.terasology.engine.entitySystem.systems.RegisterSystem;
import org.terasology.engine.entitySystem.systems.UpdateSubscriberSystem;
import org.terasology.engine.math.Side;
import org.terasology.engine.monitoring.PerformanceMonitor;
import org.terasology.engine.registry.In;
import org.terasology.engine.world.OnChangedBlock;
import org.terasology.engine.world.WorldProvider;
import org.terasology.engine.world.block.Block;
import org.terasology.engine.world.block.BlockComponent;
import org.terasology.engine.world.block.BlockManager;
import org.terasology.engine.world.chunks.ChunkProvider;
import org.terasology.engine.world.chunks.blockdata.ExtraBlockDataManager;
import org.terasology.engine.world.chunks.event.BeforeChunkUnload;
import org.terasology.engine.world.chunks.event.OnChunkGenerated;
import org.terasology.engine.world.chunks.event.OnChunkLoaded;
import org.terasology.gestalt.entitysystem.event.ReceiveEvent;

/**
 * Drives {@link LiquidFlowSolver}: feeds it the changes worth reacting to, and gives it a slice of each
 * frame to act on them.
 * <p>
 * The server alone simulates; clients learn of the result through the ordinary block changes.
 */
@RegisterSystem(RegisterMode.AUTHORITY)
public class LiquidFlowSystem extends BaseComponentSystem implements UpdateSubscriberSystem {

    private static final Logger logger = LoggerFactory.getLogger(LiquidFlowSystem.class);

    @In
    private WorldProvider worldProvider;
    @In
    private BlockManager blockManager;
    @In
    private ExtraBlockDataManager extraDataManager;
    @In
    private ChunkProvider chunkProvider;
    @In
    private Time time;

    private LiquidWorldView view;
    private LiquidFlowSolver solver;
    private LiquidSeeder seeder;

    /**
     * Whether a pass is under way. Writing blocks dispatches change events there and then, and this keeps
     * one pass from starting another inside itself.
     */
    private boolean draining;

    @Override
    public void initialise() {
        int slot = extraDataManager.getSlotNumber(LiquidExtraDataSystem.FLOW_FIELD);
        view = new WorldProviderLiquidView(worldProvider, slot, blockManager);
        // The game clock, not the frame's delta: a liquid's pace is a pace in the world's time, so it must
        // not follow the frame rate, and it must stop when the world does.
        solver = new LiquidFlowSolver(view, time::getGameTimeInMs);
        seeder = new LiquidSeeder(chunkProvider, solver, view);
    }

    /**
     * @param delta deliberately unread. What paces the solver is the game clock it was handed, so that a
     *         liquid crosses a block in the same time whatever the frame rate.
     */
    @Override
    public void update(float delta) {
        if (draining || solver == null) {
            return;
        }
        if (seeder.isIdle() && !solver.hasWorkDue()) {
            // A sea at rest costs nothing at all - and neither does one that is merely still thickening.
            // Asking isIdle() here would pay a monitored, empty pass on every frame of the second and a
            // fifth that lava spends dwelling on each block it takes.
            return;
        }
        draining = true;
        PerformanceMonitor.startActivity("Liquid Flow");
        try {
            seeder.update();
            solver.update();
        } finally {
            PerformanceMonitor.endActivity();
            solver.clearSelfWrites();
            draining = false;
        }
    }

    /**
     * Queues, and only queues. The event system dispatches synchronously and recursively with no depth
     * guard, so anything that cascaded from here would run down the stack rather than through the budget.
     */
    @ReceiveEvent(components = BlockComponent.class)
    public void onChangedBlock(OnChangedBlock event, EntityRef entity) {
        if (solver == null) {
            return;
        }
        Vector3ic pos = event.getBlockPosition();
        if (solver.wasSelfWrite(pos)) {
            return;
        }
        Block was = event.getOldType();
        Block now = event.getNewType();

        if (LiquidFlowSolver.isFlowLiquid(was) && !now.isLiquid()) {
            view.setFlow(pos, 0);
        }
        if (LiquidFlowSolver.isFlowLiquid(now) && !now.equals(was)) {
            // A liquid that appeared by some other hand - a bucket, a structure, a command - is a source.
            declareSource(pos, was, now);
            solver.enqueueFlow(pos, 0);
        }

        for (Side side : Side.allSides()) {
            Vector3i neighbour = side.getAdjacentPos(pos, new Vector3i());
            if (!worldProvider.isBlockRelevant(neighbour)) {
                continue;
            }
            if (LiquidFlowSolver.isFlowLiquid(worldProvider.getBlock(neighbour))) {
                int flow = view.getFlow(neighbour);
                solver.enqueueFlow(neighbour, flow);
                if (flow != 0) {
                    solver.enqueueCheck(neighbour);
                }
            }
        }
        if (LiquidFlowSolver.isFlowLiquid(was)) {
            // Whatever was living off this position has just lost a feeder.
            solver.enqueueCheck(new Vector3i(pos.x(), pos.y() - 1, pos.z()));
            for (Side side : Side.horizontalSides()) {
                solver.enqueueCheck(side.getAdjacentPos(pos, new Vector3i()));
            }
        }
    }

    /**
     * Marks a position a permanent source, and complains if it was not entitled to be one.
     * <p>
     * Nought is absorbing: three guards in the solver refuse to reconsider a cell that reads it, because a
     * source must never dry up. So a nought written where a distance stood is not a small mistake that later
     * passes will tidy - it is liquid made immortal, and the only way anyone would ever find out is by
     * noticing a puddle that outlives its source. A position that already carried a distance was, by
     * construction, running: whatever put it back to nought is a bug, and this is where it says so.
     */
    private void declareSource(Vector3ic pos, Block was, Block now) {
        int previous = view.getFlow(pos);
        if (previous != 0) {
            logger.warn("Liquid that had run {} steps at {} is being called a source ({} became {}).",
                    previous, pos, was.getURI(), now.getURI());
        }
        view.setFlow(pos, 0);
    }

    @ReceiveEvent
    public void onChunkGenerated(OnChunkGenerated event, EntityRef world) {
        if (seeder != null) {
            seeder.seedFirst(event.getChunkPos());
        }
    }

    @ReceiveEvent
    public void onChunkLoaded(OnChunkLoaded event, EntityRef world) {
        if (seeder != null) {
            seeder.seedLast(event.getChunkPos());
        }
    }

    @ReceiveEvent
    public void beforeChunkUnload(BeforeChunkUnload event, EntityRef world) {
        if (seeder != null) {
            seeder.forget(event.getChunkPos());
        }
    }
}
