/*
 * This file is part of VLCJ.
 *
 * VLCJ is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * VLCJ is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with VLCJ.  If not, see <http://www.gnu.org/licenses/>.
 *
 * Copyright 2009-2019 Caprica Software Limited.
 */

package me.lib720.caprica.vlcj.media;

import me.lib720.caprica.vlcj.media.events.MediaEventFactory;
import me.lib720.caprica.vlcj.binding.internal.libvlc_event_e;
import me.lib720.caprica.vlcj.binding.internal.libvlc_event_manager_t;
import me.lib720.caprica.vlcj.binding.internal.libvlc_event_t;
import me.lib720.caprica.vlcj.binding.internal.libvlc_instance_t;
import me.lib720.caprica.vlcj.support.eventmanager.EventNotification;
import me.lib720.caprica.vlcj.support.eventmanager.NativeEventManager;

import static me.lib720.caprica.vlcj.binding.lib.LibVlc.libvlc_media_event_manager;

final class MediaNativeEventManager extends NativeEventManager<Media, MediaEventListener> {

    MediaNativeEventManager(libvlc_instance_t libvlcInstance, Media eventObject) {
        super(libvlcInstance, eventObject, libvlc_event_e.libvlc_MediaMetaChanged, libvlc_event_e.libvlc_MediaThumbnailGenerated, "media-events");
    }

    @Override
    protected libvlc_event_manager_t onGetEventManager(Media eventObject) {
        return libvlc_media_event_manager(eventObject.mediaInstance());
    }

    @Override
    protected EventNotification<MediaEventListener> onCreateEvent(libvlc_instance_t libvlcInstance, libvlc_event_t event, Media eventObject) {
        return MediaEventFactory.createEvent(libvlcInstance, eventObject, event);
    }

}
