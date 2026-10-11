package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.exception.BusinessException;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : Logos.java
 * - Date      : 2026. 10. 11.
 * - User      : Hntaganira
 * - Desc      : The company's logo: a JPEG or PNG becomes a PNG, its shape and transparency kept, at most MAX_WIDTH wide
 *               (enough for an A4 letterhead at 300 dpi). It is drawn again, so nothing of the file as sent is kept. Pure.
 * </pre>
 */
public final class Logos {

    /** The widest logo kept, in pixels: 15 mm high on A4 at 300 dpi is about 180 px, a wide logo up to 1200. */
    static final int MAX_WIDTH = 1200;
    static final long MAX_PIXELS = 25_000_000L;

    private Logos() {
    }

    public static byte[] png(byte[] image) {
        return png(image, MAX_WIDTH, MAX_PIXELS);
    }

    static byte[] png(byte[] image, int maxWidth, long maxPixels) {
        String type = Documents.detect(image);
        if (!"image/jpeg".equals(type) && !"image/png".equals(type)) {
            throw BusinessException.of("settings.logo.type");
        }
        BufferedImage source = Avatars.read(image, maxPixels, "settings.logo");
        int width = Math.min(source.getWidth(), maxWidth);
        int height = Math.max(1, (int) Math.round(source.getHeight() * (width / (double) source.getWidth())));
        // Halve while still at least twice the target, then the last step, as for profile photos
        BufferedImage current = source;
        int w = source.getWidth(), h = source.getHeight();
        while (w / 2 >= width) {
            w /= 2;
            h = Math.max(1, h / 2);
            current = drawn(current, w, h);
        }
        if (w != width || current.getType() != BufferedImage.TYPE_INT_ARGB) {
            current = drawn(current, width, height);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            ImageIO.write(current, "png", out);
        } catch (IOException e) {
            throw new IllegalStateException("Could not encode the logo", e);
        }
        return out.toByteArray();
    }

    /** The picture drawn on a transparent canvas of that size (its transparency kept). */
    private static BufferedImage drawn(BufferedImage picture, int width, int height) {
        BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(picture, 0, 0, width, height, null);
        } finally {
            g.dispose();
        }
        return out;
    }
}
