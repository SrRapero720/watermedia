package me.srrapero720.watermedia.api.player;

import uk.co.caprica.vlcj.factory.MediaPlayerFactory;

/**
 * Player variant with NO VIDEO
 */
public class SyncMusicPlayer extends SyncBasePlayer {


    public SyncMusicPlayer(MediaPlayerFactory factory) {
        super(factory, null, null);
    }

    public SyncMusicPlayer() {
        super(PlayerAPI.getFactorySoundOnly(), null, null);
    }
}