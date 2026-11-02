package org.watermedia.bootstrap;

import org.watermedia.WaterMedia;

/**
 * One unit of WaterMedia's load. It receives the environment it runs in, decides by itself whether this side
 * needs it, and handles the parts it can live without: a failure that escapes {@link #start()} ends the load.
 */
public abstract class WaterMediaModule {
    protected final WaterMedia watermedia;

    protected WaterMediaModule(final WaterMedia watermedia) {
        this.watermedia = watermedia;
    }

    /** Starts the module, once, on the thread that called {@link WaterMedia#start}. */
    public abstract void start() throws Exception;

    /** Returns the display name: the API that owns a nested Module, or the class itself. */
    public final String name() {
        final Class<?> owner = this.getClass().getEnclosingClass();
        return (owner != null ? owner : this.getClass()).getSimpleName();
    }
}
