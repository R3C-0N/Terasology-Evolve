// Copyright 2026 The Terasology Foundation
// SPDX-License-Identifier: Apache-2.0
package org.terasology.benchmark.generation;

import org.joml.Vector3ic;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.terasology.engine.world.block.BlockRegion;
import org.terasology.engine.world.generation.Border3D;
import org.terasology.engine.world.generation.facets.SurfacesFacet;

import java.util.concurrent.TimeUnit;

/**
 * The surfaces facet as world generation uses it for one chunk.
 * <p>
 * {@code DensityNoiseProvider} sets every voxel of the facet: true where solid ground meets air above it, false
 * everywhere else, which is nearly everywhere. Flora, trees and {@code SurfacesFacet} itself then read each column.
 * The density is precomputed here, so the benchmark measures the facet and nothing else.
 * <p>
 * The set-up prints a hash of every column's contents in the order it iterates, so two builds can be checked to hand
 * world generation the same surfaces in the same order: flora draws its random numbers in that order.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 4, time = 2)
@Measurement(iterations = 6, time = 2)
@Fork(value = 1, jvmArgsAppend = "-Xmx1g")
@State(Scope.Benchmark)
public class SurfacesFacetBenchmark {
    private static final BlockRegion CHUNK = new BlockRegion(0, 0, 0, 31, 63, 31);
    private static final Border3D BORDER = new Border3D(1, 1, 8);

    private boolean[] surface;

    @Setup
    public void setUp() {
        SurfacesFacet facet = new SurfacesFacet(CHUNK, BORDER);
        BlockRegion region = facet.getWorldRegion();
        surface = new boolean[region.volume()];
        int i = 0;
        for (Vector3ic pos : region) {
            surface[i++] = pos.y() < region.maxY() && solid(pos.x(), pos.y(), pos.z()) && !solid(pos.x(), pos.y() + 1, pos.z());
        }
        fill(facet);
        long hash = 17;
        int marked = 0;
        for (int z = region.minZ(); z <= region.maxZ(); z++) {
            for (int x = region.minX(); x <= region.maxX(); x++) {
                for (int y : facet.getWorldColumn(x, z)) {
                    hash = hash * 31 + y;
                    marked++;
                }
                hash = hash * 31 + x * 7 + z;
            }
        }
        System.out.println("[surfaces-checksum] voxels=" + surface.length + " marked=" + marked + " hash=" + Long.toHexString(hash));
    }

    @Benchmark
    public long generateAndRead() {
        SurfacesFacet facet = new SurfacesFacet(CHUNK, BORDER);
        fill(facet);
        BlockRegion region = facet.getWorldRegion();
        long sum = 0;
        for (int z = region.minZ(); z <= region.maxZ(); z++) {
            for (int x = region.minX(); x <= region.maxX(); x++) {
                for (int y : facet.getWorldColumn(x, z)) {
                    sum += y;
                }
            }
        }
        return sum;
    }

    private void fill(SurfacesFacet facet) {
        int i = 0;
        for (Vector3ic pos : facet.getWorldRegion()) {
            facet.setWorld(pos, surface[i++]);
        }
    }

    /** Rolling ground with overhangs, so some columns hold more than one surface. */
    private static boolean solid(int x, int y, int z) {
        double height = 30 + 7 * Math.sin(x * 0.13) + 6 * Math.cos(z * 0.09);
        return height - y + 6 * Math.sin(x * 0.31) * Math.cos(z * 0.27) * Math.sin(y * 0.4) > 0;
    }
}
