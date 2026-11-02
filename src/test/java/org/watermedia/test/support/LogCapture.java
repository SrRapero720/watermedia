package org.watermedia.test.support;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Captures emitted events from one project logger and restores its level after a test. */
public final class LogCapture extends AbstractAppender implements AutoCloseable {
    private final List<LogEvent> events = new CopyOnWriteArrayList<>();
    private final Logger logger;
    private final Level previous;

    public LogCapture(final String name) {
        super("Capture-" + System.nanoTime(), null, null, false, Property.EMPTY_ARRAY);
        this.logger = ((LoggerContext) LogManager.getContext(false)).getLogger(name);
        this.previous = this.logger.getLevel();
        this.start();
        this.logger.addAppender(this);
        this.logger.setLevel(Level.TRACE);
    }

    @Override
    public void append(final LogEvent event) { this.events.add(event.toImmutable()); }

    public List<LogEvent> events() { return List.copyOf(this.events); }

    @Override
    public void close() {
        this.logger.removeAppender(this);
        this.logger.setLevel(this.previous);
        this.stop();
    }
}
