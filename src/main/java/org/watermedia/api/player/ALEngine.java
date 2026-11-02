package org.watermedia.api.player;

import org.lwjgl.openal.*;
import org.watermedia.api.util.SampleType;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * Default OpenAL-backed {@link SFXEngine}, driven through LWJGL.
 * <p>
 * Decoded PCM is uploaded into a small fixed pool of AL buffers streamed onto AL sources;
 * the pool depth is what absorbs game hitches (GC, chunk loads) without underrunning, while the
 * media clock follows the audible position via {@code pendingMs()} so depth never desyncs A/V.
 * When {@code AL_SOFT_source_latency} is present, latency queries compensate for device output
 * latency for sub-sample A/V precision.
 * <p>
 * A plain engine plays every decoded channel on one listener-relative source. A spatial engine plays
 * through {@link Sink sinks}, positional mono sources fed by one decode: it decodes mono or stereo
 * (surround folds into stereo, so the center channel reaches both sides) and each sink plays one
 * {@link Mix} of it. Every sink queues the same samples and the sinks start, pause and stop in one
 * batch, so a stereo pair stays sample aligned; a sink added later joins on the primary's sample.
 * The primary sink ({@link #source()}) lives as long as the engine and drives the media clock.
 * <p>
 * <b>Precondition:</b> a current OpenAL device/context must already exist when an ALEngine is
 * constructed; in a mod the sound system owns that context, so it must be initialized first.
 * Construction fails fast with {@link IllegalStateException} when no context is current.
 * Create instances through {@code PlayerAPI.alEngine}.
 */
public final class ALEngine extends SFXEngine {
    /** Default AL buffer pool depth used by {@code PlayerAPI.alEngine()}. */
    public static final int DEFAULT_BUFFER_COUNT = 8; // ~43 MS EACH ≈ 340 MS OF DEPTH; THE CLOCK FOLLOWS pendingMs(), SO DEPTH NEVER DESYNCS A/V

    private final SampleType[] supportedTypes;
    private final ChannelSupport[] supportedChannels;

    private final int bufferCount;
    private final boolean latencySupported;
    private final boolean spatial;
    private final boolean threadContext;
    private final long context;
    private final boolean efx;
    private final int auxiliarySends;
    private final Sink primary;
    // EVERY LIVE SINK (THE PRIMARY FIRST) AND THEIR SOURCE IDS FOR BATCH CALLS; REPLACED, NEVER MUTATED, UNDER THE ENGINE MONITOR
    private volatile List<Sink> sinks;
    private int[] sourceIds;
    // QUEUE STATE SHARED BY EVERY SINK, GUARDED BY THE ENGINE MONITOR: UPLOADS, HOST CONTROLS, ENVIRONMENT CALLBACKS AND RELEASE
    // NEVER OVERLAP. queuedDur IS A FIFO OF DURATIONS MIRRORING EACH SOURCE QUEUE ORDER, POPPED BY THE PRIMARY'S reclaimProcessed
    private final long[] queuedDur;
    // SPATIAL ONLY: THE DECODED CHUNK BEHIND EACH QUEUED BUFFER, KEPT TO REBUILD EVERY SINK ON THE PRIMARY'S SAMPLE (align)
    private final ByteBuffer[] queuedPcm;
    private int queuedHead;
    private int queuedCount;
    private long totalQueuedUs;
    // PRECOMPUTED AL FORMAT OF EVERY SINK BUFFER (MONO IN SPATIAL MODE), UPDATED BY format() AND READ BY upload
    private int alFormat;
    private int bytesPerFrame; // BYTES PER DECODED SAMPLE-FRAME (CHANNELS × SAMPLE SIZE)
    private ByteBuffer mixed; // SCRATCH FOR ONE SINK'S MONO MIX OF A STEREO CHUNK
    // REUSED SCRATCH FOR AL_SOFT_source_latency QUERIES: [0] = OFFSET SECONDS, [1] = DEVICE LATENCY SECONDS
    private final double[] latencyValues = new double[2];

    ALEngine(final int bufferCount) {
        this(bufferCount, false);
    }

    // SPATIAL MODE PLAYS THROUGH POSITIONAL MONO SINKS
    ALEngine(final int bufferCount, final boolean spatial) {
        if (bufferCount <= 0) throw new IllegalArgumentException("bufferCount must be positive, got " + bufferCount);
        final ALCapabilities caps = AL.getCapabilities();
        this.supportedChannels = channelTable(caps.AL_EXT_FLOAT32, caps.AL_EXT_DOUBLE, caps.AL_EXT_MCFORMATS, spatial);
        final EnumSet<SampleType> types = EnumSet.noneOf(SampleType.class);
        for (final ChannelSupport channels: this.supportedChannels) types.addAll(channels.types());
        this.supportedTypes = types.toArray(SampleType[]::new);
        this.threadContext = ALC.getCapabilities().ALC_EXT_thread_local_context;
        final long localContext = this.threadContext ? EXTThreadLocalContext.alcGetThreadContext() : 0;
        this.context = localContext != 0 ? localContext : ALC10.alcGetCurrentContext();
        if (this.context == 0) throw new IllegalStateException("An OpenAL context must be current before creating an ALEngine");
        this.spatial = spatial;
        this.efx = caps.ALC_EXT_EFX;
        this.auxiliarySends = this.efx
                ? ALC10.alcGetInteger(ALC10.alcGetContextsDevice(this.context), EXTEfx.ALC_MAX_AUXILIARY_SENDS) : 0;
        this.latencySupported = caps.AL_SOFT_source_latency;
        this.bufferCount = bufferCount;
        this.queuedDur = new long[bufferCount];
        this.queuedPcm = spatial ? new ByteBuffer[bufferCount] : null;
        // sampleType / channels / sampleRate / alFormat ARE POPULATED BY format() BEFORE THE FIRST UPLOAD; UNINITIALIZED
        // STATE IS A CALLER BUG
        AL10.alGetError(); // DRAIN ANY RESIDUAL HOST ERROR (MC's SOUND ENGINE) SO THE SINK SETUP SEES ONLY OURS
        this.primary = new Sink(spatial ? Mix.MONO : null);
        this.source = this.primary.source;
        this.sinks(List.of(this.primary));
    }

    // ==========================================================================
    // PUBLIC API
    // ==========================================================================

    @Override
    public ChannelSupport[] supportedChannels() {
        return this.supportedChannels.clone();
    }

    @Override
    public SampleType[] supportedTypes() {
        return this.supportedTypes.clone();
    }

    @Override
    public boolean spatial() { return this.spatial; }

    @Override
    public SpatialAudio spatialAudio() { return this.primary.spatialAudio; }

    /** Applies a host sound-thread update to the primary sink; see {@link Sink#spatialAudio(SpatialAudio)}. */
    @Override
    public boolean spatialAudio(final SpatialAudio audio) { return this.primary.spatialAudio(audio); }

    // AL_PITCH IS CORE OPENAL; ONLY A RELEASED SOURCE CANNOT CHANGE SPEED. THE VOLATILE READ NEEDS NO CONTEXT
    @Override
    public boolean canSpeed() { return this.source != 0; }

    /** Returns the live sinks, the primary first: an immutable snapshot any thread can read, empty on a plain or released engine. */
    public List<Sink> sinks() { return this.spatial ? this.sinks : List.of(); }

    /**
     * Adds a sink playing {@code mix} of the decoded audio, with the engine's OpenAL context current. A sink
     * added while audio is queued joins on the primary's sample: every sink's queue is rebuilt, which pauses
     * the others for about one mixer update. The new sink starts dry and listener-relative at the engine's
     * gain and pitch; position it with {@link Sink#spatialAudio(SpatialAudio)} or hand its source to the host.
     * @return the new sink, or {@code null} on a plain or released engine
     * @throws IllegalArgumentException when {@code mix} is null
     * @throws IllegalStateException when the engine's context is not current or OpenAL cannot create a source
     */
    public synchronized Sink addSink(final Mix mix) {
        if (mix == null) throw new IllegalArgumentException("A sink needs a mix");
        if (!this.spatial || this.source == 0) return null;
        this.requireContext();
        AL10.alGetError();
        final Sink sink = new Sink(mix);
        final List<Sink> sinks = new ArrayList<>(this.sinks);
        sinks.add(sink);
        this.sinks(sinks);
        if (this.queuedCount > 0 && this.align()) AL10.alSourcePlayv(this.sourceIds);
        return sink;
    }

    /** Returns a defensive copy of the AL buffer ids owned by the primary sink. */
    public int[] buffers() {
        return this.primary.buffers.clone();
    }

    // ==========================================================================
    // INTERNAL API
    // ==========================================================================

    @Override
    synchronized boolean apply(final SampleType type, final int channels, final int sampleRate) {
        // THE BASE ONLY PASSES TABLE COMBINATIONS, AND EVERY ONE HAS A NATIVE FORMAT; SPATIAL SINKS PLAY MONO MIXES OF IT
        if (this.source == 0) return false;
        final int al = alFormatFor(type, this.spatial ? 1 : channels);
        if (al == AL10.AL_NONE) throw new IllegalStateException("The OpenAL channel table offers " + type + " x" + channels + " without a native format");
        // A LAYOUT CHANGE WITH QUEUED AUDIO WOULD MAKE THE DRIVER REJECT THE NEXT BUFFER AND THE RETAINED CHUNKS UNREADABLE:
        // DROP THE QUEUE FIRST
        if (this.alFormat != 0 && (al != this.alFormat || channels != this.channels || sampleRate != this.sampleRate) && this.queuedCount > 0)
            this.flush();
        this.channels = channels;
        this.sampleRate = sampleRate;
        this.alFormat = al;
        this.bytesPerFrame = channels * type.bytes();
        this.sampleType = type; // LAST: IT FLAGS A CONFIGURED LAYOUT TO upload() ON ANY THREAD
        return true;
    }

    @Override
    synchronized boolean push(final ByteBuffer data) {
        if (this.source == 0) return false;
        this.requireContext();
        AL10.alGetError();
        for (final Sink sink: this.sinks) {
            // DRAIN *ALL* PROCESSED BUFFERS FIRST: alSourcePlay ON A STOPPED SOURCE (UNDERRUN) REPLAYS
            // ITS QUEUE FROM THE START, SO ANY STALE BUFFER LEFT QUEUED PLAYS OLD AUDIO AGAIN
            this.reclaimProcessed(sink);

            // A STOPPED SOURCE MARKS EVERY QUEUED BUFFER PROCESSED (EVEN FRESH ONES), SO AN UPLOAD WOULD BE RECLAIMED
            // AND LOST ON THE NEXT CALL; REWIND TO INITIAL SO NEW AUDIO PENDS PROPERLY
            final int state = AL10.alGetSourcei(sink.source, AL10.AL_SOURCE_STATE);
            checkError("query source state");
            if (sink.freeCount == sink.buffers.length && state == AL10.AL_STOPPED) {
                AL10.alSourceRewind(sink.source);
                checkError("rewind source");
            }

            // NO BUFFER AVAILABLE ON A SINK: SIGNAL BACKPRESSURE
            if (sink.freeCount == 0) return false;
        }

        final long durationUs = this.bytesPerFrame > 0 && this.sampleRate > 0
                ? (data.remaining() / this.bytesPerFrame) * 1_000_000L / this.sampleRate
                : 0L;
        // EVERY SINK QUEUES THE CHUNK; A FAILURE AFTER THE FIRST ONE DROPS THE WHOLE QUEUE SO THE SINKS NEVER DRIFT APART
        int queued = 0;
        try {
            for (final Sink sink: this.sinks) {
                final int buffer = sink.freeIds[sink.freeCount - 1];
                AL10.alBufferData(buffer, this.alFormat, this.render(data, sink.mix), this.sampleRate);
                checkError("upload PCM");
                AL10.alSourceQueueBuffers(sink.source, buffer);
                checkError("queue PCM");
                // TRANSFER OWNERSHIP ONLY AFTER BOTH NATIVE OPERATIONS SUCCEEDED
                sink.freeCount--;
                queued++;
            }
        } catch (final RuntimeException | Error failure) {
            if (queued > 0) {
                try { this.flush(); }
                catch (final RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); }
            }
            throw failure;
        }

        // ENQUEUE THE DURATION, AND IN SPATIAL MODE A COPY OF THE CHUNK, AT THE FIFO TAIL
        final int tail = (this.queuedHead + this.queuedCount) % this.bufferCount;
        this.queuedDur[tail] = durationUs;
        if (this.spatial) {
            ByteBuffer kept = this.queuedPcm[tail];
            if (kept == null || kept.capacity() < data.remaining()) kept = this.queuedPcm[tail] = ByteBuffer.allocateDirect(data.remaining());
            kept.clear();
            kept.put(data.duplicate());
            kept.flip();
        }
        this.queuedCount++;
        this.totalQueuedUs += durationUs;
        return true;
    }

    @Override
    synchronized void flush() {
        if (this.source == 0) return;
        this.requireContext();
        this.clear();
        this.queuedHead = 0;
        this.queuedCount = 0;
        this.totalQueuedUs = 0;
    }

    @Override
    synchronized long pendingMs() {
        if (this.source == 0) return 0;
        this.requireContext();
        final int state = AL10.alGetSourcei(this.source, AL10.AL_SOURCE_STATE);
        // STOPPED (UNDERRUN) MEANS EVERYTHING QUEUED ALREADY PLAYED AND THE OFFSET RESET TO 0,
        // SO THE SUBTRACTION BELOW WOULD WRONGLY REPORT THE WHOLE QUEUE AS PENDING
        if (state == AL10.AL_STOPPED) return 0;

        // QUEUE PLAYBACK OFFSET, LATENCY-COMPENSATED WHEN AL_SOFT_source_latency EXISTS: THE
        // SAMPLE AT THE LISTENER IS offset − deviceLatency, GROWING THE PENDING WINDOW
        final double offsetSec;
        if (this.latencySupported) {
            SOFTSourceLatency.alGetSourcedvSOFT(this.source, SOFTSourceLatency.AL_SEC_OFFSET_LATENCY_SOFT, this.latencyValues);
            offsetSec = this.latencyValues[0] - this.latencyValues[1];
        } else {
            offsetSec = AL10.alGetSourcef(this.source, AL11.AL_SEC_OFFSET);
        }

        final long playedUs = (long) (Math.max(0.0, offsetSec) * 1_000_000.0);
        return Math.max(0L, (this.totalQueuedUs - playedUs) / 1_000L);
    }

    // ONE BATCH, SO EVERY SINK HALTS ON THE SAME MIXER UPDATE; PAUSING A SOURCE THAT IS NOT PLAYING IS A LEGAL NO-OP
    @Override
    synchronized void pause() {
        if (this.source == 0) return;
        this.requireContext();
        AL10.alSourcePausev(this.sourceIds);
    }

    @Override
    synchronized void play() {
        if (this.source == 0) return;
        this.requireContext();
        AL10.alGetError(); // A RESIDUAL HOST ERROR WOULD FAIL THE CHECKED RECLAIM BELOW
        int playing = 0;
        for (final Sink sink: this.sinks) {
            // A STOPPED SOURCE REPLAYS ITS WHOLE QUEUE ON alSourcePlay: RECLAIM WHAT ALREADY PLAYED FIRST
            this.reclaimProcessed(sink);
            if (AL10.alGetSourcei(sink.source, AL10.AL_SOURCE_STATE) == AL10.AL_PLAYING) playing++;
        }
        if (playing == this.sinks.size()) return;
        // SOME SINKS STILL PLAY WHILE OTHERS STOPPED (AN UNDERRUN SEEN MID MIXER UPDATE): REBUILD THEM ON ONE SAMPLE FIRST
        if (playing > 0) this.align();
        AL10.alSourcePlayv(this.sourceIds);
    }

    @Override
    synchronized boolean speed(final float speed) {
        if (this.source == 0 || !Float.isFinite(speed) || speed <= 0) return false;
        this.requireContext();
        AL10.alGetError();
        // AL_PITCH RESAMPLES EACH SOURCE NATIVELY; A REFUSAL RESTORES THE OLD RATE EVERYWHERE SO THE SINKS NEVER DRIFT APART
        for (final Sink sink: this.sinks) AL10.alSourcef(sink.source, AL10.AL_PITCH, speed);
        if (AL10.alGetError() == AL10.AL_NO_ERROR) {
            this.speed = speed;
            return true;
        }
        for (final Sink sink: this.sinks) AL10.alSourcef(sink.source, AL10.AL_PITCH, this.speed);
        return false;
    }

    @Override
    synchronized void gain() {
        if (this.source == 0) return;
        this.requireContext();
        for (final Sink sink: this.sinks) AL10.alSourcef(sink.source, AL10.AL_GAIN, this.volume);
    }

    @Override
    synchronized void destroy() {
        if (this.source == 0) return;
        this.requireContext();
        Throwable failure = null;
        if (this.spatial) {
            for (final Sink sink: this.sinks) {
                try { sink.detach(null); }
                catch (final RuntimeException | Error e) {
                    if (failure == null) failure = e;
                    else failure.addSuppressed(e);
                }
            }
        }
        // DELETING A SOURCE STOPS PLAYBACK AND DETACHES EVERY BUFFER, EVEN AFTER A FAILED UPLOAD OR ENVIRONMENT RESET
        for (final Sink sink: this.sinks) {
            AL10.alDeleteSources(sink.source);
            AL10.alDeleteBuffers(sink.buffers);
            sink.freeCount = 0;
            sink.released = true;
        }
        this.source = 0;
        this.sinks(List.of());
        this.queuedHead = 0;
        this.queuedCount = 0;
        this.totalQueuedUs = 0;
        if (failure instanceof final RuntimeException e) throw e;
        if (failure instanceof final Error e) throw e;
    }

    // ==========================================================================
    // HELPERS
    // ==========================================================================

    private void requireContext() {
        final long localContext = this.threadContext ? EXTThreadLocalContext.alcGetThreadContext() : 0;
        if ((localContext != 0 ? localContext : ALC10.alcGetCurrentContext()) != this.context)
            throw new IllegalStateException("The ALEngine's original OpenAL context must be current on this thread");
    }

    // PUBLISHES A NEW SINK SET WITH ITS BATCH SOURCE IDS
    private void sinks(final List<Sink> sinks) {
        final int[] ids = new int[sinks.size()];
        for (int i = 0; i < ids.length; i++) ids[i] = sinks.get(i).source;
        this.sourceIds = ids;
        this.sinks = List.copyOf(sinks);
    }

    // RESETS A SOURCE TO A DRY, LISTENER-RELATIVE ORIGIN WITH NO DISTANCE ATTENUATION AND NO EFX FILTERS OR SENDS
    private void clearSpatial(final int source) {
        AL10.alSourcei(source, AL10.AL_SOURCE_RELATIVE, AL10.AL_TRUE);
        AL10.alSource3f(source, AL10.AL_POSITION, 0, 0, 0);
        AL10.alSourcef(source, AL10.AL_ROLLOFF_FACTOR, 0);
        if (this.efx) {
            AL10.alSourcei(source, EXTEfx.AL_DIRECT_FILTER, EXTEfx.AL_FILTER_NULL);
            for (int send = 0; send < this.auxiliarySends; send++)
                AL11.alSource3i(source, EXTEfx.AL_AUXILIARY_SEND_FILTER, EXTEfx.AL_EFFECTSLOT_NULL, send, EXTEfx.AL_FILTER_NULL);
            AL10.alSourcef(source, EXTEfx.AL_AIR_ABSORPTION_FACTOR, 0);
        }
    }

    // UNQUEUES EVERY PROCESSED BUFFER OF ONE SINK BACK TO ITS FREE STACK; THE PRIMARY ALSO POPS THE SHARED FIFO FRONT
    // (AL RETURNS PROCESSED BUFFERS IN QUEUE ORDER)
    private void reclaimProcessed(final Sink sink) {
        int processed = AL10.alGetSourcei(sink.source, AL10.AL_BUFFERS_PROCESSED);
        checkError("query processed buffers");
        if (processed > sink.buffers.length - sink.freeCount) throw new IllegalStateException("OpenAL processed-buffer count exceeds the owned queue");
        while (processed-- > 0) {
            // DRIVER MISCOUNT GUARD: A FULL FREE STACK MEANS EVERY OWNED BUFFER IS ALREADY RECLAIMED
            if (sink.freeCount == sink.freeIds.length) break;
            final int buffer = AL10.alSourceUnqueueBuffers(sink.source);
            checkError("unqueue PCM");
            if (sink == this.primary && this.queuedCount > 0) {
                this.totalQueuedUs -= this.queuedDur[this.queuedHead];
                this.queuedHead = (this.queuedHead + 1) % this.bufferCount;
                this.queuedCount--;
            }
            sink.freeIds[sink.freeCount++] = buffer;
        }
    }

    // STOPS EVERY SINK IN ONE BATCH, DETACHES EACH WHOLE QUEUE (AL_BUFFER 0 ON A STOPPED SOURCE) AND REBUILDS EACH FREE STACK TO
    // ITS EXACT BUFFER SET (INCREMENTAL UNQUEUEING OVERFLOWS ON DRIVER MISCOUNTS). THE SOURCES LAND ON INITIAL, NOT STOPPED: A
    // STOPPED SOURCE REPORTS EVERY FUTURE QUEUED BUFFER AS PROCESSED, SO PAUSED WARM-UP UPLOADS AFTER A SEEK WOULD BE LOST
    private void clear() {
        AL10.alSourceStopv(this.sourceIds);
        for (final Sink sink: this.sinks) {
            AL10.alSourcei(sink.source, AL10.AL_BUFFER, 0);
            System.arraycopy(sink.buffers, 0, sink.freeIds, 0, sink.buffers.length);
            sink.freeCount = sink.buffers.length;
        }
        AL10.alSourceRewindv(this.sourceIds);
    }

    // REBUILDS EVERY SINK'S QUEUE FROM THE RETAINED CHUNKS AT THE PRIMARY'S SAMPLE, SO A NEW SINK, A NEW MIX OR SINKS AN
    // UNDERRUN LEFT APART PLAY THE SAME SAMPLE AGAIN. THE PAUSE FREEZES THE POSITION FIRST; A FEW MILLISECONDS MAY REPLAY,
    // BUT EVERY SINK REPLAYS THE SAME ONES. THE SINKS STAY HALTED; TRUE WHEN THE PRIMARY WAS PLAYING, SO THE CALLER RESUMES
    private boolean align() {
        final boolean playing = AL10.alGetSourcei(this.source, AL10.AL_SOURCE_STATE) == AL10.AL_PLAYING;
        AL10.alSourcePausev(this.sourceIds);
        this.reclaimProcessed(this.primary);

        // DROP WHAT THE PRIMARY ALREADY PLAYED: WHOLE CHUNKS, THEN THE HEAD UP TO ITS SAMPLE OFFSET
        long skip = AL10.alGetSourcei(this.source, AL11.AL_SAMPLE_OFFSET);
        checkError("query the sample offset");
        while (skip > 0 && this.queuedCount > 0) {
            final ByteBuffer head = this.queuedPcm[this.queuedHead];
            final long frames = head.remaining() / this.bytesPerFrame;
            if (skip < frames) {
                head.position(head.position() + (int) skip * this.bytesPerFrame);
                final long playedUs = skip * 1_000_000L / this.sampleRate;
                this.queuedDur[this.queuedHead] -= playedUs;
                this.totalQueuedUs -= playedUs;
                break;
            }
            skip -= frames;
            this.totalQueuedUs -= this.queuedDur[this.queuedHead];
            this.queuedHead = (this.queuedHead + 1) % this.bufferCount;
            this.queuedCount--;
        }

        // REQUEUE THE REST ON EVERY SINK; A NATIVE FAILURE DROPS THE QUEUE INSTEAD OF LEAVING THE SINKS APART
        this.clear();
        try {
            for (int i = 0; i < this.queuedCount; i++) {
                final ByteBuffer chunk = this.queuedPcm[(this.queuedHead + i) % this.bufferCount];
                for (final Sink sink: this.sinks) {
                    final int buffer = sink.freeIds[--sink.freeCount];
                    AL10.alBufferData(buffer, this.alFormat, this.render(chunk, sink.mix), this.sampleRate);
                    AL10.alSourceQueueBuffers(sink.source, buffer);
                }
            }
            checkError("rebuild the sink queues");
        } catch (final RuntimeException | Error failure) {
            try { this.flush(); }
            catch (final RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
        return playing;
    }

    // ONE SINK'S BUFFER OF AN INTERLEAVED CHUNK: PLAIN ENGINES AND MONO DECODES PASS AS THEY ARE, STEREO GIVES ONE SIDE OR THE
    // AVERAGE OF BOTH (THE LEVEL FFMPEG USES FOR AN INTEGER MONO DOWNMIX). OPENAL READS NATIVE-ENDIAN PCM, AS FFMPEG WRITES IT
    private ByteBuffer render(final ByteBuffer chunk, final Mix mix) {
        if (!this.spatial || this.channels == 1) return chunk;
        final SampleType type = this.sampleType;
        final int frames = chunk.remaining() / this.bytesPerFrame;
        final int size = frames * type.bytes();
        if (this.mixed == null || this.mixed.capacity() < size) this.mixed = ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder());
        final ByteBuffer in = chunk.duplicate().order(ByteOrder.nativeOrder());
        final ByteBuffer out = this.mixed.clear().limit(size);
        final int base = in.position();
        final boolean mono = mix == Mix.MONO;
        final int side = mix == Mix.RIGHT ? 1 : 0;
        switch (type) {
            case U8 -> {
                for (int i = 0; i < frames; i++) {
                    final int at = base + i * 2;
                    out.put(i, mono ? (byte) (((in.get(at) & 0xFF) + (in.get(at + 1) & 0xFF)) >>> 1) : in.get(at + side));
                }
            }
            case S16 -> {
                for (int i = 0; i < frames; i++) {
                    final int at = base + i * 4;
                    out.putShort(i * 2, mono ? (short) ((in.getShort(at) + in.getShort(at + 2)) >> 1) : in.getShort(at + side * 2));
                }
            }
            case FLT -> {
                for (int i = 0; i < frames; i++) {
                    final int at = base + i * 8;
                    out.putFloat(i * 4, mono ? (in.getFloat(at) + in.getFloat(at + 4)) * 0.5f : in.getFloat(at + side * 4));
                }
            }
            case DBL -> {
                for (int i = 0; i < frames; i++) {
                    final int at = base + i * 16;
                    out.putDouble(i * 8, mono ? (in.getDouble(at) + in.getDouble(at + 8)) * 0.5 : in.getDouble(at + side * 8));
                }
            }
            case S32 -> throw new IllegalStateException("OpenAL has no S32 format to mix");
        }
        return out;
    }

    // ==========================================================================
    // UTILITY
    // ==========================================================================

    private static void checkError(final String operation) {
        final int error = AL10.alGetError();
        if (error != AL10.AL_NO_ERROR)
            throw new IllegalStateException("OpenAL failed to " + operation + " (err=0x" + Integer.toHexString(error) + ")");
    }

    private static ChannelSupport[] channelTable(final boolean floating, final boolean doubles, final boolean multichannel, final boolean spatial) {
        final EnumSet<SampleType> direct = EnumSet.of(SampleType.U8, SampleType.S16);
        if (floating) direct.add(SampleType.FLT);
        if (doubles) direct.add(SampleType.DBL);
        final var channels = new ArrayList<ChannelSupport>();
        channels.add(new ChannelSupport(1, direct));
        // SPATIAL SINKS ARE MONO SOURCES OF A STEREO DECODE, SO THEY TAKE THE MONO TYPES AT BOTH COUNTS AND NEVER MORE CHANNELS:
        // FFMPEG FOLDS SURROUND INTO STEREO
        channels.add(new ChannelSupport(2, direct));
        if (multichannel && !spatial) {
            // MCFORMATS DECLARES ITS OWN FLOAT FORMATS; EXT_FLOAT32 ONLY GATES MONO/STEREO FLOAT
            final EnumSet<SampleType> surround = EnumSet.of(SampleType.U8, SampleType.S16, SampleType.FLT);
            for (final int count: new int[] { 4, 6, 7, 8 }) channels.add(new ChannelSupport(count, surround));
        }
        return channels.toArray(ChannelSupport[]::new);
    }

    // MAPS A TABLE COMBINATION TO ITS NATIVE FORMAT; AL_NONE WHEN NO CAPABILITY TABLE CAN OFFER THE COMBINATION
    private static int alFormatFor(final SampleType type, final int channels) {
        return switch (type) {
            case U8 -> switch (channels) {
                case 1 -> AL10.AL_FORMAT_MONO8;
                case 2 -> AL10.AL_FORMAT_STEREO8;
                case 4 -> EXTMCFormats.AL_FORMAT_QUAD8;
                case 6 -> EXTMCFormats.AL_FORMAT_51CHN8;
                case 7 -> EXTMCFormats.AL_FORMAT_61CHN8;
                case 8 -> EXTMCFormats.AL_FORMAT_71CHN8;
                default -> AL10.AL_NONE;
            };
            case S16 -> switch (channels) {
                case 1 -> AL10.AL_FORMAT_MONO16;
                case 2 -> AL10.AL_FORMAT_STEREO16;
                case 4 -> EXTMCFormats.AL_FORMAT_QUAD16;
                case 6 -> EXTMCFormats.AL_FORMAT_51CHN16;
                case 7 -> EXTMCFormats.AL_FORMAT_61CHN16;
                case 8 -> EXTMCFormats.AL_FORMAT_71CHN16;
                default -> AL10.AL_NONE;
            };
            // OPENAL HAS NO NATIVE S32 INTEGER PCM FORMAT: THE CALLER MUST RESAMPLE TO S16 OR FLT
            case S32 -> AL10.AL_NONE;
            case FLT -> switch (channels) {
                case 1 -> EXTFloat32.AL_FORMAT_MONO_FLOAT32;
                case 2 -> EXTFloat32.AL_FORMAT_STEREO_FLOAT32;
                case 4 -> EXTMCFormats.AL_FORMAT_QUAD32;
                case 6 -> EXTMCFormats.AL_FORMAT_51CHN32;
                case 7 -> EXTMCFormats.AL_FORMAT_61CHN32;
                case 8 -> EXTMCFormats.AL_FORMAT_71CHN32;
                default -> AL10.AL_NONE;
            };
            // DBL COVERS MONO/STEREO ONLY: AL_EXT_DOUBLE HAS NO MULTICHANNEL VARIANTS
            case DBL -> switch (channels) {
                case 1 -> EXTDouble.AL_FORMAT_MONO_DOUBLE_EXT;
                case 2 -> EXTDouble.AL_FORMAT_STEREO_DOUBLE_EXT;
                default -> AL10.AL_NONE;
            };
        };
    }

    // ==========================================================================
    // NESTED TYPES
    // ==========================================================================

    /** Channel mix a {@link Sink} plays; on a mono decode every mix plays the same samples. */
    public enum Mix {
        /** Average of the left and right channels. */
        MONO,
        /** Left channel. */
        LEFT,
        /** Right channel. */
        RIGHT
    }

    /**
     * One positional mono OpenAL source of a spatial engine. The host adopts {@link #source()} into its sound
     * system (gain, pitch and position stay writable) but must never play, queue, unqueue or delete it: the
     * engine owns its transport and its buffers. Every mutating call needs the engine's OpenAL context current
     * and serializes on the engine with the player's uploads; the queries need no context.
     */
    public final class Sink {
        private final int source;
        private final int[] buffers;
        // FREE STACK OF FILLABLE BUFFER IDS (LIFO), GUARDED BY THE ENGINE MONITOR
        private final int[] freeIds;
        private int freeCount;
        private volatile Mix mix;
        private volatile SpatialAudio spatialAudio;
        private volatile boolean released;

        // GENERATES THE SOURCE AND ITS BUFFER POOL AS A DRY, LISTENER-RELATIVE SOURCE AT THE ENGINE'S GAIN AND PITCH; A PLAIN
        // ENGINE'S PRIMARY HAS NO MIX (IT PLAYS EVERY CHANNEL)
        private Sink(final Mix mix) {
            final ALEngine engine = ALEngine.this;
            this.mix = mix;
            this.buffers = new int[engine.bufferCount];
            this.freeIds = new int[engine.bufferCount];
            int source = 0;
            try {
                AL10.alGenBuffers(this.buffers);
                source = AL10.alGenSources();
                final int err = AL10.alGetError();
                if (source == 0 || err != AL10.AL_NO_ERROR)
                    throw new IllegalStateException("OpenAL source/buffer generation failed (err=0x" + Integer.toHexString(err) + ")");
                engine.clearSpatial(source);
                AL10.alSourcef(source, AL10.AL_GAIN, engine.volume);
                AL10.alSourcef(source, AL10.AL_PITCH, engine.speed);
                checkError("set up the source");
            } catch (final RuntimeException | Error failure) {
                // GENERATION AND SETUP CAN BOTH FAIL AFTER THE BUFFERS WERE ALLOCATED
                try {
                    if (source != 0) AL10.alDeleteSources(source);
                    for (final int buffer: this.buffers) if (buffer != 0) AL10.alDeleteBuffers(buffer);
                } catch (final RuntimeException | Error cleanup) {
                    failure.addSuppressed(cleanup);
                }
                throw failure;
            }
            this.source = source;
            System.arraycopy(this.buffers, 0, this.freeIds, 0, this.buffers.length);
            this.freeCount = this.buffers.length;
        }

        /** Returns the OpenAL source id, 0 once released. */
        public int source() { return this.released ? 0 : this.source; }

        /** Returns the channel mix this sink plays. */
        public Mix mix() { return this.mix; }

        /**
         * Switches the channel mix with the engine's OpenAL context current. Queued audio is rebuilt so the
         * change is heard at once, which pauses every sink for about one mixer update.
         * @return false once released
         * @throws IllegalArgumentException when {@code mix} is null
         */
        public boolean mix(final Mix mix) {
            if (mix == null) throw new IllegalArgumentException("A sink needs a mix");
            final ALEngine engine = ALEngine.this;
            synchronized (engine) {
                if (this.released) return false;
                if (mix == this.mix) return true;
                engine.requireContext();
                AL10.alGetError();
                this.mix = mix;
                // QUEUED STEREO AUDIO WAS MIXED FOR THE OLD CHOICE; A MONO DECODE SOUNDS THE SAME ON EVERY MIX
                if (engine.queuedCount > 0 && engine.channels == 2 && engine.align()) AL10.alSourcePlayv(engine.sourceIds);
                return true;
            }
        }

        /** Whether this is the engine's primary sink, the one {@link ALEngine#source()} returns; it lives as long as the engine. */
        public boolean primary() { return this == ALEngine.this.primary; }

        /** Whether this sink was released, alone or with its engine. */
        public boolean released() { return this.released; }

        /** Last applied spatial state, or {@code null} for a dry listener-relative source. */
        public SpatialAudio spatialAudio() { return this.spatialAudio; }

        /** Applies a host sound-thread update (null clears spatial effects) with the engine's OpenAL context current; false when released. */
        public boolean spatialAudio(final SpatialAudio audio) {
            final ALEngine engine = ALEngine.this;
            synchronized (engine) {
                if (!engine.spatial || this.released) return false;
                engine.requireContext();
                this.detach(audio);
                if (audio == null) return true;
                try {
                    AL10.alSourcei(this.source, AL10.AL_SOURCE_RELATIVE, AL10.AL_FALSE);
                    AL10.alSource3f(this.source, AL10.AL_POSITION, (float) audio.x(), (float) audio.y(), (float) audio.z());
                    AL10.alSourcef(this.source, AL10.AL_REFERENCE_DISTANCE, audio.referenceDistance());
                    AL10.alSourcef(this.source, AL10.AL_MAX_DISTANCE, audio.maxDistance());
                    AL10.alSourcef(this.source, AL10.AL_ROLLOFF_FACTOR, audio.rolloff());
                    // SOUND PHYSICS MAY MOVE AL_POSITION TOWARD A REFLECTION; ALWAYS RESTORE THE REAL ORIGIN FIRST
                    if (audio.environment() != null) audio.environment().process(this.source, audio);
                    this.spatialAudio = audio;
                    return true;
                } catch (final RuntimeException | Error failure) {
                    engine.clearSpatial(this.source);
                    throw failure;
                }
            }
        }

        /**
         * Resets this sink's environment, then stops and deletes its source and buffers, with the engine's
         * OpenAL context current. The engine releases every sink with itself.
         * @return false for the primary sink or once released
         */
        public boolean release() {
            final ALEngine engine = ALEngine.this;
            synchronized (engine) {
                if (this.released || this == engine.primary) return false;
                engine.requireContext();
                try {
                    this.detach(null);
                } finally {
                    // DELETING THE SOURCE STOPS IT AND DETACHES ITS BUFFERS, EVEN WHEN THE ENVIRONMENT RESET THREW
                    AL10.alDeleteSources(this.source);
                    AL10.alDeleteBuffers(this.buffers);
                    this.freeCount = 0;
                    this.released = true;
                    final List<Sink> sinks = new ArrayList<>(engine.sinks);
                    sinks.remove(this);
                    engine.sinks(sinks);
                }
                return true;
            }
        }

        // RESETS THE HOST ENVIRONMENT WHEN THE NEXT STATE DROPS IT AND LEAVES A DRY LISTENER-RELATIVE SOURCE, EVEN IF THE RESET
        // OR A DISABLED ADAPTER THROWS
        private void detach(final SpatialAudio next) {
            final SpatialAudio previous = this.spatialAudio;
            this.spatialAudio = null;
            try {
                if (previous != null && previous.environment() != null && (next == null || previous.environment() != next.environment()))
                    previous.environment().reset(this.source);
            } finally {
                ALEngine.this.clearSpatial(this.source);
            }
        }
    }
}
