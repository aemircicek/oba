package com.emirchat.ui;

import com.emirchat.api.Packet;
import com.emirchat.client.ChatClient;
import com.emirchat.client.History;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The two places of a community that are drawn rather than listed: the map of its channels as
 * oases, and the campfire of the voice room you are in.
 */
final class VoicePlaces {
    private final MainView main;

    VoicePlaces(MainView main) {
        this.main = main;
    }

    /** The community as a map of oases (see {@link CommunityMap}). */
    void showMap(long communityId) {
        Packet.GroupInfo root = main.group(communityId);
        if (root == null) return;
        if (main.current != null) { // the map replaces the open chat (so its unread messages count again)
            main.current.dispose();
            main.current = null;
        }
        ChatClient c = main.session.client();
        Map<String, History.Summary> summaries = main.sidebarUi.summaries();
        List<CommunityMap.Oasis> oases = new ArrayList<>();
        for (Packet.GroupInfo ch : c.channels(communityId)) {
            String conv = AccountSession.group(ch.id());
            History.Summary s = summaries.get(conv);
            String topic;
            try {
                topic = main.session.history().topic(conv);
            } catch (IOException e) {
                topic = "";
            }
            String line = s == null ? "" : (s.lastSender().equals(c.username()) ? I18n.t("conv.you") : s.lastSender()) + ": "
                    + (Sidebar.specialPreview(s.lastText()) != null ? Sidebar.specialPreview(s.lastText()) : Markdown.plain(s.lastText()));
            oases.add(new CommunityMap.Oasis(ch.id(), ConversationView.channelTitle(ch), topic, line,
                    main.sidebarUi.unread(conv, summaries), s == null ? 0 : s.lastAt(), main.voiceRooms.getOrDefault(ch.id(), List.of()).size()));
        }
        CommunityMap map = new CommunityMap(communityId, ConversationView.groupName(root), oases, new CommunityMap.Actions() {
            @Override public void open(long channelId) { main.open(AccountSession.group(channelId)); }

            @Override public void joinVoice(long channelId) {
                main.session.background().execute(() -> main.session.client().voice().join(channelId));
            }
        });
        main.campfireShown = false;
        main.mapShown = communityId;
        main.content.getChildren().setAll(map.root());
        main.sidebarUi.refreshSidebar();
    }

    /** Redraws the map if one is open (new messages, people in voice rooms). */
    void refreshMap() {
        if (main.mapShown != 0 && !main.campfireShown && main.group(main.mapShown) != null) showMap(main.mapShown);
    }

    void showCampfire() {
        if (main.campfire == null) return;
        main.campfireShown = true;
        main.content.getChildren().setAll(main.campfire.root());
        if (main.voiceState.joined()) main.callBars.showVoiceBar(main.voiceState);
    }

    /** Back from the fire to the chat that was open (or the room's own chat). */
    void backFromCampfire() {
        main.campfireShown = false;
        if (main.current != null) main.content.getChildren().setAll(main.current.root());
        else if (main.voiceState.joined()) main.open(AccountSession.group(main.voiceState.groupId()));
        else main.showEmpty();
        if (main.voiceState.joined()) main.callBars.showVoiceBar(main.voiceState);
    }
}
