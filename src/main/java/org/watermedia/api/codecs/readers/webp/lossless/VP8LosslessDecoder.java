package org.watermedia.api.codecs.readers.webp.lossless;

import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.watermedia.api.codecs.CodecException;
import org.watermedia.api.codecs.common.Limits;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.watermedia.WaterMedia.LOGGER;

/** VP8L (lossless WebP) bitstream decoder: transforms, color cache, meta prefix codes and LZ77 backward references. */
public final class VP8LosslessDecoder {
    static final Marker IT = MarkerManager.getMarker(VP8LosslessDecoder.class.getSimpleName());
    // HARD HUFFMAN GROUP CAP: THE 16-BIT META CODE ALLOWS 65536 GROUPS OF 5 TABLES EACH, WHILE NO REAL ENCODER
    // GOES NEAR THIS (libwebp CAPS ITS ENTROPY IMAGE AT ~2600 BLOCKS, SO AT MOST THAT MANY GROUPS)
    private static final int MAX_GROUPS = 4_096;
    // MINIMUM BITS ONE GROUP CAN OCCUPY: 5 SIMPLE-CODE TABLES x 4 BITS (isSimple + numSymbols +
    // is8Bits + 1-BIT SYMBOL) EACH
    private static final int MIN_GROUP_BITS = 20;
    // CEILING FOR WHAT NO AMOUNT OF INPUT BOUNDS (SEE THE FEASIBILITY GATE): A CONSTANT FILL DECODES FROM ZERO
    // BITS, SO ONLY ITS DECLARED SIZE LIMITS IT. 16 MPX COVERS EVERY REALISTIC SOLID IMAGE OR ALPHA PLANE
    // WHILE KEEPING THE FILE-TO-HEAP RATIO OF A 30-BYTE FILE BOUNDED
    private static final int MAX_FILL_PIXELS = 1 << 24;
    // LONGEST COPY ONE LZ77 TOKEN CAN EMIT (RFC 9649 LENGTH PREFIX 23 -> 3072 + 1023 + 1)
    private static final int MAX_COPY_LENGTH = 4_096;

    private VP8LosslessDecoder() {}

    // ==========================================================================
    // PUBLIC API
    // ==========================================================================
    /** Decodes VP8L image data that starts past the 5-byte header and inverse transforms it into {@code width * height} packed ARGB pixels. */
    public static int[] decode(final ByteBuffer data, final int width, final int height) throws CodecException {
        LOGGER.trace(IT, "Starting decode: {}x{}", width, height);
        final BitReader reader = new BitReader(data);

        // PARSE TRANSFORMS
        final List<Transform> transforms = new ArrayList<>();
        int currentWidth = width;
        int seenTransforms = 0;

        while (reader.readBool()) {
            final Transform.Type type = Transform.Type.VALUES[reader.read(2)];
            // RFC 9649: EACH TRANSFORM TYPE MAY APPEAR AT MOST ONCE; UNCHECKED REPEATS WOULD LET
            // A TINY FILE QUEUE THOUSANDS OF FULL-IMAGE INVERSE PASSES (CPU DOS)
            final int typeBit = 1 << type.ordinal();
            if ((seenTransforms & typeBit) != 0) throw new CodecException("Duplicate VP8L transform: " + type);
            seenTransforms |= typeBit;
            LOGGER.trace(IT, "Reading transform type: {}", type);
            final Transform transform = readTransform(reader, type, currentWidth, height);
            LOGGER.trace(IT, "Transform read complete, remaining bytes: {}", reader.remaining());
            transforms.add(transform);

            // COLOR INDEXING PACKS 1 << bits INDEXES PER PIXEL: EVERYTHING READ AFTER IT, ENTROPY
            // IMAGE AND MAIN IMAGE INCLUDED, LIVES AT THE PACKED WIDTH
            if (type == Transform.Type.COLOR_INDEXING)
                currentWidth = (currentWidth + (1 << transform.bits()) - 1) >> transform.bits();
        }

        LOGGER.trace(IT, "No more transforms, position: {}", reader.snapshot());

        // READ COLOR CACHE INFO
        ColorCache colorCache = null;
        if (reader.readBool()) {
            final int colorCacheBits = reader.read(4);
            LOGGER.trace(IT, "Main color cache bits: {}", colorCacheBits);
            colorCache = new ColorCache(colorCacheBits);
        } else {
            LOGGER.trace(IT, "No main color cache");
        }
        final int colorCacheSize = (colorCache != null) ? colorCache.size() : 0;

        // READ META PREFIX CODES (ENTROPY IMAGE)
        final HuffmanGroup[] huffmanGroups;
        int[] metaPrefixImage = null;
        int metaBits = 0;
        int metaWidth = 0;

        if (reader.readBool()) {
            // MULTIPLE HUFFMAN GROUPS
            metaBits = reader.read(3) + 2;
            metaWidth = (currentWidth + (1 << metaBits) - 1) >> metaBits;
            final int metaHeight = (height + (1 << metaBits) - 1) >> metaBits;
            LOGGER.trace(IT, "Meta prefix: bits={}, size={}x{}, remaining bytes: {}", metaBits, metaWidth, metaHeight, reader.remaining());

            // DECODE ENTROPY IMAGE
            metaPrefixImage = decodeImage(reader, metaWidth, metaHeight);

            // FIND MAX META PREFIX CODE
            int maxCode = 0;
            for (final int code: metaPrefixImage) {
                final int meta = ((code >> 8) & 0xFF) | ((code >> 16) & 0xFF) << 8;
                maxCode = Math.max(maxCode, meta);
            }

            final int numGroups = maxCode + 1;
            // GROUP-COUNT GATE BEFORE ANY TABLE IS ALLOCATED: NO MORE GROUPS THAN ENTROPY PIXELS, THAN ONE PER
            // MIN_GROUP_BITS OF THE REMAINING STREAM, OR THAN MAX_GROUPS. UNGATED, A 1x1 IMAGE BUYS 65536 GROUPS
            // OF LIVE TABLES WITH A FEW KB
            final long maxGroups = Math.min(Math.min(MAX_GROUPS, metaPrefixImage.length),
                    (reader.remaining() * 8L + reader.bitsAvail()) / MIN_GROUP_BITS);
            if (numGroups > maxGroups)
                throw new CodecException("VP8L declares " + numGroups + " huffman groups, max " + maxGroups);
            LOGGER.trace(IT, "VP8L huffman groups: declared={}, meta={}x{} (bits={}), colorCacheSize={}, tables={}",
                    numGroups, metaWidth, metaHeight, metaBits, colorCacheSize, numGroups * 5);
            huffmanGroups = new HuffmanGroup[numGroups];
            for (int i = 0; i < numGroups; i++) huffmanGroups[i] = HuffmanDecoder.readGroup(reader, colorCacheSize);
        } else {
            // SINGLE HUFFMAN GROUP
            huffmanGroups = new HuffmanGroup[]{HuffmanDecoder.readGroup(reader, colorCacheSize)};
        }

        // DECODE MAIN IMAGE
        int[] pixels = decodeImageData(reader, currentWidth, height, huffmanGroups,
                metaPrefixImage, metaBits, metaWidth, colorCache);

        // APPLY INVERSE TRANSFORMS IN REVERSE ORDER, EACH AT THE WIDTH IT WAS READ WITH: THOSE READ
        // AFTER COLOR INDEXING WORK ON THE PACKED ROWS, THE PALETTE THEN UNPACKS TO THE FULL WIDTH
        for (int i = transforms.size() - 1; i >= 0; i--) {
            final Transform t = transforms.get(i);
            switch (t.type()) {
                case COLOR_INDEXING -> pixels = ColorTransform.applyPalette(pixels, t.width(), height, t.data(), t.bits());
                case SUBTRACT_GREEN -> ColorTransform.addGreen(pixels);
                case COLOR -> ColorTransform.inverse(pixels, t.width(), height, t.data(), t.bits());
                case PREDICTOR -> Predictor.inverse(pixels, t.width(), height, t.data(), t.bits());
            }
        }

        return pixels;
    }

    // ==========================================================================
    // UTILITY
    // ==========================================================================
    private static Transform readTransform(final BitReader reader, final Transform.Type type, final int width, final int height) throws CodecException {
        return switch (type) {
            case PREDICTOR, COLOR -> {
                final int bits = reader.read(3) + 2;
                final int blockWidth = (width + (1 << bits) - 1) >> bits;
                final int blockHeight = (height + (1 << bits) - 1) >> bits;
                yield new Transform(type, width, bits, decodeImage(reader, blockWidth, blockHeight));
            }
            case SUBTRACT_GREEN -> new Transform(type, width, 0, null);
            case COLOR_INDEXING -> {
                final int tableSize = reader.read(8) + 1;
                final int[] colorTable = ColorTransform.decodeColorTable(decodeImage(reader, tableSize, 1));
                yield new Transform(type, width, ColorTransform.widthBits(tableSize), colorTable);
            }
        };
    }

    // DECODE SUB-IMAGE (FOR TRANSFORMS AND ENTROPY IMAGE)
    private static int[] decodeImage(final BitReader reader, final int width, final int height) throws CodecException {
        // READ OPTIONAL COLOR CACHE FOR THIS SUB-IMAGE; THE ColorCache CTOR REJECTS OUT-OF-RANGE
        // BITS (A BITSTREAM ERROR PER SPEC) INSTEAD OF SILENTLY DROPPING THE DECLARED CACHE
        int colorCacheSize = 0;
        ColorCache subCache = null;
        if (reader.readBool()) {
            subCache = new ColorCache(reader.read(4));
            colorCacheSize = subCache.size();
        }

        final HuffmanGroup group = HuffmanDecoder.readGroup(reader, colorCacheSize);
        return decodeImageData(reader, width, height, new HuffmanGroup[]{group}, null, 0, 0, subCache);
    }

    private static int[] decodeImageData(final BitReader reader, final int width, final int height,
                                         final HuffmanGroup[] groups, final int[] metaImage,
                                         final int metaBits, final int metaWidth,
                                         final ColorCache colorCache) throws CodecException {
        // THE SHARED PIXEL CAP COVERS EVERY SUB-IMAGE TOO: THEIR SIZES DERIVE FROM THE MAIN IMAGE'S
        final long declared = (long) width * height;
        if (width <= 0 || height <= 0 || declared > Limits.MAX_PIXELS)
            throw new CodecException("Invalid VP8L image size: " + width + "x" + height + " (max " + Limits.MAX_PIXELS + " pixels)");

        // PIXEL FEASIBILITY GATE BEFORE THE ALLOCATION: A ONE-SYMBOL CODE COSTS ZERO BITS, SO A GROUP WHOSE WHOLE
        // TOKEN PATH IS ONE-SYMBOL IS A CONSTANT FILL BOUNDED ONLY BY MAX_FILL_PIXELS. ANY OTHER GROUP SPENDS A BIT
        // PER TOKEN OF AT MOST MAX_COPY_LENGTH PIXELS, WHICH TIES THE PIXEL COUNT TO THE BITS THE STREAM HOLDS
        boolean fill = false;
        for (final HuffmanGroup group: groups) {
            final int green = group.green().symbol();
            final int dist = group.dist().symbol();
            if (green >= 256 + 24 // COLOR CACHE CODE: RESOLVES WITHOUT READING ANY OTHER TABLE
                    || (green >= 0 && green < 256 && group.red().symbol() >= 0 && group.blue().symbol() >= 0 && group.alpha().symbol() >= 0)
                    || (green >= 256 && green < 260 && dist >= 0 && dist < 4)) { // LZ77 WITHOUT LENGTH/DISTANCE EXTRA BITS
                fill = true;
                break;
            }
        }
        final long feasible = fill ? MAX_FILL_PIXELS : (reader.remaining() * 8L + reader.bitsAvail() + 1L) * MAX_COPY_LENGTH;
        if (declared > feasible)
            throw new CodecException("VP8L declares " + declared + " pixels, bitstream can encode at most " + feasible);

        final int total = (int) declared;
        final int[] pixels = new int[total];
        int pos = 0;
        // INCREMENTAL PIXEL COORDS FOR THE META-GROUP LOOKUP, AVOIDING PER-PIXEL DIV/MOD
        int x = 0, y = 0;

        while (pos < total) {
            // GET HUFFMAN GROUP FOR CURRENT POSITION
            final HuffmanGroup group;
            if (metaImage != null) {
                final int metaCode = metaImage[(y >> metaBits) * metaWidth + (x >> metaBits)];
                final int groupIndex = ((metaCode >> 8) & 0xFF) | (((metaCode >> 16) & 0xFF) << 8);
                group = groups[Math.min(groupIndex, groups.length - 1)];
            } else {
                group = groups[0];
            }

            // READ GREEN/LENGTH SYMBOL
            final int code = group.green().read(reader);

            if (code < 256) {
                // LITERAL ARGB
                final int red = group.red().read(reader);
                final int blue = group.blue().read(reader);
                final int alpha = group.alpha().read(reader);

                final int argb = (alpha << 24) | (red << 16) | (code << 8) | blue;
                pixels[pos++] = argb;
                if (colorCache != null) colorCache.insert(argb);
                if (++x == width) { x = 0; y++; }
            } else if (code < 256 + 24) {
                // LZ77 BACKWARD REFERENCE
                final int length = LZ77.prefixToValue(code - 256, reader);
                final int distCode = group.dist().read(reader);
                final int distValue = LZ77.prefixToValue(distCode, reader);
                final int dist = LZ77.distanceToOffset(distValue, width);

                // RFC 9649: BACKWARD REFERENCES MUST STAY INSIDE THE ALREADY-DECODED PIXELS;
                // OUT-OF-RANGE DISTANCE OR OVERLONG LENGTH IS A BITSTREAM ERROR, NOT BEST-EFFORT
                if (dist > pos) throw new CodecException("VP8L backward reference before image start");
                if (pos + length > total) throw new CodecException("VP8L backward reference overruns image");

                for (int i = 0; i < length; i++) {
                    final int argb = pixels[pos - dist];
                    pixels[pos++] = argb;
                    if (colorCache != null) colorCache.insert(argb);
                }
                // ADVANCE COORDS BY THE COPIED RUN (ONE DIV PER TOKEN, NOT PER PIXEL)
                x += length;
                if (x >= width) { y += x / width; x %= width; }
            } else {
                // COLOR CACHE
                final int cacheIndex = code - 256 - 24;
                if (colorCache == null) throw new CodecException("Color cache code without color cache");
                final int argb = colorCache.lookup(cacheIndex);
                pixels[pos++] = argb;
                colorCache.insert(argb);
                if (++x == width) { x = 0; y++; }
            }
        }

        return pixels;
    }

    // ==========================================================================
    // NESTED TYPES
    // ==========================================================================
    // THE FIVE PREFIX CODES OF ONE VP8L META GROUP
    record HuffmanGroup(
            HuffmanTable green,    // GREEN + LENGTH + COLOR CACHE
            HuffmanTable red,
            HuffmanTable blue,
            HuffmanTable alpha,
            HuffmanTable dist      // DISTANCE
    ) {}

    // width IS THE IMAGE WIDTH WHEN THE TRANSFORM WAS READ (libwebp xsize_): COLOR INDEXING PACKS SEVERAL
    // INDEXES PER PIXEL, SO EVERY TRANSFORM READ AFTER IT, AND ITS OWN INVERSE INPUT, USE THE PACKED WIDTH
    private record Transform(
            Type type,
            int width,         // WIDTH THE INVERSE OPERATES ON
            int bits,          // BLOCK SIZE BITS FOR PREDICTOR/COLOR, PIXEL PACKING BITS FOR COLOR_INDEXING
            int[] data         // PREDICTOR MODES, COLOR TRANSFORM ELEMENTS, OR COLOR TABLE
    ) {
        // ORDINALS ARE THE TWO-BIT WEBP TRANSFORM CODES: NEVER REORDER
        enum Type {
            PREDICTOR,       // 0
            COLOR,           // 1
            SUBTRACT_GREEN,  // 2
            COLOR_INDEXING;  // 3

            private static final Type[] VALUES = values();
        }
    }
}
