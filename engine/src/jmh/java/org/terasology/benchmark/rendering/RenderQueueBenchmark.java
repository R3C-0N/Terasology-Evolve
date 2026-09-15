// Copyright 2026 The Terasology Foundation
// SPDX-License-Identifier: Apache-2.0
package org.terasology.benchmark.rendering;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.joml.Vector3i;
import org.joml.Vector3ic;
import org.joml.Vector4f;
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
import org.terasology.engine.config.Config;
import org.terasology.engine.context.Context;
import org.terasology.engine.context.internal.ContextImpl;
import org.terasology.engine.registry.CoreRegistry;
import org.terasology.engine.rendering.assets.material.Material;
import org.terasology.engine.rendering.cameras.Camera;
import org.terasology.engine.rendering.logic.ChunkMeshRenderer;
import org.terasology.engine.rendering.primitives.ChunkMesh;
import org.terasology.engine.rendering.primitives.ChunkTessellator;
import org.terasology.engine.rendering.world.RenderQueuesHelper;
import org.terasology.engine.rendering.world.RenderableWorldImpl;
import org.terasology.engine.rendering.world.WorldRenderer;
import org.terasology.engine.rendering.world.viewDistance.ViewDistance;
import org.terasology.engine.world.WorldProvider;
import org.terasology.engine.world.block.BlockRegion;
import org.terasology.engine.world.chunks.Chunk;
import org.terasology.engine.world.chunks.ChunkProvider;
import org.terasology.engine.world.chunks.Chunks;
import org.terasology.engine.world.chunks.RenderableChunk;
import org.terasology.engine.world.chunks.blockdata.TeraArray;
import org.terasology.engine.world.chunks.blockdata.TeraSparseArray16Bit;
import org.terasology.engine.world.chunks.internal.ChunkImpl;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;

/**
 * The CPU side of one frame of world rendering: {@link RenderableWorldImpl#queueVisibleChunks} fills the five
 * render queues, then every queue is drained the way the render nodes drain them.
 * <p>
 * {@code filledLayers} is how many of the seven vertical chunk layers hold geometry. Above the surface chunks are
 * air and below it they are solid rock, so in a real world most chunks have an empty mesh; 7 is the worst case.
 * <p>
 * The set-up prints the size of each queue and a hash of the order it drains in, so two builds can be checked to
 * draw the same chunks in the same order.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 4, time = 2)
@Measurement(iterations = 6, time = 2)
@Fork(value = 1, jvmArgsAppend = "-Xmx1g")
@State(Scope.Benchmark)
public class RenderQueueBenchmark {

    @Param({"NEAR", "ULTRA"})
    public ViewDistance viewDistance;

    @Param({"2", "7"})
    public int filledLayers;

    private RenderableWorldImpl world;
    private RenderQueuesHelper queues;

    @Setup(Level.Trial)
    public void setUp() {
        Config config = new Config();
        config.loadDefaults();
        config.getRendering().setDynamicShadows(true);
        config.getRendering().setMaxChunksUsedForShadowMapping(128);
        Context context = new ContextImpl();
        context.put(Config.class, config);
        CoreRegistry.setContext(context);

        Camera camera = new BenchCamera(new Vector3f(16, 40, 16), new Vector3f(0.8f, -0.25f, 0.55f), new Vector3f(0, 1, 0));
        Camera sun = new BenchCamera(new Vector3f(16, 200, 16), new Vector3f(0.1f, -1f, 0.1f), new Vector3f(0, 0, 1));
        WorldRenderer renderer = proxy(WorldRenderer.class, (method, args) ->
                "getActiveCamera".equals(method.getName()) ? camera : null);

        Map<Vector3ic, Chunk> loaded = new HashMap<>();
        Vector3i extents = new Vector3i(viewDistance.getChunkDistance()).div(2);
        BlockRegion region = new BlockRegion(new Vector3i()).expand(extents);
        for (Vector3ic position : region) {
            Vector3i key = new Vector3i(position);
            ChunkImpl chunk = new ChunkImpl(key, new TeraSparseArray16Bit(Chunks.SIZE_X, Chunks.SIZE_Y, Chunks.SIZE_Z),
                    new TeraArray[0], null);
            chunk.setMesh(meshFor(key));
            chunk.markReady();
            chunk.setDirty(false);
            loaded.put(key, chunk);
        }
        ChunkProvider chunkProvider = proxy(ChunkProvider.class, (method, args) ->
                "getChunk".equals(method.getName()) && args.length == 1 ? loaded.get(args[0]) : null);
        WorldProvider worldProvider = proxy(WorldProvider.class, (method, args) -> null);

        world = new RenderableWorldImpl(() -> renderer, Optional.empty(), chunkProvider, new ChunkTessellator(),
                worldProvider, config, Optional.empty());
        world.setChunkMeshRenderer(new ChunkMeshRenderer());
        world.setShadowMapCamera(sun);
        world.updateChunksInProximity(region);
        queues = world.getRenderQueues();

        world.queueVisibleChunks(true);
        System.out.println("[queue-checksum] " + viewDistance + "/" + filledLayers
                + " shadow=" + describe(queues.chunksOpaqueShadow)
                + " opaque=" + describe(queues.chunksOpaque)
                + " reflection=" + describe(queues.chunksOpaqueReflection)
                + " alphaReject=" + describe(queues.chunksAlphaReject)
                + " alphaBlend=" + describe(queues.chunksAlphaBlend));
    }

    @Benchmark
    public long frame() {
        world.queueVisibleChunks(true);
        long drawn = drain(queues.chunksOpaqueShadow);
        drawn += drain(queues.chunksOpaque);
        drawn += drain(queues.chunksOpaqueReflection);
        drawn += drain(queues.chunksAlphaReject);
        drawn += drain(queues.chunksAlphaBlend);
        return drawn;
    }

    /** What a render node does with its queue: poll every chunk and read where to draw it. */
    private static long drain(PriorityQueue<RenderableChunk> queue) {
        long drawn = 0;
        while (queue.size() > 0) {
            RenderableChunk chunk = queue.poll();
            if (chunk.hasMesh()) {
                Vector3f position = chunk.getRenderPosition();
                drawn += (long) position.x;
            }
        }
        return drawn;
    }

    private static String describe(PriorityQueue<RenderableChunk> queue) {
        int size = queue.size();
        int hash = 1;
        while (queue.size() > 0) {
            hash = 31 * hash + queue.poll().getRenderPosition().hashCode();
        }
        return size + "#" + Integer.toHexString(hash);
    }

    /** Surface layers get a few thousand triangles; the rest get the empty mesh an all-air or all-rock chunk has. */
    private ChunkMesh meshFor(Vector3ic position) {
        boolean filled = position.y() >= -(filledLayers / 2) && position.y() < filledLayers - filledLayers / 2;
        if (!filled) {
            return new FakeMesh(0, 0, 0);
        }
        int hash = Math.floorMod(position.x() * 73856093 ^ position.y() * 19349663 ^ position.z() * 83492791, 1000);
        return new FakeMesh(6000 + hash * 8, hash % 2 == 0 ? 500 + hash : 0, hash % 3 == 0 ? 200 + hash / 2 : 0);
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, BiFunction<Method, Object[], Object> answer) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (self, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                switch (method.getName()) {
                    case "hashCode":
                        return System.identityHashCode(self);
                    case "equals":
                        return self == args[0];
                    default:
                        return type.getSimpleName();
                }
            }
            Object result = answer.apply(method, args == null ? new Object[0] : args);
            Class<?> returnType = method.getReturnType();
            if (result != null || !returnType.isPrimitive() || returnType == void.class) {
                return result;
            }
            if (returnType == boolean.class) {
                return false;
            }
            if (returnType == float.class) {
                return 0f;
            }
            if (returnType == double.class) {
                return 0d;
            }
            if (returnType == long.class) {
                return 0L;
            }
            if (returnType == short.class) {
                return (short) 0;
            }
            if (returnType == byte.class) {
                return (byte) 0;
            }
            if (returnType == char.class) {
                return (char) 0;
            }
            return 0;
        });
    }

    /** A perspective camera with fixed matrices, set up the way {@code PerspectiveCamera} sets its own. */
    private static final class BenchCamera extends Camera {
        BenchCamera(Vector3fc eye, Vector3fc direction, Vector3fc upVector) {
            position.set(eye);
            viewingDirection.set(direction).normalize();
            up.set(upVector);
            updateMatrices(85f);
            updateFrustum();
        }

        @Override
        public boolean isBobbingAllowed() {
            return false;
        }

        @Override
        public void updateMatrices() {
            updateMatrices(activeFov);
        }

        @Override
        public void updateMatrices(float fov) {
            float aspectRatio = 1280f / 800f;
            float fovY = (float) (2 * Math.atan2(Math.tan(0.5 * Math.toRadians(fov)), aspectRatio));
            projectionMatrix.setPerspective(fovY, aspectRatio, getzNear(), getzFar());
            viewMatrix.setLookAt(0f, 0f, 0f, viewingDirection.x, viewingDirection.y, viewingDirection.z, up.x, up.y, up.z);
            normViewMatrix.set(viewMatrix);
            Matrix4f reflection = new Matrix4f();
            reflection.setRow(0, new Vector4f(1.0f, 0.0f, 0.0f, 0.0f));
            reflection.setRow(1, new Vector4f(0.0f, -1.0f, 0.0f, 2f * (-position.y + getReflectionHeight())));
            reflection.setRow(2, new Vector4f(0.0f, 0.0f, 1.0f, 0.0f));
            reflection.setRow(3, new Vector4f(0.0f, 0.0f, 0.0f, 1.0f));
            viewMatrix.mul(reflection, viewMatrixReflected);
            projectionMatrix.mul(viewMatrix, viewProjectionMatrix);
        }
    }

    /** Triangle counts and nothing else: the queues never touch a mesh's buffers. */
    private static final class FakeMesh implements ChunkMesh {
        private final int opaque;
        private final int alphaReject;
        private final int refractive;

        FakeMesh(int opaque, int alphaReject, int refractive) {
            this.opaque = opaque;
            this.alphaReject = alphaReject;
            this.refractive = refractive;
        }

        @Override
        public VertexElements getVertexElements(RenderType renderType) {
            return null;
        }

        @Override
        public boolean hasVertexElements() {
            return false;
        }

        @Override
        public boolean updateMesh() {
            return false;
        }

        @Override
        public void discardData() {
        }

        @Override
        public void updateMaterial(Material chunkMaterial, Vector3fc chunkPosition, boolean chunkIsAnimated) {
        }

        @Override
        public int triangleCount(RenderPhase phase) {
            switch (phase) {
                case OPAQUE:
                    return opaque;
                case ALPHA_REJECT:
                    return alphaReject;
                case REFRACTIVE:
                    return refractive;
                default:
                    return 0;
            }
        }

        @Override
        public int getTimeToGenerateBlockVertices() {
            return 0;
        }

        @Override
        public int getTimeToGenerateOptimizedBuffers() {
            return 0;
        }

        @Override
        public void dispose() {
        }

        @Override
        public int render(RenderPhase type) {
            return triangleCount(type);
        }
    }
}
