# Terasology dans un navigateur

Ce dossier fait tourner le jeu sur cette machine, qui n'a pas de carte
graphique, et en sert l'interface à un navigateur. Il est écrit pour être
déployé par Coolify, derrière son proxy Traefik.

## Ce que ça monte

```
navigateur → Traefik (Coolify, TLS) → :6080 noVNC → :5900 x11vnc → Xvfb :99 → le jeu
```

Le rendu passe par **llvmpipe**, le rasteriseur logiciel de Mesa, qui annonce
OpenGL 4.5 — largement au-dessus du 3.3 que le moteur exige, et suffisant pour
les textures flottantes et le `texelFetch` en vertex shader dont la projection
cubique dépend.

## Déploiement par Coolify

| Réglage | Valeur |
|---|---|
| Type de ressource | Docker Compose |
| Fichier | `docker/docker-compose.yaml` |
| Submodules | **à activer** (`Advanced → Git Submodules`) — sans eux `modules/` reste vide et il n'y a aucun générateur |
| Source | la **GitHub App**, pas « Public GitHub » : `CubeWorlds-Evolve` est privé, et une clé de déploiement ne couvre qu'un dépôt. Les URL du `.gitmodules` sont relatives pour que les submodules héritent du jeton du parent — vérifier que l'installation de l'App a accès aux six dépôts |
| Domaine | généré depuis `SERVICE_FQDN_TERASOLOGY_6080` |
| Mot de passe | généré depuis `SERVICE_PASSWORD_VNC`, visible dans l'interface Coolify |

Aucun port n'est publié sur l'hôte : le proxy joint le conteneur par le réseau
de Coolify. Le premier démarrage est long — Gradle résout ses dépendances, puis
le jeu écrit sa configuration lors d'un passage sans écran — d'où un
`start_period` de dix minutes sur le contrôle de santé.

### Variables

| Variable | Défaut | Ce qu'elle fait |
|---|---|---|
| `SCREEN` | `1600x900x24` | taille de l'écran virtuel, la fenêtre s'y cale |
| `LP_NUM_THREADS` | `8` | fils du rasteriseur logiciel |
| `WORLD_GENERATOR` | `CubeWorlds:cubeworld` | générateur par défaut du menu |
| `GAME_ARGS` | vide | arguments passés au lanceur, par exemple `--load-last-game` |
| `CPUS`, `MEM_LIMIT` | `8`, `8g` | plafonds du conteneur |

## Sécurité

Le mot de passe VNC est **obligatoire** : sans lui l'entrypoint refuse de
démarrer, plutôt que de retomber sur une valeur par défaut. Mais un mot de passe
VNC est un secret court et le protocole n'en fait pas grand-chose. **Mettre
l'Authelia déjà en place devant ce domaine** : c'est la seule authentification
sérieuse ici, le mot de passe VNC n'étant qu'un second verrou.

Trois points, détaillés dans le README du dépôt `authelia-config` :

- le domaine doit être **sous la portée du cookie** d'Authelia, faute de quoi la
  session n'y sera jamais vue ;
- une **règle d'`access_control`** doit nommer ce domaine — la politique par
  défaut est `deny`, donc sans elle il est fermé à son propre propriétaire ;
- le middleware `authelia@docker` doit être **ajouté** à la liste du routeur que
  Coolify engendre (`middlewares=gzip,authelia@docker`), et non la remplacer.

Un `curl -sI` sur le domaine doit répondre `302` vers le portail. Un `200`
signifie que le middleware n'est pas accroché et que la page s'ouvre sans rien
demander.

## Ce à quoi il ne faut pas s'attendre

**La fluidité.** Un rasteriseur logiciel dessine un moteur de voxels à pipeline
différé ; la limite d'images est posée à 15 et ce sera bien en dessous. C'est
fait pour regarder et pour capturer, pas pour jouer.

**La caméra libre.** Le jeu capture le curseur, alors que VNC transmet une
position absolue. Les menus et les captures d'écran se comportent bien ; le
regard à la souris, non. Prévoir de piloter au clavier.

**Un monde chargé.** Au 9 septembre 2026, aucun générateur ne se charge — pas
même celui d'amont : `NoClassDefFoundError` sur une classe de CoreWorlds, dont
on a vérifié qu'elle est bien présente dans le jar comme sur le disque. C'est un
problème de chargement de modules construits depuis les sources, et il précède
tout ce qu'on voudrait voir à l'écran. Le menu, lui, s'affiche.

## Captures depuis l'hôte

```bash
docker exec -e DISPLAY=:99 <conteneur> import -window root /tmp/shot.png
docker cp <conteneur>:/tmp/shot.png ./shot.png
```

## Essai local, hors Coolify

Les chemins du compose sont relatifs au **répertoire de projet**, pas au fichier.
Coolify passe `--project-directory` sur la racine du dépôt ; en local il faut le
dire, sans quoi Compose prend le dossier `docker/` et tout se décale d'un cran —
`context: .` devient `docker/`, et le build échoue sur `lstat …/docker`.

```bash
docker compose --project-directory . -f docker/docker-compose.yaml build
docker run -d --name tera -e VNC_PASSWORD=changeme -e SCREEN=1280x720x24 \
  -v "$PWD":/work -v "$HOME/.gradle":/home/game/.gradle \
  -v tera-home:/home/game/terasology \
  -p 127.0.0.1:6080:6080 --shm-size=512m docker-terasology
```

Puis `http://127.0.0.1:6080/` — par un tunnel SSH si la machine est distante,
ce qui évite d'exposer quoi que ce soit. Monter le `~/.gradle` de l'hôte évite
de retélécharger les trois gigaoctets de dépendances.
