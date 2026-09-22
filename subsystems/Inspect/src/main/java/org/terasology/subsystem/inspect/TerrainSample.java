// Copyright 2021 The Terasology Foundation
// SPDX-License-Identifier: Apache-2.0

package org.terasology.subsystem.inspect;

import org.joml.Vector3i;
import org.terasology.engine.context.Context;
import org.terasology.engine.world.block.Block;
import org.terasology.engine.world.block.BlockRegion;
import org.terasology.engine.world.block.BlockRegionc;
import org.terasology.engine.world.chunks.Chunk;
import org.terasology.engine.world.chunks.ChunkProvider;
import org.terasology.engine.world.chunks.Chunks;

import java.util.Arrays;

/**
 * Lit une region du monde, chunk par chunk, et la range en ordre de lecture.
 * <p>
 * <b>Pourquoi passer par {@link ChunkProvider} et non par {@code WorldProvider.getBlock}.</b> Ce
 * dernier coute, <em>a chaque appel</em>, une allocation de {@code Vector3i}, un
 * {@code toChunkPos}, une recherche dans la carte des chunks et une lecture de tableau. Sur une
 * requete de dizaines de milliers de blocs, executee sur le thread de jeu, c'est autant
 * d'allocations et de recherches. En groupant par chunk, la recherche tombe a une par chunk — une
 * poignee — et toute la requete n'alloue que ses deux tableaux de resultat.
 * <p>
 * <b>Et surtout pas {@code getWorldViewAround} / {@code ChunkViewCore}.</b> Cette vue rend le bloc
 * d'<em>air</em> pour toute position hors de son champ : elle ne distingue donc pas « pas charge »
 * de « vide », qui est justement la distinction dont ces grilles vivent. C'est une erreur de
 * justesse, pas de performance. Elle n'expose pas non plus les donnees supplementaires, donc pas
 * les niveaux d'ecoulement des liquides.
 */
public final class TerrainSample {

    /** Case illisible : aucun chunk pret ne la couvre. Se rend par {@code ?}, jamais par du vide. */
    public static final byte FLOW_NONE = -1;

    private final Block[] blocks;
    private final byte[] flow;
    private int chunksTotal;
    private int chunksLoaded;

    private TerrainSample(Block[] blocks, byte[] flow) {
        this.blocks = blocks;
        this.flow = flow;
    }

    /**
     * @param flowSlot l'emplacement de donnee supplementaire des liquides, ou -1 s'il n'est pas
     *         disponible — auquel cas aucun niveau n'est lu.
     */
    public static TerrainSample read(Context context, GridSpec spec, int flowSlot) {
        Block[] blocks = new Block[spec.cellCount()];
        byte[] flow = null;
        if (spec.wantFlow() && flowSlot >= 0) {
            flow = new byte[spec.cellCount()];
            // Obligatoire, et pas par hygiene : le defaut de Java est 0, et 0 signifie « source
            // permanente ». L'oublier ferait lire chaque case d'air et de pierre comme une source,
            // et un test qui ne verifie qu'un changement entre deux echantillons passerait quand
            // meme.
            Arrays.fill(flow, FLOW_NONE);
        }
        TerrainSample sample = new TerrainSample(blocks, flow);
        sample.scan(context, spec, flowSlot);
        return sample;
    }

    private void scan(Context context, GridSpec spec, int flowSlot) {
        ChunkProvider chunkProvider = context.get(ChunkProvider.class);
        if (chunkProvider == null) {
            return;     // tout reste nul, donc tout se rend en « illisible »
        }
        BlockRegionc region = spec.region();
        BlockRegion chunkRegion = new BlockRegion(BlockRegion.INVALID);
        Chunks.toChunkRegion(region, chunkRegion);

        // Un seul vecteur pour toute la requete : la surcharge getChunk(int,int,int) en allouerait
        // un par appel.
        Vector3i cursor = new Vector3i();

        for (int cx = chunkRegion.minX(); cx <= chunkRegion.maxX(); cx++) {
            for (int cy = chunkRegion.minY(); cy <= chunkRegion.maxY(); cy++) {
                for (int cz = chunkRegion.minZ(); cz <= chunkRegion.maxZ(); cz++) {
                    chunksTotal++;
                    // getChunk filtre deja isReady() : « non nul » implique « pret », donc il n'y a
                    // pas de troisieme categorie a compter.
                    Chunk chunk = chunkProvider.getChunk(cursor.set(cx, cy, cz));
                    if (chunk == null) {
                        continue;
                    }
                    chunksLoaded++;
                    copyChunk(chunk, region, spec, flowSlot);
                }
            }
        }
    }

    private void copyChunk(Chunk chunk, BlockRegionc region, GridSpec spec, int flowSlot) {
        BlockRegionc bounds = chunk.getRegion();    // en coordonnees de blocs
        int x0 = Math.max(region.minX(), bounds.minX());
        int x1 = Math.min(region.maxX(), bounds.maxX());
        int y0 = Math.max(region.minY(), bounds.minY());
        int y1 = Math.min(region.maxY(), bounds.maxY());
        int z0 = Math.max(region.minZ(), bounds.minZ());
        int z1 = Math.min(region.maxZ(), bounds.maxZ());

        for (int x = x0; x <= x1; x++) {
            int rx = Chunks.toRelativeX(x);
            for (int y = y0; y <= y1; y++) {
                int ry = Chunks.toRelativeY(y);
                for (int z = z0; z <= z1; z++) {
                    int rz = Chunks.toRelativeZ(z);
                    int i = spec.index(x, y, z);
                    Block block = chunk.getBlock(rx, ry, rz);
                    blocks[i] = block;
                    if (flow != null && block.isLiquid()) {
                        flow[i] = (byte) chunk.getExtraData(flowSlot, rx, ry, rz);
                    }
                }
            }
        }
    }

    /** Le bloc range a cet indice, ou {@code null} si aucun chunk pret ne le couvrait. */
    public Block block(int index) {
        return blocks[index];
    }

    /** Le niveau d'ecoulement, ou {@link #FLOW_NONE} si la case n'est pas un liquide. */
    public byte flow(int index) {
        return flow == null ? FLOW_NONE : flow[index];
    }

    public boolean hasFlow() {
        return flow != null;
    }

    public int chunksTotal() {
        return chunksTotal;
    }

    public int chunksLoaded() {
        return chunksLoaded;
    }
}
