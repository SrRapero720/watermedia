package me.lib720.watermod.safety;

/**
 * @deprecated No replacement
 */
public class TryCore {
    public static <T> T withReturn(ActionWithReturn<T> runnable, T defaultVar) {
        return withReturn(runnable, null, defaultVar);
    }

    public static <T> T withReturn(ActionWithReturn<T> runnable, CatchSimple catchSimple, T defaultVar) {
        return withReturn(runnable, catchSimple, null, defaultVar);
    }

    public static <T> T withReturn(ActionWithReturn<T> runnable, CatchSimple catchSimple, ActionFinal<T> finallyRunnable, T defaultVar) {
        T returned = defaultVar;
        try {
            return returned = runnable.run(defaultVar);
        } catch (Exception exception) {
            if (catchSimple != null) catchSimple.run(exception);
            return defaultVar;
        } finally {
            if (finallyRunnable != null) finallyRunnable.run(returned);
        }
    }

    public static void simple(ActionSimple runnable) {
        try {
            runnable.run();
        } catch (Exception ignored) {}
    }

    public static void simple(ActionSimple runnable, CatchSimple catchSimple) {
        try {
            runnable.run();
        } catch (Exception e) {
            catchSimple.run(e);
        }
    }

    public interface ActionWithReturn<T> {
        T run(T defaultVar) throws Exception;
    }

    public interface ActionFinal<T> {
        void run(T returnedVar);
    }

    public interface ActionSimple {
        void run() throws Exception;
    }

    public interface CatchSimple {
        void run(Exception e);
    }

    public interface FinallySimple {
        void run();
    }
}