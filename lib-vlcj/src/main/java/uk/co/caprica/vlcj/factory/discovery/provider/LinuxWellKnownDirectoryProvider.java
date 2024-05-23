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

import uk.co.caprica.vlcj.binding.support.runtime.RuntimeUtil;

/**
 * Implementation of a directory provider that returns a list of well-known directory locations to search on Linux.
 */
public class LinuxWellKnownDirectoryProvider extends WellKnownDirectoryProvider {

    // WATERMeDIA PATCH - This is patched, not sure what but is patched
    private static final String[] DIRECTORIES = {
            "/usr/lib/x86_64-linux-gnu",
            "/usr/lib64",
            "/usr/local/lib64",
            "/usr/lib/i386-linux-gnu",
            "/usr/lib",
            "/usr/lib/vlc",
            "/usr/bin/",
            "/usr/bin/vlc",
            "/usr/local/lib",
            "/usr/local/lib/vlc",
            "/var/lib/flatpak",
            "/var/lib/flatpak/org.videolan.VLC",
            "/var/lib/flatpak/app/org.videolan.VLC",
            "/bin"
    };

    @Override
    public String[] directories() {
        return DIRECTORIES;
    }

    @Override
    public boolean supported() {
        return RuntimeUtil.isNix();
    }
}