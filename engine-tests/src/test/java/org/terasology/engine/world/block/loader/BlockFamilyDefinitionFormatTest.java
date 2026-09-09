// Copyright 2021 The Terasology Foundation
// SPDX-License-Identifier: Apache-2.0
package org.terasology.engine.world.block.loader;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.terasology.engine.TerasologyTestingEnvironment;
import org.terasology.engine.registry.CoreRegistry;
import org.terasology.engine.world.block.BlockPart;
import org.terasology.engine.world.block.tiles.BlockTile;
import org.terasology.gestalt.assets.management.AssetManager;

import java.util.EnumMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Covers the tile names a block definition picks up without declaring them - see
 * {@link BlockFamilyDefinitionFormat}.
 */
@Tag("TteTest")
public class BlockFamilyDefinitionFormatTest extends TerasologyTestingEnvironment {

    private AssetManager assetManager;

    @BeforeEach
    public void setup() throws Exception {
        super.setup();
        this.assetManager = CoreRegistry.get(AssetManager.class);
    }

    private EnumMap<BlockPart, BlockTile> tilesOf(String urn) {
        return assetManager.getAsset(urn, BlockFamilyDefinition.class).orElseThrow()
                .getData().getBaseSection().getBlockTiles();
    }

    private void assertTile(EnumMap<BlockPart, BlockTile> tiles, BlockPart part, String expectedUrn) {
        BlockTile tile = tiles.get(part);
        assertNotNull(tile, "expected a tile on " + part);
        assertEquals(expectedUrn, tile.getUrn().toString(), "tile on " + part);
    }

    /**
     * Pins the whole precedence lattice at once: {@code Front} claims the front before the coarser
     * {@code Sides} reaches it, {@code Top}/{@code Bottom} coexist with {@code Sides}, and the
     * same-named tile backstops only what is left - here the centre.
     */
    @Test
    public void inferredFacesFromSuffixedTiles() {
        EnumMap<BlockPart, BlockTile> tiles = tilesOf("unittest:inferAll");

        assertTile(tiles, BlockPart.TOP, "unittest:inferAllTop");
        assertTile(tiles, BlockPart.BOTTOM, "unittest:inferAllBottom");
        assertTile(tiles, BlockPart.FRONT, "unittest:inferAllFront");
        assertTile(tiles, BlockPart.BACK, "unittest:inferAllSides");
        assertTile(tiles, BlockPart.LEFT, "unittest:inferAllSides");
        assertTile(tiles, BlockPart.RIGHT, "unittest:inferAllSides");
        assertTile(tiles, BlockPart.CENTER, "unittest:inferAll");
    }

    /** A suffix that has no tile leaves its face on the same-named tile. */
    @Test
    public void missingSuffixFallsBackToGenericTile() {
        EnumMap<BlockPart, BlockTile> tiles = tilesOf("unittest:inferPartial");

        assertTile(tiles, BlockPart.TOP, "unittest:inferPartialTop");
        for (BlockPart part : BlockPart.allParts()) {
            if (part != BlockPart.TOP) {
                assertTile(tiles, part, "unittest:inferPartial");
            }
        }
    }

    /**
     * The regression guard for the ordering inside {@code applyDefaults}. An explicit {@code tiles}
     * entry must beat a tile the name convention would otherwise have found.
     */
    @Test
    public void explicitTileEntryBeatsInference() {
        EnumMap<BlockPart, BlockTile> tiles = tilesOf("unittest:inferExplicit");

        assertTile(tiles, BlockPart.TOP, "unittest:inferPartial");
        assertTile(tiles, BlockPart.BOTTOM, "unittest:inferExplicit");
    }

    @Test
    public void coarseSuffixFillsTopAndBottom() {
        EnumMap<BlockPart, BlockTile> tiles = tilesOf("unittest:inferCoarse");

        assertTile(tiles, BlockPart.TOP, "unittest:inferCoarseTopBottom");
        assertTile(tiles, BlockPart.BOTTOM, "unittest:inferCoarseTopBottom");
        assertTile(tiles, BlockPart.LEFT, "unittest:inferCoarse");
    }

    /** {@code Sides} is the only accepted spelling; the singular is warned about, never used. */
    @Test
    public void singularSideSuffixIsRejected() {
        EnumMap<BlockPart, BlockTile> tiles = tilesOf("unittest:inferSingular");

        for (BlockPart part : BlockPart.allHorizontalParts()) {
            assertTile(tiles, part, "unittest:inferSingular");
        }
    }

    /**
     * A template is never probed. Were it, {@code basedOn} would copy the resolved face into every
     * child, and the child's own tile could no longer fill that hole.
     */
    @Test
    public void templatesAreNotInferred() {
        EnumMap<BlockPart, BlockTile> tiles = tilesOf("unittest:inferTemplateHost");

        for (BlockPart part : BlockPart.allParts()) {
            assertTile(tiles, part, "unittest:inferTemplateHost");
        }
    }

    /** A definition with no holes left is not probed at all. */
    @Test
    public void explicitAllTileSuppressesInference() {
        EnumMap<BlockPart, BlockTile> tiles = tilesOf("unittest:inferNone");

        for (BlockPart part : BlockPart.allParts()) {
            assertTile(tiles, part, "unittest:inferNone");
        }
    }

    /**
     * {@code Normal}, {@code Height} and {@code Gloss} belong to the supplementary maps of
     * {@link org.terasology.engine.world.block.tiles.WorldAtlasImpl}. Binding one as a colour face
     * would corrupt the block in silence, so no face suffix may ever collide with them.
     */
    @Test
    public void supplementaryMapSuffixesAreNeverFaces() {
        EnumMap<BlockPart, BlockTile> tiles = tilesOf("unittest:inferMaps");

        for (BlockPart part : BlockPart.allParts()) {
            assertTile(tiles, part, "unittest:inferMaps");
        }
    }
}
