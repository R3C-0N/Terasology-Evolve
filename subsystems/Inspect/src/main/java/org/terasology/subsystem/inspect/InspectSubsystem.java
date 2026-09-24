// Copyright 2021 The Terasology Foundation
// SPDX-License-Identifier: Apache-2.0

package org.terasology.subsystem.inspect;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.terasology.engine.context.Context;
import org.terasology.engine.core.modes.GameState;
import org.terasology.engine.core.subsystem.EngineSubsystem;

import javax.inject.Inject;

/**
 * Ouvre un canal d'inspection local sur le jeu qui tourne.
 * <p>
 * Le besoin : le seul chemin dont disposait un agent pour interroger le jeu etait d'ouvrir la
 * console en jeu, d'y taper une commande lettre par lettre a 60 ms, puis de relire le fichier de
 * journal par decalage d'octets — quatre secondes par question, la fenetre volee au premier plan a
 * chaque appel, et rien de parallelisable. Ce sous-systeme rend les memes reponses par HTTP sur la
 * loopback, sans toucher au clavier ni a la fenetre.
 * <p>
 * <b>Pourquoi un sous-systeme et non un module.</b> {@code ModuleManager.setupSandbox()} installe
 * un vrai {@code SecurityManager} — la garde est {@code Runtime.version().feature() < 18} et le
 * projet est epingle sur Java 17 —, le mode strict est le defaut, et {@code ExternalApiWhitelist}
 * ne liste ni {@code java.net}, ni {@code io.netty}, ni {@code com.sun.net.httpserver}. Un module
 * ne peut donc pas ouvrir de socket. Le code d'un sous-systeme, lui, est charge par le chargeur de
 * classes de l'application et echappe au bac a sable.
 * <p>
 * <b>Avertissement a qui reprendra ce code : ne jamais ajouter de classe ECS ici.</b> Pas de
 * {@code @RegisterSystem}, pas de {@code Component}, pas d'{@code AutoConfig}. Une telle classe
 * compilerait, serait livree, et ne serait <em>jamais enregistree</em> — sans erreur ni message —
 * parce que {@code Inspect} ne figure pas dans {@code LEGACY_ENGINE_MODULE_POLLUTERS}
 * ({@code TerasologyEngine}), la liste qui verse le classpath d'un sous-systeme dans le module
 * moteur. C'est precisement en restant hors de cette liste que ce projet n'oblige a modifier
 * aucune ligne de {@code engine/}.
 * <p>
 * Corollaire de la meme regle : ce sous-systeme n'emploie jamais {@code @In}.
 * {@code postInitialise} injecte contre le contexte <em>racine</em>, alors que
 * {@code WorldProvider}, {@code EntityManager} et {@code LocalPlayer} vivent dans le contexte
 * <em>de l'etat</em>. Tout se resout par requete depuis {@code currentState.getContext()}, que
 * {@code postUpdate} fournit gratuitement.
 */
public class InspectSubsystem implements EngineSubsystem {

    /**
     * Le port se transmet par propriete systeme, et non par le constructeur, parce que le
     * constructeur doit rester sans argument : {@code TerasologyEngine.postInitSubsystems} appelle
     * {@code gameContext.inject(subsystem)} sur <em>chaque</em> sous-systeme, ce qui exige une
     * {@code BeanDefinition}, que le processeur d'annotations ne genere qu'en presence d'un
     * {@code @Inject}. C'est le meme detour que {@code --server-port}, qui passe par
     * {@code ConfigurationSubsystem.SERVER_PORT_PROPERTY}.
     */
    public static final String PORT_PROPERTY = "org.terasology.inspectPort";

    /**
     * Ouvre {@code POST /console}, qui execute une commande sans passer par le clavier. Separe du
     * port parce que c'est un canal d'<em>ecriture</em> : la route court-circuite le controle de
     * permission, et le registre des commandes etant ouvert, on ne peut pas la rendre inoffensive
     * en filtrant une liste. Allumee, tout processus local pilote le monde.
     */
    public static final String ALLOW_CONSOLE_PROPERTY = "org.terasology.inspectAllowConsole";

    /**
     * Ouvre {@code /place}, qui ecrit des blocs dans le monde sans passer par le joueur. Separee de
     * la clef de la console parce que les deux n'ont pas le meme usage : une session qui lit l'etat
     * n'a aucune raison de pouvoir refaire le terrain sous les pieds de qui joue.
     */
    public static final String ALLOW_WRITE_PROPERTY = "org.terasology.inspectAllowWrite";

    private static final Logger logger = LoggerFactory.getLogger(InspectSubsystem.class);

    private final InspectBridge bridge = new InspectBridge();
    private final Recorder recorder = new Recorder();

    private InspectServer server;

    @Inject
    public InspectSubsystem() {
    }

    @Override
    public String getName() {
        return "Inspect";
    }

    @Override
    public void postInitialise(Context rootContext) {
        Integer port = Integer.getInteger(PORT_PROPERTY);
        if (port == null) {
            logger.debug("Canal d'inspection eteint : {} n'est pas defini.", PORT_PROPERTY);
            return;
        }
        server = new InspectServer(bridge, recorder, port,
                Boolean.getBoolean(ALLOW_CONSOLE_PROPERTY), Boolean.getBoolean(ALLOW_WRITE_PROPERTY));
        server.start();
    }

    /**
     * Le vidage du sas d'abord, l'image du magnetophone ensuite : une bande armee dans ce tour de
     * boucle doit prendre son premier releve dans le meme, sinon son origine de temps precede sa
     * premiere mesure.
     */
    @Override
    public void postUpdate(GameState currentState, float delta) {
        bridge.drain(currentState);
        if (currentState != null && currentState.getContext() != null) {
            recorder.tick(currentState.getContext());
        }
    }

    @Override
    public void preShutdown() {
        // Fermer l'ecoute avant de liberer les requetes en vol, pour ne pas en accepter de
        // nouvelles entre les deux.
        if (server != null) {
            server.stop();
        }
        bridge.close();
    }
}
