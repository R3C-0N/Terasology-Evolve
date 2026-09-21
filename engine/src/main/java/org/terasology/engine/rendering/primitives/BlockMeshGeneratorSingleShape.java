// Copyright 2021 The Terasology Foundation
// SPDX-License-Identifier: Apache-2.0
package org.terasology.engine.rendering.primitives;

import org.joml.Vector3ic;
import org.terasology.engine.math.Side;
import org.terasology.engine.world.ChunkView;
import org.terasology.engine.world.block.Block;
import org.terasology.engine.world.block.BlockAppearance;
import org.terasology.engine.world.block.BlockManager;
import org.terasology.engine.world.block.BlockPart;
import org.terasology.engine.world.block.shapes.BlockMeshPart;
import org.terasology.gestalt.assets.ResourceUrn;
import org.terasology.nui.Color;
import org.terasology.nui.Colorc;

import java.util.List;

public class BlockMeshGeneratorSingleShape extends BlockMeshShapeGenerator {
    private final Block block;
    private final ResourceUrn baseUrn = new ResourceUrn("engine", "blockmesh");

    public BlockMeshGeneratorSingleShape(Block block) {
        this.block = block;
    }

    @Override
    public Block getBlock() {
        return block;
    }

    @Override
    public ResourceUrn getBaseUrn() {
        return this.baseUrn;
    }


    @Override
    public void generateChunkMesh(ChunkView view, ChunkMesh chunkMesh, int x, int y, int z) {
        final BlockAppearance blockAppearance = block.getPrimaryAppearance();
        if (!blockAppearance.hasAppearance()) {
            // perf: Skip mesh generation for blocks without appearance, e.g., air blocks.
            return;
        }

        final ChunkMesh.RenderType renderType = getRenderType(block);
        final ChunkVertexFlag vertexFlag = getChunkVertexFlag(view, x, y, z, block);
        boolean isRendered = false;

        // perf: this runs for every block of every chunk, and most of them are buried. Nothing is allocated until a
        // face turns out to be visible, and the block above a liquid is read once, when first needed.
        Color colorCache = null;
        Block topBlock = null;
        // The four corner heights of a liquid surface, read once for the block and shared by all of its faces, so
        // that the top and the sides that meet it cannot disagree. Null while unread, and still null afterwards
        // wherever the surface does not slope.
        float[] corners = null;
        boolean cornersRead = false;

        List<Side> sides = Side.allSides();
        for (int i = 0; i < sides.size(); i++) {
            final Side side = sides.get(i);
            Vector3ic sideOffset = side.direction();
            final Block adjacent = view.getBlock(x + sideOffset.x(), y + sideOffset.y(), z + sideOffset.z());
            if (isSideVisibleForBlockTypes(adjacent, block, side)) {
                isRendered = true;

                BlockMeshPart blockMeshPart = blockAppearance.getPart(BlockPart.fromSide(side));

                // If the selfBlock isn't lowered, some more faces may have to be drawn
                boolean surfaceOfLiquid = false;
                if (block.isLiquid()) {
                    if (topBlock == null) {
                        topBlock = view.getBlock(x, y + 1, z);
                    }
                    surfaceOfLiquid = !topBlock.isLiquid();
                    // Draw horizontal sides if visible from below
                    if (topBlock.isLiquid() && Side.horizontalSides().contains(side)) {
                        final Vector3ic offset = side.direction();
                        final Block adjacentAbove = view.getBlock(x + offset.x(), y + 1, z + offset.z());

                        if (adjacent.isLiquid() && !adjacentAbove.isLiquid()) {
                            blockMeshPart = block.getTopLiquidMesh(side);
                        }
                    } else {
                        if (blockMeshPart != null) {
                            blockMeshPart = block.getLowLiquidMesh(side);
                        }
                    }
                }

                if (blockMeshPart != null) {
                    // TODO: Needs review since the new per-vertex flags introduce a lot of special scenarios - probably a per-side setting?
                    ChunkVertexFlag sideVertexFlag = vertexFlag;
                    if (block.isGrass() && side != Side.TOP && side != Side.BOTTOM) {
                        sideVertexFlag = ChunkVertexFlag.COLOR_MASK;
                    }
                    if (colorCache == null) {
                        colorCache = new Color();
                    }
                    Colorc colorOffset = block.getColorOffset(BlockPart.fromSide(side));
                    Colorc colorSource = block.getColorSource(BlockPart.fromSide(side)).calcColor(view, x, y, z);
                    colorCache.setRed(colorSource.rf() * colorOffset.rf())
                            .setGreen(colorSource.gf() * colorOffset.gf())
                            .setBlue(colorSource.bf() * colorOffset.bf())
                            .setAlpha(colorSource.af() * colorOffset.af());
                    if (surfaceOfLiquid && LiquidSurfaceField.deforms(block)) {
                        if (!cornersRead) {
                            corners = LiquidSurfaceField.corners(view, block, x, y, z);
                            cornersRead = true;
                        }
                        blockMeshPart.appendTo(chunkMesh, view, x, y, z, renderType, colorCache, sideVertexFlag,
                                corners, LiquidSurfaceField.HIGH);
                    } else {
                        blockMeshPart.appendTo(chunkMesh, view, x, y, z, renderType, colorCache, sideVertexFlag);
                    }
                }
            }
        }

        if (isRendered && blockAppearance.getPart(BlockPart.CENTER) != null) {
            if (colorCache == null) {
                colorCache = new Color();
            }
            Colorc colorOffset = block.getColorOffset(BlockPart.CENTER);
            Colorc colorSource = block.getColorSource(BlockPart.CENTER).calcColor(view, x, y, z);
            colorCache.setRed(colorSource.rf() * colorOffset.rf())
                    .setGreen(colorSource.gf() * colorOffset.gf())
                    .setBlue(colorSource.bf() * colorOffset.bf())
                    .setAlpha(colorSource.af() * colorOffset.af());
            blockAppearance.getPart(BlockPart.CENTER).appendTo(chunkMesh, view, x, y, z, renderType, colorCache, vertexFlag);
        }
    }

    private ChunkVertexFlag getChunkVertexFlag(ChunkView view, int x, int y, int z, Block selfBlock) {
        // TODO: Needs review - too much hardcoded special cases and corner cases resulting from this.
        ChunkVertexFlag vertexFlag = ChunkVertexFlag.NORMAL;
        if (selfBlock.isWater()) {
            if (view.getBlock(x, y + 1, z).isWater()) {
                vertexFlag = ChunkVertexFlag.WATER;
            } else {
                vertexFlag = ChunkVertexFlag.WATER_SURFACE;
            }
        } else if (selfBlock.isWaving() && selfBlock.isDoubleSided()) {
            vertexFlag = ChunkVertexFlag.WAVING;
        } else if (selfBlock.isWaving()) {
            vertexFlag = ChunkVertexFlag.WAVING_BLOCK;
        }
        return vertexFlag;
    }

    /**
     * Determine the render process of the block.
     *
     * @return The render process for the block
     */
    private ChunkMesh.RenderType getRenderType(final Block selfBlock) {
        ChunkMesh.RenderType renderType = ChunkMesh.RenderType.TRANSLUCENT;

        if (!selfBlock.isTranslucent()) {
            renderType = ChunkMesh.RenderType.OPAQUE;
        }
        // TODO: Review special case, or alternatively compare uris.
        if (selfBlock.isWater() || selfBlock.isIce()) {
            renderType = ChunkMesh.RenderType.WATER_AND_ICE;
        }
        if (selfBlock.isDoubleSided()) {
            renderType = ChunkMesh.RenderType.BILLBOARD;
        }
        return renderType;
    }


    /**
     * Returns true if the side should be rendered adjacent to the second side provided.
     *
     * @param blockToCheck The block to check
     * @param currentBlock The current block
     * @return True if the side is visible for the given block types
     */
    private boolean isSideVisibleForBlockTypes(Block blockToCheck, Block currentBlock, Side side) {
        // Liquids can be transparent but there should be no visible adjacent faces
        if (currentBlock.isLiquid() && blockToCheck.isLiquid()) {
            return false;
        }

        //TODO: This only fixes the "water under block" issue of the top side not being rendered. (see bug #3889)
        //Note: originally tried .isLiquid() instead of isWater for both checks, but IntelliJ was warning that
        //      !blockToCheck.isWater() is always true, may need further investigation
        if (currentBlock.isWater() && (side == Side.TOP) && !blockToCheck.isWater()) {
            return true;
        }

        if (blockToCheck.getURI().equals(BlockManager.UNLOADED_ID)) {
            return false;
        }

        return currentBlock.isWaving() != blockToCheck.isWaving() || blockToCheck.getMeshGenerator() == null
                || !blockToCheck.isFullSide(side.reverse()) || (!currentBlock.isTranslucent() && blockToCheck.isTranslucent());

    }

}
