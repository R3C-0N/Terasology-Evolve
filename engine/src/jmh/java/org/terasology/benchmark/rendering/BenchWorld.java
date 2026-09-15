// Copyright 2026 The Terasology Foundation
// SPDX-License-Identifier: Apache-2.0
package org.terasology.benchmark.rendering;

import org.joml.Vector2f;
import org.joml.Vector2fc;
import org.joml.Vector3f;
import org.joml.Vector3i;
import org.joml.Vector3ic;
import org.terasology.engine.math.Side;
import org.terasology.engine.rendering.primitives.BlockMeshGeneratorSingleShape;
import org.terasology.engine.world.block.Block;
import org.terasology.engine.world.block.BlockAppearance;
import org.terasology.engine.world.block.BlockManager;
import org.terasology.engine.world.block.BlockPart;
import org.terasology.engine.world.block.BlockUri;
import org.terasology.engine.world.block.family.BlockFamily;
import org.terasology.engine.world.block.family.BlockPlacementData;
import org.terasology.engine.world.block.internal.BlockManagerImpl;
import org.terasology.engine.world.block.shapes.BlockMeshPart;
import org.terasology.engine.world.chunks.Chunk;
import org.terasology.engine.world.chunks.blockdata.ExtraBlockDataManager;
import org.terasology.engine.world.chunks.internal.ChunkImpl;
import org.terasology.engine.world.propagation.light.InternalLightProcessor;
import org.terasology.engine.world.propagation.light.LightMerger;
import org.terasology.gestalt.assets.ResourceUrn;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A small deterministic world for the rendering benchmarks.
 * <p>
 * Six blocks - air, stone, dirt, grass, water and a flower - over a 3x3x3 neighbourhood of chunks, lit and
 * deflated the way the loading pipeline hands them to the mesher. Nothing is read from assets, so the
 * benchmarks run without a module environment.
 */
final class BenchWorld {
    static final int SEA_LEVEL = 30;

    private BenchWorld() {
    }

    static Block[] blocks() {
        Block air = block(0, "engine", "air");
        air.setTranslucent(true);
        air.setShadowCasting(false);
        air.setPrimaryAppearance(new BlockAppearance());

        Block stone = solid(1, "stone");
        Block dirt = solid(2, "dirt");
        Block grass = solid(3, "grass");
        grass.setGrass(true);

        Block water = block(4, "bench", "water");
        water.setLiquid(true);
        water.setWater(true);
        water.setTranslucent(true);
        water.setShadowCasting(false);
        Map<BlockPart, BlockMeshPart> waterParts = new EnumMap<>(BlockPart.class);
        for (Side side : Side.values()) {
            waterParts.put(BlockPart.fromSide(side), face(side));
            water.setLowLiquidMesh(side, face(side));
            water.setTopLiquidMesh(side, face(side));
        }
        water.setPrimaryAppearance(new BlockAppearance(waterParts, atlas()));

        Block flower = block(5, "bench", "flower");
        flower.setDoubleSided(true);
        flower.setTranslucent(true);
        flower.setWaving(true);
        flower.setShadowCasting(false);
        Map<BlockPart, BlockMeshPart> flowerParts = new EnumMap<>(BlockPart.class);
        flowerParts.put(BlockPart.CENTER, cross());
        flower.setPrimaryAppearance(new BlockAppearance(flowerParts, atlas()));

        return new Block[]{air, stone, dirt, grass, water, flower};
    }

    static BlockManager manager(Block[] blocks) {
        return new BlockManager() {
            @Override
            public Map<String, Short> getBlockIdMap() {
                return Collections.emptyMap();
            }

            @Override
            public BlockFamily getBlockFamily(String uri) {
                return null;
            }

            @Override
            public BlockFamily getBlockFamily(BlockUri uri) {
                return null;
            }

            @Override
            public Block getBlock(String uri) {
                return blocks[0];
            }

            @Override
            public Block getBlock(BlockUri uri) {
                return blocks[0];
            }

            @Override
            public Block getBlock(short id) {
                return id >= 0 && id < blocks.length ? blocks[id] : blocks[0];
            }

            @Override
            public Collection<BlockUri> listRegisteredBlockUris() {
                return Collections.emptyList();
            }

            @Override
            public Collection<BlockFamily> listRegisteredBlockFamilies() {
                return Collections.emptyList();
            }

            @Override
            public int getBlockFamilyCount() {
                return 0;
            }

            @Override
            public Collection<Block> listRegisteredBlocks() {
                return Arrays.asList(blocks);
            }
        };
    }

    /**
     * The engine's own block manager, holding the bench blocks.
     * <p>
     * Every block the mesher reads goes through {@link BlockManager#getBlock(short)}, so a stand-in would hide what
     * that lookup costs in the game. The real constructor loads block shapes through the asset manager, though, so
     * the manager is allocated bare and given only the state that registering and looking up blocks touch.
     */
    static BlockManager engineManager(Block[] blocks) {
        try {
            Field unsafeField = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
            unsafeField.setAccessible(true);
            Object unsafe = unsafeField.get(null);
            Method allocateInstance = unsafe.getClass().getMethod("allocateInstance", Class.class);
            BlockManagerImpl manager = (BlockManagerImpl) allocateInstance.invoke(unsafe, BlockManagerImpl.class);

            Constructor<?> emptyState = Class.forName(BlockManagerImpl.class.getName() + "$RegisteredState").getDeclaredConstructor();
            emptyState.setAccessible(true);
            setField(manager, "registeredBlockInfo", new AtomicReference<>(emptyState.newInstance()));
            setField(manager, "lock", new ReentrantLock());
            setField(manager, "listeners", new LinkedHashSet<>());
            setField(manager, "nextId", new AtomicInteger(blocks.length));

            Method registerFamily = BlockManagerImpl.class.getDeclaredMethod("registerFamily", BlockFamily.class);
            registerFamily.setAccessible(true);
            registerFamily.invoke(manager, family(blocks));
            return manager;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Could not build a bare BlockManagerImpl", e);
        }
    }

    private static void setField(Object target, String name, Object value) throws ReflectiveOperationException {
        Field field = BlockManagerImpl.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static BlockFamily family(Block[] blocks) {
        BlockUri uri = new BlockUri(new ResourceUrn("bench", "blocks"));
        return new BlockFamily() {
            @Override
            public BlockUri getURI() {
                return uri;
            }

            @Override
            public String getDisplayName() {
                return "Bench blocks";
            }

            @Override
            public Block getBlockForPlacement(BlockPlacementData data) {
                return blocks[1];
            }

            @Override
            public Block getArchetypeBlock() {
                return blocks[1];
            }

            @Override
            public Block getBlockFor(BlockUri blockUri) {
                for (Block block : blocks) {
                    if (block.getURI().equals(blockUri)) {
                        return block;
                    }
                }
                return null;
            }

            @Override
            public Iterable<Block> getBlocks() {
                return Arrays.asList(blocks);
            }

            @Override
            public Iterable<String> getCategories() {
                return Collections.emptyList();
            }

            @Override
            public boolean hasCategory(String category) {
                return false;
            }

            @Override
            public String toString() {
                return "BenchFamily";
            }
        };
    }

    /**
     * The 3x3x3 chunks around the origin, in the order {@code ChunkViewCoreImpl} indexes them.
     *
     * @param surfaceShift how far up the terrain is moved: 0 puts the surface through the centre chunk, 64 buries
     *     the centre chunk under a full chunk of ground
     * @param caves whether tunnels are carved into the stone
     * @param manager the block manager the chunks read their blocks through
     */
    static Chunk[] neighbourhood(Block[] blocks, BlockManager manager, int surfaceShift, boolean caves) {
        ExtraBlockDataManager extraData = new ExtraBlockDataManager();
        Chunk[] chunks = new Chunk[27];
        for (int cy = -1; cy <= 1; cy++) {
            for (int cz = -1; cz <= 1; cz++) {
                for (int cx = -1; cx <= 1; cx++) {
                    ChunkImpl chunk = new ChunkImpl(new Vector3i(cx, cy, cz), manager, extraData);
                    fill(chunk, blocks, surfaceShift, caves);
                    InternalLightProcessor.generateInternalLighting(chunk);
                    chunk.deflate();
                    chunks[(cx + 1) + 3 * ((cz + 1) + 3 * (cy + 1))] = chunk;
                }
            }
        }
        // Merging sorts the array it is given, so it gets a copy; it lights the centre from its neighbours.
        LightMerger.merge(Arrays.copyOf(chunks, chunks.length));
        for (Chunk chunk : chunks) {
            chunk.markReady();
        }
        return chunks;
    }

    private static void fill(Chunk chunk, Block[] blocks, int surfaceShift, boolean caves) {
        int seaLevel = SEA_LEVEL + surfaceShift;
        for (int x = 0; x < chunk.getChunkSizeX(); x++) {
            for (int z = 0; z < chunk.getChunkSizeZ(); z++) {
                int wx = chunk.getChunkWorldOffsetX() + x;
                int wz = chunk.getChunkWorldOffsetZ() + z;
                int height = height(wx, wz) + surfaceShift;
                for (int y = 0; y < chunk.getChunkSizeY(); y++) {
                    int wy = chunk.getChunkWorldOffsetY() + y;
                    Block block;
                    if (wy > height) {
                        if (wy <= seaLevel) {
                            block = blocks[4];
                        } else if (wy == height + 1 && height > seaLevel && hasFlower(wx, wz)) {
                            block = blocks[5];
                        } else {
                            block = blocks[0];
                        }
                    } else if (wy == height) {
                        block = height >= seaLevel ? blocks[3] : blocks[2];
                    } else if (wy > height - 4) {
                        block = blocks[2];
                    } else if (caves && Math.sin(wx * 0.19) * Math.cos(wz * 0.17) * Math.sin(wy * 0.23) > 0.45) {
                        block = blocks[0];
                    } else {
                        block = blocks[1];
                    }
                    if (block != blocks[0]) {
                        chunk.setBlock(x, y, z, block);
                    }
                }
            }
        }
    }

    private static int height(int wx, int wz) {
        return SEA_LEVEL + 2 + (int) Math.round(7 * Math.sin(wx * 0.13) + 6 * Math.cos(wz * 0.09) + 3 * Math.sin((wx + wz) * 0.21));
    }

    private static boolean hasFlower(int wx, int wz) {
        return Math.floorMod((wx * 73856093) ^ (wz * 19349663), 7) == 0;
    }

    private static Block block(int id, String module, String name) {
        Block block = new Block();
        block.setId((short) id);
        block.setUri(new BlockUri(new ResourceUrn(module, name)));
        block.setMeshGenerator(new BlockMeshGeneratorSingleShape(block));
        return block;
    }

    private static Block solid(int id, String name) {
        Block block = block(id, "bench", name);
        block.setShadowCasting(true);
        block.setAttachmentAllowed(true);
        Map<BlockPart, BlockMeshPart> parts = new EnumMap<>(BlockPart.class);
        for (Side side : Side.values()) {
            block.setFullSide(side, true);
            parts.put(BlockPart.fromSide(side), face(side));
        }
        block.setPrimaryAppearance(new BlockAppearance(parts, atlas()));
        return block;
    }

    private static Map<BlockPart, Vector2fc> atlas() {
        Map<BlockPart, Vector2fc> atlas = new EnumMap<>(BlockPart.class);
        for (BlockPart part : BlockPart.values()) {
            atlas.put(part, new Vector2f());
        }
        return atlas;
    }

    /** One face of the unit cube centred on the block, facing {@code side}. */
    private static BlockMeshPart face(Side side) {
        Vector3ic direction = side.direction();
        Vector3f normal = new Vector3f(direction.x(), direction.y(), direction.z());
        Vector3f a = direction.y() != 0 ? new Vector3f(0.5f, 0, 0) : new Vector3f(0, 0.5f, 0);
        Vector3f b = new Vector3f(normal).cross(a);
        Vector3f centre = new Vector3f(normal).mul(0.5f);
        Vector3f[] vertices = {
                new Vector3f(centre).sub(a).sub(b),
                new Vector3f(centre).add(a).sub(b),
                new Vector3f(centre).add(a).add(b),
                new Vector3f(centre).sub(a).add(b)
        };
        Vector3f[] normals = {normal, normal, normal, normal};
        return new BlockMeshPart(vertices, normals, quadTexCoords(), new int[]{0, 1, 2, 0, 2, 3});
    }

    /** The two crossed quads of a plant. */
    private static BlockMeshPart cross() {
        Vector3f[] vertices = {
                new Vector3f(-0.5f, -0.5f, -0.5f), new Vector3f(0.5f, -0.5f, 0.5f),
                new Vector3f(0.5f, 0.5f, 0.5f), new Vector3f(-0.5f, 0.5f, -0.5f),
                new Vector3f(-0.5f, -0.5f, 0.5f), new Vector3f(0.5f, -0.5f, -0.5f),
                new Vector3f(0.5f, 0.5f, -0.5f), new Vector3f(-0.5f, 0.5f, 0.5f)
        };
        Vector3f first = new Vector3f(0.7071f, 0, -0.7071f);
        Vector3f second = new Vector3f(0.7071f, 0, 0.7071f);
        Vector3f[] normals = {first, first, first, first, second, second, second, second};
        Vector2f[] texCoords = new Vector2f[8];
        Vector2f[] quad = quadTexCoords();
        for (int i = 0; i < texCoords.length; i++) {
            texCoords[i] = quad[i % 4];
        }
        return new BlockMeshPart(vertices, normals, texCoords, new int[]{0, 1, 2, 0, 2, 3, 4, 5, 6, 4, 6, 7});
    }

    private static Vector2f[] quadTexCoords() {
        return new Vector2f[]{new Vector2f(0, 0), new Vector2f(1, 0), new Vector2f(1, 1), new Vector2f(0, 1)};
    }
}
