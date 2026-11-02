package org.watermedia.api.player;

import org.watermedia.WaterMedia;
import org.watermedia.api.util.SampleType;

import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Sound engine abstraction for uploading decoded audio data to playback systems.
 * <p>
 * WATERMeDIA decodes audio, uploads sample data, and controls playback.
 * A native source handle and optional spatial controls are available for host integrations.
 * <p>
 * Engines are created through {@link PlayerAPI} factory methods and are client-side only:
 * construction throws on a server-side environment. Implementations are backend-specific:
 * OpenAL ({@link ALEngine}) binds to the context current when it is created, and every later call
 * except the pure queries ({@link #canSpeed()}, {@link #released()}, the capability tables) must run
 * with that context current on the calling thread (the player's decode thread and the sync ticker
 * included) or it throws; Java Sound ({@link JSEngine}) needs no context and is thread-safe.
 * <p>
 * {@link #release()} is idempotent and leaves the engine inert: formats and uploads are refused.
 * {@link #source()} is the native handle, 0 when the backend has none (Java Sound) or after release.
 */
public abstract sealed class SFXEngine permits ALEngine, JSEngine {
    /** Minimum reasonable sample rate (sub-telephony threshold, below is garbage). */
    public static final int MIN_SAMPLE_RATE_HZ = 4_000;
    /** Maximum reasonable sample rate (covers DXD and any hi-res consumer content). */
    public static final int MAX_SAMPLE_RATE_HZ = 384_000;

    // CONFIGURED LAYOUT, WRITTEN BY THE PRODUCER THREAD AND READ FROM ANY HOST THREAD
    volatile int source;
    volatile SampleType sampleType;
    volatile int channels;
    volatile int sampleRate;
    volatile float speed = 1.0f;
    volatile float volume = 1.0f;
    volatile boolean released;

    // SINGLE ENFORCEMENT POINT: EVERY AUDIO ENGINE CONSTRUCTION PASSES THROUGH HERE
    SFXEngine() {
        WaterMedia.checkIsClientSideOrThrow(this.getClass());
    }

    // ==========================================================================
    // PUBLIC API
    // ==========================================================================

    /** Returns a copy of the channel-support table, one entry per supported channel count: the only combinations the engine plays. */
    public abstract ChannelSupport[] supportedChannels();

    /** Returns a copy of the sample types playable at one channel count at least; confirm each count with {@link ChannelSupport#supports(SampleType)}. */
    public abstract SampleType[] supportedTypes();

    /** Support entry for exactly {@code channels}, or {@code null} when this backend does not expose that channel count. */
    public final ChannelSupport channelSupport(final int channels) {
        for (final ChannelSupport entry: this.supportedChannels())
            if (entry.channels() == channels) return entry;
        return null;
    }

    /** Support entry with the channel count closest to {@code channels}, ties preferring the lower count, or {@code null} on an empty table. */
    public final ChannelSupport closestChannelSupport(final int channels) {
        ChannelSupport best = null;
        int bestDiff = Integer.MAX_VALUE;
        for (final ChannelSupport entry: this.supportedChannels()) {
            final int diff = Math.abs(entry.channels() - channels);
            // TIES PREFER THE LOWER COUNT: A DOWNMIX IS MORE PREDICTABLE THAN AN UPMIX
            if (diff < bestDiff || (diff == bestDiff && best != null && entry.channels() < best.channels())) {
                best = entry;
                bestDiff = diff;
            }
        }
        return best;
    }

    /** Current source handle, 0 when the backend has none or the engine was released. */
    public int source() { return this.source; }

    /** Whether {@link #release()} already ran; a released engine refuses formats and uploads. */
    public final boolean released() { return this.released; }

    /** Whether this engine plays through positional mono sinks ({@link ALEngine#sinks()}) that accept host sound-thread updates. */
    public boolean spatial() { return false; }

    /** Last applied spatial state, or {@code null} for a dry listener-relative source. */
    public SpatialAudio spatialAudio() { return null; }

    /** Applies a host sound-thread update (null clears spatial effects) with the OpenAL context current; false when unsupported or released. */
    public boolean spatialAudio(final SpatialAudio audio) { return false; }

    /** Current sample type, or {@code null} before the player configures the first format. */
    public SampleType sampleType() { return this.sampleType; }

    /** Audio channels. */
    public int channels() { return this.channels; }

    /** Sample rate. */
    public int sampleRate() { return this.sampleRate; }

    /** Current playback speed multiplier (1.0 = normal). */
    public float speed() { return this.speed; }

    /** Current gain: 0 = silent, 1 = full. */
    public float volume() { return this.volume; }

    /** Whether the player can change the playback rate through this backend; a pure query any thread can ask every frame. */
    public abstract boolean canSpeed();

    /** Releases all resources of the source; later calls are no-ops. OpenAL needs its original context current here as well. */
    public final void release() {
        // THE TEST-AND-SET IS ATOMIC SO A CONCURRENT format() CANNOT REOPEN A LINE BEHIND THE TEARDOWN
        synchronized (this) {
            if (this.released) return;
            this.released = true;
        }
        try {
            this.destroy();
        } catch (final RuntimeException | Error e) {
            // ONLY A TEARDOWN REFUSED BEFORE THE SOURCE WAS FREED (WRONG OPENAL CONTEXT) CAN BE RETRIED; A FAILURE AFTER IT
            // (A HOST ENVIRONMENT RESET) STILL LEFT THE ENGINE INERT
            if (this.source != 0) this.released = false;
            throw e;
        }
    }

    // ==========================================================================
    // INTERNAL API
    // ==========================================================================

    // THE OWNING PLAYER DRIVES EVERY MEMBER OF THIS SECTION FROM ONE PRODUCER THREAD

    // RECONFIGURES THE SOURCE FOR A COMBINATION OF THE CHANNEL-SUPPORT TABLE AND A RATE (MIN_SAMPLE_RATE_HZ..MAX_SAMPLE_RATE_HZ)
    // BEFORE THE FIRST UPLOAD AND ON EVERY CHANGE; FALSE WHEN UNSUPPORTED OR RELEASED. A LAYOUT CHANGE DROPS QUEUED AUDIO
    final boolean format(final SampleType type, final int channels, final int sampleRate) {
        if (this.released || sampleRate < MIN_SAMPLE_RATE_HZ || sampleRate > MAX_SAMPLE_RATE_HZ) return false;
        final ChannelSupport support = this.channelSupport(channels);
        return support != null && support.supports(type) && this.apply(type, channels, sampleRate);
    }

    // BACKEND RECONFIGURATION FOR A TABLE COMBINATION THE BASE ALREADY VALIDATED; STREAM BACKENDS REOPEN THEIR LINE
    abstract boolean apply(final SampleType type, final int channels, final int sampleRate);

    // QUEUES PCM IN THE LAST format(), READING [position, limit) WITHOUT MOVING position: FALSE WHEN FULL (RETRY LATER),
    // UNCONFIGURED OR RELEASED. A HEAP, EMPTY OR FRAME-MISALIGNED CHUNK IS A CALLER BUG ON EVERY BACKEND
    final boolean upload(final ByteBuffer data) {
        final SampleType type = this.sampleType;
        if (this.released || type == null) return false;
        if (!data.isDirect() || !data.hasRemaining() || data.remaining() % (this.channels * type.bytes()) != 0)
            throw new IllegalArgumentException("Audio data must be direct, nonempty and sample-frame aligned");
        return this.push(data);
    }

    // BACKEND UPLOAD OF A CHUNK THE BASE ALREADY VALIDATED (SEE upload)
    abstract boolean push(final ByteBuffer data);

    // DROPS QUEUED AUDIO THAT HAS NOT PLAYED (SEEKS, QUALITY SWITCHES); PLAYBACK STOPS UNTIL THE NEXT upload + play
    abstract void flush();

    // MILLISECONDS OF UPLOADED AUDIO NOT YET AUDIBLE (DEVICE LATENCY INCLUDED WHEN THE BACKEND EXPOSES IT); 0 WHEN NOTHING
    // PENDS. THE AUDIBLE POSITION IS endPtsOfLastUpload - pendingMs()
    abstract long pendingMs();

    // PAUSES PLAYBACK
    abstract void pause();

    // STARTS OR RESUMES PLAYBACK
    abstract void play();

    // SETS THE PLAYBACK RATE (PITCH); FALSE WHEN THE BACKEND CANNOT CHANGE SPEED, PLAYBACK THEN STAYS AT 1.0x AND THE
    // PLAYER MUST NOT SCALE ITS TIMELINE
    abstract boolean speed(final float speed);

    // SETS THE GAIN: 0 = SILENT, 1 = FULL. A NEGATIVE OR NON-FINITE VALUE IS A CALLER BUG; IGNORED ONCE RELEASED
    final void volume(final float volume) {
        if (!Float.isFinite(volume) || volume < 0) throw new IllegalArgumentException("Volume must be finite and nonnegative");
        if (this.released) return;
        this.volume = volume;
        this.gain();
    }

    // APPLIES volume TO THE BACKEND; IT ALWAYS READS THE LATEST VALUE, SO CONCURRENT CALLS SETTLE ON THE LAST WRITE
    abstract void gain();

    // BACKEND TEARDOWN; A THROW THAT KEEPS THE SOURCE LEAVES THE ENGINE LIVE SO THE CALLER CAN RETRY ON THE RIGHT THREAD
    abstract void destroy();

    // ==========================================================================
    // NESTED TYPES
    // ==========================================================================

    /**
     * One supported channel count and the sample types playable at it. Declared per channel
     * count because support is not rectangular (e.g. OpenAL plays {@link SampleType#DBL} only
     * in mono/stereo, never multichannel).
     *
     * @param channels audio channel count (1..8)
     * @param types    sample types playable at this channel count, never empty
     */
    public record ChannelSupport(int channels, Set<SampleType> types) {
        public ChannelSupport {
            if (channels < 1 || channels > 8) throw new IllegalArgumentException("Channel count must be 1..8, got " + channels);
            if (types == null || types.isEmpty()) throw new IllegalArgumentException("A channel count needs at least one sample type");
            types = Collections.unmodifiableSet(EnumSet.copyOf(types));
        }

        public ChannelSupport(final int channels, final SampleType... types) {
            this(channels, types.length == 0 ? EnumSet.noneOf(SampleType.class) : EnumSet.copyOf(List.of(types)));
        }

        /** Whether {@code type} is playable at this channel count. */
        public boolean supports(final SampleType type) {
            return type != null && this.types.contains(type);
        }
    }

    /**
     * World position and attenuation for a mono sound source. Distances use the host's OpenAL distance model.
     * The host submits updates on its sound executor while the source's OpenAL context is current.
     *
     * @param x world X coordinate
     * @param y world Y coordinate
     * @param z world Z coordinate
     * @param referenceDistance distance at which attenuation starts
     * @param maxDistance maximum distance used by the host's attenuation model
     * @param rolloff attenuation multiplier; zero disables distance attenuation
     * @param auxOnly whether the environment should emit only reflected audio
     * @param environment optional processor for occlusion and reverb
     */
    public record SpatialAudio(double x, double y, double z, float referenceDistance, float maxDistance, float rolloff, boolean auxOnly, Environment environment) {
        public SpatialAudio {
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                    || !Float.isFinite((float) x) || !Float.isFinite((float) y) || !Float.isFinite((float) z))
                throw new IllegalArgumentException("Position must fit finite OpenAL coordinates");
            if (!Float.isFinite(referenceDistance) || referenceDistance <= 0
                    || !Float.isFinite(maxDistance) || maxDistance <= referenceDistance
                    || !Float.isFinite(rolloff) || rolloff < 0)
                throw new IllegalArgumentException("Distances must be finite with 0 < reference < max, and rolloff >= 0");
            if (auxOnly && environment == null)
                throw new IllegalArgumentException("Reflected-only audio requires an environment processor");
        }

        /** Host adapter for effects such as Sound Physics Remastered, called on the host's sound thread; it must not retain or delete the source nor switch context. */
        public interface Environment {
            /** Applies the complete environment after the original world position and dry sound are restored. */
            void process(int source, SpatialAudio audio);

            /** Clears adapter state when detached. The engine always removes source filters afterward. */
            default void reset(final int source) {}
        }
    }
}
