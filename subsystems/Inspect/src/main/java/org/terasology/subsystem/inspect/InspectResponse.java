// Copyright 2021 The Terasology Foundation
// SPDX-License-Identifier: Apache-2.0

package org.terasology.subsystem.inspect;

/**
 * Une reponse du canal d'inspection : un code HTTP et un corps en texte brut.
 * <p>
 * Le texte brut plutot que JSON parce que le destinataire est une fenetre de contexte de modele,
 * ou les accolades, les guillemets et les cles repetees d'un JSON coutent environ un tiers des
 * jetons sans rien apprendre a personne.
 * <p>
 * Les trois refus ont chacun leur code, et la distinction est le but : « le moteur ne repond pas »
 * (503), « il repond, mais il n'y a pas de monde » (409) et « ta question est mal formee » (400)
 * appellent trois reactions differentes de l'appelant. Un unique code d'erreur les rendrait
 * indiscernables, et c'est exactement ce que le canal est cense supprimer.
 */
public final class InspectResponse {

    private final int status;
    private final String body;

    private InspectResponse(int status, String body) {
        this.status = status;
        this.body = body;
    }

    public static InspectResponse ok(String body) {
        return new InspectResponse(200, body);
    }

    public static InspectResponse text(int status, String body) {
        return new InspectResponse(status, body);
    }

    /**
     * Le thread de jeu n'a pas vide la file a temps : bloque, en plein chargement, ou en train de
     * s'arreter. L'age du dernier vidage est joint parce qu'il distingue « occupe » de « mort ».
     */
    public static InspectResponse unavailable(String state, long sinceLastDrainMs) {
        return new InspectResponse(503, String.format(
                "error=unavailable state=%s last_tick_ms=%d%n", state, sinceLastDrainMs));
    }

    /**
     * La file s'est bien videe, mais aucun monde n'est charge. Emis <em>depuis le thread de jeu</em>,
     * donc cette reponse fait foi — au contraire d'un delai expire, qui ne sait rien.
     */
    public static InspectResponse noWorld(String state) {
        return new InspectResponse(409, String.format("error=no-world state=%s%n", state));
    }

    public static InspectResponse badParam(String name, String reason) {
        return new InspectResponse(400, String.format(
                "error=bad-param name=%s reason=%s%n", name, reason));
    }

    public static InspectResponse notFound(String path) {
        return new InspectResponse(404, String.format("error=not-found path=%s%n", path));
    }

    public static InspectResponse error(Throwable cause) {
        return new InspectResponse(500, String.format("error=exception type=%s message=%s%n",
                cause.getClass().getSimpleName(), String.valueOf(cause.getMessage())));
    }

    public int getStatus() {
        return status;
    }

    public String getBody() {
        return body;
    }
}
