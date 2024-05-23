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

package uk.co.caprica.vlcj.binding;

import com.sun.jna.Native;

import java.awt.*;
import java.lang.reflect.Method;

/**
 * Helper class to get the native component id of a component on OSX.
 */
final public class OsxComponentId {

    /**
     * Get the native component id for a component.
     * <p>
     * This implementation uses reflection to drill-down and find the native component id.
     *
     * @param component component
     * @return component id
     */
    public static long getOsxComponentId(Component component) {
        // Try the usual method first, this should still work on JDK 1.6
        try {
            long componentId = Native.getComponentID(component);
            if (componentId != 0) {
                return componentId;
            }
        }
        catch (Exception e) {
        }

        // Try for Apple's JDK 1.6
        try {
            Method getPeer = Component.class.getMethod("getPeer");
            Object peer = getPeer.invoke(component);
            Method getViewPtr = peer.getClass().getMethod("getViewPtr");
            return (Long) getViewPtr.invoke(peer);
        }
        catch (Exception e) {
        }

        // Window at least is heavyweight so this might work
        if (component instanceof Window) {
            Window window = (Window) component;
            try {
                Method getPeer = Window.class.getMethod("getPeer");
                Object peer = getPeer.invoke(window);
                Method getPlatformWindow = peer.getClass().getMethod("getPlatformWindow");
                Object platformWindow = getPlatformWindow.invoke(peer);
                Method getContentView = platformWindow.getClass().getMethod("getContentView");
                Object contentView = getContentView.invoke(platformWindow);
                Method getAwtView = contentView.getClass().getMethod("getAWTView");
                return (Long) getAwtView.invoke(contentView);
            }
            catch (Exception e) {
            }
        }

        return 0L;
    }

    private OsxComponentId() {
    }

}
