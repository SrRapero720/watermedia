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

import me.lib720.caprica.vlcj.binding.RuntimeUtil;

/**
 * Implementation of a directory provider that returns a list of well-known directory locations to search on Linux.
 */
public class LinuxWellKnownDirectoryProvider extends WellKnownDirectoryProvider {

    private static final String[] DIRECTORIES = {
            "/usr/lib/x86_64-linux-gnu",
            "/usr/lib64",
            "/usr/local/lib64",
            "/usr/lib/i386-linux-gnu",
            "/usr/lib",
            "/usr/lib/vlc",
            "/usr/bin/",
            "/usr/local/lib",
            "/bin" // WATERMeDIA PATCH - Adds pacman dir support
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