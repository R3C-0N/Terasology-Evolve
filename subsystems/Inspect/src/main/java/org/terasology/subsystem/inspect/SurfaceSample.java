// Copyright 2021 The Terasology Foundation
// SPDX-License-Identifier: Apache-2.0

package org.terasology.subsystem.inspect;

import org.joml.Vector3i;
import org.terasology.engine.context.Context;
import org.terasology.engine.world.block.Block;
import org.terasology.engine.world.block.BlockRegionc;
import org.terasology.engine.world.chunks.Chunk;
import org.terasology.engine.world.chunks.ChunkProvider;
import org.terasology.engine.world.chunks.Chunks;

import java.util.Arrays;

/**
 * Cherche la premiere surface sous un plafond, colonne par colonne.
 * <p>
 * <b>Pourquoi un echantillonneur separe de {@link TerrainSample}.</b> Celui-ci ne produit pas une
 * case par bloc mais <em>une valeur par colonne</em>, avec un drapeau « resolue » et une sortie
 * anticipee des que toutes le sont. Le plier dans l'autre voudrait dire un second point d'entree
 * et un tableau qui n'a pas la meme forme. Ils partagent la legende et le rendu, pas le parcours.
 * <p>
 * Le balayage reste groupe par chunk et descend couche de chunks par couche de chunks : la surface
 * se trouve d'ordinaire dans la premiere, si bien qu'un rayon de 16 coute quelques milliers de
 * sondes au lieu des dizaines de milliers que sa borne autorise.
 */
public final class SurfaceSample {

    public static final int NO_HEIGHT = Integer.MIN_VALUE;

    private final Block[] surface;
    private final int[] height;
    private final boolean[] seen;
    private int probes;
    private int resolved;
    private int chunksTotal;
    private int chunksLoaded;

    private SurfaceSample(int columns) {
        surface = new Block[columns];
        height = new int[columns];
        seen = new boolean[columns];
        Arrays.fill(height, NO_HEIGHT);
    }

    /**
     * @param minX,minZ le coin de l'emprise ; les colonnes sont rangees z puis x, en ordre de
     *         lecture.
     * @param from le plafond, inclus. @param depth le nombre de niveaux sondes sous lui.
     */
    public static SurfaceSample read(Context context, int minX, int minZ, int width, int from,
                                     int depth) {
        SurfaceSample sample = new SurfaceSample(width * width);
        ChunkProvider chunkProvider = context.get(ChunkProvider.class);
        if (chunkProvider != null) {
            sample.scan(chunkProvider, minX, minZ, width, from, depth);
        }
        return sample;
    }

    private void scan(ChunkProvider chunkProvider, int minX, int minZ, int width, int from,
                      int depth) {
        int bottom = from - depth + 1;
        int maxX = minX + width - 1;
        int maxZ = minZ + width - 1;
        int cxMin = Chunks.toChunkPosX(minX);
        int cxMax = Chunks.toChunkPosX(maxX);
        int czMin = Chunks.toChunkPosZ(minZ);
        int czMax = Chunks.toChunkPosZ(maxZ);
        int cyTop = Chunks.toChunkPosY(from);
        int cyBottom = Chunks.toChunkPosY(bottom);

        Vector3i cursor = new Vector3i();
        for (int cy = cyTop; cy >= cyBottom; cy--) {
            for (int cx = cxMin; cx <= cxMax; cx++) {
                for (int cz = czMin; cz <= czMax; cz++) {
                    chunksTotal++;
                    Chunk chunk = chunkProvider.getChunk(cursor.set(cx, cy, cz));
                    if (chunk == null) {
                        continue;
                    }
                    chunksLoaded++;
                    probeChunk(chunk, minX, minZ, width, from, bottom);
                }
            }
            if (resolved == surface.length) {
                return;     // toutes les colonnes sont tombees sur leur surface
            }
        }
    }

    private void probeChunk(Chunk chunk, int minX, int minZ, int width, int from, int bottom) {
        BlockRegionc bounds = chunk.getRegion();
        int x0 = Math.max(minX, bounds.minX());
        int x1 = Math.min(minX + width - 1, bounds.maxX());
        int z0 = Math.max(minZ, bounds.minZ());
        int z1 = Math.min(minZ + width - 1, bounds.maxZ());
        int yTop = Math.min(from, bounds.maxY());
        int yBottom = Math.max(bottom, bounds.minY());

        for (int x = x0; x <= x1; x++) {
            int rx = Chunks.toRelativeX(x);
            for (int z = z0; z <= z1; z++) {
                int index = (z - minZ) * width + (x - minX);
                seen[index] = true;
                if (height[index] != NO_HEIGHT) {
                    continue;   // deja resolue par une couche superieure
                }
                int rz = Chunks.toRelativeZ(z);
                for (int y = yTop; y >= yBottom; y--) {
                    probes++;
                    Block block = chunk.getBlock(rx, Chunks.toRelativeY(y), rz);
                    if (!TerrainLegend.isEmpty(block)) {
                        surface[index] = block;
                        height[index] = y;
                        resolved++;
                        break;
                    }
                }
            }
        }
    }

    public Block surface(int index) {
        return surface[index];
    }

    public int height(int index) {
        return height[index];
    }

    /** Vrai si un chunk pret couvrait la colonne : distingue « rien trouve » de « illisible ». */
    public boolean seen(int index) {
        return seen[index];
    }

    public int probes() {
        return probes;
    }

    public int resolved() {
        return resolved;
    }

    public int chunksTotal() {
        return chunksTotal;
    }

    public int chunksLoaded() {
        return chunksLoaded;
    }
}
