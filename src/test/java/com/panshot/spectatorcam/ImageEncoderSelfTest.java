package com.panshot.spectatorcam;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Random;

public final class ImageEncoderSelfTest {
    private ImageEncoderSelfTest() {
    }

    public static void main(String[] args) throws Exception {
        BufferedImage source = createNoiseImage(320, 240);
        byte[] lightlyCompressed = ImageEncoder.encodeJpeg(source, 10);
        byte[] heavilyCompressed = ImageEncoder.encodeJpeg(source, 85);

        require(isJpeg(lightlyCompressed), "Lightly compressed output is not a JPEG.");
        require(isJpeg(heavilyCompressed), "Heavily compressed output is not a JPEG.");
        require(
            heavilyCompressed.length < lightlyCompressed.length,
            "Increasing the compression amount did not reduce the JPEG size."
        );

        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(heavilyCompressed));
        require(decoded != null, "The compressed JPEG could not be decoded.");
        require(decoded.getWidth() == source.getWidth(), "JPEG encoding changed the image width.");
        require(decoded.getHeight() == source.getHeight(), "JPEG encoding changed the image height.");

        require(ImageEncoder.jpegQualityForCompressionAmount(0) == 1.0f, "0% compression must map to full JPEG quality.");
        require(ImageEncoder.jpegQualityForCompressionAmount(100) == 0.0f, "100% compression must map to minimum JPEG quality.");

        byte[] png = encodePng(source);
        require("image/jpeg".equals(SinglePreviewWebServer.detectImageContentType(heavilyCompressed)), "JPEG MIME detection failed.");
        require("image/png".equals(SinglePreviewWebServer.detectImageContentType(png)), "PNG MIME detection failed.");

        source.flush();
        decoded.flush();
        System.out.printf(
            "JPEG compression self-test passed (10%%: %d bytes, 85%%: %d bytes).%n",
            lightlyCompressed.length,
            heavilyCompressed.length
        );
    }

    private static BufferedImage createNoiseImage(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        int[] pixels = new int[width * height];
        Random random = new Random(0x5A17C0DEL);
        for (int index = 0; index < pixels.length; index++) {
            pixels[index] = 0xFF000000 | random.nextInt(0x01000000);
        }
        image.setRGB(0, 0, width, height, pixels, 0, width);
        return image;
    }

    private static byte[] encodePng(BufferedImage image) throws Exception {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            require(ImageIO.write(image, "png", output), "No PNG image writer is available.");
            return output.toByteArray();
        }
    }

    private static boolean isJpeg(byte[] bytes) {
        return bytes.length >= 4
            && (bytes[0] & 0xFF) == 0xFF
            && (bytes[1] & 0xFF) == 0xD8
            && (bytes[bytes.length - 2] & 0xFF) == 0xFF
            && (bytes[bytes.length - 1] & 0xFF) == 0xD9;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
