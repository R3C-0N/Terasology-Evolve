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

Les deux portes d'écriture s'arment séparément, et l'une n'ouvre pas l'autre :

```bash
java … --inspect-port=17888 --inspect-allow-console --inspect-allow-write
```

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
| `GET /stats` | Images par seconde, tas, entités, chunks, heure du monde, moyennes de performance |
| `GET /entities` | Une ligne par entité : identifiant, prefab, position, **noms** de composants. `r=`, `with=`, `limit=` |
| `GET /entity/…` | Toutes les valeurs. Identifiant, ou les alias `player`, `client`, `target` |
| `/record/start|stop|mark|dump|save` | Le magnétophone : positions image par image et actions horodatées |
| `POST /place` | Pose des blocs sans passer par le joueur. Exige `--inspect-allow-write` |

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

## Le magnétophone

Une question comme « de combien recule un loup frappé ? » ne se lit ni sur une
capture d'écran, ni sur deux `/entity` séparés d'une seconde : le mouvement dure
quatre dixièmes de seconde, et le canal répond en vingt millisecondes **quand on
pense à demander**. On arme la bande, on frappe, on l'arrête, on relit.

```bash
curl -X POST "$P/record/start?ids=1378568&hz=30&flags=Recul,Cadavre&probes=Health.currentHealth"
curl -X POST -d "clic-gauche" $P/record/mark     # au moment où le pilote clique
curl -X POST $P/record/stop
curl -X POST "$P/record/save?name=scene-loup"    # la bande entière, dans un fichier
curl "$P/record/dump?from=0"                     # ou page par page
```

| Paramètre de `start` | Effet |
|---|---|
| `ids=` | Les entités à suivre, par identifiant |
| `with=` + `r=` | Ou bien : tout ce qui porte ce composant dans ce rayon |
| `hz=` | Relevés de position par seconde, 20 par défaut, 120 au plus |
| `flags=` | Composants suivis en tout-ou-rien, par **nom court** — `Recul,Cadavre` |
| `probes=` | Champs numériques suivis, `Composant.champ`, `Health.currentHealth` par défaut |

Une ligne par image, une par événement :

```
f 5.921 P 5.780,37.210,24.140 -134.5 20.0 currentHealth=100.00 | 1378568 4.114,37.006,22.505 45.5 currentHealth=34.00
ev 5.921 use item=CoreSampleGameplay:FlintKnife cooldown=0.200
ev 5.921 probe 1378568 currentHealth 34.00->32.00
ev 5.921 flag 1378568 +Recul
ev 6.341 flag 1378568 -Recul
```

**Le lot de sujets est figé à l'armement.** Une bête qui meurt garde son
identifiant — le cadavre *est* la créature —, et un lot rescanné à chaque image
ferait entrer et sortir des lignes au milieu de la bande.

**Les événements sont déduits, pas écoutés.** Un sous-système n'a pas le droit
d'être un système ECS, donc rien ici ne reçoit `AttackEvent` : les trois sources
sont des *différences* relevées d'une image à l'autre —
`CharacterHeldItemComponent.lastItemUsedTime` avance quand un coup part **et
passe le verrou de cadence**, une sonde tombe, un composant apparaît. C'est moins
riche qu'un événement, et c'est plus honnête. Corollaire utile : un `mark` sans
`use` derrière est un clic mangé par la cadence, un `use` sans `probe` est un coup
qui n'a pas porté — la bande les distingue, l'œil non.

**Les événements sont relevés à chaque tick**, jamais à la cadence des images :
un coup porté entre deux relevés disparaîtrait, et c'est précisément ce qu'on
enregistre. Seule la ligne de position obéit à `hz`.

**Les marques viennent du pilote.** Ce qu'un joueur fait au clavier et à la
souris n'est visible nulle part dans l'état du monde — un clic qui rate n'y
laisse rien. `POST /record/mark` est le seul canal pour cela, horodaté au tick
suivant son arrivée : au plus une image de retard.

**Le joueur se relève autrement que les bêtes.** Son *regard* n'est pas dans sa
`LocationComponent`, qui ne porte que le cap ; le tangage vit dans la caméra, et
c'est lui qui décide si un coup porte. La bande lit donc `LocalPlayer`, comme
`/view`.

**`/save` plutôt que `/dump` dès que la bande est longue.** Le plafond de réponse
couperait une ligne en deux, et une position tronquée se lit comme une position :
`dump` ferme donc sa page sur un budget d'octets et dit `next=`, et `save` écrit
tout d'un bloc dans `<jeu>/records/<nom>.txt`.

Plafonds : 6 000 images, 800 événements, 16 sujets. Atteint, la bande s'arrête
d'elle-même et le dit (`full=true`) — une fuite silencieuse serait pire.

## Poser des blocs sans le joueur

Tout ce qui posait un bloc passait par le personnage : viser, tenir l'objet, être
à portée. C'est la bonne règle pour un joueur et un mur pour qui doit bâtir un
décor d'essai — une arène, un sol plat sous une bête, un repère à un endroit
mesuré. `POST /place` écrit le monde directement, et n'existe qu'avec
`--inspect-allow-write`.

```bash
curl -X POST "$P/place?x=12&y=34&z=16&uri=CoreAssets:Stone" -d " "              # un bloc
curl -X POST "$P/place?x=12&y=34&z=12&x2=20&y2=35&z2=20&uri=CoreAssets:Brick" -d " "   # un pavé
printf '0 3 0
1 3 0
' | curl -X POST "$P/place?rel=player&uri=CoreAssets:Iron" --data-binary @-
```

`rel=player` compte les coordonnées depuis le bloc où se tient le joueur. Le
corps POST porte une pose par ligne, `x y z [uri]`, ce qui bâtit autre chose
qu'une boîte. Plafond : 4 096 blocs par requête.

La réponse compte les trois cas séparément — `placed`, `unchanged`, `refused` —
et nomme la première case refusée. **Un refus est un chunk non chargé**, ce que
`setBlock` signale en rendant `null` ; l'avaler laisserait un mur troué que rien
n'expliquerait.

**Un nom de bloc inconnu rend de l'air, en silence** : c'est le contrat de
`BlockManager.getBlock`, qui journalise et dégrade. Poser mille blocs d'air à la
place d'un mur serait une destruction pure, donc tous les noms sont résolus
**avant la première écriture** et la requête entière est refusée — sauf si c'est
bien de l'air qu'on a demandé.

`setBlock` est le bon niveau : `WorldProvider` le fait suivre à
`EntityAwareWorldProvider`, qui émet `OnChangedBlock` et tient les entités de
bloc. Un bloc posé par ici vit exactement comme un bloc posé à la main.

## Les entités, et les pièges qu'elles cachent

Deux étages, et c'est l'étagement qui tient le volume : `/entities` ne donne que
les **noms** des composants, `/entity/…` leurs valeurs.

**Ni `toFullDescription()` ni `dumpEntities`** : tous deux rendent un delta contre
le prefab, donc un composant égal à ses valeurs par défaut **disparaît** — on
conclurait qu'il n'est pas là. La sérialisation passe par Gson monté sur la
`TypeHandlerLibrary`, ce qui rend `Vector3f`, `Name`, `BlockUri`, `Block` et
`Prefab` en scalaires, et chaque `EntityRef` en **identifiant nu** — donc aucun
risque de récursion.

Trois détails qui ne sont pas cosmétiques :

- **`serializeNulls()` est obligatoire.** `EntityRefTypeHandler` rend nul dès
  qu'une référence n'est pas persistante ; sans ce réglage le champ *disparaît*,
  et un lien qui existe se lirait comme un lien absent.
- **Gson supprime en silence toute `Map` dont la clé n'est pas une chaîne.** La
  route le dit en pied, sinon un composant paraîtrait plus vide qu'il n'est.
- **`getEntity(identifiantAbsent)` écrit une ERREUR dans le journal du jeu** à
  chaque appel : `contains()` d'abord, toujours.

**`PerformanceMonitor` est éteint par défaut, et pas par configuration** — son
instance statique est un moniteur nul dont les cartes restent vides à jamais, et
le seul `setEnabled` du dépôt est la bascule F4 de l'incrustation. `/stats` dit
donc `perf=disabled` franchement plutôt que d'émettre un bloc vide ; `?perf=on`
l'allume, et la prochaine bascule F4 le remettra à zéro.

Effet de bord utile : `/stats` rend `paused=true`, là où `SKILL.md` explique que
la pause ne se détecte qu'en comparant deux captures d'écran à trois secondes.

## L'enveloppe MCP

`.claude/mcp/terasology_inspect.py`, déclaré par `.mcp.json` à la racine : un
outil par route, dix-sept en tout. **Bibliothèque standard seule** — c'est la
propriété que `driver.py` tient déjà, et la perdre imposerait un `pip install`
sur une machine où rien ne le garantit. Le serveur parle donc JSON-RPC 2.0 sur
stdio directement.

Ce qu'il apporte sur `curl` : les schémas d'outils arrivent dans le contexte,
donc les routes sont découvrables sans lire ce fichier ; et plus de piège de
guillemets entre PowerShell et Git Bash, qui coexistent ici. Ce qu'il n'apporte
pas : ni parallélisme ni vitesse — `curl http://127.0.0.1:17888/view` reste
parfaitement valable.

**Piège Windows payé :** `sys.stdout` prend l'encodage de la console, cp1252 sur
cette machine, et ce fork a des noms de blocs français. « Acier ouvragé » perdait
son accent entre un serveur qui envoie pourtant de l'UTF-8 correct et le client.
Les deux flux sont reconfigurés en UTF-8 au démarrage.

Jeu éteint, chaque outil rend la ligne de commande à taper pour le lancer, pas
une erreur : un jeu éteint est un état normal, pas une panne.

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
