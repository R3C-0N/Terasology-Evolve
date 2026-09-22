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
| `GET /slice` | Une coupe du monde en caractères. `axis=x\|y\|z`, `w`, `h`, `flow=0` |
| `GET /cube` | Le voisinage dense, couches en Y décroissant. `r` ≤ 8 |
| `GET /surface` | La vue de dessus : matière et hauteur de chaque colonne. `r` ≤ 24, `from`, `depth` |
| `GET /block` | Le forage sur une case, dans le vocabulaire exact de `liquidFlow` |

## Les grilles de terrain

Sans paramètre, tout se centre sur **le joueur** — jamais sur la visée : l'eau et
la lave sont `targetable: false`, donc le réticule traverse une mare et atterrit
sur le solide derrière. `at=target` demande la visée, `x=`/`y=`/`z=` surchargent
individuellement.

```
slice axis=z plane=-45 anchor=explicit pos=-40,42,-45 w=16 h=10 ms=0.3 sample=0.1
stats solid=69% air=28% liquid=4% unloaded=0%
legend . air  ? unloaded  # CoreAssets:Grass  A CoreAssets:Dirt  ~ CoreAssets:Water
  45 ####..........##
  44 AAAA####..####AA
  43 AAAAAAAA~~AAAAAA
  42 AAAAAAAAA~AAAAAA
flow  0=source  liquid_cells=6 sources=1 max_flow=2
  43 ........02......
  42 .........1......
```

Trois caractères sont réservés et leur distinction porte tout : `.` vide, `?`
**illisible**, `~` liquide. « Je ne sais pas » et « il n'y a rien » sont deux
réponses différentes ; les confondre ferait halluciner des grottes.

**L'écoulement est une seconde grille**, rognée aux lignes qui portent du
liquide, et omise quand il n'y en a pas. Y mêler les chiffres détruirait la
*forme* de la coulée, seule raison de regarder une image. La lecture ci-dessus se
dit : la source vaut `0`, le pas latéral `2`, et la colonne de chute revient à
`1` — une chute rend toute la portée.

**Un plafond dépassé est refusé en 400, jamais raboté** : une grille dont le
rayon aurait été réduit en silence répondrait à une question que personne n'a
posée. `/cube?r=8` est donc refusé sur la taille estimée du corps, calculée
*avant* l'échantillonnage.

`/surface` a son propre échantillonneur : il ne rend pas une case par bloc mais
**une valeur par colonne**, avec sortie anticipée dès que toutes sont résolues.
Son plafond par défaut est `min(plafond chargé, ancreY + 48)` et non le seul
plafond chargé — sinon un joueur enfoui à deux cents blocs verrait la surface du
monde au lieu de ce qui l'entoure.

Mesuré sur ce dépôt : `/slice` 24×16 **0,40 ms** médiane, 48×32 **0,80 ms**,
`/cube?r=4` **0,90 ms**, `/surface?r=8` **1,35 ms**, `/surface?r=24`
**2,20 ms pour 82 793 sondes**, `last_tick_ms` entre 2 et 4. Le parcours groupe
par chunk — une recherche par chunk au lieu d'une par bloc — et n'alloue que ses
tableaux de résultat.

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
