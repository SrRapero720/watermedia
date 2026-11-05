/**
 * Experimental public API package
 * <p>Try to avoid using any class contained here: most of the implementation is still experimental or internal, so
 * stay away until it gets a proper API behavior. Its API is {@link CodecsAPI} plus the decoder ({@link ImageReader})
 * and encoder ({@link Encoder}) it hands out; the readers, encoders and chunk structures behind them are internal.</p>
 *
 * <p>The breaking change schema doesn't apply to these classes: any update can break them without following the
 * version schema.</p>
 */
package org.watermedia.api.codecs;