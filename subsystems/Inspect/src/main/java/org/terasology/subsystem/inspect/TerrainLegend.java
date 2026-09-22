// Copyright 2021 The Terasology Foundation
// SPDX-License-Identifier: Apache-2.0

package org.terasology.subsystem.inspect;

import org.terasology.engine.world.block.Block;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Attribue un caractere par bloc rencontre, et rend les grilles.
 * <p>
 * <b>Pourquoi l'attribution se fait au second passage, en ordre de lecture.</b> Faite pendant le
 * parcours par chunk, elle dependrait des frontieres de chunk : deux coupes du meme terrain a des
 * decalages differents n'auraient pas la meme legende. En ordre de lecture, la legende apparait
 * dans l'ordre ou un humain lit la grille, et elle est reproductible.
 * <p>
 * Trois caracteres sont reserves, et leur distinction porte tout le sens de ces grilles :
 * {@code .} vide, {@code ?} illisible, {@code ~} liquide. « Je ne sais pas » et « il n'y a rien »
 * sont deux reponses differentes, et les confondre ferait halluciner des grottes.
 */
public final class TerrainLegend {

    public static final char AIR = '.';
    public static final char UNREADABLE = '?';
    public static final char LIQUID = '~';
    public static final char OVERFLOW = '*';

    /** Le premier bloc plein prend {@code #} : l'image se lit alors sans consulter la legende. */
    private static final char FIRST_SOLID = '#';

    /** I, l et O sont exclus : confondus avec 1 et 0 dans une grille dense. */
    private static final String POOL = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";

    /**
     * Plafonne a 24 entrees pour que la taille de la reponse soit bornable <em>avant</em>
     * l'echantillonnage — le nombre de types distincts n'est connu qu'apres.
     */
    private static final int MAX_ENTRIES = 24;

    private final Map<Block, Character> chars = new LinkedHashMap<>();
    private int poolNext;
    private boolean solidTaken;
    private int overflowCount;

    private int air;
    private int unreadable;
    private int liquid;
    private int solid;

    /**
     * Le caractere de ce bloc, en l'attribuant si c'est la premiere rencontre. Met a jour les
     * compteurs au passage.
     */
    public char charFor(Block block) {
        if (block == null) {
            unreadable++;
            return UNREADABLE;
        }
        if (block.isLiquid()) {
            liquid++;
        } else if (isEmpty(block)) {
            air++;
            return AIR;
        } else {
            solid++;
        }
        Character existing = chars.get(block);
        if (existing != null) {
            return existing;
        }
        char assigned;
        if (block.isLiquid() && !chars.containsValue(LIQUID)) {
            assigned = LIQUID;
        } else if (!block.isLiquid() && !solidTaken) {
            assigned = FIRST_SOLID;
            solidTaken = true;
        } else if (chars.size() >= MAX_ENTRIES || poolNext >= POOL.length()) {
            overflowCount++;
            return OVERFLOW;
        } else {
            assigned = POOL.charAt(poolNext++);
        }
        chars.put(block, assigned);
        return assigned;
    }

    /**
     * L'air n'a pas de predicat : il n'existe ni {@code Block.isInvisible()} ni discriminateur de
     * famille. Un bloc traversable et non ciblable est du vide pour ce qui nous occupe.
     */
    public static boolean isEmpty(Block block) {
        return block.isPenetrable() && !block.isTargetable();
    }

    public String legendLine() {
        StringBuilder out = new StringBuilder("legend ");
        out.append(AIR).append(" air  ").append(UNREADABLE).append(" unloaded");
        for (Map.Entry<Block, Character> entry : chars.entrySet()) {
            out.append("  ").append(entry.getValue()).append(' ')
               .append(entry.getKey().getURI());
        }
        if (overflowCount > 0) {
            out.append("  ").append(OVERFLOW).append(" ").append(overflowCount)
               .append(" autres (voir /block)");
        }
        return out.append('\n').toString();
    }

    public String statsLine(int cells) {
        if (cells <= 0) {
            return "stats cells=0\n";
        }
        return String.format(Locale.ROOT,
                "stats solid=%d%% air=%d%% liquid=%d%% unloaded=%d%%%n",
                percent(solid, cells), percent(air, cells),
                percent(liquid, cells), percent(unreadable, cells));
    }

    private static int percent(int part, int total) {
        return Math.round(100f * part / total);
    }

    public int unreadableCount() {
        return unreadable;
    }

    public int liquidCount() {
        return liquid;
    }

    /** Une regle de colonnes tous les dix pas, pour situer une case sans la compter a l'oeil. */
    public static String ruler(int labelWidth, int cols) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < labelWidth; i++) {
            out.append(' ');
        }
        for (int c = 0; c < cols; c++) {
            out.append(c % 10 == 0 ? '|' : '.');
        }
        return out.append('\n').toString();
    }
}
