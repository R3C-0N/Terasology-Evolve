// Copyright 2026 The Terasology Foundation
// SPDX-License-Identifier: Apache-2.0
package org.terasology.engine.world.generation.facets.base;

import org.joml.Vector3i;
import org.joml.Vector3ic;
import org.junit.jupiter.api.Test;
import org.terasology.engine.world.block.BlockRegion;
import org.terasology.engine.world.generation.Border3D;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What world generation reads out of the surfaces facet must not depend on how it was written.
 * <p>
 * Providers write every voxel of the region, true for the few that are surfaces and false for all the rest. Flora and
 * trees then walk each column, and draw their random numbers in the order the column hands its values over: two ways of
 * writing the same surfaces have to give the same columns, in the same order, or the world changes.
 */
class VerticallySparseBooleanFacet3DTest {

    private static final BlockRegion REGION = new BlockRegion(0, 0, 0, 7, 15, 7);
    private static final Border3D BORDER = new Border3D(1, 1, 2);

    @Test
    void writingEveryVoxelReadsBackLikeWritingOnlyTheSurfaces() {
        VerticallySparseBooleanFacet3D everyVoxel = new VerticallySparseBooleanFacet3D(REGION, BORDER);
        VerticallySparseBooleanFacet3D surfacesOnly = new VerticallySparseBooleanFacet3D(REGION, BORDER);

        for (Vector3ic pos : everyVoxel.getWorldRegion()) {
            boolean surface = isSurface(pos);
            everyVoxel.setWorld(pos, surface);
            if (surface) {
                surfacesOnly.setWorld(pos, true);
            }
        }

        assertEquals(columns(everyVoxel), columns(surfacesOnly));
    }

    @Test
    void clearingAValueRemovesItAndLeavesTheRest() {
        VerticallySparseBooleanFacet3D facet = new VerticallySparseBooleanFacet3D(REGION, BORDER);
        facet.setWorld(3, 4, 5, true);
        facet.setWorld(3, 9, 5, true);

        facet.setWorld(3, 4, 5, false);

        assertFalse(facet.getWorld(3, 4, 5));
        assertTrue(facet.getWorld(3, 9, 5));
        assertEquals(1, facet.getWorldColumn(3, 5).size());
    }

    @Test
    void clearingAValueNeverWrittenChangesNothing() {
        VerticallySparseBooleanFacet3D facet = new VerticallySparseBooleanFacet3D(REGION, BORDER);
        facet.setWorld(2, 6, 2, true);

        facet.setWorld(2, 7, 2, false);
        facet.setWorld(4, 7, 4, false);

        assertTrue(facet.getWorld(2, 6, 2));
        assertFalse(facet.getWorld(2, 7, 2));
        assertTrue(facet.getWorldColumn(4, 4).isEmpty());
    }

    /** Every column of the facet, in the order it is read, as a flat list. */
    private static List<Integer> columns(VerticallySparseBooleanFacet3D facet) {
        BlockRegion region = facet.getWorldRegion();
        List<Integer> read = new ArrayList<>();
        for (int z = region.minZ(); z <= region.maxZ(); z++) {
            for (int x = region.minX(); x <= region.maxX(); x++) {
                read.add(Integer.MIN_VALUE); // a separator, so two columns never merge into one sequence
                read.addAll(facet.getWorldColumn(x, z));
            }
        }
        return read;
    }

    /** A rolling ground with an overhang, so some columns hold more than one surface. */
    private static boolean isSurface(Vector3ic pos) {
        return solid(pos) && !solid(new Vector3i(pos.x(), pos.y() + 1, pos.z()));
    }

    private static boolean solid(Vector3ic pos) {
        double height = 6 + 3 * Math.sin(pos.x() * 0.7) + 2 * Math.cos(pos.z() * 0.5);
        return height - pos.y() + 3 * Math.sin(pos.x() * 1.1) * Math.sin(pos.y() * 0.9) > 0;
    }
}
