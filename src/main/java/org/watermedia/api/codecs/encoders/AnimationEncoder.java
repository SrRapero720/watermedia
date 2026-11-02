package org.watermedia.api.codecs.encoders;

import org.watermedia.api.codecs.Container;
import org.watermedia.api.codecs.Encoder;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;

/**
 * Base of the house-made animation encoders: it holds each frame until the next timestamp gives its duration, so
 * every picture is written knowing how long it shows. The last one lasts {@code 1 / fps}.
 */
abstract class AnimationEncoder extends Encoder {
    final OutputStream out;
    private ByteBuffer held; // COPY OF THE LAST FRAME, WRITTEN ONCE THE NEXT TIMESTAMP OR close() GIVES ITS DURATION
    private long heldPtsUs;
    private int pictures;

    AnimationEncoder(final Container container, final OutputStream out, final Video video, final Audio audio) {
        super(container, video, audio);
        if (out == null) throw new IllegalArgumentException("The output stream is null");
        this.out = out;
    }

    @Override
    protected final void frame(final ByteBuffer frame, final long ptsUs) throws IOException {
        if (this.held == null) this.held = ByteBuffer.allocate(frame.remaining());
        else this.picture(this.held, ptsUs - this.heldPtsUs, this.pictures++);
        this.held.clear();
        this.held.put(frame.duplicate()).flip();
        this.heldPtsUs = ptsUs;
    }

    @Override
    protected final void finish() throws IOException {
        try (this.out) {
            if (this.held == null) throw new IllegalStateException(this.container + " needs at least one frame");
            this.picture(this.held, 1_000_000L / this.video.fps(), this.pictures++);
            this.end(this.pictures);
        }
    }

    // WRITES ONE PICTURE SHOWN FOR durationUs; THE BUFFER IS ONLY VALID DURING THE CALL AND index COUNTS FROM ZERO
    abstract void picture(ByteBuffer frame, long durationUs, int index) throws IOException;

    // WRITES WHAT FOLLOWS THE LAST PICTURE; pictures IS THE TOTAL WRITTEN, AT LEAST ONE
    abstract void end(int pictures) throws IOException;
}
