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

package me.lib720.caprica.vlcj.factory.discovery.provider;

import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.WinReg;
import me.lib720.caprica.vlcj.binding.RuntimeUtil;

/**
 * Implementation of a directory provider that uses the native Windows Registry to locate the VLC installation directory
 * on Windows.
 */
public class WindowsInstallDirectoryProvider implements DiscoveryDirectoryProvider {

    /**
     * The VLC registry key, under HKLM.
     */
    private static final String VLC_REGISTRY_KEY = "SOFTWARE\\VideoLAN\\VLC";

    /**
     * The VLC registry key for the installation directory.
     */
    private static final String VLC_INSTALL_DIR_KEY = "InstallDir";

    @Override
    public int priority() {
        return DiscoveryProviderPriority.INSTALL_DIR;
    }

    @Override
    public String[] directories() {
        String installDir = getVlcInstallDir();
        String userInstallDir = getVlcUserInstallDir();
        if (installDir != null && userInstallDir != null) {
            return new String[] { installDir, userInstallDir };
        } else if (installDir != null) {
            return new String[] { installDir };
        } else if (userInstallDir != null) {
            return new String[] { userInstallDir };
        } else {
            return new String[0];
        }
    }

    @Override
    public boolean supported() {
        return RuntimeUtil.isWindows();
    }

    private String getVlcInstallDir() {
        try {
            return Advapi32Util.registryGetStringValue(WinReg.HKEY_LOCAL_MACHINE, VLC_REGISTRY_KEY, VLC_INSTALL_DIR_KEY);
        }
        catch(Exception e) {
            return null;
        }
    }

    private String getVlcUserInstallDir() {
        try {
            return Advapi32Util.registryGetStringValue(WinReg.HKEY_CURRENT_USER, VLC_REGISTRY_KEY, VLC_INSTALL_DIR_KEY);
        }
        catch(Exception e) {
            return null;
        }
    }

}
