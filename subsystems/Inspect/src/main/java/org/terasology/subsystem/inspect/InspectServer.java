// Copyright 2021 The Terasology Foundation
// SPDX-License-Identifier: Apache-2.0

package org.terasology.subsystem.inspect;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.joml.Vector3f;
import org.joml.Vector3i;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.terasology.engine.context.Context;
import org.terasology.engine.input.cameraTarget.CameraTargetSystem;
import org.terasology.engine.logic.players.LocalPlayer;
import org.terasology.engine.world.WorldProvider;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Le point d'ecoute HTTP du canal d'inspection.
 * <p>
 * Le serveur est celui du JDK ({@code com.sun.net.httpserver}) : il apporte le routage, l'analyse
 * des en-tetes et la gestion de la connexion persistante pour zero dependance. Netty est bien
 * present dans le moteur, mais son seul precedent en arbre — {@code ServerInfoService} — est un
 * bootstrap <em>client</em>, donc il ne fournit aucun pipeline serveur a copier : il aurait fallu
 * ecrire deux cents lignes pour refaire ce que le JDK donne.
 * <p>
 * Ce serveur ne vit que si {@code --inspect-port} est passe. Pas de drapeau, pas de port, pas de
 * thread : la machine d'un joueur n'ouvre rien.
 */
public final class InspectServer {

    private static final Logger logger = LoggerFactory.getLogger(InspectServer.class);

    /** Plafond de corps de reponse. Au-dela on tronque en le disant, jamais en silence. */
    private static final int MAX_BYTES = 8 * 1024;

    private static final int HTTP_THREADS = 3;

    private final InspectBridge bridge;
    private final int port;
    private final long startedNanos = System.nanoTime();

    private HttpServer server;

    public InspectServer(InspectBridge bridge, int port) {
        this.bridge = bridge;
        this.port = port;
    }

    /**
     * Ouvre le port. Un echec est journalise et avale : un canal de diagnostic qui empecherait le
     * jeu de demarrer serait une perte nette, et un port reste en TIME_WAIT apres un plantage
     * suffirait a le provoquer.
     */
    public void start() {
        try {
            // Le bind par defaut de HttpServer ecoute sur 0.0.0.0. On veut strictement la
            // loopback : ce canal expose une porte d'ecriture sur le monde.
            server = HttpServer.create(
                    new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);

            // Sans executor explicite, HttpServer traite les requetes en serie sur le thread
            // accepteur — la parallelisation, qui est l'objet meme de ce canal, disparaitrait sans
            // le moindre message. Threads demons, sinon la JVM survivrait a la fermeture du jeu.
            AtomicInteger seq = new AtomicInteger();
            ThreadFactory factory = runnable -> {
                Thread thread = new Thread(runnable, "inspect-http-" + seq.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            };
            server.setExecutor(Executors.newFixedThreadPool(HTTP_THREADS, factory));

            server.createContext("/health", this::handleHealth);
            server.createContext("/view", this::handleView);
            server.start();
            logger.info("Canal d'inspection ouvert sur http://127.0.0.1:{}", port);
        } catch (IOException e) {
            logger.warn("Canal d'inspection indisponible sur le port {} : {}", port, e.toString());
            server = null;
        }
    }

    public void stop() {
        if (server != null) {
            server.stop(1);
            server = null;
        }
    }

    /**
     * Sonde de vie. Repondue <em>sur le thread HTTP</em>, sans passer par la file : elle doit
     * repondre precisement quand le thread de jeu ne repond plus, sinon elle ne sert a rien.
     */
    private void handleHealth(HttpExchange exchange) throws IOException {
        String body = String.format(Locale.ROOT,
                "ok state=%s last_tick_ms=%d queue=%d uptime_s=%d port=%d%n",
                bridge.getStateName(), bridge.sinceLastDrainMs(), bridge.getQueueDepth(),
                (System.nanoTime() - startedNanos) / 1_000_000_000L, port);
        respond(exchange, InspectResponse.ok(body));
    }

    /**
     * Position, cap et bloc vise. Reprend la logique de {@code ClientCommands.showView()}, qui sert
     * d'ancre de comparaison entre ce canal et le chemin clavier.
     */
    private void handleView(HttpExchange exchange) throws IOException {
        respond(exchange, bridge.call(InspectServer::view));
    }

    private static InspectResponse view(Context context) {
        LocalPlayer localPlayer = context.get(LocalPlayer.class);
        if (localPlayer == null || !localPlayer.isValid()) {
            return InspectResponse.text(409, "error=no-player reason=aucun joueur local\n");
        }
        Vector3f pos = localPlayer.getPosition(new Vector3f());
        Vector3f dir = localPlayer.getViewDirection(new Vector3f());

        // Meme convention que showView, etablie par aller-retour et non par lecture du code :
        // l'axe avant est +Z et le tangage compte positivement vers le bas. asin d'une valeur a
        // peine hors de [-1, 1] rend NaN, et un vecteur normalise peut y tomber.
        float pitch = (float) -Math.toDegrees(Math.asin(Math.max(-1.0, Math.min(1.0, dir.y))));
        float yaw = (float) Math.toDegrees(Math.atan2(dir.x, dir.z));

        StringBuilder out = new StringBuilder(String.format(Locale.ROOT,
                "pos=%.2f,%.2f,%.2f dir=%.4f,%.4f,%.4f yaw=%.2f pitch=%.2f",
                pos.x, pos.y, pos.z, dir.x, dir.y, dir.z, yaw, pitch));

        CameraTargetSystem target = context.get(CameraTargetSystem.class);
        WorldProvider worldProvider = context.get(WorldProvider.class);
        if (target != null && worldProvider != null && target.isTargetAvailable() && target.isBlock()) {
            Vector3i block = target.getTargetBlockPosition();
            out.append(String.format(Locale.ROOT, " block=%d,%d,%d uri=%s",
                    block.x, block.y, block.z, worldProvider.getBlock(block).getURI()));
        } else {
            out.append(" block=none");
        }
        out.append('\n');
        return InspectResponse.ok(out.toString());
    }

    /**
     * Ecrit la reponse, plafonnee, avec un pied de page qui dit toujours la taille et si elle a ete
     * coupee — une troncature silencieuse se lirait comme un monde qui s'arrete.
     */
    private void respond(HttpExchange exchange, InspectResponse response) throws IOException {
        byte[] payload = response.getBody().getBytes(StandardCharsets.UTF_8);
        boolean truncated = payload.length > MAX_BYTES;
        if (truncated) {
            byte[] clipped = new byte[MAX_BYTES];
            System.arraycopy(payload, 0, clipped, 0, MAX_BYTES);
            payload = clipped;
        }
        byte[] footer = String.format(Locale.ROOT, "-- bytes=%d truncated=%b%n",
                payload.length, truncated).getBytes(StandardCharsets.UTF_8);

        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(response.getStatus(), (long) payload.length + footer.length);
        try (OutputStream body = exchange.getResponseBody()) {
            body.write(payload);
            body.write(footer);
        }
    }

    /**
     * Decoupe la chaine de requete en paires. Suffit aux parametres scalaires des routes a venir ;
     * rien ici n'est encode en pourcentage.
     */
    static Map<String, String> query(HttpExchange exchange) {
        Map<String, String> params = new HashMap<>();
        String raw = exchange.getRequestURI().getRawQuery();
        if (raw == null || raw.isEmpty()) {
            return params;
        }
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                params.put(pair.substring(0, eq), pair.substring(eq + 1));
            }
        }
        return params;
    }
}
