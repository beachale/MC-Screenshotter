package com.panshot.spectatorcam;

import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.DataBuffer;
import java.awt.image.DataBufferInt;

final class VideoCompressionFilter {
    private static final int MACROBLOCK_SIZE = 16;

    private int width;
    private int height;
    private int compressionAmount = -1;
    private int[] previousFrame;
    private Profile profile;

    synchronized BufferedImage apply(BufferedImage image, int amount) {
        return apply(image, amount, Profile.VIDEO);
    }

    synchronized BufferedImage applyOldYoutube(BufferedImage image, int amount) {
        return apply(image, amount, Profile.OLD_YOUTUBE);
    }

    static int oldYoutubeJpegCompressionAmount(int amount) {
        validateAmount(amount);
        return 10 + amount / 2;
    }

    private BufferedImage apply(BufferedImage image, int amount, Profile requestedProfile) {
        validateAmount(amount);
        int imageWidth = image.getWidth();
        int imageHeight = image.getHeight();
        if (imageWidth != width
            || imageHeight != height
            || amount != compressionAmount
            || requestedProfile != profile) {
            reset();
            width = imageWidth;
            height = imageHeight;
            compressionAmount = amount;
            profile = requestedProfile;
        }

        if (requestedProfile == Profile.OLD_YOUTUBE && amount > 0) {
            applyOldYoutubeScaling(image, amount);
        }
        int[] pixels = mutablePixels(image);
        if (amount > 0) {
            applyArtifacts(pixels, amount, requestedProfile == Profile.OLD_YOUTUBE);
        }
        previousFrame = pixels;
        return image;
    }

    synchronized void reset() {
        width = 0;
        height = 0;
        compressionAmount = -1;
        previousFrame = null;
        profile = null;
    }

    private void applyArtifacts(int[] pixels, int amount, boolean oldYoutube) {
        int lumaStep = 1 + amount * amount / (oldYoutube ? 320 : 400);
        int chromaStep = 1 + amount * amount / (oldYoutube ? 500 : 600);
        int chromaBlockSize = amount < 20 ? 1 : amount < 60 ? 2 : amount < 90 ? 4 : 8;
        int smoothing = Math.max(0, amount - (oldYoutube ? 20 : 35));
        int maximumTemporalBlend = Math.max(0, amount - (oldYoutube ? 25 : 35)) / 2;
        int temporalMotionRange = 16 + amount / 2;

        for (int blockY = 0; blockY < height; blockY += MACROBLOCK_SIZE) {
            int blockHeight = Math.min(MACROBLOCK_SIZE, height - blockY);
            for (int blockX = 0; blockX < width; blockX += MACROBLOCK_SIZE) {
                int blockWidth = Math.min(MACROBLOCK_SIZE, width - blockX);
                compressBlock(
                    pixels,
                    blockX,
                    blockY,
                    blockWidth,
                    blockHeight,
                    chromaBlockSize,
                    lumaStep,
                    chromaStep,
                    smoothing,
                    maximumTemporalBlend,
                    temporalMotionRange
                );
            }
        }
    }

    private static void applyOldYoutubeScaling(BufferedImage image, int amount) {
        double scaleFactor = 1.0 + amount * 3.0 / 100.0;
        int reducedWidth = Math.max(1, (int)Math.round(image.getWidth() / scaleFactor));
        int reducedHeight = Math.max(1, (int)Math.round(image.getHeight() / scaleFactor));
        if (reducedWidth == image.getWidth() && reducedHeight == image.getHeight()) {
            return;
        }

        BufferedImage reduced = new BufferedImage(reducedWidth, reducedHeight, BufferedImage.TYPE_INT_ARGB);
        Graphics2D downscale = reduced.createGraphics();
        try {
            downscale.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            downscale.drawImage(image, 0, 0, reducedWidth, reducedHeight, null);
        } finally {
            downscale.dispose();
        }

        Graphics2D upscale = image.createGraphics();
        try {
            upscale.setComposite(AlphaComposite.Src);
            upscale.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            upscale.drawImage(reduced, 0, 0, image.getWidth(), image.getHeight(), null);
        } finally {
            upscale.dispose();
            reduced.flush();
        }
    }

    private void compressBlock(
        int[] pixels,
        int blockX,
        int blockY,
        int blockWidth,
        int blockHeight,
        int cellSize,
        int lumaStep,
        int chromaStep,
        int smoothing,
        int maximumTemporalBlend,
        int temporalMotionRange
    ) {
        int blockRight = blockX + blockWidth;
        int blockBottom = blockY + blockHeight;
        for (int cellY = blockY; cellY < blockBottom; cellY += cellSize) {
            int cellBottom = Math.min(blockBottom, cellY + cellSize);
            for (int cellX = blockX; cellX < blockRight; cellX += cellSize) {
                int cellRight = Math.min(blockRight, cellX + cellSize);
                int lumaTotal = 0;
                int blueDifferenceTotal = 0;
                int redDifferenceTotal = 0;
                int count = 0;

                for (int y = cellY; y < cellBottom; y++) {
                    int row = y * width;
                    for (int x = cellX; x < cellRight; x++) {
                        int color = pixels[row + x];
                        int value = luma(color);
                        lumaTotal += value;
                        blueDifferenceTotal += (color & 0xFF) - value;
                        redDifferenceTotal += (color >>> 16 & 0xFF) - value;
                        count++;
                    }
                }

                int averageLuma = lumaTotal / count;
                int blueDifference = quantizeSigned(blueDifferenceTotal / count, chromaStep);
                int redDifference = quantizeSigned(redDifferenceTotal / count, chromaStep);
                for (int y = cellY; y < cellBottom; y++) {
                    int row = y * width;
                    for (int x = cellX; x < cellRight; x++) {
                        int offset = row + x;
                        int sourceLuma = luma(pixels[offset]);
                        int value = sourceLuma;
                        value = (value * (100 - smoothing) + averageLuma * smoothing + 50) / 100;
                        value = quantize(value, lumaStep);

                        int red = clamp(value + redDifference);
                        int blue = clamp(value + blueDifference);
                        int green = clamp((value * 256 - red * 77 - blue * 29 + 75) / 150);
                        if (previousFrame != null && maximumTemporalBlend > 0) {
                            int previous = previousFrame[offset];
                            int motion = Math.abs(sourceLuma - luma(previous));
                            int temporalBlend = maximumTemporalBlend
                                * Math.max(0, temporalMotionRange - motion)
                                / temporalMotionRange;
                            red = blend(red, previous >>> 16 & 0xFF, temporalBlend);
                            green = blend(green, previous >>> 8 & 0xFF, temporalBlend);
                            blue = blend(blue, previous & 0xFF, temporalBlend);
                        }
                        pixels[offset] = 0xFF000000 | red << 16 | green << 8 | blue;
                    }
                }
            }
        }
    }

    private static int[] mutablePixels(BufferedImage image) {
        DataBuffer buffer = image.getRaster().getDataBuffer();
        if (buffer instanceof DataBufferInt intBuffer && intBuffer.getNumBanks() == 1) {
            return intBuffer.getData();
        }
        throw new IllegalArgumentException("Video compression requires an integer ARGB image.");
    }

    private static int luma(int color) {
        int red = color >>> 16 & 0xFF;
        int green = color >>> 8 & 0xFF;
        int blue = color & 0xFF;
        return (red * 77 + green * 150 + blue * 29 + 128) >>> 8;
    }

    private static int quantize(int value, int step) {
        return clamp((value + step / 2) / step * step);
    }

    private static int quantizeSigned(int value, int step) {
        int magnitude = (Math.abs(value) + step / 2) / step * step;
        return value < 0 ? -magnitude : magnitude;
    }

    private static int blend(int current, int previous, int previousWeight) {
        return (current * (100 - previousWeight) + previous * previousWeight + 50) / 100;
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(255, value));
    }

    private static void validateAmount(int amount) {
        if (amount < 0 || amount > 100) {
            throw new IllegalArgumentException("Video compression amount must be between 0 and 100.");
        }
    }

    private enum Profile {
        VIDEO,
        OLD_YOUTUBE
    }
}
