package com.panshot.spectatorcam;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;

public final class VideoCompressionFilterSelfTest {
    private VideoCompressionFilterSelfTest() {
    }

    public static void main(String[] args) throws Exception {
        int width = 320;
        int height = 240;
        BufferedImage source = createTestImage(width, height, 0);
        BufferedImage lightFrame = copy(source);
        BufferedImage heavyFrame = copy(source);
        BufferedImage losslessFrame = copy(source);
        long started = System.nanoTime();

        new VideoCompressionFilter().apply(lightFrame, 10);
        new VideoCompressionFilter().apply(heavyFrame, 90);
        new VideoCompressionFilter().apply(losslessFrame, 0);
        double lightError = meanSquaredError(source, lightFrame);
        double heavyError = meanSquaredError(source, heavyFrame);

        require(heavyError > lightError, "Increasing video compression did not increase image loss.");
        require(meanSquaredError(source, losslessFrame) == 0.0, "Zero video compression must preserve the frame.");
        require(heavyFrame.getWidth() == width, "Video compression changed the frame width.");
        require(heavyFrame.getHeight() == height, "Video compression changed the frame height.");

        requireColorPreserved(0xFF5A8C28, 70);
        requireColorPreserved(0xFF7DAAF5, 70);

        BufferedImage ytSource = createTestImage(width, height, 0);
        BufferedImage ytLightFrame = copy(ytSource);
        BufferedImage ytHeavyFrame = copy(ytSource);
        BufferedImage ytLosslessFrame = copy(ytSource);
        new VideoCompressionFilter().applyOldYt(ytLightFrame, 10);
        new VideoCompressionFilter().applyOldYt(ytHeavyFrame, 90);
        new VideoCompressionFilter().applyOldYt(ytLosslessFrame, 0);
        require(
            meanSquaredError(ytSource, ytHeavyFrame) > meanSquaredError(ytSource, ytLightFrame),
            "Increasing old YT compression did not increase image loss."
        );
        require(
            meanSquaredError(ytSource, ytLosslessFrame) == 0.0,
            "Zero old YT compression must preserve the frame before transport encoding."
        );
        byte[] ytTransport = ImageEncoder.encodeJpeg(
            ytHeavyFrame,
            VideoCompressionFilter.oldYtJpegCompressionAmount(90)
        );
        BufferedImage decodedYtFrame = ImageIO.read(new ByteArrayInputStream(ytTransport));
        require(decodedYtFrame != null, "Old YT compression produced an unreadable JPEG.");
        require(
            decodedYtFrame.getWidth() == width && decodedYtFrame.getHeight() == height,
            "Old YT emulation changed the served image dimensions."
        );

        VideoCompressionFilter temporalFilter = new VideoCompressionFilter();
        BufferedImage firstFrame = createTestImage(width, height, 0);
        BufferedImage temporalFrame = createTestImage(width, height, 20);
        BufferedImage independentFrame = createTestImage(width, height, 20);
        temporalFilter.apply(firstFrame, 85);
        temporalFilter.apply(temporalFrame, 85);
        new VideoCompressionFilter().apply(independentFrame, 85);
        require(
            meanSquaredError(temporalFrame, independentFrame) > 0.0,
            "Video compression did not retain temporal state between frames."
        );

        BufferedImage previousSolidFrame = solidImage(width, height, 96);
        BufferedImage currentSolidFrame = solidImage(width, height, 116);
        VideoCompressionFilter patternFilter = new VideoCompressionFilter();
        patternFilter.apply(previousSolidFrame, 85);
        patternFilter.apply(currentSolidFrame, 85);
        require(
            !hasFullyRetainedMacroblock(previousSolidFrame, currentSolidFrame),
            "Temporal compression left a fully stale macroblock in the frame."
        );

        BufferedImage defaultSizeFrame = createTestImage(1024, 1024, 0);
        long defaultSizeStarted = System.nanoTime();
        new VideoCompressionFilter().apply(defaultSizeFrame, 70);
        long filterMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - defaultSizeStarted);
        byte[] transportImage = ImageEncoder.encodeJpeg(defaultSizeFrame, 10);
        long defaultSizeMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - defaultSizeStarted);
        require(transportImage.length > 0, "Video compression produced an empty transport image.");

        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        System.out.printf(
            "Video compression self-test passed (light MSE %.2f, heavy MSE %.2f, temporal state verified; 1024 filter %d ms, encoded frame %d ms, total %d ms).%n",
            lightError,
            heavyError,
            filterMillis,
            defaultSizeMillis,
            elapsedMillis
        );

        source.flush();
        lightFrame.flush();
        heavyFrame.flush();
        losslessFrame.flush();
        firstFrame.flush();
        temporalFrame.flush();
        independentFrame.flush();
        ytSource.flush();
        ytLightFrame.flush();
        ytHeavyFrame.flush();
        ytLosslessFrame.flush();
        decodedYtFrame.flush();
        previousSolidFrame.flush();
        currentSolidFrame.flush();
        defaultSizeFrame.flush();
    }

    private static BufferedImage createTestImage(int width, int height, int offset) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        int[] pixels = new int[width * height];
        Random random = new Random(0x51A7C0DEL);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int red = (x * 3 + offset) & 0xFF;
                int green = (y * 5 + offset) & 0xFF;
                int blue = ((x + y) * 2 + random.nextInt(32)) & 0xFF;
                pixels[y * width + x] = 0xFF000000 | red << 16 | green << 8 | blue;
            }
        }
        image.setRGB(0, 0, width, height, pixels, 0, width);
        return image;
    }

    private static BufferedImage copy(BufferedImage source) {
        BufferedImage copy = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_ARGB);
        copy.setRGB(
            0,
            0,
            source.getWidth(),
            source.getHeight(),
            source.getRGB(0, 0, source.getWidth(), source.getHeight(), null, 0, source.getWidth()),
            0,
            source.getWidth()
        );
        return copy;
    }

    private static BufferedImage solidImage(int width, int height, int value) {
        int color = 0xFF000000 | value << 16 | value << 8 | value;
        return solidColorImage(width, height, color);
    }

    private static BufferedImage solidColorImage(int width, int height, int color) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        int[] pixels = new int[width * height];
        java.util.Arrays.fill(pixels, color);
        image.setRGB(0, 0, width, height, pixels, 0, width);
        return image;
    }

    private static void requireColorPreserved(int color, int amount) {
        BufferedImage image = solidColorImage(16, 16, color);
        new VideoCompressionFilter().apply(image, amount);
        int filtered = image.getRGB(8, 8);
        int maximumError = Math.max(
            Math.abs((color >>> 16 & 0xFF) - (filtered >>> 16 & 0xFF)),
            Math.max(
                Math.abs((color >>> 8 & 0xFF) - (filtered >>> 8 & 0xFF)),
                Math.abs((color & 0xFF) - (filtered & 0xFF))
            )
        );
        image.flush();
        require(maximumError <= 10, "Video compression shifted a representative color by " + maximumError + ".");
    }

    private static boolean hasFullyRetainedMacroblock(BufferedImage previous, BufferedImage current) {
        for (int blockY = 0; blockY < current.getHeight(); blockY += 16) {
            for (int blockX = 0; blockX < current.getWidth(); blockX += 16) {
                boolean retained = true;
                int blockBottom = Math.min(current.getHeight(), blockY + 16);
                int blockRight = Math.min(current.getWidth(), blockX + 16);
                for (int y = blockY; y < blockBottom && retained; y++) {
                    for (int x = blockX; x < blockRight; x++) {
                        if (current.getRGB(x, y) != previous.getRGB(x, y)) {
                            retained = false;
                            break;
                        }
                    }
                }
                if (retained) {
                    return true;
                }
            }
        }
        return false;
    }

    private static double meanSquaredError(BufferedImage expected, BufferedImage actual) {
        int width = expected.getWidth();
        int height = expected.getHeight();
        require(actual.getWidth() == width && actual.getHeight() == height, "Video compression changed frame dimensions.");
        long error = 0L;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int left = expected.getRGB(x, y);
                int right = actual.getRGB(x, y);
                int red = (left >>> 16 & 0xFF) - (right >>> 16 & 0xFF);
                int green = (left >>> 8 & 0xFF) - (right >>> 8 & 0xFF);
                int blue = (left & 0xFF) - (right & 0xFF);
                error += (long)red * red + (long)green * green + (long)blue * blue;
            }
        }
        return error / (double)(width * height * 3L);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
