package com.panshot.spectatorcam;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;

final class ImageEncoder {
    private static final int INITIAL_BUFFER_LIMIT = 64 * 1024 * 1024;

    private ImageEncoder() {
    }

    static byte[] encodeJpeg(BufferedImage source, int compressionAmount) throws IOException {
        float quality = jpegQualityForCompressionAmount(compressionAmount);
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            throw new IOException("No JPEG image writer available.");
        }

        BufferedImage rgbImage = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = rgbImage.createGraphics();
        try {
            graphics.drawImage(source, 0, 0, null);
        } finally {
            graphics.dispose();
        }

        ImageWriter writer = writers.next();
        try (ByteArrayOutputStream output = new ByteArrayOutputStream(initialBufferSize(source));
             ImageOutputStream imageOutput = ImageIO.createImageOutputStream(output)) {
            if (imageOutput == null) {
                throw new IOException("Could not create a JPEG image output stream.");
            }

            ImageWriteParam parameters = writer.getDefaultWriteParam();
            if (!parameters.canWriteCompressed()) {
                throw new IOException("The JPEG image writer does not support configurable compression.");
            }
            parameters.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            parameters.setCompressionQuality(quality);

            writer.setOutput(imageOutput);
            writer.write(null, new IIOImage(rgbImage, null, null), parameters);
            imageOutput.flush();
            return output.toByteArray();
        } finally {
            writer.dispose();
            rgbImage.flush();
        }
    }

    static float jpegQualityForCompressionAmount(int compressionAmount) {
        if (compressionAmount < 0 || compressionAmount > 100) {
            throw new IllegalArgumentException("JPEG compression amount must be between 0 and 100.");
        }
        return 1.0f - compressionAmount / 100.0f;
    }

    private static int initialBufferSize(BufferedImage image) {
        long estimate = Math.max(1024L, ((long)image.getWidth() * image.getHeight()) / 2L);
        return (int)Math.min(estimate, INITIAL_BUFFER_LIMIT);
    }
}
