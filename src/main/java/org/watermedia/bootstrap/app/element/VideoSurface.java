package org.watermedia.bootstrap.app.element;

import org.watermedia.api.player.MediaPlayer;

import java.awt.Color;

/**
 * Draws the context player's current frame aspect-fitted and centered on black, re-reading the player on every draw.
 * With both {@link MediaPlayer#scale} caps set, the frame is center-cropped to the cap aspect.
 */
public final class VideoSurface extends Element<VideoSurface> {

    public VideoSurface() {
        this.width = MAX_PARENT;
        this.height = MAX_PARENT;
    }

    @Override
    protected void onDraw(final Canvas canvas) {
        final int boxW = this.measuredWidth;
        final int boxH = this.measuredHeight;
        if (boxW <= 0 || boxH <= 0) return;

        // BLACK BACKDROP, ALSO THE PLACEHOLDER WHEN NO FRAME IS READY
        canvas.fill(this.left, this.top, boxW, boxH, Color.BLACK);

        final MediaPlayer player = this.ctx != null ? this.ctx.media.player : null;
        // 0 = NO FRAME READY; THE HANDLE IS A GL TEXTURE NAME OR A 64-BIT VULKAN VkImageView, SO THE TEST IS == 0, NOT <= 0
        if (player == null || player.ended() || player.stopped() || player.error() || player.texture() == 0) return;

        final int videoW = player.width();
        final int videoH = player.height();

        float renderW = boxW, renderH = boxH, offsetX = 0, offsetY = 0;
        float u0 = 0f, v0 = 0f, u1 = 1f, v1 = 1f;

        if (videoW > 0 && videoH > 0) {
            final float srcAspect = (float) videoW / videoH;

            // WITH BOTH CAPS SET THE FRAME SHOWS AT THE CAP ASPECT: THE SAMPLED UV REGION IS CENTER-CROPPED TO IT
            float dispAspect = srcAspect;
            final int capW = player.scaleWidth();
            final int capH = player.scaleHeight();
            if (capW > 0 && capH > 0) {
                dispAspect = (float) capW / capH;
                if (dispAspect > srcAspect) {
                    final float frac = srcAspect / dispAspect;
                    v0 = (1f - frac) / 2f;
                    v1 = 1f - v0;
                } else if (dispAspect < srcAspect) {
                    final float frac = dispAspect / srcAspect;
                    u0 = (1f - frac) / 2f;
                    u1 = 1f - u0;
                }
            }

            final float boxAspect = (float) boxW / boxH;
            if (dispAspect > boxAspect) {
                renderW = boxW;
                renderH = boxW / dispAspect;
                offsetY = (boxH - renderH) / 2f;
            } else {
                renderH = boxH;
                renderW = boxH * dispAspect;
                offsetX = (boxW - renderW) / 2f;
            }
        }

        canvas.mediaImage(player.texture(), this.left + offsetX, this.top + offsetY, renderW, renderH, u0, v0, u1, v1);
    }
}
