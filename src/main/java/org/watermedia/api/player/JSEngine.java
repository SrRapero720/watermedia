package org.watermedia.api.player;

import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.watermedia.api.util.SampleType;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.FloatControl;
import javax.sound.sampled.SourceDataLine;
import java.nio.ByteBuffer;

import static org.watermedia.WaterMedia.LOGGER;

/**
 * Native, dependency-free {@link SFXEngine} backed by the Java Sound API
 * ({@code javax.sound.sampled}). It plays decoded PCM straight through the operating-system
 * mixer (WASAPI/DirectSound, ALSA/PulseAudio, CoreAudio), so it needs no OpenAL context and
 * no external library.
 * <p>
 * A first-class, user-selectable alternative to {@link ALEngine} that needs no OpenAL context.
 * Unlike it, this engine is stream-based: {@code format(SampleType, int, int)} opens a fresh
 * {@link SourceDataLine} and reconfiguration reopens it. Uploads are non-blocking: the line's
 * internal buffer is the backpressure, mirroring {@code ALEngine}'s buffer pool, so the media
 * clock keeps tracking the audible position via {@code pendingMs()}; a chunk larger than that
 * buffer reopens the line with room for it. Every entry point holds the engine monitor, so the
 * player's lifecycle thread and the host's control thread never interleave on the line.
 * <p>
 * Java Sound backend limitations: playback speed has no portable equivalent, so {@code speed(float)}
 * refuses and audio stays at 1.0×; there is no per-source spatialization, so {@link #source()}
 * reports no handle.
 */
public final class JSEngine extends SFXEngine {
    private static final Marker IT = MarkerManager.getMarker(JSEngine.class.getSimpleName());

    /** Default internal line buffer depth used by {@code PlayerAPI.jsEngine()}. */
    public static final int DEFAULT_BUFFER_MS = 300; // RIDES OVER GAME HITCHES; THE CLOCK FOLLOWS THE AUDIBLE POSITION VIA pendingMs()

    // CAPABILITY TABLES: JAVA SOUND SUPPORTS U8/S16 PCM MONO/STEREO ON EVERY PLATFORM; IT
    // DOESN'T SUPPORT FLOAT, S32 NOR MULTICHANNEL THROUGH THE DEFAULT MIXER, SO NEGOTIATION
    // ALWAYS FALLS BACK INSIDE THIS SET (THERE IS NO SECOND FALLBACK IF format() REJECTS)
    private static final SampleType[] SUPPORTED_TYPES = { SampleType.U8, SampleType.S16 };
    private static final ChannelSupport[] SUPPORTED_CHANNELS = {
            new ChannelSupport(1, SampleType.U8, SampleType.S16), // MONO
            new ChannelSupport(2, SampleType.U8, SampleType.S16), // STEREO
    };

    private final int bufferMs;
    // LINE + CONTROL ARE REPLACED ON RECONFIGURE (apply) FROM THE PLAYER'S LIFECYCLE THREAD AND READ FROM
    // THE CONTROL THREAD (volume/pause); EVERY ACCESS HOLDS THE ENGINE MONITOR
    private SourceDataLine line;
    private FloatControl gainControl;
    // start() WAS CALLED SINCE THE LAST stop(): isActive()/isRunning() STAY FALSE UNTIL DATA FLOWS, SO THEY CANNOT TELL
    private boolean started;
    // PENDING-AUDIO BOOKKEEPING (pendingMs): framesWritten - line.getLongFramePosition() = FRAMES WRITTEN BUT NOT YET AUDIBLE
    private long framesWritten;
    private int bytesPerFrame;
    private byte[] scratch = new byte[0]; // REUSABLE COPY TARGET FOR ByteBuffer → line.write
    private boolean dropWarned; // A DROPPED CHUNK IS LOGGED ONCE PER ENGINE

    // JAVA SOUND HAS NO OPENAL-STYLE SOURCE ID, SO source() STAYS 0
    JSEngine(final int bufferMs) {
        if (bufferMs <= 0) throw new IllegalArgumentException("bufferMs must be positive, got " + bufferMs);
        this.bufferMs = bufferMs;
    }

    // ==========================================================================
    // PUBLIC API
    // ==========================================================================

    @Override
    public ChannelSupport[] supportedChannels() {
        return SUPPORTED_CHANNELS.clone();
    }

    @Override
    public SampleType[] supportedTypes() {
        return SUPPORTED_TYPES.clone();
    }

    @Override
    public boolean canSpeed() { return false; }

    // ==========================================================================
    // INTERNAL API
    // ==========================================================================

    @Override
    synchronized boolean apply(final SampleType type, final int channels, final int sampleRate) {
        // A RELEASE BETWEEN THE BASE CHECK AND THIS MONITOR WOULD LEAK THE LINE OPENED BELOW
        if (this.released) return false;
        // THE BASE ONLY PASSES U8/S16 MONO/STEREO (THE TABLE), LITTLE-ENDIAN LIKE EVERY CANONICAL TYPE
        final AudioFormat fmt = new AudioFormat(type == SampleType.U8 ? AudioFormat.Encoding.PCM_UNSIGNED : AudioFormat.Encoding.PCM_SIGNED,
                sampleRate, type.bytes() * 8, channels, channels * type.bytes(), sampleRate, false);
        final int frameSize = fmt.getFrameSize();
        // REUSE THE OPEN LINE ON AN IDENTICAL RECONFIGURE (MIRRORS ALEngine's STATE-ONLY UPDATE); A NEW LAYOUT
        // OPENS A FRESH LINE WITH DEPTH ≈ bufferMs, DROPPING QUEUED AUDIO LIKE ALEngine's FLUSH
        final boolean reusable = this.line != null && this.line.isOpen() && fmt.matches(this.line.getFormat());
        if (!reusable && !this.openLine(fmt, (int) ((long) sampleRate * frameSize * this.bufferMs / 1_000L))) {
            // NO OPENABLE LINE FOR THIS FORMAT (NO DEVICE, UNSUPPORTED COMBINATION, ...): REPORT NO AUDIO
            // INSTEAD OF CRASHING PLAYER CREATION
            LOGGER.error(IT, "Java Sound line unavailable for type={} ch={} rate={}", type, channels, sampleRate);
            return false;
        }
        this.channels = channels;
        this.sampleRate = sampleRate;
        this.bytesPerFrame = frameSize;
        this.sampleType = type; // LAST: IT FLAGS A CONFIGURED LAYOUT TO upload() ON ANY THREAD
        return true;
    }

    @Override
    synchronized boolean push(final ByteBuffer data) {
        if (this.line == null) return false; // A RELEASE CLOSED THE LINE AFTER THE BASE LET THIS CALL THROUGH
        final int len = data.remaining();
        // A CHUNK LARGER THAN THE LINE BUFFER COULD NEVER PASS available(): REOPEN WITH ROOM FOR TWO, ONCE PER JUMP, DROPPING WHAT
        // THE OLD LINE QUEUED (FF CHUNKS RARELY OUTGROW IT) AND RESUMING IF IT RAN; WITHOUT A WIDER LINE THE CHUNK IS DROPPED
        if (len > this.line.getBufferSize()) {
            final boolean resume = this.started;
            LOGGER.debug(IT, "Audio chunk of {} bytes exceeds the {}-byte line buffer; reopening wider", len, this.line.getBufferSize());
            if (!this.openLine(this.line.getFormat(), 2 * len)) {
                if (!this.dropWarned) LOGGER.warn(IT, "Dropped a {}-byte audio chunk: no wider Java Sound line", len);
                this.dropWarned = true;
                return true;
            }
            if (resume) this.play();
        }
        final SourceDataLine l = this.line;
        // NON-BLOCKING: available() < len MEANS THE LINE IS FULL (BACKPRESSURE, LIKE ALEngine's EXHAUSTED POOL).
        // THE MIXER ONLY FREES SPACE, SO A len ≤ available() WRITE CANNOT BLOCK
        if (l.available() < len) return false;
        if (this.scratch.length < len) this.scratch = new byte[len];
        data.get(data.position(), this.scratch, 0, len); // ABSOLUTE READ: position NEVER MOVES (UPLOAD CONTRACT)
        l.write(this.scratch, 0, len);
        if (this.bytesPerFrame > 0) this.framesWritten += len / this.bytesPerFrame;
        return true;
    }

    @Override
    synchronized void flush() {
        final SourceDataLine l = this.line;
        if (l == null) return;
        // STOP + DISCARD (SEEK/QUALITY SWITCH): FLUSHED FRAMES NEVER ADVANCE getLongFramePosition(),
        // SO ALIGNING framesWritten TO IT ZEROES pendingMs() CORRECTLY
        l.stop();
        l.flush();
        this.started = false;
        this.framesWritten = l.getLongFramePosition();
    }

    @Override
    synchronized long pendingMs() {
        final SourceDataLine l = this.line;
        if (l == null || this.sampleRate <= 0) return 0;
        final long pendingFrames = this.framesWritten - l.getLongFramePosition();
        return pendingFrames <= 0 ? 0 : pendingFrames * 1_000L / this.sampleRate;
    }

    @Override
    synchronized void pause() {
        if (this.line != null) this.line.stop();
        this.started = false;
    }

    @Override
    synchronized void play() {
        // A STARTED LINE RENDERS WHATEVER IS WRITTEN AND AUTO-RESUMES AFTER AN UNDERRUN, SO START ONCE PER PAUSE CYCLE
        if (this.line == null || this.started) return;
        this.line.start();
        this.started = true;
    }

    @Override
    boolean speed(final float speed) {
        return false; // JAVA SOUND DOESN'T SUPPORT SPEED: REFUSED, PLAYBACK STAYS AT 1.0×
    }

    @Override
    synchronized void gain() {
        // MASTER_GAIN IS LOGARITHMIC (dB = 20·log10(linearGain)), CLAMPED TO THE CONTROL RANGE; SOME MIXERS EXPOSE NO CONTROL
        final FloatControl c = this.gainControl;
        if (c == null) return;
        final float gain = this.volume;
        if (gain <= 0.0f) {
            c.setValue(c.getMinimum()); // SILENCE
            return;
        }
        final float db = (float) (20.0 * Math.log10(gain));
        c.setValue(Math.min(c.getMaximum(), Math.max(c.getMinimum(), db)));
    }

    @Override
    synchronized void destroy() {
        this.closeLine();
        this.gainControl = null; // A LATE volume() CAN'T DRIVE A CLOSED LINE
    }

    // ==========================================================================
    // HELPERS
    // ==========================================================================

    // OPENS A LINE FOR fmt WITH AT LEAST bytes OF BUFFER (FRAME-ALIGNED) AND INSTALLS IT IN PLACE OF THE CURRENT ONE;
    // FALSE (CURRENT LINE UNTOUCHED, LOGGED AT DEBUG SINCE THE CALLER DECIDES THE SEVERITY) WHEN THE MIXER HAS NO SUCH LINE
    private boolean openLine(final AudioFormat fmt, final int bytes) {
        final int frameSize = fmt.getFrameSize();
        final SourceDataLine fresh;
        try {
            fresh = (SourceDataLine) AudioSystem.getLine(new DataLine.Info(SourceDataLine.class, fmt));
            fresh.open(fmt, Math.max(frameSize, bytes - bytes % frameSize));
        } catch (final Exception e) {
            LOGGER.debug(IT, "No Java Sound line for {} ({} bytes)", fmt, bytes, e);
            return false;
        }
        this.closeLine();
        this.line = fresh;
        this.gainControl = fresh.isControlSupported(FloatControl.Type.MASTER_GAIN)
                ? (FloatControl) fresh.getControl(FloatControl.Type.MASTER_GAIN) : null;
        this.framesWritten = 0L;
        this.gain(); // RE-APPLY THE LAST REQUESTED VOLUME TO THE NEW LINE
        return true;
    }

    private void closeLine() {
        final SourceDataLine l = this.line;
        if (l == null) return;
        l.stop();
        l.flush();
        l.close();
        this.line = null;
        this.started = false;
    }
}
