// Copyright 2021 The Terasology Foundation
// SPDX-License-Identifier: Apache-2.0

package org.terasology.engine.world.liquid;

import org.joml.Vector3i;
import org.terasology.engine.entitySystem.systems.BaseComponentSystem;
import org.terasology.engine.entitySystem.systems.RegisterMode;
import org.terasology.engine.entitySystem.systems.RegisterSystem;
import org.terasology.engine.logic.console.commandSystem.annotations.Command;
import org.terasology.engine.logic.console.commandSystem.annotations.CommandParam;
import org.terasology.engine.logic.permission.PermissionManager;
import org.terasology.engine.math.Side;
import org.terasology.engine.registry.In;
import org.terasology.engine.world.WorldProvider;
import org.terasology.engine.world.block.Block;
import org.terasology.engine.world.chunks.blockdata.ExtraBlockDataManager;

/**
 * Reads back what the flow solver thinks, because a liquid that stops has no way of saying why.
 * <p>
 * A liquid is not targetable, so the crosshair cannot be pointed at one: the position is given outright.
 */
@RegisterSystem(RegisterMode.AUTHORITY)
public class LiquidFlowCommands extends BaseComponentSystem {

    @In
    private WorldProvider worldProvider;
    @In
    private ExtraBlockDataManager extraDataManager;

    private int flowSlot = -1;

    @Override
    public void initialise() {
        flowSlot = extraDataManager.getSlotNumber(LiquidExtraDataSystem.FLOW_FIELD);
    }

    @Command(shortDescription = "Show what the liquid flow solver holds at a position",
            helpText = "Names the block, the distance it has walked from its source - nought meaning a"
                    + " permanent source - how much reach it has left, and what each of its six neighbours"
                    + " is. A liquid that has stopped says here whether it ran out of reach or ran out of"
                    + " room.",
            requiredPermission = PermissionManager.CHEAT_PERMISSION)
    public String liquidFlow(@CommandParam("x") int x, @CommandParam("y") int y, @CommandParam("z") int z) {
        Vector3i pos = new Vector3i(x, y, z);
        if (!worldProvider.isBlockRelevant(pos)) {
            return "That position is not in a loaded chunk.";
        }
        Block block = worldProvider.getBlock(pos);
        StringBuilder out = new StringBuilder();
        out.append(pos).append(" is ").append(block.getURI());
        if (!block.isLiquid()) {
            return out.append(", which is not a liquid.").toString();
        }
        int flow = worldProvider.getExtraData(flowSlot, x, y, z);
        out.append(", flow=").append(flow)
                .append(flow == 0 ? " (a source)" : ", steps taken=" + (flow - 1))
                .append(", range=").append(block.getFlowRange())
                .append(", sideways steps left=").append(Math.max(0, block.getFlowRange() + 1 - flow - 1));
        for (Side side : Side.allSides()) {
            Vector3i neighbour = side.getAdjacentPos(pos, new Vector3i());
            out.append("\n  ").append(side).append(": ");
            if (!worldProvider.isBlockRelevant(neighbour)) {
                out.append("unloaded");
                continue;
            }
            Block other = worldProvider.getBlock(neighbour);
            out.append(other.getURI());
            if (other.isLiquid()) {
                out.append(" flow=").append(worldProvider.getExtraData(flowSlot, neighbour.x, neighbour.y,
                        neighbour.z));
            } else if (LiquidFlowSolver.isFlowPassable(other)) {
                out.append(" (free)");
            }
        }
        return out.toString();
    }
}
