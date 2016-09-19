/*
 * Copyright (c) 2015 Google Inc.  All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package uk.org.ngo.squeezer.service;

import android.os.Looper;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.Log;

import com.google.common.collect.ImmutableMap;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.client.ClientSessionChannel;
import org.cometd.client.BayeuxClient;
import org.cometd.client.transport.ClientTransport;
import org.cometd.client.transport.LongPollingTransport;
import org.cometd.common.HashMapMessage;
import org.eclipse.jetty.client.HttpClient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import de.greenrobot.event.EventBus;
import uk.org.ngo.squeezer.BuildConfig;
import uk.org.ngo.squeezer.itemlist.IServiceItemListCallback;
import uk.org.ngo.squeezer.model.Album;
import uk.org.ngo.squeezer.model.Artist;
import uk.org.ngo.squeezer.model.Player;
import uk.org.ngo.squeezer.service.event.HandshakeComplete;
import uk.org.ngo.squeezer.service.event.PlayersChanged;

public class CometClient extends BaseClient {
    private static final String TAG = CometClient.class.getSimpleName();

    /** Client to the comet server. */
    @Nullable
    private BayeuxClient mBayeuxClient;

    /** The channel to publish one-shot requests to. */
    private static final String CHANNEL_SLIM_REQUEST = "/slim/request";

    /** Map from a command ("players") to the listener class for responses. */
    private  final Map<String, ItemListener> mRequestMap;

    /** The format string for the channel to listen to for responses to one-shot requests. */
    private static final String CHANNEL_SLIM_REQUEST_RESPONSE_FORMAT = "/%s/slim/request/%s";

    /** The channel to publish subscription requests to. */
    private static final String CHANNEL_SLIM_SUBSCRIBE = "/slim/subscribe";

    /** The format string for the channel to listen to for responses to playerstatus requests. */
    private static final String CHANNEL_PLAYER_STATUS_RESPONSE_FORMAT = "/%s/slim/playerstatus/%s";

    /** Server capabilities */
    private boolean mCanMusicfolder = false;
    private boolean mCanRandomplay = false;
    private boolean mCanFavorites = false;
    private boolean mCanApps = false;
    private String mVersion = "";

    private String mPreferredAlbumSort = "album";
    private String[] mMediaDirs;

    /** Channels */
    private ClientSessionChannel mRequestChannel;

    private Map<String, Player> mPlayers = new HashMap<>();

    private final Map<String, ClientSessionChannel.MessageListener> mPendingRequests
            = new ConcurrentHashMap<>();

    private final Map<String, IServiceItemListCallback> mPendingBrowseRequests
            = new ConcurrentHashMap<>();

    // All requests are tagged with a correlation id, which can be used when
    // asynchronous responses are received.
    private volatile int mCorrelationId = 0;

    CometClient(@NonNull EventBus eventBus) {
        super(eventBus);

        mRequestMap = ImmutableMap.<String, ItemListener>builder()
                .put("players", new PlayersListener())
                .put("artists", new ArtistsListener())
                .put("albums", new AlbumsListener())
                .build();
    }

    // Shims around ConnectionState methods.
    public void startConnect(final SqueezeService service, String hostPort, final String userName,
                             final String password) {
        mConnectionState.setConnectionState(mEventBus, ConnectionState.CONNECTION_STARTED);

        HttpClient httpClient = new HttpClient();
        try {
            httpClient.start();
        } catch (Exception e) {
            // XXX: Handle this properly. Maybe startConnect() should throw exceptions if the
            // connection fails?
            e.printStackTrace();
            mConnectionState.setConnectionState(mEventBus, ConnectionState.CONNECTION_FAILED);
            return;
        }

        // XXX: Need to split apart hostPort, and provide a config mechanism that can
        // distinguish between CLI and Comet.
        // XXX: Also need to deal with usernames and passwords, and HTTPS
        //String url = String.format("http://%s/cometd", hostPort);
        String url = "http://192.168.0.44:9000/cometd";

        Map<String, Object> options = new HashMap<>();
        ClientTransport transport = new LongPollingTransport(options, httpClient);
        mBayeuxClient = new SqueezerBayeuxClient(url, transport);

        mExecutor.execute(new Runnable() {
            @Override
            public void run() {
                mBayeuxClient.handshake();
                if (!mBayeuxClient.waitFor(10000, BayeuxClient.State.CONNECTED)) {
                    mConnectionState.setConnectionState(mEventBus, ConnectionState.CONNECTION_FAILED);
                    return;  // XXX: Check if returning here is the right thing to do? Any other cleanup?
                }

                // Connected from this point on.
                mConnectionState.setConnectionState(mEventBus, ConnectionState.CONNECTION_COMPLETED);
                mConnectionState.setConnectionState(mEventBus, ConnectionState.LOGIN_STARTED);
                mConnectionState.setConnectionState(mEventBus, ConnectionState.LOGIN_COMPLETED);

                String clientId = mBayeuxClient.getId();

                mBayeuxClient.getChannel(String.format(CHANNEL_SLIM_REQUEST_RESPONSE_FORMAT, clientId, "*")).subscribe(new ClientSessionChannel.MessageListener() {
                    @Override
                    public void onMessage(ClientSessionChannel channel, Message message) {
                        ClientSessionChannel.MessageListener listener = mPendingRequests.get(message.getChannel());
                        if (listener != null) {
                            listener.onMessage(channel, message);
                            mPendingRequests.remove(channel);
                        }
                    }
                });

                //mConnectionState.startConnect(service, mEventBus, mExecutor, this, hostPort, userName, password);

                // There's no persistent connection to manage.  Run the connection state machine
                // so that the UI gets in to a consistent state
                fetchPlayers();

                // XXX: "listen 1" -- serverstatus?

                // Learn server capabilites.

                request(new ClientSessionChannel.MessageListener() {
                    @Override
                    public void onMessage(ClientSessionChannel channel, Message message) {
                        mCanMusicfolder = "1".equals(message.getDataAsMap().get("_can"));
                    }
                }, "can", "musicfolder", "?");

                request(new ClientSessionChannel.MessageListener() {
                    @Override
                    public void onMessage(ClientSessionChannel channel, Message message) {
                        mCanRandomplay = "1".equals(message.getDataAsMap().get("_can"));
                    }
                }, "can", "randomplay", "?");

                request(new ClientSessionChannel.MessageListener() {
                    @Override
                    public void onMessage(ClientSessionChannel channel, Message message) {
                        mCanFavorites = "1".equals(message.getDataAsMap().get("_can"));
                    }
                }, "can", "favorites", "items", "?");

                request(new ClientSessionChannel.MessageListener() {
                    @Override
                    public void onMessage(ClientSessionChannel channel, Message message) {
                        mCanApps = "1".equals(message.getDataAsMap().get("_can"));
                    }
                }, "can", "myapps", "items", "?");

                request(new ClientSessionChannel.MessageListener() {
                    @Override
                    public void onMessage(ClientSessionChannel channel, Message message) {
                        //mMediaDirs = (String[]) message.getDataAsMap().get("_p2");
                    }
                }, "pref", "mediadirs", "?");

                request(new ClientSessionChannel.MessageListener() {
                    @Override
                    public void onMessage(ClientSessionChannel channel, Message message) {
                        mPreferredAlbumSort = (String) message.getDataAsMap().get("_p2");
                    }
                }, "pref", "jivealbumsort", "?");

                request(new ClientSessionChannel.MessageListener() {
                    @Override
                    public void onMessage(ClientSessionChannel channel, Message message) {
                        //XXX implement wait for all replies and run the state machine accordingly
                        mVersion = (String) message.getDataAsMap().get("_version");
                        mEventBus.postSticky(new HandshakeComplete(
                                mCanFavorites, mCanMusicfolder,
                                mCanApps, mCanRandomplay,
                                mVersion));
                    }
                }, "version", "?");

                // XXX: Skipped "pref httpport ?"

//                sendCommandImmediately(
//                        "listen 1", // subscribe to all server notifications
//                        "can musicfolder ?", // learn music folder browsing support
//                        "can randomplay ?", // learn random play function functionality
//                        "can favorites items ?", // learn support for "Favorites" plugin
//                        "can myapps items ?", // learn support for "MyApps" plugin
//                        "pref httpport ?", // learn the HTTP port (needed for images)
//                        "pref jivealbumsort ?", // learn the preferred album sort order
//                        "pref mediadirs ?", // learn the base path(s) of the server music library
//
//                        // Fetch the version number. This must be the last thing
//                        // fetched, as seeing the result triggers the
//                        // "handshake is complete" logic elsewhere.
//                        "version ?"
            }
        });
    }

    private static class SqueezerBayeuxClient extends BayeuxClient {
        private Map<Object,String> subscriptionIds;

        public SqueezerBayeuxClient(String url, ClientTransport transport) {
            super(url, transport);
            subscriptionIds = new HashMap<>();
        }

        @Override
        public void onSending(List<? extends Message> messages) {
            super.onSending(messages);
            for (Message message : messages) {
                String channelName = message.getChannel();
                if (Channel.META_SUBSCRIBE.equals(channelName)) {
                    subscriptionIds.put(message.get(Message.SUBSCRIPTION_FIELD), message.getId());
                }
                if (BuildConfig.DEBUG) {
                    if (!Channel.META_CONNECT.equals(channelName)) {
                        Log.d(TAG, "SEND: " + message.getJSON());
                    }
                }
            }
        }

        @Override
        public void onMessages(List<Message.Mutable> messages) {
            super.onMessages(messages);
            for (Message message : messages) {
                String channelName = message.getChannel();
                if (Channel.META_SUBSCRIBE.equals(channelName)) {
                    if (message.getId() == null && message instanceof HashMapMessage) {
                        ((HashMapMessage)message).setId(subscriptionIds.get(message.get(Message.SUBSCRIPTION_FIELD)));
                    }
                }
                if (BuildConfig.DEBUG) {
                    if (!Channel.META_CONNECT.equals(channelName)) {
                        Log.d(TAG, "RECV: " + message.getJSON());
                    }
                }
            }
        }
    }

    abstract class ItemListener implements ClientSessionChannel.MessageListener {}

    private class PlayersListener extends ItemListener {
        @Override
        public void onMessage(ClientSessionChannel channel, Message message) {
            // XXX: Sanity check that the message contains an ID and players_loop

            // Note: This is probably wrong, need to figure out result paging.
            IServiceItemListCallback callback = mPendingBrowseRequests.get(message.getChannel());
            if (callback == null) {
                return;
            }

            Map<String, Object> data = message.getDataAsMap();
            Object[] item_data = (Object[]) data.get("players_loop");

            List<Player> items = new ArrayList<>();

            if (item_data != null) {
                for (Object player_d : item_data) {
                    items.add(new Player((Map<String, String>) player_d));
                }
            }

            callback.onItemsReceived(item_data.length, 0, null, items, Player.class);
        }
    }

    private class ArtistsListener extends ItemListener {
        @Override
        public void onMessage(ClientSessionChannel channel, Message message) {
            // Note: This is probably wrong, need to figure out result paging.
            IServiceItemListCallback callback = mPendingBrowseRequests.get(message.getChannel());
            if (callback == null) {
                return;
            }

            Map<String, Object> data = message.getDataAsMap();
            Object[] item_data = (Object[]) data.get("artists_loop");

            List<Artist> items = new ArrayList<>();

            if (item_data != null) {
                for (Object item_d : item_data) {
                    Map<String, String> record = (Map<String, String>) item_d;
                    for (Map.Entry<String, String> entry : record.entrySet()) {
                        Object value = entry.getValue();
                        if (value != null && !(value instanceof String)) {
                            record.put(entry.getKey(), value.toString());
                        }
                    }
                    items.add(new Artist(record));
                }
            }

            callback.onItemsReceived(item_data.length, 0, null, items, Player.class);
        }
    }

    private class AlbumsListener extends ItemListener {
        @Override
        public void onMessage(ClientSessionChannel channel, Message message) {
            // Note: This is probably wrong, need to figure out result paging.
            IServiceItemListCallback callback = mPendingBrowseRequests.get(message.getChannel());
            if (callback == null) {
                return;
            }

            Map<String, Object> data = message.getDataAsMap();
            Object[] item_data = (Object[]) data.get("albums_loop");

            List<Album> items = new ArrayList<>();

            if (item_data != null) {
                for (Object item_d : item_data) {
                    Map<String, String> record = (Map<String, String>) item_d;
                    for (Map.Entry<String, String> entry : record.entrySet()) {
                        Object value = entry.getValue();
                        if (value != null && !(value instanceof String)) {
                            record.put(entry.getKey(), value.toString());
                        }
                    }
                    items.add(new Album(record));
                }
            }

            callback.onItemsReceived(item_data.length, 0, null, items, Player.class);
        }
    }

    @Override
    public void disconnect(boolean loginFailed) {
        mBayeuxClient.disconnect();
        mConnectionState.disconnect(mEventBus, loginFailed);
        mPlayers.clear();
    }

    @Override
    public void sendCommandImmediately(String... commands) {

    }

    @Override
    public void sendCommand(String... commands) {

    }

    @Override
    public void sendPlayerCommand(Player player, String command) {

    }

    @Override
    public String[] getMediaDirs() {
        return new String[0];
    }

    @Override
    public boolean isConnected() {
        return false;
    }

    @Override
    public boolean isConnectInProgress() {
        return false;
    }


    /**
     * Queries for all players known by the server.
     * </p>
     * Posts a PlayersChanged message if the list of players has changed.
     */
    // XXX Copied from CliClient.
    private void fetchPlayers() {
        requestItems("players", -1, new IServiceItemListCallback<Player>() {
            private final HashMap<String, Player> players = new HashMap<String, Player>();

            @Override
            public void onItemsReceived(int count, int start, Map<String, String> parameters,
                                        List<Player> items, Class<Player> dataType) {
                for (Player player : items) {
                    players.put(player.getId(), player);
                }

                // If all players have been received then determine the new active player.
                if (start + items.size() >= count) {
                    if (players.equals(mPlayers)) {
                        return;
                    }

                    mPlayers.clear();
                    mPlayers.putAll(players);

                    // XXX: postSticky?
                    mEventBus.postSticky(new PlayersChanged(mPlayers));
                }
            }

            // XXX modified to change CliClient.this -> JsonClient.this.
            @Override
            public Object getClient() {
                return CometClient.this;
            }
        });
    }

    @Override
    public void onLineReceived(String serverLine) {

    }

    @Override
    public String getPreferredAlbumSort() {
        return mPreferredAlbumSort;
    }

    @Override
    public void cancelClientRequests(Object object) {

    }

    // Should probably have a pool of these.
    private class Request {
        String[] cmd;

        Request(String... cmd) {
            this.cmd = cmd;
        }

//        Request(String player, String cmd, List<String> parameters) {
//            if (player != null) {
//                this.player = player;
//            }
//            this.cmd = new String[parameters.size() + 1];
//            this.cmd[0] = cmd;
//            for (int i = 1; i <= parameters.size(); i++) {
//                this.cmd[i] = parameters.get(i - 1);
//            }
//        }

        Map<String, Object> getData(String chnResponse) {
            Map<String, Object> data = new HashMap<>();

            List<Object> request = new ArrayList<>();
            request.add("");
            request.add(cmd);

            data.put("request", request);
            data.put("response", chnResponse);
            return data;
        }
    }

    private String request(ClientSessionChannel.MessageListener callback, String... cmd) {
        String responseChannel = String.format(CHANNEL_SLIM_REQUEST_RESPONSE_FORMAT, mBayeuxClient.getId(), mCorrelationId++);
        mPendingRequests.put(responseChannel, callback);
        mBayeuxClient.getChannel(CHANNEL_SLIM_REQUEST).publish(new Request(cmd).getData(responseChannel));
        return responseChannel;
    }

    /**
     * Send an asynchronous request to the SqueezeboxServer for the specified items.
     * <p>
     * Items are returned to the caller via the specified callback.
     * <p>
     * See {@link #parseSqueezerList(CliClient.ExtendedQueryFormatCmd, List)} for details.
     *
     * @param playerId Id of the current player or null
     * @param cmd Identifies the type of items
     * @param start First item to return
     * @param pageSize No of items to return
     * @param parameters Item specific parameters for the request
     * @see #parseSqueezerList(CliClient.ExtendedQueryFormatCmd, List)
     */
    private void internalRequestItems(String playerId, String cmd, int start, int pageSize,
                                     List<String> parameters, final IServiceItemListCallback callback) {

        final ItemListener itemListener = mRequestMap.get(cmd);

        // XXX: Check for NPE (and or save the channel earlier)
        if (playerId != null) {
            Log.e(TAG, "Haven't written code for players yet");
            return;
        }

        final String[] request = new String[parameters.size() + 1];
        request[0] = cmd;
        for (int i = 1; i <= parameters.size(); i++) {
            request[i] = parameters.get(i - 1);
        }

        if (Looper.getMainLooper() != Looper.myLooper()) {
            mPendingBrowseRequests.put(request(itemListener, request), callback);
        } else {
            mExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    mPendingBrowseRequests.put(request(itemListener, request), callback);
                }
            });
        }

    }

    /**
     * Send an asynchronous request to the SqueezeboxServer for the specified items.
     * <p>
     * Items are requested in chunks of <code>R.integer.PageSize</code>, and returned
     * to the caller via the specified callback.
     * <p>
     * If start is zero, this will order one item, to quickly learn the number of items
     * from the server. When the server response with this item it is transferred to the
     * caller. The remaining items in the first page are then ordered, and transferred
     * to the caller when they arrive.
     * <p>
     * If start is < 0, it means the caller wants the entire list. They are ordered in
     * pages, and transferred to the caller as they arrive.
     * <p>
     * Otherwise request a page of items starting from start.
     * <p>
     * See {@link #parseSqueezerList(CliClient.ExtendedQueryFormatCmd, List)} for details.
     *
     * @param playerId Id of the current player or null
     * @param cmd Identifies the type of items
     * @param start First item to return
     * @param parameters Item specific parameters for the request
     * @see #parseSqueezerList(CliClient.ExtendedQueryFormatCmd, List)
     */
    private void internalRequestItems(String playerId, String cmd, int start, List<String> parameters, IServiceItemListCallback callback) {
        boolean full_list = (start < 0);

        if (full_list) {
            if (parameters == null)
                parameters = new ArrayList<String>();
            parameters.add("full_list:1");
        }

        internalRequestItems(playerId, cmd, (full_list ? 0 : start), (start == 0 ? 1 : mPageSize), parameters, callback);
    }

    @Override
    public void requestItems(String cmd, int start, List<String> parameters, IServiceItemListCallback callback) {
        internalRequestItems(null, cmd, start, parameters, callback);
    }

    @Override
    public void requestItems(String cmd, int start, IServiceItemListCallback callback) {
        requestItems(cmd, start, null, callback);
    }



    @Override
    public void requestItems(String cmd, int start, int pageSize, IServiceItemListCallback callback) {

    }

    @Override
    public void requestPlayerItems(@Nullable Player player, String cmd, int start, List<String> parameters, IServiceItemListCallback callback) {

    }
}
