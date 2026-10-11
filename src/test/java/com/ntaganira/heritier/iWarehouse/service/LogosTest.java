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

/** The company's logo: a PNG of its own shape, transparency kept, never wider than MAX_WIDTH; anything else refused. */
class LogosTest {

    @Test
    void aWideLogoShrinksToTheMaximumWidthKeepingItsShape() throws IOException {
        byte[] png = Logos.png(encode(new BufferedImage(3000, 1000, BufferedImage.TYPE_INT_RGB), "jpg"));

        BufferedImage out = read(png);
        assertThat(Documents.detect(png)).isEqualTo("image/png");
        assertThat(out.getWidth()).isEqualTo(Logos.MAX_WIDTH);
        assertThat(out.getHeight()).isEqualTo(400);
    }

    @Test
    void aSmallLogoKeepsItsSizeAndItsTransparency() throws IOException {
        BufferedImage logo = new BufferedImage(187, 139, BufferedImage.TYPE_INT_ARGB); // transparent
        Graphics2D g = logo.createGraphics();
        g.setColor(new Color(11, 61, 97));
        g.fillRect(40, 20, 100, 60);
        g.dispose();

        BufferedImage out = read(Logos.png(encode(logo, "png")));

        assertThat(out.getWidth()).isEqualTo(187);
        assertThat(out.getHeight()).isEqualTo(139);
        assertThat(new Color(out.getRGB(5, 5), true).getAlpha()).isZero();                // the background stays clear
        Color mark = new Color(out.getRGB(90, 50), true);
        assertThat(mark.getAlpha()).isEqualTo(255);
        assertThat(mark.getBlue()).isBetween(90, 104);                                    // the navy kept
    }

    @Test
    void onlyPngAndJpegAreKept() {
        assertThatThrownBy(() -> Logos.png("<svg xmlns='http://www.w3.org/2000/svg'/>".getBytes()))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getMessageKey()).isEqualTo("settings.logo.type"));
        assertThatThrownBy(() -> Logos.png(new byte[] {(byte) 0x89, 'P', 'N', 'G', 0, 0, 0, 0}))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getMessageKey()).isEqualTo("settings.logo.unreadable"));
    }

    @Test
    void aPictureTooLargeIsRefusedBeforeItIsDecoded() throws IOException {
        byte[] png = encode(new BufferedImage(300, 200, BufferedImage.TYPE_INT_RGB), "png");

        assertThatThrownBy(() -> Logos.png(png, Logos.MAX_WIDTH, 50_000))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getMessageKey()).isEqualTo("settings.logo.tooLarge"));
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
