package me.srrapero720.watermedia.api.video.players.events.common;

import me.srrapero720.watermedia.api.video.players.Player;

public interface PlayerPreparingEvent<P extends Player> extends Event<PlayerPreparingEvent.EventData, P> {
    record EventData() {}
}
