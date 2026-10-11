package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Profile photos: squared from the centre, redrawn as a JPEG, refused when they are no picture or too large. */
class AvatarsTest {

    @Test
    void aWidePhotoIsCutSquareAroundItsCentre() throws IOException {
        // 600 x 300: red left quarter, green middle half, blue right quarter. The centre square is the green half.
        BufferedImage wide = new BufferedImage(600, 300, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = wide.createGraphics();
        g.setColor(Color.RED);
        g.fillRect(0, 0, 150, 300);
        g.setColor(Color.GREEN);
        g.fillRect(150, 0, 300, 300);
        g.setColor(Color.BLUE);
        g.fillRect(450, 0, 150, 300);
        g.dispose();

        BufferedImage out = read(Avatars.square(encode(wide, "png")));

        assertThat(out.getWidth()).isEqualTo(Avatars.SIZE);
        assertThat(out.getHeight()).isEqualTo(Avatars.SIZE);
        for (int[] p : new int[][] {{4, 4}, {251, 4}, {128, 128}, {4, 251}, {251, 251}}) {
            Color c = new Color(out.getRGB(p[0], p[1]));
            assertThat(c.getGreen()).as("pixel %s,%s", p[0], p[1]).isGreaterThan(200);
            assertThat(c.getRed() + c.getBlue()).as("pixel %s,%s", p[0], p[1]).isLessThan(60);
        }
    }

    @Test
    void theResultIsAJpegWithoutTheTransparency() throws IOException {
        BufferedImage clear = new BufferedImage(40, 40, BufferedImage.TYPE_INT_ARGB); // fully transparent
        byte[] jpeg = Avatars.square(encode(clear, "png"));

        assertThat(Documents.detect(jpeg)).isEqualTo("image/jpeg");
        Color c = new Color(read(jpeg).getRGB(128, 128));
        assertThat(c.getRed()).isGreaterThan(245); // drawn on white
        assertThat(c.getBlue()).isGreaterThan(245);
    }

    @Test
    void aJpegIsReadToo() throws IOException {
        BufferedImage tall = new BufferedImage(300, 900, BufferedImage.TYPE_INT_RGB);
        byte[] jpeg = Avatars.square(encode(tall, "jpg"));

        assertThat(read(jpeg).getWidth()).isEqualTo(Avatars.SIZE);
    }

    @Test
    void anythingButAJpegOrPngIsRefused() {
        assertThatThrownBy(() -> Avatars.square("%PDF-1.7 not a photo".getBytes()))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getMessageKey()).isEqualTo("profile.photo.type"));
        assertThatThrownBy(() -> Avatars.square(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1, 2, 3}))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getMessageKey()).isEqualTo("profile.photo.unreadable"));
    }

    @Test
    void aPictureTooLargeIsRefusedBeforeItIsDecoded() throws IOException {
        byte[] png = encode(new BufferedImage(200, 100, BufferedImage.TYPE_INT_RGB), "png");

        assertThatThrownBy(() -> Avatars.square(png, Avatars.SIZE, 10_000))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getMessageKey()).isEqualTo("profile.photo.tooLarge"));
        assertThat(Avatars.square(png, Avatars.SIZE, 20_000)).isNotEmpty();
    }

    private static byte[] encode(BufferedImage image, String format) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, format, out);
        return out.toByteArray();
    }

    private static BufferedImage read(byte[] bytes) throws IOException {
        return ImageIO.read(new ByteArrayInputStream(bytes));
    }
}
