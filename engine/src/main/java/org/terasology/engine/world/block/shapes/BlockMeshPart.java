// Copyright 2021 The Terasology Foundation
// SPDX-License-Identifier: Apache-2.0
package org.terasology.engine.world.block.shapes;

import org.joml.Quaternionf;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.terasology.engine.math.Direction;
import org.terasology.engine.rendering.primitives.ChunkMesh;
import org.terasology.engine.rendering.primitives.ChunkVertexFlag;
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

    public BlockMeshPart(Vector3f[] vertices, Vector3f[] normals, Vector2f[] texCoords, int[] indices) {
        this(vertices, normals, texCoords, indices, 1);
    }

    private BlockMeshPart(Vector3f[] vertices, Vector3f[] normals, Vector2f[] texCoords, int[] indices, int texFrames) {
        this.vertices = Arrays.copyOf(vertices, vertices.length);
        this.normals = Arrays.copyOf(normals, normals.length);
        this.texCoords = Arrays.copyOf(texCoords, texCoords.length);
        this.indices = Arrays.copyOf(indices, indices.length);
        this.texFrames = texFrames;
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
        ChunkMesh.VertexElements elements = chunk.getVertexElements(renderType);
        for (Vector2f texCoord : texCoords) {
            elements.uv0.put(texCoord);
        }

        int nextIndex = elements.vertexCount;
        elements.buffer.reserveElements(nextIndex + vertices.length);
        Vector3f pos = new Vector3f();
        // Only the water surface carries the depth; nothing else reads the byte.
        boolean surfaceOfWater = flags == ChunkVertexFlag.WATER_SURFACE;
        for (int vIdx = 0; vIdx < vertices.length; ++vIdx) {
            elements.color.put(colorOffset);
            elements.position.put(pos.set(vertices[vIdx]).add(offsetX, offsetY, offsetZ));
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
            // The buffer took a copy, so pos is still the vertex in chunk coordinates.
            appendLighting(elements, chunkView, pos.x, pos.y, pos.z, normals[vIdx]);
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
            }
        }

        elements.sunlight.put(sunlitSamples == 0 ? 0 : sunlight / sunlitSamples / 15f);
        elements.blockLight.put(blockLitSamples == 0 ? 0 : blockLight / blockLitSamples / 15f);
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
