// Copyright 2021 The Terasology Foundation
// SPDX-License-Identifier: Apache-2.0
package org.terasology.engine.world.block.shapes;

import org.joml.Quaternionf;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.terasology.engine.math.Direction;
import org.terasology.engine.rendering.primitives.ChunkMesh;
import org.terasology.engine.rendering.primitives.ChunkVertexFlag;
import org.terasology.engine.rendering.primitives.LiquidSurfaceField;
import org.terasology.engine.rendering.primitives.WaterDepthField;
import org.terasology.engine.world.ChunkView;
import org.terasology.engine.world.block.Block;
import org.terasology.math.TeraMath;
import org.terasology.nui.Colorc;

import java.util.Arrays;

/**
 * Describes the elements composing part of a block mesh. Multiple parts are patched together to define the mesh
 * for a block, or its appearance in the world.
 *
 */
public class BlockMeshPart {
    private static final float BORDER = 1f / 128f;

    /**
     * Where the eight light samples of a vertex are taken, above it then below it. Negative steps are stored
     * negated, which adds exactly what subtracting did.
     */
    private static final float[] LIGHT_SAMPLE_X = {0.1f, 0.1f, -0.1f, -0.1f, 0.1f, 0.1f, -0.1f, -0.1f};
    private static final float[] LIGHT_SAMPLE_Y = {0.8f, 0.8f, 0.8f, 0.8f, -0.1f, -0.1f, -0.1f, -0.1f};
    private static final float[] LIGHT_SAMPLE_Z = {0.1f, -0.1f, -0.1f, 0.1f, 0.1f, -0.1f, -0.1f, 0.1f};

    /** 0.4 and 0.8 to the power of the occluding blocks, zero to four of them, taken once instead of per vertex. */
    private static final double[] OPAQUE_OCCLUSION = occlusionTable(0.40);
    private static final double[] BILLBOARD_OCCLUSION = occlusionTable(0.80);

    private Vector3f[] vertices;
    private Vector3f[] normals;
    private Vector2f[] texCoords;
    private int[] indices;
    private int texFrames;

    /**
     * How this part is stretched over its own height, so a vertex moved up or down can keep the texture where it
     * belongs. The shapes hand-trim their side texcoords by exactly what they trim off the geometry - the lowered
     * cube stops at 0.4 and starts its {@code v} at 0.1 - and moving a vertex without moving its {@code v} would
     * undo precisely what that trimming is for.
     *
     * A part whose vertices all sit at one height, such as a top or a bottom face, has {@code lowY == highY}: its
     * texcoords are a plan view and have nothing to do with height, so they are left alone.
     */
    private final float lowY;
    private final float highY;
    private final float vAtLowY;
    private final float vAtHighY;

    public BlockMeshPart(Vector3f[] vertices, Vector3f[] normals, Vector2f[] texCoords, int[] indices) {
        this(vertices, normals, texCoords, indices, 1);
    }

    private BlockMeshPart(Vector3f[] vertices, Vector3f[] normals, Vector2f[] texCoords, int[] indices, int texFrames) {
        this.vertices = Arrays.copyOf(vertices, vertices.length);
        this.normals = Arrays.copyOf(normals, normals.length);
        this.texCoords = Arrays.copyOf(texCoords, texCoords.length);
        this.indices = Arrays.copyOf(indices, indices.length);
        this.texFrames = texFrames;

        int lowest = 0;
        int highest = 0;
        for (int i = 1; i < vertices.length; i++) {
            if (vertices[i].y < vertices[lowest].y) {
                lowest = i;
            }
            if (vertices[i].y > vertices[highest].y) {
                highest = i;
            }
        }
        // The loader refuses a shape whose vertices and texcoords differ in length, so these always pair up.
        this.lowY = vertices[lowest].y;
        this.highY = vertices[highest].y;
        this.vAtLowY = texCoords[lowest].y;
        this.vAtHighY = texCoords[highest].y;
    }

    public int size() {
        return vertices.length;
    }

    public int indicesSize() {
        return indices.length;
    }

    public Vector3f getVertex(int i) {
        return vertices[i];
    }

    public Vector3f getNormal(int i) {
        return normals[i];
    }

    public Vector2f getTexCoord(int i) {
        return texCoords[i];
    }

    public int getIndex(int i) {
        return indices[i];
    }

    public int getTexFrames() {
        return texFrames;
    }

    public BlockMeshPart mapTexCoords(Vector2f offset, float width, int frames) {
        float normalisedBorder = BORDER * width;
        Vector2f[] newTexCoords = new Vector2f[texCoords.length];
        for (int i = 0; i < newTexCoords.length; ++i) {
            newTexCoords[i] = new Vector2f(offset.x + normalisedBorder + texCoords[i].x * (width - 2 * normalisedBorder),
                    offset.y + normalisedBorder + texCoords[i].y * (width - 2 * normalisedBorder));
        }
        return new BlockMeshPart(vertices, normals, newTexCoords, indices, frames);
    }

    public void appendTo(ChunkMesh chunk, ChunkView chunkView, int offsetX, int offsetY, int offsetZ,
                         ChunkMesh.RenderType renderType, Colorc colorOffset, ChunkVertexFlag flags) {
        appendTo(chunk, chunkView, offsetX, offsetY, offsetZ, renderType, colorOffset, flags, null, 0);
    }

    /**
     * Appends this part, optionally pulling the top of it to the height of the liquid surface standing there.
     *
     * @param corners the four corner heights, indexed as {@link LiquidSurfaceField#cornerIndex}, or null to append
     *         the part exactly as the shape describes it
     * @param deformAbove the height, in shape coordinates, at or over which a vertex belongs to the top of the shape
     *         and follows the corner. It is the shape's top rather than this part's own, because a bottom face is
     *         also entirely at its own highest point and must not be lifted.
     */
    public void appendTo(ChunkMesh chunk, ChunkView chunkView, int offsetX, int offsetY, int offsetZ,
                         ChunkMesh.RenderType renderType, Colorc colorOffset, ChunkVertexFlag flags,
                         float[] corners, float deformAbove) {
        ChunkMesh.VertexElements elements = chunk.getVertexElements(renderType);

        int nextIndex = elements.vertexCount;
        elements.buffer.reserveElements(nextIndex + vertices.length);
        Vector3f pos = new Vector3f();
        Vector2f uv = new Vector2f();
        // Only the water surface carries the depth; nothing else reads the byte.
        boolean surfaceOfWater = flags == ChunkVertexFlag.WATER_SURFACE;
        for (int vIdx = 0; vIdx < vertices.length; ++vIdx) {
            elements.color.put(colorOffset);
            pos.set(vertices[vIdx]).add(offsetX, offsetY, offsetZ);
            uv.set(texCoords[vIdx]);
            // The vertex arrays are shared with the shape asset and with every other block built from it, and
            // meshing runs on several threads: the scratch copies are the only things that may be moved.
            if (corners != null && vertices[vIdx].y >= deformAbove) {
                float height = corners[LiquidSurfaceField.cornerIndex(vertices[vIdx].x, vertices[vIdx].z)];
                pos.y = offsetY + height;
                if (highY > lowY) {
                    uv.y = vAtLowY + (vAtHighY - vAtLowY) * (height - lowY) / (highY - lowY);
                }
            }
            elements.position.put(pos);
            elements.uv0.put(uv);
            elements.normals.put(normals[vIdx]);
            elements.flags.put((byte) (flags.getValue()));
            elements.frames.put((byte) (texFrames - 1));
            if (surfaceOfWater) {
                // The field lives on column centres, the vertex sits on a corner, hence the half block shift.
                float depth = WaterDepthField.sample(chunkView, offsetY,
                        vertices[vIdx].x + offsetX - 0.5f, vertices[vIdx].z + offsetZ - 0.5f);
                float scaled = Math.min(1.0f, depth / WaterDepthField.RANGE);
                elements.waterDepth.put((byte) Math.round(scaled * 127.0f));
            } else {
                elements.waterDepth.put((byte) 0);
            }
            // Deliberately the undeformed height: the light is sampled eight tenths of a block up and down, so a
            // vertex pulled down three quarters of a block would gather the light of the block underneath, and the
            // surface would flicker every time the flow changed by a step.
            appendLighting(elements, chunkView, pos.x, vertices[vIdx].y + offsetY, pos.z, normals[vIdx]);
        }
        elements.vertexCount += vertices.length;

        for (int index : indices) {
            elements.indices.put(index + nextIndex);
        }
    }

    public BlockMeshPart rotate(Quaternionf rotation) {
        Vector3f[] newVertices = new Vector3f[vertices.length];
        Vector3f[] newNormals = new Vector3f[normals.length];

        for (int i = 0; i < newVertices.length; ++i) {
            newVertices[i] = rotation.transform(vertices[i], new Vector3f());
            newNormals[i] = rotation.transform(normals[i], new Vector3f());
            newNormals[i].normalize();
        }

        return new BlockMeshPart(newVertices, newNormals, texCoords, indices, texFrames);
    }

    /**
     * Writes the sunlight, block light and ambient occlusion of one vertex into the mesh.
     * <p>
     * This runs for every vertex of every chunk mesh, so it allocates nothing: the light samples are summed as they
     * are read rather than gathered into arrays first, and the result goes straight into the buffers.
     */
    private static void appendLighting(ChunkMesh.VertexElements elements, ChunkView chunkView,
                                       float x, float y, float z, Vector3f normal) {
        Block b0;
        Block b1;
        Block b2;
        Block b3;
        switch (Direction.inDirection(normal)) {
            case LEFT:
            case RIGHT:
                b0 = chunkView.getBlock((x + 0.8f * normal.x), (y + 0.1f), (z + 0.1f));
                b1 = chunkView.getBlock((x + 0.8f * normal.x), (y + 0.1f), (z - 0.1f));
                b2 = chunkView.getBlock((x + 0.8f * normal.x), (y - 0.1f), (z - 0.1f));
                b3 = chunkView.getBlock((x + 0.8f * normal.x), (y - 0.1f), (z + 0.1f));
                break;
            case FORWARD:
            case BACKWARD:
                b0 = chunkView.getBlock((x + 0.1f), (y + 0.1f), (z + 0.8f * normal.z));
                b1 = chunkView.getBlock((x + 0.1f), (y - 0.1f), (z + 0.8f * normal.z));
                b2 = chunkView.getBlock((x - 0.1f), (y - 0.1f), (z + 0.8f * normal.z));
                b3 = chunkView.getBlock((x - 0.1f), (y + 0.1f), (z + 0.8f * normal.z));
                break;
            default:
                b0 = chunkView.getBlock((x + 0.1f), (y + 0.8f * normal.y), (z + 0.1f));
                b1 = chunkView.getBlock((x + 0.1f), (y + 0.8f * normal.y), (z - 0.1f));
                b2 = chunkView.getBlock((x - 0.1f), (y + 0.8f * normal.y), (z - 0.1f));
                b3 = chunkView.getBlock((x - 0.1f), (y + 0.8f * normal.y), (z + 0.1f));
        }
        int occluders = opaqueOccluder(b0) + opaqueOccluder(b1) + opaqueOccluder(b2) + opaqueOccluder(b3);
        int billboardOccluders = billboardOccluder(b0) + billboardOccluder(b1) + billboardOccluder(b2) + billboardOccluder(b3);

        float sunlight = 0;
        int sunlitSamples = 0;
        float blockLight = 0;
        int blockLitSamples = 0;
        float warmth = 0;
        for (int sample = 0; sample < LIGHT_SAMPLE_X.length; sample++) {
            float sampleX = x + LIGHT_SAMPLE_X[sample];
            float sampleY = y + LIGHT_SAMPLE_Y[sample];
            float sampleZ = z + LIGHT_SAMPLE_Z[sample];
            byte sun = chunkView.getSunlight(sampleX, sampleY, sampleZ);
            if (sun > 0) {
                sunlight += sun;
                sunlitSamples++;
            }
            byte light = chunkView.getLight(sampleX, sampleY, sampleZ);
            if (light > 0) {
                blockLight += light;
                blockLitSamples++;
                warmth += chunkView.getWarmth(sampleX, sampleY, sampleZ);
            }
        }

        elements.sunlight.put(sunlitSamples == 0 ? 0 : sunlight / sunlitSamples / 15f);
        elements.blockLight.put(blockLitSamples == 0 ? 0 : blockLight / blockLitSamples / 15f);
        // The warm share of that light, as a byte holding zero to a hundred and twenty seven. Summing both over the
        // same samples is what makes the ratio of the sums the ratio of the averages, so the two need not be divided
        // out first. The min is a belt: warmth is at or below the light at every point, unless a block definition
        // has broken the invariant by declaring itself warmer than it is bright.
        float warmShare = blockLitSamples == 0 ? 0f : Math.min(1f, warmth / blockLight);
        elements.warmth.put((byte) Math.round(warmShare * 127f));
        elements.ambientOcclusion.put((float) ((OPAQUE_OCCLUSION[occluders] + BILLBOARD_OCCLUSION[billboardOccluders]) / 2.0));
    }

    private static int opaqueOccluder(Block block) {
        return block.isShadowCasting() && !block.isTranslucent() ? 1 : 0;
    }

    private static int billboardOccluder(Block block) {
        return block.isShadowCasting() && block.isTranslucent() ? 1 : 0;
    }

    private static double[] occlusionTable(double base) {
        double[] table = new double[5];
        for (int occluders = 0; occluders < table.length; occluders++) {
            table[occluders] = TeraMath.pow(base, occluders);
        }
        return table;
    }
}
