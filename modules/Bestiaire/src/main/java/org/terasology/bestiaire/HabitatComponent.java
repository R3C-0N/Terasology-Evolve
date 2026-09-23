// Copyright 2026 R3C-0N
// SPDX-License-Identifier: Apache-2.0
package org.terasology.bestiaire;

import org.terasology.gestalt.entitysystem.component.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Where a species belongs, in numbers on its prefab.
 * <p>
 * A creature without this component never appears on its own. That is the right answer for the mannequin, and
 * it is the right answer for the nine beasts of the deeps, the demonic world and the fey woods: their worlds
 * do not exist yet, and D7 wants the present peaceful. They keep their totem and wait.
 * <p>
 * <strong>Biomes are strings, not an enum.</strong> {@code CoreWorlds} is a git submodule and this module has
 * no business depending on it to name a forest; a future biome module can supply habitats without anyone
 * touching this file. The price is that a typo disables a species in silence, which is exactly why
 * {@link FauneCommands} prints the component back as the server parsed it, and why an unknown biome name is
 * logged once at start-up.
 */
public class HabitatComponent implements Component<HabitatComponent> {

    /** Biome ids it lives in, as {@code "CoreWorlds:FOREST"}. Empty means nowhere, so nowhere it goes. */
    public List<String> biomes = new ArrayList<>();

    /** Its weight in the draw among the species that fit the same spot. */
    public float densite = 1f;

    /** How many appear together, and how far apart they are laid down. */
    public int groupeMin = 1;
    public int groupeMax = 1;
    public float ecart = 3f;

    /**
     * The hours it comes out, as fractions of a day — 0 is midnight, and {@code de > a} spans the night.
     * <p>
     * The engine's own dawn and dusk are 0.3 and 0.7, so a night creature is {@code 0.7 → 0.3}.
     */
    public float heureDe;
    public float heureA = 1f;

    /** How many of this species may already live within the crowd radius before the spot is refused. */
    public int plafond = 4;

    /**
     * The block above the ground must be one of these. Empty turns the test off.
     * <p>
     * A list rather than a flag because the bestiary asks for bushes and the world has none: the pheasant
     * hides in tall grass today, and the day something bush-shaped is placed by a rasterizer, the fix is one
     * more string in one prefab and not a line of code.
     */
    public List<String> couvert = new ArrayList<>();

    /** A liquid must be within this many blocks. 0 turns the test off. */
    public float eau;

    /** Sky light at the spot must be at most this. 16 turns the test off; 15 is open sky. */
    public int ombre = 16;

    /**
     * Sky light at the spot must be at least this, and 15 means open sky.
     * <p>
     * The guard against appearing under a roof, in a corridor or inside a walled base — the engine records no
     * provenance for a block, so this and the ground material are the whole defence. A forest creature lowers
     * it: a canopy shades the ground it lives on, and at 15 a deer would only ever appear in clearings. Even
     * at ten a roofed room, which reads zero, stays out.
     */
    public int ciel = 15;

    /**
     * How many of the enabled terrain tests must pass. 0 means all of them.
     * <p>
     * This is how the bear gets "water's edge <em>or</em> a dark hollow" without a branch in code:
     * {@code eau 12, ombre 4, exige 1}.
     */
    public int exige;

    /**
     * Ground it stands on, as block uris. Empty means the module's own list of surface materials.
     * <p>
     * The deeps needed it and nothing else does: a gallery floor is stone, and stone on the surface is a
     * mountain top a cow has no business on. A list rather than a flag for the same reason {@link #couvert}
     * is one — the day a cave grows its own floor material, the fix is a string in a prefab.
     */
    public List<String> sols = new ArrayList<>();

    /**
     * How many of the eight neighbouring columns may sit more than a block off. 8 turns the flatness test off.
     * <p>
     * Flatness is a proxy for "nobody built this", and it is the wrong proxy underground: a gallery is a tube,
     * so two or three of the eight neighbours are wall wherever one stands, and a cave creature would be
     * refused every spot in the world. What replaces it down there is stronger than flatness ever was — see
     * {@link FauneAuthoritySystem}, where the underground biome is the one piece of real provenance the
     * engine offers.
     */
    public int relief = 2;

    /**
     * Blocks of headroom for a species laid <em>under the ceiling</em> rather than on the floor. 0 is the floor.
     * <p>
     * The cave lizard is the only one, and the number is both a placement and a test: a roof further up than
     * this is no roof at all, and the spot is refused. Its fall is then whatever the gallery was high, which
     * is why the number is small — a lizard dropping from twelve blocks would kill itself on the landing.
     */
    public float voute;

    /** A species that takes the place of a group member, with this chance, keeping the same band. */
    public String melange = "";
    public float chanceMelange;

    @Override
    public void copyFrom(HabitatComponent other) {
        this.biomes = new ArrayList<>(other.biomes);
        this.densite = other.densite;
        this.groupeMin = other.groupeMin;
        this.groupeMax = other.groupeMax;
        this.ecart = other.ecart;
        this.heureDe = other.heureDe;
        this.heureA = other.heureA;
        this.plafond = other.plafond;
        this.couvert = new ArrayList<>(other.couvert);
        this.eau = other.eau;
        this.ombre = other.ombre;
        this.ciel = other.ciel;
        this.exige = other.exige;
        this.sols = new ArrayList<>(other.sols);
        this.relief = other.relief;
        this.voute = other.voute;
        this.melange = other.melange;
        this.chanceMelange = other.chanceMelange;
    }

    /** Whether a time of day, as a fraction where 0 is midnight, falls in this creature's hours. */
    public boolean aLHeure(float fraction) {
        if (heureDe <= heureA) {
            return fraction >= heureDe && fraction <= heureA;
        }
        return fraction >= heureDe || fraction <= heureA;
    }
}
