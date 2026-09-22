// Copyright 2021 The Terasology Foundation
// SPDX-License-Identifier: Apache-2.0

package org.terasology.subsystem.inspect;

import org.terasology.engine.context.Context;
import org.terasology.engine.core.modes.GameState;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

/**
 * Le sas entre les threads HTTP et le thread de jeu.
 * <p>
 * Le moteur ne tolere pas qu'on lise ses entites depuis un autre thread : {@code ComponentTable}
 * garde une carte externe concurrente dont les cartes internes sont de simples
 * {@code TLongObjectHashMap}, que le thread de jeu reecrit pendant qu'on les parcourt. Toute
 * lecture doit donc etre executee <em>sur</em> le thread de jeu, et c'est ce que cette classe fait.
 * <p>
 * <b>Pourquoi une file privee plutot que {@code GameThread.synch()}.</b> Trois raisons, toutes
 * bloquantes :
 * <ul>
 * <li>{@code GameThread.asynch} empile avec {@code push()}, donc en LIFO : deux requetes
 *     concurrentes se repondraient dans l'ordre inverse.</li>
 * <li>{@code GameThread.synch} attend sur un {@code Semaphore.acquire()} <em>sans delai</em> : si
 *     le moteur cale ou s'arrete, le thread HTTP est perdu pour de bon.</li>
 * <li>Le runnable qu'on lui confie ne recoit aucune poignee sur l'etat courant, donc il ne peut
 *     pas distinguer « pas de monde charge » de « moteur bloque » — la distinction meme qui rend
 *     ce canal utile.</li>
 * </ul>
 * <p>
 * {@code postUpdate} est appele a chaque tick pour <em>tout</em> etat, menu compris, donc la file
 * se vide toujours et on sait toujours dire pourquoi on ne repond pas.
 */
public final class InspectBridge {

    /** Deux secondes couvrent l'image a 100 ms du mode veille et les a-coups de chargement. */
    public static final long DEFAULT_TIMEOUT_MS = 2000;

    private static final int QUEUE_CAPACITY = 64;

    private final BlockingQueue<Task> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);

    /**
     * Ecrits une fois par image, lus par les threads HTTP. Ils ne coutent rien et transforment un
     * delai expire, qui n'apprend rien, en diagnostic sur lequel l'appelant peut agir.
     */
    private volatile String stateName = "not-started";
    private volatile long lastDrainNanos = System.nanoTime();
    private volatile boolean closed;

    /**
     * Fait executer {@code work} sur le thread de jeu et attend sa reponse, au plus
     * {@code timeoutMs}.
     */
    public InspectResponse call(Function<Context, InspectResponse> work, long timeoutMs) {
        CompletableFuture<InspectResponse> future = new CompletableFuture<>();
        // File pleine : le moteur est bloque et les requetes s'empilent. Refuser tout de suite vaut
        // mieux qu'attendre un vidage qui n'arrivera pas.
        if (closed || !queue.offer(new Task(work, future))) {
            return InspectResponse.unavailable(stateName, sinceLastDrainMs());
        }
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            // Marquer le future comme abandonne : si le vidage arrive en retard, le drain verra
            // isDone() et sautera la tache au lieu de payer un calcul que plus personne n'attend.
            future.completeExceptionally(e);
            return InspectResponse.unavailable(stateName, sinceLastDrainMs());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return InspectResponse.unavailable(stateName, sinceLastDrainMs());
        } catch (ExecutionException e) {
            return InspectResponse.error(e.getCause() == null ? e : e.getCause());
        }
    }

    public InspectResponse call(Function<Context, InspectResponse> work) {
        return call(work, DEFAULT_TIMEOUT_MS);
    }

    /**
     * Vide la file. A n'appeler que depuis le thread de jeu, une fois par image.
     */
    public void drain(GameState currentState) {
        stateName = currentState == null ? "none" : currentState.getClass().getSimpleName();
        lastDrainNanos = System.nanoTime();

        Context context = currentState == null ? null : currentState.getContext();
        Task task;
        while ((task = queue.poll()) != null) {
            if (task.future.isDone()) {
                continue;   // l'appelant est parti en delai expire
            }
            // Le try/catch n'est pas une precaution de style : une exception qui s'echapperait
            // d'ici remonterait dans TerasologyEngine.tick() et tuerait le jeu sur une requete
            // mal formee. Un canal de diagnostic n'a pas le droit d'abattre ce qu'il observe.
            try {
                if (context == null) {
                    task.future.complete(InspectResponse.noWorld(stateName));
                } else {
                    task.future.complete(task.work.apply(context));
                }
            } catch (Exception e) {
                task.future.complete(InspectResponse.error(e));
            }
        }
    }

    /**
     * Ferme le sas et libere les requetes en vol : sans ce vidage, une requete deja posee
     * attendrait un tour de boucle qui n'aura plus lieu.
     */
    public void close() {
        closed = true;
        Task pending;
        while ((pending = queue.poll()) != null) {
            pending.future.complete(InspectResponse.unavailable("shutting-down", 0));
        }
    }

    public String getStateName() {
        return stateName;
    }

    public int getQueueDepth() {
        return queue.size();
    }

    public long sinceLastDrainMs() {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - lastDrainNanos);
    }

    private static final class Task {
        private final Function<Context, InspectResponse> work;
        private final CompletableFuture<InspectResponse> future;

        private Task(Function<Context, InspectResponse> work, CompletableFuture<InspectResponse> future) {
            this.work = work;
            this.future = future;
        }
    }
}
