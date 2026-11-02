package org.watermedia.test.support;

/** Channel-wise difference report between two equally-sized byte arrays. */
public record PixelDiff(int maxDiff, double meanDiff, int valuesAboveSmall, int totalValues) {

    /** Compares two arrays, counting the values that drift more than {@code smallDiffThreshold} (lossy codecs). */
    public static PixelDiff compare(final byte[] reference, final byte[] actual, final int smallDiffThreshold) {
        if (reference.length != actual.length)
            throw new IllegalArgumentException("Length mismatch: ref=" + reference.length + " actual=" + actual.length);
        int maxDiff = 0;
        long total = 0L;
        int aboveSmall = 0;
        for (int i = 0; i < reference.length; i++) {
            final int diff = Math.abs((reference[i] & 0xFF) - (actual[i] & 0xFF));
            if (diff > maxDiff) maxDiff = diff;
            total += diff;
            if (diff > smallDiffThreshold) aboveSmall++;
        }
        final double mean = reference.length == 0 ? 0.0 : (double) total / reference.length;
        return new PixelDiff(maxDiff, mean, aboveSmall, reference.length);
    }

    /** Returns the percentage of values whose difference exceeds the small-diff threshold. */
    public double percentAboveSmall() {
        return this.totalValues == 0 ? 0.0 : (this.valuesAboveSmall * 100.0) / this.totalValues;
    }
}
