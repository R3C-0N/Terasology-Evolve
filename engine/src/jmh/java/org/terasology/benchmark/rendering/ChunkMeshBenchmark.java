// Copyright 2026 The Terasology Foundation
// SPDX-License-Identifier: Apache-2.0
package org.terasology.benchmark.rendering;

import org.joml.Vector3i;
import org.joml.Vector3ic;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.terasology.engine.rendering.primitives.ChunkMesh;
import org.terasology.engine.rendering.primitives.ChunkTessellator;
import org.terasology.engine.world.block.Block;
import org.terasology.engine.world.block.BlockRegion;
import org.terasology.engine.world.chunks.Chunk;
import org.terasology.engine.world.internal.ChunkViewCoreImpl;

import java.util.concurrent.TimeUnit;
import java.util.zip.CRC32;

/**
 * Tessellation of one chunk, as a mesh worker does it for every chunk that loads or changes.
 * <p>
 * {@code surface} is the common case: a rolling surface with water and flowers through the chunk.
 * {@code caves} is a chunk buried in stone with tunnels, where most blocks are hidden. Blocks are read through
 * the engine's own {@code BlockManagerImpl}, as they are in the game. Run with
 * {@code -prof gc} to see how much each mesh allocates.
 * <p>
 * The set-up prints a CRC of every vertex and index buffer, so two builds can be checked to produce the very
 * same mesh.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 4, time = 3)
@Measurement(iterations = 6, time = 3)
@Fork(value = 1, jvmArgsAppend = {"-Xmx1g", "-XX:MaxDirectMemorySize=512M"})
@State(Scope.Benchmark)
public class ChunkMeshBenchmark {

    @Param({"surface", "caves"})
    public String terrain;

    private final ChunkTessellator tessellator = new ChunkTessellator();
    private final BlockRegion region = new BlockRegion(new Vector3i()).expand(1, 1, 1);
    private final Vector3ic offset = new Vector3i(1, 1, 1);
    private Chunk[] chunks;
    private Block air;

    @Setup(Level.Trial)
    public void setUp() {
        Block[] blocks = BenchWorld.blocks();
        air = blocks[0];
        boolean caves = "caves".equals(terrain);
        chunks = BenchWorld.neighbourhood(blocks, BenchWorld.engineManager(blocks), caves ? 64 : 0, caves);
        System.out.println("[mesh-checksum] " + terrain + " " + checksum(tessellate()));
    }

    @Benchmark
    public ChunkMesh tessellate() {
        // The world hands out a fresh view per chunk, and the water depth cache is keyed on it.
        return tessellator.generateMesh(new ChunkViewCoreImpl(chunks, region, offset, air));
    }

    private static String checksum(ChunkMesh mesh) {
        CRC32 crc = new CRC32();
        StringBuilder vertices = new StringBuilder();
        for (ChunkMesh.RenderType type : ChunkMesh.RenderType.values()) {
            ChunkMesh.VertexElements elements = mesh.getVertexElements(type);
            elements.buffer.writeBuffer(crc::update);
            elements.indices.writeBuffer(crc::update);
            vertices.append(type).append('=').append(elements.vertexCount).append(' ');
        }
        return vertices + "crc=" + Long.toHexString(crc.getValue());
    }
}
