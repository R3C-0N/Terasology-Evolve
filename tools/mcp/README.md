# Le débogueur JVM, par MCP

`mcp-jdwp-java` donne un contrôle de débogueur sur la JVM du jeu — points de
trace, points d'arrêt conditionnels, piles, variables locales, évaluation
d'expressions. **Le jar n'est pas versionné** (24,7 Mo) : le récupérer d'abord.

```bash
curl -sL -o tools/mcp/mcp-jdwp-java-2.10.1.jar \
  https://github.com/FgForrest/mcp-jdwp-java/releases/download/v2.10.1/mcp-jdwp-java-2.10.1.jar
# sha256 : 686f1229e35b001c702ffd57204f8a72bf7f87a43ca19afa1ac779978e8ca321
```

`FgForrest/mcp-jdwp-java`, MIT, publié le 11 juillet 2026. **À savoir :** 16 à 20
téléchargements au moment où il a été pris — ce n'est pas un logiciel éprouvé par
beaucoup d'autres, et il obtient un accès de débogage complet à la JVM du jeu.
Le construire depuis les sources demande un JDK 21 ; le jar publié, lui, est en
bytecode 17 et tourne donc ici.

## Employer

```bash
python .claude/skills/run-terasology/driver.py launch --load-last-game --jdwp \
    -- --inspect-port=17888
```

`--jdwp` ouvre le port 5005 sur la loopback. `.mcp.json` déclare le serveur ;
47 outils `jdwp_*` en découlent.

## Ce que ça coûte, mesuré ici

| | `last_tick_ms` |
|---|---|
| Au repos, rien de posé | 18 |
| **Attaché**, sans point posé | 14-18 — **l'attache est gratuite** |
| Point de trace posé, code non atteint | 5 |
| **Point de trace atteint deux fois** | **2 542, 2 945** |

**Environ 1,5 seconde de thread de jeu gelé par déclenchement**, et ce n'est pas
un coût de compilation amorti : deux essais successifs donnent les mêmes
chiffres. La règle qui en découle n'est pas celle qu'on attendait —

> **Un point de trace ne se pose que sur un chemin traversé une poignée de fois.**
> Jamais dans une boucle chaude, et le solveur de liquides en est une : sa
> méthode `processAdds` visite des milliers de cases par seconde. Deux passages y
> ont suffi à geler trois secondes et à faire répondre 503 au canal d'inspection.

**Et l'évaluateur ne voit pas les méthodes privées.** `canFall(q, liquid)` dans
une expression rend une erreur de compilation : la classe synthétique qu'il
génère n'y a pas accès. Or c'est précisément ce genre d'appel qu'on veut sur ce
dépôt. Une expression se limite donc aux variables locales et aux membres
accessibles — `"q=" + q + " value=" + value` fonctionne et rend la valeur **au
moment où le solveur visite la case**, ce qu'aucune requête d'état ne donne.

## Verdict

L'outil tient ses promesses, et son attache est gratuite. Mais sur ce dépôt, ce
qu'on veut déboguer — la propagation des liquides — est exactement là où il ne
peut pas aller. Pour ces questions-là, le canal d'inspection (`/slice`, `/block`)
répond mieux et pour rien.

À garder pour ce qu'il fait seul : un chemin froid, une exception dont on veut
les locales au moment du `throw`, un cas rare atteint une fois.
