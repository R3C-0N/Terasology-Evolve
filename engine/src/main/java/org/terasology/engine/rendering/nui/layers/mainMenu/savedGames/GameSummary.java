// Copyright 2026 The Terasology Foundation
// SPDX-License-Identifier: Apache-2.0
package org.terasology.engine.rendering.nui.layers.mainMenu.savedGames;

import org.terasology.engine.core.module.ModuleManager;
import org.terasology.engine.core.module.StandardModuleExtension;
import org.terasology.engine.game.GameManifest;
import org.terasology.engine.i18n.TranslationSystem;
import org.terasology.engine.utilities.time.DateTimeHelper;
import org.terasology.gestalt.module.Module;
import org.terasology.gestalt.naming.Name;
import org.terasology.gestalt.naming.NameVersion;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;

/**
 * Met en mots ce qu'une partie sauvegardee a d'interessant pour le joueur : quand il y a joue,
 * combien de temps, et dans quel mode. Les ecrans de selection n'affichent plus que cela — ni
 * generateur de monde, ni liste de modules, ni graine.
 */
public final class GameSummary {

    private static final SimpleDateFormat TIME = new SimpleDateFormat("HH:mm");
    private static final SimpleDateFormat FULL = new SimpleDateFormat("d MMM yyyy, HH:mm");

    private GameSummary() {
    }

    /**
     * Derniere session, en date relative : « Aujourd'hui, 21:43 », « Hier, 09:12 », sinon la date complete.
     */
    public static String lastPlayed(GameInfo info, TranslationSystem i18n) {
        Date when = info.getTimestamp();
        int days = daysAgo(when);
        if (days == 0) {
            return i18n.translate("${engine:menu#today}") + ", " + TIME.format(when);
        }
        if (days == 1) {
            return i18n.translate("${engine:menu#yesterday}") + ", " + TIME.format(when);
        }
        return FULL.format(when);
    }

    /**
     * Temps de jeu, lu sur l'horloge du monde comme le fait l'ecran de details : l'ecart entre
     * l'epoque et {@link GameManifest#getTime()}.
     */
    public static String playtime(GameInfo info) {
        return DateTimeHelper.getDeltaBetweenTimestamps(new Date(0).getTime(), info.getManifest().getTime());
    }

    /**
     * Mode de jeu : le nom d'affichage du module gameplay du manifeste. Un manifeste en porte
     * exactement un ; s'il n'en porte aucun — sauvegarde d'une version anterieure, module desinstalle —
     * on rend une chaine vide plutot que d'inventer un mode.
     */
    public static String mode(GameInfo info, ModuleManager moduleManager) {
        for (NameVersion nameVersion : info.getManifest().getModules()) {
            Name id = nameVersion.getName();
            Module module = moduleManager.getRegistry().getLatestModuleVersion(id);
            if (module != null && StandardModuleExtension.isGameplayModule(module)) {
                return module.getMetadata().getDisplayName().value();
            }
        }
        return "";
    }

    /** Ligne secondaire d'une entree de liste : « Hier · 16 minutes de jeu ». */
    public static String listSubtitle(GameInfo info, TranslationSystem i18n) {
        return lastPlayed(info, i18n) + "  ·  " + playtime(info);
    }

    private static int daysAgo(Date when) {
        Calendar then = midnight(when);
        Calendar now = midnight(new Date());
        long millis = now.getTimeInMillis() - then.getTimeInMillis();
        return (int) (millis / (24L * 60 * 60 * 1000));
    }

    private static Calendar midnight(Date date) {
        Calendar cal = Calendar.getInstance();
        cal.setTime(date);
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        return cal;
    }
}
