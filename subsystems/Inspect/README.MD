# Inspect — canal d'inspection local

Ouvre un serveur HTTP sur la loopback pour qu'un outil extérieur — en pratique un
agent — interroge le jeu qui tourne sans passer par le clavier.

Le chemin qu'il remplace : ouvrir la console en jeu, y taper une commande lettre
par lettre à 60 ms, puis relire le journal par décalage d'octets. Quatre secondes
par question, la fenêtre volée au premier plan, rien de parallélisable.

## Emploi

```bash
java … org.terasology.engine.Terasology --inspect-port=17888 --inspect-allow-console
curl http://127.0.0.1:17888/health
curl http://127.0.0.1:17888/view
curl "http://127.0.0.1:17888/commands?q=look"
curl -X POST -d "look 42 10" http://127.0.0.1:17888/console
```

Sans `--inspect-port`, rien n'écoute : pas de port, pas de thread.

| Route | Réponse |
|---|---|
| `GET /health` | `ok state=… last_tick_ms=… queue=… uptime_s=… port=…` — répondue sur le thread HTTP, donc elle répond quand le thread de jeu ne répond plus |
| `GET /view` | `pos=… dir=… yaw=… pitch=… block=… uri=…` |
| `GET /commands` | Les commandes enregistrées, filtrables par `?q=` |
| `POST /console` | Exécute une commande et rend sa sortie. Aussi `GET /console?cmd=…` |

Codes de refus, et la distinction est le but : **503** le thread de jeu n'a pas
vidé la file à temps, **409** il l'a vidée mais aucun monde n'est chargé, **400**
paramètre invalide, **404** commande inconnue — ou route fermée.

## Le canal d'écriture

`POST /console` n'existe qu'avec `--inspect-allow-console`, et rend **404** sans
lui. Mesure : `showView` coûte 5 177 ms par le clavier contre 23 ms ici, pour une
réponse identique au caractère près.

**Avertissement :** la route résout la commande et l'appelle directement, ce qui
**court-circuite le contrôle de permission**. Le registre des commandes étant
ouvert, on ne peut pas la rendre inoffensive en filtrant une liste : `teleport`,
`giveBlock` et `ghost` sont atteignables. Allumée, tout processus local pilote le
monde. C'est acceptable pour un harnais de développement, pas ailleurs.

**À savoir :** le raccourci contourne aussi le routage `isRunOnServer`. Sans
effet en solo, où le client local fait autorité ; sur un client distant, une
commande serveur resterait sans effet.

## Trois choses à savoir avant d'y toucher

**Ne jamais ajouter de classe ECS ici.** Pas de `@RegisterSystem`, pas de
`Component`, pas d'`AutoConfig`. Une telle classe compilerait, serait livrée, et
ne serait **jamais enregistrée** — sans erreur ni message — parce que `Inspect`
ne figure pas dans `LEGACY_ENGINE_MODULE_POLLUTERS` (`TerasologyEngine`). C'est
en restant hors de cette liste que ce projet n'oblige à modifier aucune ligne de
`engine/`.

**Ne jamais employer `@In`.** `postInitialise` injecte contre le contexte
*racine*, alors que `WorldProvider`, `EntityManager` et `LocalPlayer` vivent dans
le contexte *de l'état*. Tout se résout par requête depuis
`currentState.getContext()`.

**Ne jamais lire le monde depuis un thread HTTP.** `ComponentTable` garde des
`TLongObjectHashMap` non sûres que le thread de jeu réécrit. Tout passe par
`InspectBridge`, qui fait exécuter le travail sur le thread de jeu — et *pas* par
`GameThread.synch`, qui empile en LIFO et attend sans délai.

## Latence

Plancher d'environ 16 ms fenêtre au premier plan, et **100 ms en arrière-plan** :
`HibernationSubsystem` endort la boucle principale à 100 ms par image dès que la
fenêtre perd le focus. Contre 4 000 ms pour le chemin clavier.
