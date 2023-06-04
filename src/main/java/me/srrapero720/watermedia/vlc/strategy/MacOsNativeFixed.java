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
 * Copyright 2009-2022 Caprica Software Limited.
 */

package me.srrapero720.watermedia.vlc.strategy;

import me.lib720.caprica.vlcj.binding.lib.LibC;
import me.lib720.caprica.vlcj.binding.support.runtime.RuntimeUtil;

public class MacOsNativeFixed extends DirsDiscoveryFixed {
    private static final String[] FILENAME_PATTERNS = new String[] { "libvlc\\.dylib", "libvlccore\\.dylib" };
    private static final String[] PLUGIN_PATH_FORMATS = new String[] { "%s/plugins" };
    
    public MacOsNativeFixed() {
        super(FILENAME_PATTERNS, PLUGIN_PATH_FORMATS);
    }
    
    @Override
    public boolean supported() {
        return RuntimeUtil.isMac();
    }
    
    @Override
    protected boolean setPluginPath(String pluginPath) { return LibC.INSTANCE.setenv(PLUGIN_ENV_NAME, pluginPath, 1) == 0; }
}