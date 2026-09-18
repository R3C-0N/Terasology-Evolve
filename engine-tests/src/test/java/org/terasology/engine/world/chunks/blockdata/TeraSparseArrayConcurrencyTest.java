// Copyright 2026 The Terasology Foundation
// SPDX-License-Identifier: Apache-2.0

package org.terasology.engine.world.chunks.blockdata;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * That growing a sparse array does not crash a thread reading it at the same time.
 * <p>
 * A chunk is read by the mesh worker while light propagation writes into it: {@code LightMerger}
 * spreads sunlight into the neighbours it was given, and those neighbours are live chunks out of the
 * cache, already meshed. So {@code set} and {@code get} genuinely run at once on one array, and the
 * engine has no lock anywhere on that path.
 * <p>
 * The failure that prompted this test appeared once in sixteen minutes of play — a
 * {@code NullPointerException} on {@code this.deflated} inside {@code get}, reached from
 * {@code BlockMeshPart.appendLighting}. It is rare because the window is two instructions wide: the
 * array published its row table before the plane the rows fall back to, so a reader could find the
 * first and dereference the second while it was still null.
 * <p>
 * This is not a proof of thread safety, and the class is still racy for the <em>value</em> a reader
 * gets while a row is being split. It is a regression test for the one window that throws, and it is
 * the first concurrency test this package has ever had.
 */
class TeraSparseArrayConcurrencyTest {

    private static final int SIZE_X = 32;
    private static final int SIZE_Y = 64;
    private static final int SIZE_Z = 32;

    /** Each round races one freshly grown array; the window only exists on the first write. */
    private static final int ROUNDS = 4000;

    /** Enough reads per round to sit inside a two-instruction window now and then. */
    private static final int READS_PER_ROUND = 400;

    @Test
    void growingAnArrayDoesNotCrashAConcurrentReader() throws Exception {
        assertNull(race((x, y, z) -> new TeraSparseArray8Bit(x, y, z, (byte) 0)),
                "a reader crashed on the 8 bit array");
    }

    @Test
    void growingAFourBitArrayDoesNotCrashAConcurrentReader() throws Exception {
        assertNull(race((x, y, z) -> new TeraSparseArray4Bit(x, y, z, (byte) 0)),
                "a reader crashed on the 4 bit array");
    }

    @Test
    void growingASixteenBitArrayDoesNotCrashAConcurrentReader() throws Exception {
        assertNull(race((x, y, z) -> new TeraSparseArray16Bit(x, y, z, (short) 0)),
                "a reader crashed on the 16 bit array");
    }

    /**
     * @return the first throwable either thread saw, or null if the rounds all survived
     */
    private static Throwable race(ArrayMaker maker) throws Exception {
        AtomicReference<TeraArray> shared = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        // Two barriers per round: one to line the threads up on a fresh array, one to keep them in
        // step afterwards. Without the first, the writer would usually have finished before the
        // reader started and the window would never be visited.
        CyclicBarrier start = new CyclicBarrier(2);
        CyclicBarrier end = new CyclicBarrier(2);

        Thread reader = new Thread(() -> {
            try {
                for (int round = 0; round < ROUNDS; round++) {
                    start.await(10, TimeUnit.SECONDS);
                    TeraArray array = shared.get();
                    for (int i = 0; i < READS_PER_ROUND; i++) {
                        // Read the whole height: the plane is indexed by y, so only a read that
                        // lands on a row the writer has not split reaches the fallback that threw.
                        array.get(i % SIZE_X, i % SIZE_Y, (i * 7) % SIZE_Z);
                    }
                    end.await(10, TimeUnit.SECONDS);
                }
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
                // Break both barriers, or the writer waits for a partner that has already left and
                // the test hangs instead of reporting the very failure it exists to catch.
                start.reset();
                end.reset();
            }
        }, "sparse-array-reader");

        reader.setDaemon(true);
        reader.start();

        try {
            for (int round = 0; round < ROUNDS; round++) {
                shared.set(maker.make(SIZE_X, SIZE_Y, SIZE_Z));
                start.await(10, TimeUnit.SECONDS);
                // One write is all it takes: it is the transition out of the all-fill form that
                // allocates, and that allocation is the window.
                shared.get().set(1, 1, 1, 1);
                end.await(10, TimeUnit.SECONDS);
                if (failure.get() != null) {
                    break;
                }
            }
        } catch (Throwable t) {
            failure.compareAndSet(null, t);
        }

        reader.join(30_000);
        return failure.get();
    }

    /** Each variant has its own fill type, so the caller supplies a whole fresh all-fill array. */
    @FunctionalInterface
    private interface ArrayMaker {
        TeraArray make(int sizeX, int sizeY, int sizeZ);
    }
}
