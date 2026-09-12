// Copyright 2022 The Terasology Foundation
// SPDX-License-Identifier: Apache-2.0
package org.terasology.engine.rendering.nui.layers.mainMenu;

import com.google.common.collect.Collections2;
import org.joml.Vector2i;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.terasology.engine.config.Config;
import org.terasology.engine.config.PlayerConfig;
import org.terasology.engine.config.ServerInfo;
import org.terasology.engine.context.Context;
import org.terasology.engine.core.GameEngine;
import org.terasology.engine.core.GameThread;
import org.terasology.engine.core.modes.StateLoading;
import org.terasology.engine.i18n.TranslationSystem;
import org.terasology.engine.identity.storageServiceClient.StorageServiceWorker;
import org.terasology.engine.network.JoinStatus;
import org.terasology.engine.network.NetworkSystem;
import org.terasology.engine.network.PingService;
import org.terasology.engine.network.ServerInfoMessage;
import org.terasology.engine.network.ServerInfoService;
import org.terasology.engine.registry.In;
import org.terasology.engine.rendering.nui.CoreScreenLayer;
import org.terasology.engine.rendering.nui.animation.MenuAnimationSystems;
import org.terasology.engine.rendering.nui.layers.mainMenu.savedGames.GameInfo;
import org.terasology.engine.rendering.nui.layers.mainMenu.savedGames.GameProvider;
import org.terasology.engine.rendering.nui.layers.mainMenu.savedGames.GameSummary;
import org.terasology.gestalt.assets.ResourceUrn;
import org.terasology.input.Keyboard;
import org.terasology.joml.geom.Rectanglei;
import org.terasology.nui.Canvas;
import org.terasology.nui.Color;
import org.terasology.nui.HorizontalAlign;
import org.terasology.nui.VerticalAlign;
import org.terasology.nui.WidgetUtil;
import org.terasology.nui.asset.font.Font;
import org.terasology.nui.databinding.ReadOnlyBinding;
import org.terasology.nui.events.NUIKeyEvent;
import org.terasology.nui.itemRendering.AbstractItemRenderer;
import org.terasology.nui.layouts.CardLayout;
import org.terasology.nui.widgets.ActivateEventListener;
import org.terasology.nui.widgets.UIButton;
import org.terasology.nui.widgets.UILabel;
import org.terasology.nui.widgets.UIList;
import org.terasology.nui.widgets.UIText;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.terasology.engine.registry.InjectionHelper.createWithConstructorInjection;

/**
 * Le multijoueur, en un ecran a deux onglets : trouver un serveur, ou en creer un chez soi.
 *
 * <p>Les deux listes d'antan — serveurs listes par le serveur maitre, serveurs saisis a la main —
 * n'en font plus qu'une : le joueur cherche un serveur, il ne choisit pas d'abord d'ou il vient.
 * Une entree saisie a la main reste modifiable et supprimable, les autres non.
 *
 * <p>Le second onglet remplace le passage par la liste des parties en mode hote : on choisit la
 * sauvegarde a servir et on lance, sans detour.
 */
public class JoinGameScreen extends CoreScreenLayer {
    public static final ResourceUrn ASSET_URI = new ResourceUrn("engine:joinGameScreen");

    private static final Logger logger = LoggerFactory.getLogger(JoinGameScreen.class);
    private static final Color TITLE_COLOR = new Color(0xFFF3DCFF);
    private static final Color MUTED_COLOR = new Color(0xB49E7CFF);
    private static final Color GOOD_COLOR = new Color(0x7CC452FF);
    private static final Color BAD_COLOR = new Color(0xC13B27FF);
    private static final String UNKNOWN = "—";
    /** Quatre sondes a la fois : de quoi remplir un tableau sans ouvrir une socket par ligne d'un coup. */
    private static final int PING_THREADS = 4;

    @In
    private Context context;
    @In
    private Config config;
    @In
    private PlayerConfig playerConfig;
    @In
    private NetworkSystem networkSystem;
    @In
    private GameEngine engine;
    @In
    private TranslationSystem translationSystem;
    @In
    private StorageServiceWorker storageServiceWorker;

    private final Map<ServerInfo, Future<ServerInfoMessage>> extInfo = new HashMap<>();
    private final Map<String, String> pings = new HashMap<>();
    private final List<ServerInfo> listedServers = new ArrayList<>();
    private final List<ServerInfo> shownServers = new ArrayList<>();

    private ServerInfoService infoService;
    private ServerListDownloader downloader;
    private ExecutorService pingPool;

    private UIList<ServerInfo> serverList;
    private UIList<GameInfo> hostGameList;
    private UIText serverSearch;
    private UILabel emptyHint;
    private UILabel emptyTitle;

    private boolean updateComplete;
    private int lastKnownServerCount = -1;

    @Override
    public void initialise() {
        setAnimationSystem(MenuAnimationSystems.createDefaultSwipeAnimation());
        downloader = new ServerListDownloader(config.getNetwork().getMasterServer());

        initTabs();
        initServerList();
        initHostTab();

        ActivateEventListener back = e -> {
            config.save();
            triggerBackAnimation();
        };
        WidgetUtil.trySubscribe(this, "close", back);
        WidgetUtil.trySubscribe(this, "closeHost", back);
    }

    private void initTabs() {
        CardLayout cards = find("cards", CardLayout.class);
        UIButton findTab = find("findTab", UIButton.class);
        UIButton hostTab = find("hostTab", UIButton.class);

        ActivateEventListener showFind = e -> {
            cards.setDisplayedCard("findCard");
            findTab.setFamily("tab-active");
            hostTab.setFamily("tab-bar");
        };
        ActivateEventListener showHost = e -> {
            cards.setDisplayedCard("hostCard");
            findTab.setFamily("tab-bar");
            hostTab.setFamily("tab-active");
            hostGameList.setList(GameProvider.getSavedGames());
            hostGameList.select(0);
        };
        findTab.subscribe(showFind);
        hostTab.subscribe(showHost);
        showFind.onActivated(null);
    }

    private void initServerList() {
        serverList = find("serverList", UIList.class);
        serverSearch = find("serverSearch", UIText.class);
        emptyTitle = find("emptyTitle", UILabel.class);
        emptyHint = find("emptyHint", UILabel.class);

        serverList.setList(shownServers);
        serverList.setItemRenderer(new ServerRowRenderer());
        serverList.subscribe((widget, item) -> join(item.getAddress(), item.getPort()));
        serverList.subscribeSelection((widget, item) -> {
            if (item != null && !extInfo.containsKey(item)) {
                extInfo.put(item, infoService.requestInfo(item.getAddress(), item.getPort()));
            }
        });

        UILabel selected = find("selectedServer", UILabel.class);
        selected.bindText(new ReadOnlyBinding<String>() {
            @Override
            public String get() {
                ServerInfo item = serverList.getSelection();
                if (item == null) {
                    return "";
                }
                return translationSystem.translate("${engine:menu#selected-server}") + " : "
                        + item.getName() + "  ·  " + item.getAddress() + ":" + item.getPort();
            }
        });

        UIButton join = find("join", UIButton.class);
        join.bindEnabled(new ReadOnlyBinding<Boolean>() {
            @Override
            public Boolean get() {
                return serverList.getSelection() != null;
            }
        });
        join.subscribe(button -> {
            config.save();
            ServerInfo item = serverList.getSelection();
            if (item != null) {
                join(item.getAddress(), item.getPort());
            }
        });

        WidgetUtil.trySubscribe(this, "refresh", button -> refresh());

        WidgetUtil.trySubscribe(this, "addServer", button -> {
            AddServerPopup popup = getManager().pushScreen(AddServerPopup.ASSET_URI, AddServerPopup.class);
            popup.onSuccess(item -> {
                config.getNetwork().addServerInfo(item);
                rebuildShownServers();
                serverList.setSelection(item);
            });
        });

        ReadOnlyBinding<Boolean> customSelected = new ReadOnlyBinding<Boolean>() {
            @Override
            public Boolean get() {
                return isCustom(serverList.getSelection());
            }
        };

        UIButton edit = find("editServer", UIButton.class);
        edit.bindEnabled(customSelected);
        edit.subscribe(button -> {
            AddServerPopup popup = getManager().pushScreen(AddServerPopup.ASSET_URI, AddServerPopup.class);
            ServerInfo info = serverList.getSelection();
            popup.setServerInfo(info);
            // editing invalidates the currently known info, so query it again
            popup.onSuccess(item -> {
                extInfo.put(item, infoService.requestInfo(item.getAddress(), item.getPort()));
                pings.remove(key(item));
                rebuildShownServers();
            });
        });

        UIButton remove = find("removeServer", UIButton.class);
        remove.bindEnabled(customSelected);
        remove.subscribe(button -> {
            ServerInfo info = serverList.getSelection();
            if (info != null) {
                config.getNetwork().removeServerInfo(info);
                extInfo.remove(info);
                serverList.setSelection(null);
                rebuildShownServers();
            }
        });

        UILabel downloadLabel = find("download", UILabel.class);
        downloadLabel.bindText(new ReadOnlyBinding<String>() {
            @Override
            public String get() {
                return translationSystem.translate(downloader.getStatus());
            }
        });
    }

    private void initHostTab() {
        hostGameList = find("hostGameList", UIList.class);
        hostGameList.setItemRenderer(new TwoLineItemRenderer<GameInfo>() {
            @Override
            public String getTitle(GameInfo value) {
                return value.getManifest().getTitle();
            }

            @Override
            public String getSubtitle(GameInfo value) {
                return GameSummary.listSubtitle(value, translationSystem);
            }
        });

        UIText motd = find("motd", UIText.class);
        motd.setText(config.getNetwork().getServerMOTD());
        UIText port = find("serverPort", UIText.class);
        port.setText(Integer.toString(config.getNetwork().getServerPort()));

        UILabel hint = find("hostHint", UILabel.class);
        hint.bindText(new ReadOnlyBinding<String>() {
            @Override
            public String get() {
                return "127.0.0.1:" + port.getText().trim() + "  —  "
                        + translationSystem.translate("${engine:menu#local-server-hint}");
            }
        });

        UIButton start = find("startServer", UIButton.class);
        start.bindEnabled(new ReadOnlyBinding<Boolean>() {
            @Override
            public Boolean get() {
                return hostGameList.getSelection() != null;
            }
        });
        start.subscribe(button -> {
            GameInfo selection = hostGameList.getSelection();
            if (selection == null) {
                return;
            }
            config.getNetwork().setServerMOTD(motd.getText());
            try {
                config.getNetwork().setServerPort(Integer.parseInt(port.getText().trim()));
            } catch (NumberFormatException e) {
                getManager().pushScreen(MessagePopup.ASSET_URI, MessagePopup.class)
                        .setMessage(translationSystem.translate("${engine:menu#warning}"),
                                translationSystem.translate("${engine:menu#server-port-label}"));
                return;
            }
            config.save();
            if (playerConfig.playerName.getDefaultValue().equals(playerConfig.playerName.get())) {
                getManager().pushScreen(EnterUsernamePopup.ASSET_URI, EnterUsernamePopup.class);
                return;
            }
            try {
                GameLauncher.launch(selection, config, true);
            } catch (Exception e) {
                logger.error("Failed to host saved game", e);
                getManager().pushScreen(MessagePopup.ASSET_URI, MessagePopup.class)
                        .setMessage("Error Loading Game", e.getMessage());
            }
        });
    }

    private boolean isCustom(ServerInfo info) {
        return info != null && config.getNetwork().getServerInfos().contains(info);
    }

    private static String key(ServerInfo info) {
        return info.getAddress() + ":" + info.getPort();
    }

    /**
     * Recompose la liste affichee : les serveurs saisis a la main d'abord, puis ceux annonces,
     * le tout filtre par le champ de recherche. Toute sonde de latence manquante est lancee ici.
     */
    private void rebuildShownServers() {
        String filter = serverSearch == null ? "" : serverSearch.getText().trim().toLowerCase(Locale.ROOT);
        ServerInfo previous = serverList.getSelection();

        shownServers.clear();
        shownServers.addAll(config.getNetwork().getServerInfos());
        shownServers.addAll(listedServers);
        if (!filter.isEmpty()) {
            shownServers.removeIf(info -> !(info.getName().toLowerCase(Locale.ROOT).contains(filter)
                    || info.getAddress().toLowerCase(Locale.ROOT).contains(filter)));
        }

        if (previous != null && shownServers.contains(previous)) {
            serverList.setSelection(previous);
        } else if (!shownServers.isEmpty()) {
            serverList.select(0);
        } else {
            serverList.setSelection(null);
        }

        boolean empty = shownServers.isEmpty();
        emptyTitle.setVisible(empty);
        emptyHint.setVisible(empty);

        for (ServerInfo info : shownServers) {
            requestPing(info);
            extInfo.computeIfAbsent(info, i -> infoService.requestInfo(i.getAddress(), i.getPort()));
        }
    }

    /**
     * Une sonde par serveur, au plus une fois : la reponse arrive sur le fil de jeu, seule place
     * d'ou l'on peut toucher un widget.
     */
    private void requestPing(ServerInfo info) {
        final String address = info.getAddress();
        final int port = info.getPort();
        final String mapKey = key(info);
        if (pings.containsKey(mapKey)) {
            return;
        }
        pings.put(mapKey, translationSystem.translate("${engine:menu#join-server-requested}"));
        pingPool.submit(() -> {
            String result;
            try {
                result = new PingService(address, port).call() + " ms";
            } catch (IOException | RuntimeException e) {
                result = UNKNOWN;
            }
            final String value = result;
            GameThread.asynch(() -> pings.put(mapKey, value));
        });
    }

    @Override
    public void onOpened() {
        super.onOpened();

        infoService = createWithConstructorInjection(ServerInfoService.class, context);
        pingPool = Executors.newFixedThreadPool(PING_THREADS, runnable -> {
            Thread thread = new Thread(runnable, "server-ping");
            thread.setDaemon(true);
            return thread;
        });
        lastKnownServerCount = -1;
        rebuildShownServers();

        if (playerConfig.playerName.getDefaultValue().equals(playerConfig.playerName.get())) {
            getManager().pushScreen(EnterUsernamePopup.ASSET_URI, EnterUsernamePopup.class);
        }

        if (storageServiceWorker.hasConflictingIdentities()) {
            new IdentityConflictHelper(storageServiceWorker, getManager(), translationSystem).runSolver();
        }
    }

    @Override
    public void update(float delta) {
        super.update(delta);

        if (!updateComplete) {
            if (downloader.isDone()) {
                updateComplete = true;
            }
            listedServers.clear();
            listedServers.addAll(Collections2.filter(downloader.getServers(), ServerInfo::isActive));
        }

        // Le telechargement arrive par a-coups : on ne refait la liste que quand elle a change,
        // sinon la selection du joueur sauterait a chaque image.
        int total = config.getNetwork().getServerInfos().size() + listedServers.size();
        if (total != lastKnownServerCount) {
            lastKnownServerCount = total;
            rebuildShownServers();
        }
    }

    @Override
    public void onClosed() {
        infoService.close();
        if (pingPool != null) {
            pingPool.shutdownNow();
        }
        super.onClosed();
    }

    @Override
    public boolean isLowerLayerVisible() {
        return false;
    }

    private void join(final String address, final int port) {
        Callable<JoinStatus> operation = () -> networkSystem.join(address, port);

        final WaitPopup<JoinStatus> popup = getManager().pushScreen(WaitPopup.ASSET_URI, WaitPopup.class);
        popup.setMessage(translationSystem.translate("${engine:menu#join-game-online}"),
                translationSystem.translate("${engine:menu#connecting-to}")
                        + " '" + address + ":" + port + "' - "
                        + translationSystem.translate("${engine:menu#please-wait}"));
        popup.onSuccess(result -> {
            if (result.getStatus() != JoinStatus.Status.FAILED) {
                engine.changeState(new StateLoading(result));
            } else {
                MessagePopup screen = getManager().pushScreen(MessagePopup.ASSET_URI, MessagePopup.class);
                screen.setMessage(translationSystem.translate("${engine:menu#failed-to-join}"),
                        translationSystem.translate("${engine:menu#could-not-connect-to-server}")
                                + " - " + result.getErrorMessage());
            }
        });
        popup.startOperation(operation, true);
    }

    public void refresh() {
        pings.clear();
        extInfo.clear();
        lastKnownServerCount = -1;
        rebuildShownServers();
    }

    @Override
    public boolean onKeyEvent(NUIKeyEvent event) {
        if (event.isDown()) {
            if (event.getKey() == Keyboard.Key.ESCAPE && isEscapeToCloseAllowed()) {
                triggerBackAnimation();
                return true;
            } else if (event.getKey() == Keyboard.Key.R) {
                refresh();
            }
        }
        return false;
    }

    /**
     * Quatre colonnes dans une ligne de liste. Un {@code UIList} n'a pas de colonnes : on place le
     * texte a des abscisses calculees sur la largeur de la ligne, et l'en-tete du tableau suit les
     * memes proportions dans le {@code .ui}.
     */
    private final class ServerRowRenderer extends AbstractItemRenderer<ServerInfo> {

        private static final float ADDRESS_START = 0.46f;
        private static final float PLAYERS_START = 0.73f;
        private static final float LATENCY_START = 0.87f;

        @Override
        public void draw(ServerInfo value, Canvas canvas) {
            Rectanglei region = canvas.getRegion();
            Font nameFont = TwoLineItemRenderer.font("engine:Uncial-Item");
            Font smallFont = TwoLineItemRenderer.font("engine:Grenze-Small");
            int width = region.maxX - region.minX;
            int x1 = region.minX + (int) (width * ADDRESS_START);
            int x2 = region.minX + (int) (width * PLAYERS_START);
            int x3 = region.minX + (int) (width * LATENCY_START);
            int line = nameFont.getLineHeight();

            canvas.drawTextRaw(value.getName(), nameFont, TITLE_COLOR,
                    new Rectanglei(region.minX, region.minY, x1 - 8, region.minY + line),
                    HorizontalAlign.LEFT, VerticalAlign.TOP);
            String owner = isCustom(value)
                    ? translationSystem.translate("${engine:menu#custom-servers}")
                    : value.getOwner();
            canvas.drawTextRaw(owner == null ? "" : owner, smallFont, MUTED_COLOR,
                    new Rectanglei(region.minX, region.minY + line + 2, x1 - 8, region.maxY),
                    HorizontalAlign.LEFT, VerticalAlign.TOP);

            canvas.drawTextRaw(value.getAddress() + ":" + value.getPort(), smallFont, MUTED_COLOR,
                    new Rectanglei(x1, region.minY, x2 - 8, region.maxY),
                    HorizontalAlign.LEFT, VerticalAlign.MIDDLE);
            canvas.drawTextRaw(players(value), smallFont, TITLE_COLOR,
                    new Rectanglei(x2, region.minY, x3 - 8, region.maxY),
                    HorizontalAlign.LEFT, VerticalAlign.MIDDLE);

            String ping = pings.getOrDefault(key(value), UNKNOWN);
            canvas.drawTextRaw(ping, smallFont, UNKNOWN.equals(ping) ? BAD_COLOR : GOOD_COLOR,
                    new Rectanglei(x3, region.minY, region.maxX, region.maxY),
                    HorizontalAlign.RIGHT, VerticalAlign.MIDDLE);
        }

        private String players(ServerInfo value) {
            Future<ServerInfoMessage> info = extInfo.get(value);
            if (info == null || !info.isDone()) {
                return UNKNOWN;
            }
            try {
                ServerInfoMessage message = info.get();
                return message == null ? UNKNOWN : Integer.toString(message.getOnlinePlayersAmount());
            } catch (ExecutionException | InterruptedException e) {
                return UNKNOWN;
            }
        }

        @Override
        public Vector2i getPreferredSize(ServerInfo value, Canvas canvas) {
            Font nameFont = TwoLineItemRenderer.font("engine:Uncial-Item");
            Font smallFont = TwoLineItemRenderer.font("engine:Grenze-Small");
            return new Vector2i(canvas.getRegion().maxX - canvas.getRegion().minX,
                    nameFont.getLineHeight() + 2 + smallFont.getLineHeight());
        }
    }
}
