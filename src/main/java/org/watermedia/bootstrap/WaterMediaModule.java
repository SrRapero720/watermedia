package org.watermedia.bootstrap;

import org.watermedia.WaterMedia;

import java.nio.file.Path;

/**
 * One unit of WaterMedia's load. It receives the environment it runs in, decides by itself whether this side
 * needs it, and handles the parts it can live without: a failure that escapes {@link #start} ends the load.
 */
public abstract class WaterMediaModule {
    /** Starts the module, once, on the thread that called {@link WaterMedia#start}, with the environment it received. */
    public abstract void start(final String name, final Path tmp, final Path cwd, final boolean clientSide) throws Exception;

    /** Returns the display name: the API that owns a nested Module, or the class itself. */
    public final String name() {
        final Class<?> owner = this.getClass().getEnclosingClass();
        return (owner != null ? owner : this.getClass()).getSimpleName();
    }
}
