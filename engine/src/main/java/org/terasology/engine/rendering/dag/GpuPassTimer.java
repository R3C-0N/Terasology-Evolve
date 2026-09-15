// Copyright 2026 The Terasology Foundation
// SPDX-License-Identifier: Apache-2.0
package org.terasology.engine.rendering.dag;

import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL33;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Measures how long each node of the render graph keeps the GPU busy, over a window of frames.
 * <p>
 * The CPU profile of a frame only shows the driver waiting, and the frame rate only the sum. Here each node's
 * {@code process()} runs inside its own {@code GL_TIME_ELAPSED} query. Queries are never nested, and their results are
 * read back frames later, once the driver has them, so the measurement does not stall the pipeline it measures.
 * Outside a window the renderer pays one boolean test per task.
 */
public class GpuPassTimer {
    private static final double NANOS_PER_MILLI = 1_000_000.0;

    private final ArrayDeque<Pending> pending = new ArrayDeque<>();
    private final ArrayDeque<Integer> freeQueries = new ArrayDeque<>();
    private final Map<String, List<Long>> samples = new LinkedHashMap<>();
    private final Map<Integer, Long> frameTotals = new HashMap<>();

    private boolean armed;
    private boolean recording;
    private long startAt;
    private int framesLeft;
    private int frame;
    private String label;
    private Consumer<String> report;

    /**
     * Opens a measurement window.
     *
     * @param delayNanos how long to wait before the first measured frame
     * @param frames how many frames to measure
     * @param windowLabel the label every report line carries
     * @param reportLine receives the report, one line at a time, once every result has come back
     */
    public void start(long delayNanos, int frames, String windowLabel, Consumer<String> reportLine) {
        samples.clear();
        frameTotals.clear();
        label = windowLabel;
        report = reportLine;
        startAt = System.nanoTime() + delayNanos;
        framesLeft = frames;
        frame = 0;
        recording = false;
        armed = true;
    }

    /** Collects the results the driver has finished, and opens the window once its delay has passed. */
    public void beginFrame() {
        collect();
        if (armed && !recording && System.nanoTime() >= startAt) {
            recording = true;
        }
    }

    /** Runs one task of the render task list, timed on the GPU when it is a node and a window is open. */
    public void process(RenderPipelineTask task) {
        if (!recording || !(task instanceof Node)) {
            task.process();
            return;
        }
        int query = freeQueries.isEmpty() ? GL15.glGenQueries() : freeQueries.pop();
        GL15.glBeginQuery(GL33.GL_TIME_ELAPSED, query);
        task.process();
        GL15.glEndQuery(GL33.GL_TIME_ELAPSED);
        pending.add(new Pending(query, ((Node) task).getUri().toString(), frame));
    }

    /** Closes the frame, and reports once the window is over and every query has been read back. */
    public void endFrame() {
        if (recording) {
            frame++;
            framesLeft--;
            if (framesLeft <= 0) {
                recording = false;
                armed = false;
            }
        }
        if (!armed && report != null && pending.isEmpty()) {
            if (!samples.isEmpty()) {
                writeReport();
            }
            report = null;
        }
    }

    /** Deletes the query objects. Call with the GL context current. */
    public void dispose() {
        while (!pending.isEmpty()) {
            GL15.glDeleteQueries(pending.poll().query);
        }
        while (!freeQueries.isEmpty()) {
            GL15.glDeleteQueries(freeQueries.pop());
        }
    }

    private void collect() {
        while (!pending.isEmpty()) {
            Pending oldest = pending.peek();
            if (GL15.glGetQueryObjecti(oldest.query, GL15.GL_QUERY_RESULT_AVAILABLE) == 0) {
                return;
            }
            long nanos = GL33.glGetQueryObjecti64(oldest.query, GL15.GL_QUERY_RESULT);
            pending.poll();
            freeQueries.push(oldest.query);
            samples.computeIfAbsent(oldest.node, node -> new ArrayList<>()).add(nanos);
            frameTotals.merge(oldest.frame, nanos, Long::sum);
        }
    }

    private void writeReport() {
        List<Long> totals = new ArrayList<>(frameTotals.values());
        report.accept(String.format(Locale.ROOT, "gpuTimes %s: frames=%d graph %s", label, totals.size(), describe(totals)));
        List<Map.Entry<String, List<Long>>> nodes = new ArrayList<>(samples.entrySet());
        nodes.sort((a, b) -> Double.compare(mean(b.getValue()), mean(a.getValue())));
        for (Map.Entry<String, List<Long>> node : nodes) {
            report.accept(String.format(Locale.ROOT, "gpuTimes %s %s: %s", label, node.getKey(), describe(node.getValue())));
        }
    }

    private static String describe(List<Long> values) {
        List<Long> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        return String.format(Locale.ROOT, "mean=%.3f p95=%.3f max=%.3f", mean(sorted) / NANOS_PER_MILLI,
                sorted.get((int) Math.min(sorted.size() - 1, Math.round(0.95 * (sorted.size() - 1)))) / NANOS_PER_MILLI,
                sorted.get(sorted.size() - 1) / NANOS_PER_MILLI);
    }

    private static double mean(List<Long> values) {
        long total = 0;
        for (long value : values) {
            total += value;
        }
        return values.isEmpty() ? 0 : (double) total / values.size();
    }

    private static final class Pending {
        private final int query;
        private final String node;
        private final int frame;

        private Pending(int query, String node, int frame) {
            this.query = query;
            this.node = node;
            this.frame = frame;
        }
    }
}
