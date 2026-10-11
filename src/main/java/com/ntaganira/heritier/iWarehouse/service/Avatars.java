package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.exception.BusinessException;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : Avatars.java
 * - Date      : 2026. 10. 11.
 * - User      : Hntaganira
 * - Desc      : Profile photos: a JPEG or PNG becomes a square JPEG of SIZE pixels cut from its centre. The picture is
 *               drawn again, so nothing of the file as sent is kept (no EXIF, no position, no hidden content); its size
 *               is checked before it is decoded. Pure, no Spring.
 * </pre>
 */
public final class Avatars {

    /** The side of a stored photo, in pixels: twice the largest place it is shown (the profile page, 96 px). */
    public static final int SIZE = 256;
    /** Larger pictures are refused before they are decoded: 25 megapixels is a 6000 x 4000 camera photo, 100 MB decoded. */
    static final long MAX_PIXELS = 25_000_000L;
    private static final float QUALITY = 0.86f;

    private Avatars() {
    }

    /** The photo as a square JPEG of SIZE pixels. */
    public static byte[] square(byte[] image) {
        return square(image, SIZE, MAX_PIXELS);
    }

    static byte[] square(byte[] image, int size, long maxPixels) {
        String type = Documents.detect(image);
        if (!"image/jpeg".equals(type) && !"image/png".equals(type)) {
            throw BusinessException.of("profile.photo.type");
        }
        BufferedImage source = read(image, maxPixels, "profile.photo");
        int side = Math.min(source.getWidth(), source.getHeight());
        BufferedImage current = source.getSubimage((source.getWidth() - side) / 2, (source.getHeight() - side) / 2, side, side);
        // Halve while it stays at least the target size, then the last step: one bicubic step from a camera photo would alias
        int width = side;
        while (width / 2 >= size) {
            width /= 2;
            current = scaled(current, width);
        }
        if (width != size || current.getType() != BufferedImage.TYPE_INT_RGB) {
            current = scaled(current, size);
        }
        return jpeg(current);
    }

    /** Decodes a JPEG or PNG, refused before decoding when larger than maxPixels; refusals are {prefix}.unreadable / .tooLarge. */
    static BufferedImage read(byte[] image, long maxPixels, String prefix) {
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(image))) {
            Iterator<ImageReader> readers = in == null ? null : ImageIO.getImageReaders(in);
            if (readers == null || !readers.hasNext()) {
                throw BusinessException.of(prefix + ".unreadable");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, true);
                long pixels = (long) reader.getWidth(0) * reader.getHeight(0);
                if (pixels > maxPixels) {
                    throw BusinessException.of(prefix + ".tooLarge", maxPixels / 1_000_000);
                }
                BufferedImage picture = reader.read(0);
                if (picture.getWidth() < 1 || picture.getHeight() < 1) {
                    throw BusinessException.of(prefix + ".unreadable");
                }
                return picture;
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException e) {
            if (e instanceof BusinessException b) {
                throw b;
            }
            throw BusinessException.of(prefix + ".unreadable");
        }
    }

    /** The picture drawn on a white square (a transparent PNG gets a white background). */
    private static BufferedImage scaled(BufferedImage picture, int side) {
        BufferedImage out = new BufferedImage(side, side, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, side, side);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(picture, 0, 0, side, side, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    private static byte[] jpeg(BufferedImage picture) {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ImageOutputStream out = ImageIO.createImageOutputStream(bytes)) {
            writer.setOutput(out);
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(QUALITY);
            writer.write(null, new IIOImage(picture, null, null), param);
        } catch (IOException e) {
            throw new IllegalStateException("Could not encode the photo", e);
        } finally {
            writer.dispose();
        }
        return bytes.toByteArray();
    }
}
