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

package uk.co.caprica.vlcj.factory.discovery.provider;

import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.WinReg;
import uk.co.caprica.vlcj.binding.support.runtime.RuntimeUtil;
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery;

import java.io.File;

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
            NativeDiscovery.LOGGER.error("Failed to get VLC installation path from HKEY_LOCAL_MACHINE: {}", e.getMessage());
            NativeDiscovery.LOGGER.warn("Recurring to fallback system...");

            if (new File("C:\\Program Files\\VideoLAN\\VLC").exists()) {
                NativeDiscovery.LOGGER.info("VLC well-know installation folder founded.");
                return "C:\\Program Files\\VideoLAN\\VLC";
            }

            if (new File("C:\\Program Files (x86)\\VideoLAN\\VLC").exists()) {
                NativeDiscovery.LOGGER.info("VLC well-know installation folder founded.");
                NativeDiscovery.LOGGER.warn("DEPRECATION NOTICE: x32 is not longer supported. Please switch to an x64 installation");
                return "C:\\Program Files (x86)\\VideoLAN\\VLC";
            }
            NativeDiscovery.LOGGER.warn("Fallback system failed... delegating decision to NativeDiscovery");
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
