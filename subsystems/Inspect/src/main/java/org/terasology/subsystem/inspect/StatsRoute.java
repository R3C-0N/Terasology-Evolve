// Copyright 2021 The Terasology Foundation
// SPDX-License-Identifier: Apache-2.0

package org.terasology.subsystem.inspect;

import gnu.trove.map.TObjectDoubleMap;
import org.terasology.engine.context.Context;
import org.terasology.engine.core.Time;
import org.terasology.engine.entitySystem.entity.EntityManager;
import org.terasology.engine.monitoring.PerformanceMonitor;
import org.terasology.engine.rendering.world.WorldRenderer;
import org.terasology.engine.world.WorldProvider;
import org.terasology.engine.world.chunks.Chunk;
import org.terasology.engine.world.chunks.ChunkProvider;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Ce que l'incrustation F3 montre, mais lisible sans capture d'ecran : images par seconde, tas,
 * entites, chunks, ce que le rendu a dessine, heure du monde, et les moyennes de performance.
 * <p>
 * <b>Le piege qui rendrait cette route inutile.</b> {@code PerformanceMonitor} est eteint par
 * defaut, et pas par configuration : son instance statique est un moniteur nul dont les cartes sont
 * vides a jamais. Le seul appel a {@code setEnabled} de tout le depot est dans la bascule F4 de
 * l'incrustation de debogage. Les moyennes n'existent donc que si un joueur est passe par la — d'ou
 * {@code perf=disabled} dit franchement plutot qu'un bloc vide, et {@code ?perf=on} pour l'allumer.
 */
public final class StatsRoute {

    private static final int TOP_MEANS = 10;

    private StatsRoute() {
    }

    public static InspectResponse stats(Context context, Map<String, String> params) {
        StringBuilder out = new StringBuilder();

        Time time = context.get(Time.class);
        Runtime runtime = Runtime.getRuntime();
        long used = runtime.totalMemory() - runtime.freeMemory();
        out.append(String.format(Locale.ROOT,
                "engine fps=%.1f paused=%b heap_used_mb=%d heap_total_mb=%d heap_max_mb=%d%n",
                time == null ? -1f : time.getFps(), time != null && time.isPaused(),
                used >> 20, runtime.totalMemory() >> 20, runtime.maxMemory() >> 20));

        EntityManager entityManager = context.get(EntityManager.class);
        if (entityManager != null) {
            out.append(String.format(Locale.ROOT, "entities active=%d%n",
                    entityManager.getActiveEntityCount()));
        }

        WorldProvider world = context.get(WorldProvider.class);
        if (world != null) {
            out.append(String.format(Locale.ROOT,
                    "world title=%s seed=%s days=%.3f time_of_day=%.3f%n",
                    world.getTitle(), world.getSeed(),
                    world.getTime().getDays(), world.getTime().getDays() % 1.0f));
        }

        out.append(chunks(context));
        out.append(render(context));
        out.append(performance(params));
        return InspectResponse.ok(out.toString());
    }

    /**
     * Compte les chunks par {@link ChunkProvider}, et non par {@code ChunkMonitor} dont la carte
     * fuit les chunks detruits et n'expose ni etat ni maillage.
     */
    private static String chunks(Context context) {
        ChunkProvider provider = context.get(ChunkProvider.class);
        if (provider == null) {
            return "chunks unavailable=no-provider\n";
        }
        Collection<Chunk> all;
        try {
            // Un client distant leve ici : sa version de getAllChunks n'est pas implementee.
            all = new ArrayList<>(provider.getAllChunks());
        } catch (UnsupportedOperationException e) {
            return "chunks unavailable=remote-provider\n";
        }
        int ready = 0;
        int dirty = 0;
        long bytes = 0;
        for (Chunk chunk : all) {
            if (chunk.isReady()) {
                ready++;
            }
            if (chunk.isDirty()) {
                dirty++;
            }
            bytes += chunk.getEstimatedMemoryConsumptionInBytes();
        }
        return String.format(Locale.ROOT, "chunks total=%d ready=%d dirty=%d est_mb=%d%n",
                all.size(), ready, dirty, bytes >> 20);
    }

    /**
     * Ce que le moteur de rendu a dessine a la derniere image : chunks visibles, sales, maillages
     * vides, triangles. Repris des metriques de l'incrustation F3, une ligne {@code Cle: valeur} par
     * chiffre, remises sur une seule ligne {@code cle=valeur}. C'est la seule preuve qu'un chunk
     * charge est aussi dessine : {@code chunks ready} compte ce qui existe, pas ce qui est a l'ecran.
     */
    private static String render(Context context) {
        WorldRenderer renderer = context.get(WorldRenderer.class);
        if (renderer == null) {
            return "render unavailable=no-renderer\n";
        }
        StringBuilder out = new StringBuilder("render");
        for (String line : renderer.getMetrics().split("\n")) {
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String key = line.substring(0, colon).trim().toLowerCase(Locale.ROOT).replace(' ', '_');
            out.append(' ').append(key).append('=').append(line.substring(colon + 1).trim());
        }
        return out.append('\n').toString();
    }

    private static String performance(Map<String, String> params) {
        if ("on".equals(params.get("perf"))) {
            PerformanceMonitor.setEnabled(true);
        }
        TObjectDoubleMap<String> means = PerformanceMonitor.getRunningMean();
        if (means.isEmpty()) {
            return "perf=disabled hint=?perf=on, ou F3 puis F4 en jeu ; "
                    + "la prochaine bascule F4 le remet a zero\n";
        }
        List<Map.Entry<String, Double>> sorted = new ArrayList<>();
        means.forEachEntry((name, ms) -> {
            sorted.add(Map.entry(name, ms));
            return true;
        });
        sorted.sort(Comparator.comparingDouble((Map.Entry<String, Double> e) -> e.getValue())
                .reversed());
        StringBuilder out = new StringBuilder("perf running_mean_ms top=" + TOP_MEANS + "\n");
        for (int i = 0; i < Math.min(TOP_MEANS, sorted.size()); i++) {
            out.append(String.format(Locale.ROOT, "  %7.3f  %s%n",
                    sorted.get(i).getValue(), sorted.get(i).getKey()));
        }
        return out.toString();
    }
}
