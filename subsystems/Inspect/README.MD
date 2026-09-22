# Inspect — canal d'inspection local

Ouvre un serveur HTTP sur la loopback pour qu'un outil extérieur — en pratique un
agent — interroge le jeu qui tourne sans passer par le clavier.

Le chemin qu'il remplace : ouvrir la console en jeu, y taper une commande lettre
par lettre à 60 ms, puis relire le journal par décalage d'octets. Quatre secondes
par question, la fenêtre volée au premier plan, rien de parallélisable.

## Emploi

```bash
java … org.terasology.engine.Terasology --inspect-port=17888
curl http://127.0.0.1:17888/health
curl http://127.0.0.1:17888/view
```

Sans `--inspect-port`, rien n'écoute : pas de port, pas de thread.

| Route | Réponse |
|---|---|
| `GET /health` | `ok state=… last_tick_ms=… queue=… uptime_s=… port=…` |
| `GET /view` | `pos=… dir=… yaw=… pitch=… block=… uri=…` |

Codes de refus, et la distinction est le but : **503** le thread de jeu n'a pas
vidé la file à temps, **409** il l'a vidée mais aucun monde n'est chargé, **400**
paramètre invalide.

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
