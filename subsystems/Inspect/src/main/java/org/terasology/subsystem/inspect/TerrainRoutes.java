// Copyright 2021 The Terasology Foundation
// SPDX-License-Identifier: Apache-2.0

package org.terasology.subsystem.inspect;

import org.joml.Vector3f;
import org.joml.Vector3i;
import org.terasology.engine.context.Context;
import org.terasology.engine.input.cameraTarget.CameraTargetSystem;
import org.terasology.engine.logic.players.LocalPlayer;
import org.terasology.engine.math.Side;
import org.terasology.engine.world.WorldProvider;
import org.terasology.engine.world.block.Block;
import org.terasology.engine.world.chunks.blockdata.ExtraBlockDataManager;
import org.terasology.engine.world.liquid.LiquidExtraDataSystem;
import org.terasology.engine.world.liquid.LiquidFlowSolver;

import java.util.Locale;
import java.util.Map;

/**
 * Les routes de terrain : {@code /slice}, {@code /cube} et {@code /block}.
 * <p>
 * Elles rendent le monde en grilles de caracteres parce que le destinataire est une fenetre de
 * contexte de modele : une coupe de 288 cases tient en douze lignes, la ou la meme information en
 * JSON coute des milliers de jetons de ponctuation.
 * <p>
 * <b>L'ancre par defaut est le joueur, jamais la visee.</b> L'eau et la lave sont
 * {@code targetable: false}, donc le rayon du reticule traverse une mare et atterrit sur le solide
 * derriere : un defaut derive de la visee manquerait systematiquement ce que ces routes existent
 * pour montrer.
 */
public final class TerrainRoutes {

    /** Le seul plafond qui borne vraiment le temps passe sur le thread de jeu. */
    private static final int MAX_PROBES = 262144;

    /** Sous le plafond d'octets du serveur, pour qu'une reponse pleine ne soit jamais coupee. */
    private static final int MAX_BODY = 7168;

    private static final int SLICE_MAX_W = 64;
    private static final int SLICE_MAX_H = 48;
    private static final int CUBE_MAX_R = 8;
    private static final int SURFACE_MAX_R = 24;
    private static final int SURFACE_MAX_DEPTH = 256;

    private TerrainRoutes() {
    }

    /** Refus immediat, porte jusqu'au gestionnaire sans polluer chaque signature. */
    private static final class BadParam extends RuntimeException {
        private final transient InspectResponse response;

        BadParam(InspectResponse response) {
            super(null, null, false, false);
            this.response = response;
        }
    }

    // ----------------------------------------------------------------- routes

    public static InspectResponse slice(Context context, Map<String, String> params) {
        try {
            long began = System.nanoTime();
            Vector3i anchor = new Vector3i();
            GridSpec.Anchor kind = resolveAnchor(context, params, anchor);
            String fallback = params.containsKey("at") && kind != GridSpec.Anchor.TARGET
                    ? "no-target" : null;

            GridSpec.Axis axis = axis(params.getOrDefault("axis", "z"));
            int w = intParam(params, "w", 24, 1, SLICE_MAX_W);
            int h = intParam(params, "h", 16, 1, SLICE_MAX_H);
            boolean wantFlow = !"0".equals(params.get("flow"));

            // Les lignes descendent en Y : une coupe se lit comme on la dessine.
            GridSpec.Dim rows = GridSpec.Dim.descending(GridSpec.Axis.Y, anchor.y + h / 2, h);
            GridSpec.Axis colAxis = axis == GridSpec.Axis.X ? GridSpec.Axis.Z : GridSpec.Axis.X;
            int colCentre = GridSpec.coord(colAxis, anchor.x, anchor.y, anchor.z);
            GridSpec.Dim cols = GridSpec.Dim.ascending(colAxis, colCentre - w / 2, w);
            int plane = GridSpec.coord(axis, anchor.x, anchor.y, anchor.z);
            GridSpec.Dim layers = GridSpec.Dim.ascending(axis, plane, 1);

            if (axis == GridSpec.Axis.Y) {
                // Le plan horizontal : lignes en Z croissant, colonnes en X.
                rows = GridSpec.Dim.ascending(GridSpec.Axis.Z, anchor.z - h / 2, h);
                cols = GridSpec.Dim.ascending(GridSpec.Axis.X, anchor.x - w / 2, w);
                layers = GridSpec.Dim.ascending(GridSpec.Axis.Y, anchor.y, 1);
            }

            GridSpec spec = new GridSpec(anchor, kind, fallback, layers, rows, cols, wantFlow);
            checkBudget(spec.cellCount(), estimateBytes(1, h, w), "h");

            int flowSlot = flowSlot(context);
            long sampleBegan = System.nanoTime();
            TerrainSample sample = TerrainSample.read(context, spec, flowSlot);
            long sampled = System.nanoTime();

            StringBuilder out = new StringBuilder();
            TerrainLegend legend = new TerrainLegend();
            String grid = renderPlane(spec, sample, legend, 0);
            String flowGrid = wantFlow && sample.hasFlow() ? renderFlow(spec, sample) : "";

            out.append(String.format(Locale.ROOT,
                    "slice axis=%s plane=%d anchor=%s%s pos=%d,%d,%d w=%d h=%d ms=%.1f sample=%.1f%n",
                    axis.name().toLowerCase(Locale.ROOT), plane, spec.anchorLabel(),
                    fallback == null ? "" : " fallback=" + fallback,
                    anchor.x, anchor.y, anchor.z, w, h,
                    ms(began, System.nanoTime()), ms(began, sampled)));
            out.append(String.format(Locale.ROOT,
                    "grid rows=%s %d..%d desc  cols=%s %d..%d asc  cells=%d  chunks=%d/%d%n",
                    rows.axis(), rows.coordAt(0), rows.coordAt(h - 1),
                    cols.axis(), cols.coordAt(0), cols.coordAt(w - 1),
                    spec.cellCount(), sample.chunksLoaded(), sample.chunksTotal()));
            out.append(legend.statsLine(spec.cellCount()));
            out.append(legend.legendLine());
            if (sample.chunksLoaded() == 0) {
                out.append("note=region entierement non chargee\n");
            }
            out.append(grid).append(flowGrid);
            return InspectResponse.ok(out.toString());
        } catch (BadParam e) {
            return e.response;
        }
    }

    public static InspectResponse cube(Context context, Map<String, String> params) {
        try {
            long began = System.nanoTime();
            Vector3i anchor = new Vector3i();
            GridSpec.Anchor kind = resolveAnchor(context, params, anchor);
            int r = intParam(params, "r", 3, 0, CUBE_MAX_R);
            int side = 2 * r + 1;
            boolean wantFlow = !"0".equals(params.get("flow"));

            GridSpec.Dim layers = GridSpec.Dim.descending(GridSpec.Axis.Y, anchor.y + r, side);
            GridSpec.Dim rows = GridSpec.Dim.ascending(GridSpec.Axis.Z, anchor.z - r, side);
            GridSpec.Dim cols = GridSpec.Dim.ascending(GridSpec.Axis.X, anchor.x - r, side);
            GridSpec spec = new GridSpec(anchor, kind, null, layers, rows, cols, wantFlow);
            checkBudget(spec.cellCount(), estimateBytes(side, side, side), "r");

            TerrainSample sample = TerrainSample.read(context, spec, flowSlot(context));
            TerrainLegend legend = new TerrainLegend();

            StringBuilder body = new StringBuilder();
            for (int layer = 0; layer < side; layer++) {
                String plane = renderPlane(spec, sample, legend, layer);
                body.append(String.format(Locale.ROOT, "y=%d%s", layers.coordAt(layer),
                        collapse(plane))).append('\n');
                if (collapse(plane).isEmpty()) {
                    body.append(plane);
                }
            }

            StringBuilder out = new StringBuilder();
            out.append(String.format(Locale.ROOT,
                    "cube anchor=%s pos=%d,%d,%d r=%d ms=%.1f%n",
                    spec.anchorLabel(), anchor.x, anchor.y, anchor.z, r,
                    ms(began, System.nanoTime())));
            out.append(String.format(Locale.ROOT,
                    "grid layers=y %d..%d desc  rows=z  cols=x  cells=%d  chunks=%d/%d%n",
                    layers.coordAt(0), layers.coordAt(side - 1), spec.cellCount(),
                    sample.chunksLoaded(), sample.chunksTotal()));
            out.append(legend.statsLine(spec.cellCount()));
            out.append(legend.legendLine()).append(body);
            return InspectResponse.ok(out.toString());
        } catch (BadParam e) {
            return e.response;
        }
    }

    /**
     * La vue de dessus : la premiere surface de chaque colonne, plus une grille de hauteurs.
     * <p>
     * Le plafond par defaut est {@code min(plafond charge, ancreY + 48)} et non le seul plafond
     * charge : un joueur enfoui a deux cents blocs sous celui-ci verrait sinon la surface du
     * monde plutot que ce qui l'entoure.
     */
    public static InspectResponse surface(Context context, Map<String, String> params) {
        try {
            long began = System.nanoTime();
            Vector3i anchor = new Vector3i();
            GridSpec.Anchor kind = resolveAnchor(context, params, anchor);
            int r = intParam(params, "r", 8, 1, SURFACE_MAX_R);
            int depth = intParam(params, "depth", 64, 1, SURFACE_MAX_DEPTH);
            int width = 2 * r + 1;

            WorldProvider world = context.get(WorldProvider.class);
            if (world == null) {
                return InspectResponse.text(409, "error=no-world reason=aucun monde\n");
            }
            int loadedTop = Integer.MIN_VALUE;
            for (var region : world.getRelevantRegions()) {
                loadedTop = Math.max(loadedTop, region.maxY());
            }
            int from = intParam(params, "from",
                    loadedTop == Integer.MIN_VALUE ? anchor.y + 48
                            : Math.min(loadedTop, anchor.y + 48),
                    Integer.MIN_VALUE, Integer.MAX_VALUE);

            int columns = width * width;
            checkBudget(columns * depth, 320 + 24 * 28 + 2 * width * (width + 7), "r");

            SurfaceSample sample = SurfaceSample.read(context, anchor.x - r, anchor.z - r,
                    width, from, depth);

            TerrainLegend legend = new TerrainLegend();
            int hmin = Integer.MAX_VALUE;
            int hmax = Integer.MIN_VALUE;
            for (int i = 0; i < columns; i++) {
                if (sample.height(i) != SurfaceSample.NO_HEIGHT) {
                    hmin = Math.min(hmin, sample.height(i));
                    hmax = Math.max(hmax, sample.height(i));
                }
            }

            StringBuilder mat = new StringBuilder(TerrainLegend.ruler(5, width));
            StringBuilder hgt = new StringBuilder(TerrainLegend.ruler(5, width));
            for (int row = 0; row < width; row++) {
                mat.append(String.format(Locale.ROOT, "%4d ", anchor.z - r + row));
                hgt.append(String.format(Locale.ROOT, "%4d ", anchor.z - r + row));
                for (int col = 0; col < width; col++) {
                    int i = row * width + col;
                    Block block = sample.surface(i);
                    mat.append(block == null && !sample.seen(i)
                            ? TerrainLegend.UNREADABLE : legend.charFor(block));
                    hgt.append(sample.height(i) == SurfaceSample.NO_HEIGHT ? '-'
                            : Character.forDigit((sample.height(i) - hmin) % 36, 36));
                }
                mat.append('\n');
                hgt.append('\n');
            }

            StringBuilder out = new StringBuilder();
            out.append(String.format(Locale.ROOT,
                    "surface anchor=%s pos=%d,%d,%d r=%d from=%d depth=%d ms=%.1f%n",
                    spec(kind), anchor.x, anchor.y, anchor.z, r, from, depth,
                    ms(began, System.nanoTime())));
            out.append(String.format(Locale.ROOT,
                    "grid rows=z %d..%d asc  cols=x %d..%d asc  columns=%d probes=%d chunks=%d/%d%n",
                    anchor.z - r, anchor.z + r, anchor.x - r, anchor.x + r,
                    columns, sample.probes(), sample.chunksLoaded(), sample.chunksTotal()));
            out.append(String.format(Locale.ROOT, "stats found=%d%% y=%s%n",
                    Math.round(100f * sample.resolved() / columns),
                    hmin == Integer.MAX_VALUE ? "aucune" : hmin + ".." + hmax));
            out.append("legend . rien trouve  ? unloaded")
               .append(legend.legendLine().substring("legend . air  ? unloaded".length()));
            out.append("top\n").append(mat);
            if (hmin != Integer.MAX_VALUE) {
                out.append(String.format(Locale.ROOT, "height  base36 de (y - %d)  -=rien%n", hmin));
                out.append(hgt);
            }
            return InspectResponse.ok(out.toString());
        } catch (BadParam e) {
            return e.response;
        }
    }

    private static String spec(GridSpec.Anchor kind) {
        return kind.name().toLowerCase(Locale.ROOT);
    }

    /**
     * Le forage sur une case. Volontairement un surensemble de la commande {@code liquidFlow}, dans
     * son vocabulaire exact, pour que les deux sorties se comparent mecaniquement — c'est ce qui
     * prouve que le chemin par chunk s'accorde avec {@code WorldProvider.getBlock}.
     */
    public static InspectResponse block(Context context, Map<String, String> params) {
        try {
            WorldProvider world = context.get(WorldProvider.class);
            if (world == null) {
                return InspectResponse.text(409, "error=no-world reason=aucun monde\n");
            }
            Vector3i anchor = new Vector3i();
            GridSpec.Anchor kind = resolveAnchor(context, params, anchor);
            if (!world.isBlockRelevant(anchor)) {
                return InspectResponse.ok(String.format(Locale.ROOT,
                        "block pos=%d,%d,%d unloaded=true anchor=%s%n",
                        anchor.x, anchor.y, anchor.z, kind.name().toLowerCase(Locale.ROOT)));
            }
            Block block = world.getBlock(anchor);
            int flowSlot = flowSlot(context);

            StringBuilder out = new StringBuilder();
            out.append(String.format(Locale.ROOT, "block pos=%d,%d,%d uri=%s name=%s anchor=%s%n",
                    anchor.x, anchor.y, anchor.z, block.getURI(), block.getDisplayName(),
                    kind.name().toLowerCase(Locale.ROOT)));
            if (block.isLiquid() && flowSlot >= 0) {
                int flow = world.getExtraData(flowSlot, anchor);
                out.append(String.format(Locale.ROOT,
                        "liquid=true water=%b %s range=%d%n", block.isWater(),
                        flow == 0 ? "(a source)" : "flow=" + flow + " steps=" + (flow - 1),
                        block.getFlowRange()));
            }
            out.append(String.format(Locale.ROOT,
                    "penetrable=%b translucent=%b targetable=%b replaceable=%b%n",
                    block.isPenetrable(), block.isTranslucent(), block.isTargetable(),
                    block.isReplacementAllowed()));
            out.append(String.format(Locale.ROOT,
                    "luminance=%d hardness=%d viscosity=%d light=%d sunlight=%d%n",
                    block.getLuminance(), block.getHardness(), block.getViscosity(),
                    world.getLight(anchor), world.getSunlight(anchor)));

            Vector3i probe = new Vector3i();
            for (Side side : Side.allSides()) {
                probe.set(anchor).add(side.direction());
                out.append("  ").append(side).append(": ");
                if (!world.isBlockRelevant(probe)) {
                    out.append("unloaded\n");
                    continue;
                }
                Block neighbour = world.getBlock(probe);
                out.append(neighbour.getURI());
                if (neighbour.isLiquid() && flowSlot >= 0) {
                    int flow = world.getExtraData(flowSlot, probe);
                    out.append(flow == 0 ? " (a source)" : " flow=" + flow);
                } else if (LiquidFlowSolver.isFlowPassable(neighbour)) {
                    out.append(" (free)");
                }
                out.append('\n');
            }
            return InspectResponse.ok(out.toString());
        } catch (BadParam e) {
            return e.response;
        }
    }

    // ----------------------------------------------------------------- rendu

    private static String renderPlane(GridSpec spec, TerrainSample sample,
                                      TerrainLegend legend, int layer) {
        GridSpec.Dim rows = spec.rows();
        GridSpec.Dim cols = spec.cols();
        StringBuilder out = new StringBuilder();
        out.append(TerrainLegend.ruler(5, cols.count()));
        for (int r = 0; r < rows.count(); r++) {
            out.append(String.format(Locale.ROOT, "%4d ", rows.coordAt(r)));
            for (int c = 0; c < cols.count(); c++) {
                int index = (layer * rows.count() + r) * cols.count() + c;
                out.append(legend.charFor(sample.block(index)));
            }
            out.append('\n');
        }
        return out.toString();
    }

    /**
     * La grille d'ecoulement est separee de celle des blocs, et rognee aux lignes qui portent du
     * liquide. Melanger les chiffres dans la grille des blocs detruirait la <em>forme</em> de la
     * coulee, seule raison de regarder une image ; et une grille a 90 % de pierre serait 90 % de
     * gachis dans une fenetre de contexte.
     */
    private static String renderFlow(GridSpec spec, TerrainSample sample) {
        GridSpec.Dim rows = spec.rows();
        GridSpec.Dim cols = spec.cols();
        StringBuilder body = new StringBuilder();
        int liquidCells = 0;
        int sources = 0;
        int maxFlow = 0;
        for (int r = 0; r < rows.count(); r++) {
            StringBuilder line = new StringBuilder();
            boolean any = false;
            for (int c = 0; c < cols.count(); c++) {
                byte flow = sample.flow(r * cols.count() + c);
                if (flow == TerrainSample.FLOW_NONE) {
                    line.append(sample.block(r * cols.count() + c) == null ? '?' : '.');
                } else {
                    any = true;
                    liquidCells++;
                    if (flow == 0) {
                        sources++;
                    }
                    maxFlow = Math.max(maxFlow, flow);
                    line.append(Character.forDigit(flow, 16));
                }
            }
            if (any) {
                body.append(String.format(Locale.ROOT, "%4d ", rows.coordAt(r)))
                    .append(line).append('\n');
            }
        }
        if (liquidCells == 0) {
            return "";
        }
        return String.format(Locale.ROOT,
                "flow  0=source  liquid_cells=%d sources=%d max_flow=%d%n",
                liquidCells, sources, maxFlow)
                + TerrainLegend.ruler(5, cols.count()) + body;
    }

    /** Une couche uniforme se replie en une ligne : dans un cube surtout vide, c'est tout le gain. */
    private static String collapse(String plane) {
        String cells = plane.substring(plane.indexOf('\n') + 1).replaceAll("[0-9 ]|\\n", "");
        if (cells.isEmpty()) {
            return "";
        }
        char first = cells.charAt(0);
        if (first != TerrainLegend.AIR && first != TerrainLegend.UNREADABLE) {
            return "";
        }
        for (int i = 1; i < cells.length(); i++) {
            if (cells.charAt(i) != first) {
                return "";
            }
        }
        return first == TerrainLegend.AIR ? " tout air" : " tout non charge";
    }

    // ------------------------------------------------------------- utilitaires

    private static GridSpec.Anchor resolveAnchor(Context context, Map<String, String> params,
                                                 Vector3i dest) {
        boolean explicit = params.containsKey("x") || params.containsKey("y")
                || params.containsKey("z");
        GridSpec.Anchor kind = GridSpec.Anchor.PLAYER;

        if ("target".equals(params.get("at"))) {
            CameraTargetSystem target = context.get(CameraTargetSystem.class);
            if (target != null && target.isTargetAvailable() && target.isBlock()) {
                dest.set(target.getTargetBlockPosition());
                kind = GridSpec.Anchor.TARGET;
            }
        }
        if (kind == GridSpec.Anchor.PLAYER) {
            LocalPlayer player = context.get(LocalPlayer.class);
            if (player == null || !player.isValid()) {
                throw new BadParam(InspectResponse.text(409,
                        "error=no-player reason=aucun joueur local\n"));
            }
            Vector3f pos = player.getPosition(new Vector3f());
            // Tronquer soi-meme : getBlock(Vector3fc) arrondit HALF_UP la ou getLight tronque, donc
            // les surcharges flottantes se contredisent sur « dans quel bloc suis-je ».
            dest.set((int) Math.floor(pos.x), (int) Math.floor(pos.y), (int) Math.floor(pos.z));
        }
        if (explicit) {
            dest.set(intParam(params, "x", dest.x, Integer.MIN_VALUE, Integer.MAX_VALUE),
                    intParam(params, "y", dest.y, Integer.MIN_VALUE, Integer.MAX_VALUE),
                    intParam(params, "z", dest.z, Integer.MIN_VALUE, Integer.MAX_VALUE));
            kind = GridSpec.Anchor.EXPLICIT;
        }
        return kind;
    }

    /** -1 quand le champ n'est pas enregistre : la grille des blocs sort quand meme. */
    static int flowSlot(Context context) {
        ExtraBlockDataManager manager = context.get(ExtraBlockDataManager.class);
        if (manager == null) {
            return -1;
        }
        try {
            return manager.getSlotNumber(LiquidExtraDataSystem.FLOW_FIELD);
        } catch (IllegalArgumentException e) {
            return -1;
        }
    }

    private static GridSpec.Axis axis(String raw) {
        switch (raw) {
            case "x": return GridSpec.Axis.X;
            case "y": return GridSpec.Axis.Y;
            case "z": return GridSpec.Axis.Z;
            default:
                throw new BadParam(InspectResponse.badParam("axis", "attendu x, y ou z"));
        }
    }

    private static int intParam(Map<String, String> params, String name, int fallback,
                                int min, int max) {
        String raw = params.get(name);
        if (raw == null || raw.isEmpty()) {
            return fallback;
        }
        int value;
        try {
            value = Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new BadParam(InspectResponse.badParam(name, "entier attendu, recu " + raw));
        }
        if (value < min || value > max) {
            throw new BadParam(InspectResponse.badParam(name, String.format(Locale.ROOT,
                    "%d hors des bornes %d..%d", value, min, max)));
        }
        return value;
    }

    private static int estimateBytes(int layers, int rows, int cols) {
        return 320 + 24 * 28 + layers * rows * (cols + 7);
    }

    /**
     * Refuse plutot que de raboter : une grille dont le rayon a ete reduit en silence repond a une
     * question que personne n'a posee.
     */
    private static void checkBudget(int probes, int bytes, String blame) {
        if (probes > MAX_PROBES) {
            throw new BadParam(InspectResponse.badParam(blame, String.format(Locale.ROOT,
                    "%d sondes, plafond %d", probes, MAX_PROBES)));
        }
        if (bytes > MAX_BODY) {
            throw new BadParam(InspectResponse.badParam(blame, String.format(Locale.ROOT,
                    "environ %d octets, plafond %d", bytes, MAX_BODY)));
        }
    }

    private static double ms(long fromNanos, long toNanos) {
        return (toNanos - fromNanos) / 1_000_000.0;
    }
}
