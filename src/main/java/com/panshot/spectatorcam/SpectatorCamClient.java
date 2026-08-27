package com.panshot.spectatorcam;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.panshot.spectatorcam.mixin.GameRendererAccessor;
import com.panshot.spectatorcam.mixin.MinecraftClientAccessor;
import com.panshot.spectatorcam.mixin.WindowAccessor;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.gl.SimpleFramebuffer;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.OtherClientPlayerEntity;
import net.minecraft.client.option.Perspective;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.util.Window;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityPose;
import net.minecraft.entity.player.SkinTextures;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.system.MemoryUtil;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.DataBuffer;
import java.awt.image.DataBufferInt;
import java.awt.image.Raster;
import java.awt.image.WritableRaster;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.IntBuffer;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import javax.imageio.ImageIO;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

public final class SpectatorCamClient implements ClientModInitializer {
    private static final String MESSAGE_PREFIX = "[PanShot] ";
    private static final double DEFAULT_PANORAMA_INTERVAL_SECONDS = 10.0;
    private static final double DEFAULT_SINGLE_INTERVAL_SECONDS = 1.0;
    private static final int DEFAULT_JPEG_COMPRESSION_AMOUNT = 25;
    private static final int DEFAULT_VIDEO_COMPRESSION_AMOUNT = 70;
    private static final int DEFAULT_OLD_YOUTUBE_COMPRESSION_AMOUNT = 70;
    private static final int VIDEO_TRANSPORT_JPEG_COMPRESSION_AMOUNT = 10;
    private static final double MIN_DOWNSCALE_FACTOR = 1.0;
    private static final String[] INTERVAL_SUGGESTIONS = {"0.1", "0.5", "1", "2", "5", "10", "30", "60"};
    private static final String[] DOWNSCALE_FACTOR_SUGGESTIONS = {"1", "1.5", "2", "3", "4", "6", "8", "16"};
    private static final String[] PANORAMA_STAGE_SUGGESTIONS = {"faces", "cubemap"};
    private static final String[] SINGLE_STAGE_SUGGESTIONS = {"image", "faces", "cubemap"};
    private static final String[] DOWNSCALE_INTERPOLATION_SUGGESTIONS = {"nearest", "bilinear", "bicubic", "box", "supersample"};
    private static final String[] PANORAMA_RESOLUTION_SUGGESTIONS = {"512", "1024", "2048", "4096", "8192"};
    private static final String[] SINGLE_RESOLUTION_SUGGESTIONS = {"256", "512", "1024", "1920", "2048", "3840", "4096"};
    private static final String[] FOV_SUGGESTIONS = {"0.5", "1", "30", "45", "60", "70", "90", "110", "150", "220"};
    private static final String[] NUDGE_SUGGESTIONS = {"-0.1", "-0.05", "0.05", "0.1", "0.25", "0.5"};
    private static final String[] COMPRESSION_AMOUNT_SUGGESTIONS = {"0", "10", "25", "40", "50", "65", "70", "80", "90", "100"};
    private static final UUID CAMERA_PROFILE_ID = UUID.fromString("f0d6643c-af19-4e1e-948d-a5d2d7e2f27b");
    private static final PanoramaWebServer PANORAMA_WEB_SERVER = new PanoramaWebServer();
    private static final SinglePreviewWebServer SINGLE_WEB_SERVER = new SinglePreviewWebServer();
    private static final PanoramaCaptureController PANORAMA_CONTROLLER = new PanoramaCaptureController();
    private static final SingleCaptureController SINGLE_CONTROLLER = new SingleCaptureController();
    private static final SpectatorCameraController CAMERA_CONTROLLER = new SpectatorCameraController();
    private static final int[] ARGB_BAND_MASKS = {0x00FF0000, 0x0000FF00, 0x000000FF, 0xFF000000};
    private static final ColorModel ARGB_COLOR_MODEL = ColorModel.getRGBdefault();
    private static final int PNG_INITIAL_BUFFER_LIMIT = 64 * 1024 * 1024;
    private static final ExecutorService READBACK_CONVERT_EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "panshot-readback-convert");
        thread.setDaemon(true);
        return thread;
    });

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            CAMERA_CONTROLLER.tick(client);
            PANORAMA_CONTROLLER.tick(client);
            SINGLE_CONTROLLER.tick(client);
        });
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> registerCommands(dispatcher));
    }

    private static void registerCommands(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(buildRootCommand("panshot"));
    }

    public static boolean isPanShotCameraEntity(Entity entity) {
        return entity != null
            && (CAMERA_CONTROLLER.isCameraEntity(entity)
                || PANORAMA_CONTROLLER.isCameraEntity(entity)
                || SINGLE_CONTROLLER.isCameraEntity(entity));
    }

    private static final SuggestionProvider<FabricClientCommandSource> CURRENT_X_SUGGESTIONS =
        (context, builder) -> suggestCurrentCoordinate(context.getSource().getClient(), builder, CoordinateSuggestionAxis.X);
    private static final SuggestionProvider<FabricClientCommandSource> CURRENT_Y_SUGGESTIONS =
        (context, builder) -> suggestCurrentCoordinate(context.getSource().getClient(), builder, CoordinateSuggestionAxis.Y);
    private static final SuggestionProvider<FabricClientCommandSource> CURRENT_Z_SUGGESTIONS =
        (context, builder) -> suggestCurrentCoordinate(context.getSource().getClient(), builder, CoordinateSuggestionAxis.Z);
    private static final SuggestionProvider<FabricClientCommandSource> CURRENT_YAW_SUGGESTIONS =
        (context, builder) -> suggestCurrentRotation(context.getSource().getClient(), builder, true);
    private static final SuggestionProvider<FabricClientCommandSource> CURRENT_PITCH_SUGGESTIONS =
        (context, builder) -> suggestCurrentRotation(context.getSource().getClient(), builder, false);

    private static void takeScreenshotAsyncFast(MinecraftClient client, Framebuffer framebuffer, Consumer<NativeImage> consumer) {
        GpuTexture colorAttachment = framebuffer.getColorAttachment();
        if (colorAttachment == null) {
            throw new IllegalStateException("Tried to capture screenshot of an incomplete framebuffer");
        }

        int width = framebuffer.textureWidth;
        int height = framebuffer.textureHeight;
        int pixelSize = colorAttachment.getFormat().pixelSize();
        if (pixelSize != Integer.BYTES) {
            ScreenshotRecorder.takeScreenshot(framebuffer, consumer);
            return;
        }
        int pixelCount = width * height;
        int requiredBytes = pixelCount * pixelSize;
        GpuBuffer gpuBuffer = RenderSystem.getDevice().createBuffer(() -> "PanShot readback buffer", 9, requiredBytes);
        CommandEncoder commandEncoder = RenderSystem.getDevice().createCommandEncoder();
        commandEncoder.copyTextureToBuffer(colorAttachment, gpuBuffer, 0, () -> {
            GpuBuffer.MappedView mappedView;
            try {
                mappedView = commandEncoder.mapBuffer(gpuBuffer, true, false);
            } catch (RuntimeException exception) {
                gpuBuffer.close();
                throw exception;
            }

            READBACK_CONVERT_EXECUTOR.execute(() -> {
                NativeImage image = null;
                RuntimeException failure = null;
                try {
                    IntBuffer intBuffer = mappedView.data().asIntBuffer();
                    image = new NativeImage(width, height, false);
                    copyReadbackToNativeImage(intBuffer, image, width, height);
                } catch (RuntimeException exception) {
                    if (image != null) {
                        image.close();
                        image = null;
                    }
                    failure = exception;
                }
                NativeImage completedImage = image;
                RuntimeException capturedFailure = failure;
                client.execute(() -> {
                    RuntimeException unmapFailure = null;
                    try {
                        mappedView.close();
                    } catch (RuntimeException exception) {
                        unmapFailure = exception;
                    } finally {
                        gpuBuffer.close();
                    }

                    if (unmapFailure != null) {
                        if (completedImage != null) {
                            completedImage.close();
                        }
                        unmapFailure.printStackTrace();
                        ScreenshotRecorder.takeScreenshot(framebuffer, consumer);
                        return;
                    }

                    if (completedImage != null) {
                        consumer.accept(completedImage);
                    } else {
                        if (capturedFailure != null) {
                            capturedFailure.printStackTrace();
                        }
                        ScreenshotRecorder.takeScreenshot(framebuffer, consumer);
                    }
                });
            });
        }, 0);
    }

    private static void copyReadbackToNativeImage(IntBuffer readbackPixels, NativeImage image, int width, int height) {
        int[] rowPixels = new int[width];
        IntBuffer imagePixels = MemoryUtil.memIntBuffer(image.imageId(), width * height);
        for (int y = 0; y < height; y++) {
            readbackPixels.position(y * width);
            readbackPixels.get(rowPixels, 0, width);
            for (int x = 0; x < width; x++) {
                rowPixels[x] |= 0xFF000000;
            }

            imagePixels.position((height - y - 1) * width);
            imagePixels.put(rowPixels, 0, width);
        }
    }

    private static OtherClientPlayerEntity createRenderPlayerEntity(
        ClientWorld world,
        UUID profileId,
        int entityId,
        ClientPlayerEntity sourcePlayer
    ) {
        GameProfile sourceProfile = sourcePlayer.getGameProfile();
        GameProfile renderProfile = new GameProfile(profileId, sourceProfile.name(), sourceProfile.properties());
        OtherClientPlayerEntity renderPlayer = new OtherClientPlayerEntity(world, renderProfile) {
            @Override
            public SkinTextures getSkin() {
                return sourcePlayer.getSkin();
            }
        };
        renderPlayer.setId(entityId);
        return renderPlayer;
    }

    private static void syncRenderPlayerEntityState(ClientPlayerEntity source, OtherClientPlayerEntity target) {
        target.refreshPositionAndAngles(source.getX(), source.getY(), source.getZ(), source.getYaw(), source.getPitch());
        target.setYaw(source.getYaw());
        target.setPitch(source.getPitch());
        target.lastYaw = source.lastYaw;
        target.lastPitch = source.lastPitch;
        target.lastX = source.lastX;
        target.lastY = source.lastY;
        target.lastZ = source.lastZ;
        target.setVelocity(source.getVelocity());
        target.setOnGround(source.isOnGround());
        target.setSneaking(source.isSneaking());
        target.setSprinting(source.isSprinting());
        target.setSwimming(source.isSwimming());
        target.setPose(source.getPose());
        target.setHeadYaw(source.getHeadYaw());
        target.setBodyYaw(source.getBodyYaw());
        target.lastHeadYaw = source.lastHeadYaw;
        target.lastBodyYaw = source.lastBodyYaw;
        target.setInvisible(source.isInvisible());
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            target.equipStack(slot, source.getEquippedStack(slot));
        }
        if (source.isUsingItem()) {
            target.setCurrentHand(source.getActiveHand());
        } else {
            target.clearActiveItem();
        }
    }

    private static void removeEntityIfPresent(ClientWorld world, int entityId) {
        try {
            world.removeEntity(entityId, Entity.RemovalReason.DISCARDED);
        } catch (RuntimeException ignored) {
            // Best-effort cleanup.
        }
    }

    private enum CoordinateSuggestionAxis {
        X,
        Y,
        Z
    }

    private enum PerspectiveImportMode {
        AUTO,
        SINGLE,
        PANORAMA
    }

    private record PerspectiveReverserState(
        double cameraX,
        double cameraY,
        double cameraZ,
        double cameraYaw,
        double cameraPitch,
        double cameraFov,
        double cameraYawSpeed,
        int screenWidth,
        int screenHeight,
        double imageScale,
        double imageScaleY,
        double frameCenterDx,
        double frameCenterDy,
        int frameCount,
        boolean panoramaFrameNames,
        double frameIntervalSeconds
    ) {
        private boolean looksLikePanorama() {
            double size = Math.max(screenWidth, screenHeight);
            boolean squareFrame = Math.abs(screenWidth - screenHeight) <= Math.max(2.0, size * 0.02);
            boolean yawStepped = frameCount >= 2 && Math.abs(cameraYawSpeed) >= 1.0;
            return panoramaFrameNames || (yawStepped && squareFrame && Math.abs(cameraFov - 90.0) <= 5.0);
        }

        private double singleIntervalSeconds() {
            return frameIntervalSeconds > 0.0 ? frameIntervalSeconds : DEFAULT_SINGLE_INTERVAL_SECONDS;
        }

        private double panoramaIntervalSeconds() {
            if (frameIntervalSeconds > 0.0) {
                return frameIntervalSeconds * 6.0;
            }
            if (Math.abs(cameraYawSpeed) >= 1.0E-6) {
                return 540.0 / Math.abs(cameraYawSpeed);
            }
            return DEFAULT_PANORAMA_INTERVAL_SECONDS;
        }

        private ReferenceTransform toReferenceTransform(int outputWidth, int outputHeight) {
            if (outputWidth == screenWidth && outputHeight == screenHeight) {
                return new ReferenceTransform(screenWidth, screenHeight, imageScale, imageScaleY, frameCenterDx, frameCenterDy);
            }

            double scaleX = outputWidth / (double)screenWidth;
            double scaleY = outputHeight / (double)screenHeight;
            return new ReferenceTransform(
                outputWidth,
                outputHeight,
                imageScale * scaleX,
                imageScaleY * scaleY / scaleX,
                frameCenterDx * scaleX,
                frameCenterDy * scaleY
            );
        }
    }

    private record ReferenceTransform(
        int screenWidth,
        int screenHeight,
        double imageScale,
        double imageScaleY,
        double frameCenterDx,
        double frameCenterDy
    ) {
        private String toJson() {
            return "{\"screenWidth\":"
                + screenWidth
                + ",\"screenHeight\":"
                + screenHeight
                + ",\"imageScale\":"
                + formatJsonDouble(imageScale)
                + ",\"imageScaleY\":"
                + formatJsonDouble(imageScaleY)
                + ",\"frameCenterDx\":"
                + formatJsonDouble(frameCenterDx)
                + ",\"frameCenterDy\":"
                + formatJsonDouble(frameCenterDy)
                + "}";
        }
    }

    private enum DownscaleInterpolation {
        NEAREST("nearest"),
        BILINEAR("bilinear"),
        BICUBIC("bicubic"),
        BOX("box"),
        SUPERSAMPLE("supersample");

        private final String label;

        DownscaleInterpolation(String label) {
            this.label = label;
        }

        private static DownscaleInterpolation parse(String token) {
            String normalized = token.toLowerCase(Locale.ROOT);
            return switch (normalized) {
                case "nearest", "nearest_neighbor", "nearest-neighbor" -> NEAREST;
                case "linear", "bilinear" -> BILINEAR;
                case "cubic", "bicubic" -> BICUBIC;
                case "box", "area", "boxscale", "box_scaling", "box-scaling" -> BOX;
                case "supersample", "supersampling", "super", "ssaa" -> SUPERSAMPLE;
                default -> null;
            };
        }
    }

    private enum SingleDownscaleStage {
        IMAGE;

        private static SingleDownscaleStage parse(String token) {
            String normalized = token.toLowerCase(Locale.ROOT);
            return switch (normalized) {
                case "image", "single", "frame", "faces", "face", "cubemap", "cube",
                    "pre", "post", "before", "after", "before_stitch", "after_stitch",
                    "prestitch", "poststitch" -> IMAGE;
                default -> null;
            };
        }
    }

    private enum SingleCompressionMode {
        OFF,
        JPEG,
        VIDEO,
        OLD_YOUTUBE
    }

    private static byte[] encodePngBytes(NativeImage image) throws IOException {
        return encodePngBytes(toBufferedImage(image));
    }

    private static byte[] encodePngBytes(BufferedImage bufferedImage) throws IOException {
        int width = bufferedImage.getWidth();
        int height = bufferedImage.getHeight();
        try (ByteArrayOutputStream output = new ByteArrayOutputStream(initialPngBufferSize(width, height))) {
            if (!ImageIO.write(bufferedImage, "png", output)) {
                throw new IOException("No PNG image writer available.");
            }
            return output.toByteArray();
        }
    }

    private static int initialPngBufferSize(int width, int height) {
        long estimate = Math.max(1024L, ((long)width * height) / 2L);
        return (int)Math.min(estimate, PNG_INITIAL_BUFFER_LIMIT);
    }

    private static BufferedImage toBufferedImage(NativeImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        return createArgbImage(width, height, image.copyPixelsArgb());
    }

    private static BufferedImage createArgbImage(int width, int height) {
        return new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
    }

    private static BufferedImage createArgbImage(int width, int height, int[] pixels) {
        DataBufferInt buffer = new DataBufferInt(pixels, pixels.length);
        WritableRaster raster = Raster.createPackedRaster(buffer, width, height, width, ARGB_BAND_MASKS, null);
        return new BufferedImage(ARGB_COLOR_MODEL, raster, false, null);
    }

    private static int[] argbPixels(BufferedImage image) {
        DataBuffer buffer = image.getRaster().getDataBuffer();
        if (buffer instanceof DataBufferInt intBuffer && intBuffer.getNumBanks() == 1) {
            return intBuffer.getData();
        }

        return image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
    }

    private static int[] mutableArgbPixels(BufferedImage image) {
        DataBuffer buffer = image.getRaster().getDataBuffer();
        if (buffer instanceof DataBufferInt intBuffer && intBuffer.getNumBanks() == 1) {
            return intBuffer.getData();
        }

        throw new IllegalArgumentException("Expected a mutable integer ARGB image.");
    }

    private static void copyNativeImageToArgbImage(NativeImage source, BufferedImage target, int targetX, int targetY) {
        int sourceWidth = source.getWidth();
        int sourceHeight = source.getHeight();
        int targetWidth = target.getWidth();
        int[] targetPixels = mutableArgbPixels(target);
        int[] rowPixels = new int[sourceWidth];
        IntBuffer sourcePixels = MemoryUtil.memIntBuffer(source.imageId(), sourceWidth * sourceHeight);

        for (int y = 0; y < sourceHeight; y++) {
            sourcePixels.position(y * sourceWidth);
            sourcePixels.get(rowPixels, 0, sourceWidth);
            int targetOffset = (targetY + y) * targetWidth + targetX;
            for (int x = 0; x < sourceWidth; x++) {
                targetPixels[targetOffset + x] = abgrToArgb(rowPixels[x]);
            }
        }
    }

    private static int abgrToArgb(int abgr) {
        return (abgr & 0xFF00FF00)
            | ((abgr & 0x00FF0000) >>> 16)
            | ((abgr & 0x000000FF) << 16);
    }

    private static int scaledDimension(int sourceDimension, double factor) {
        if (factor <= MIN_DOWNSCALE_FACTOR) {
            return sourceDimension;
        }
        int scaled = (int)Math.round(sourceDimension / factor);
        return Math.max(1, Math.min(sourceDimension, scaled));
    }

    private static BufferedImage resizeBufferedImage(
        BufferedImage source,
        int targetWidth,
        int targetHeight,
        DownscaleInterpolation interpolation
    ) {
        if (source.getWidth() == targetWidth && source.getHeight() == targetHeight) {
            return source;
        }

        return switch (interpolation) {
            case NEAREST -> drawResizedImage(source, targetWidth, targetHeight, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            case BILINEAR -> drawResizedImage(source, targetWidth, targetHeight, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            case BICUBIC -> drawResizedImage(source, targetWidth, targetHeight, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            case BOX -> resizeBoxAveraging(source, targetWidth, targetHeight);
            case SUPERSAMPLE -> resizeSupersample(source, targetWidth, targetHeight);
        };
    }

    private static BufferedImage drawResizedImage(BufferedImage source, int targetWidth, int targetHeight, Object interpolationHint) {
        BufferedImage output = createArgbImage(targetWidth, targetHeight);
        Graphics2D graphics = output.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, interpolationHint);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.setRenderingHint(RenderingHints.KEY_ALPHA_INTERPOLATION, RenderingHints.VALUE_ALPHA_INTERPOLATION_QUALITY);
            graphics.drawImage(source, 0, 0, targetWidth, targetHeight, null);
        } finally {
            graphics.dispose();
        }
        return output;
    }

    private static BufferedImage resizeSupersample(BufferedImage source, int targetWidth, int targetHeight) {
        if (targetWidth >= source.getWidth() || targetHeight >= source.getHeight()) {
            return drawResizedImage(source, targetWidth, targetHeight, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        }

        BufferedImage current = source;
        boolean currentOwned = false;
        while (current.getWidth() / 2 >= targetWidth && current.getHeight() / 2 >= targetHeight) {
            int nextWidth = Math.max(targetWidth, current.getWidth() / 2);
            int nextHeight = Math.max(targetHeight, current.getHeight() / 2);
            BufferedImage next = drawResizedImage(current, nextWidth, nextHeight, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            if (currentOwned) {
                current.flush();
            }
            current = next;
            currentOwned = true;
        }

        if (current.getWidth() != targetWidth || current.getHeight() != targetHeight) {
            BufferedImage next = drawResizedImage(current, targetWidth, targetHeight, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            if (currentOwned) {
                current.flush();
            }
            current = next;
        }

        return current;
    }

    private static BufferedImage resizeBoxAveraging(BufferedImage source, int targetWidth, int targetHeight) {
        if (targetWidth >= source.getWidth() || targetHeight >= source.getHeight()) {
            return drawResizedImage(source, targetWidth, targetHeight, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        }

        int sourceWidth = source.getWidth();
        int sourceHeight = source.getHeight();
        int[] sourcePixels = argbPixels(source);
        int[] outputPixels = new int[targetWidth * targetHeight];

        double scaleX = (double)sourceWidth / targetWidth;
        double scaleY = (double)sourceHeight / targetHeight;

        for (int y = 0; y < targetHeight; y++) {
            double srcY0 = y * scaleY;
            double srcY1 = srcY0 + scaleY;
            int minY = (int)Math.floor(srcY0);
            int maxY = (int)Math.ceil(srcY1);
            int outputRow = y * targetWidth;

            for (int x = 0; x < targetWidth; x++) {
                double srcX0 = x * scaleX;
                double srcX1 = srcX0 + scaleX;
                int minX = (int)Math.floor(srcX0);
                int maxX = (int)Math.ceil(srcX1);

                double weightSum = 0.0;
                double alphaSum = 0.0;
                double redSum = 0.0;
                double greenSum = 0.0;
                double blueSum = 0.0;

                for (int srcY = minY; srcY < maxY; srcY++) {
                    if (srcY < 0 || srcY >= sourceHeight) {
                        continue;
                    }
                    double yCoverage = pixelCoverage(srcY, srcY0, srcY1);
                    if (yCoverage <= 0.0) {
                        continue;
                    }

                    int sourceRow = srcY * sourceWidth;
                    for (int srcX = minX; srcX < maxX; srcX++) {
                        if (srcX < 0 || srcX >= sourceWidth) {
                            continue;
                        }
                        double xCoverage = pixelCoverage(srcX, srcX0, srcX1);
                        double weight = xCoverage * yCoverage;
                        if (weight <= 0.0) {
                            continue;
                        }

                        int argb = sourcePixels[sourceRow + srcX];
                        int alpha = (argb >>> 24) & 0xFF;
                        int red = (argb >>> 16) & 0xFF;
                        int green = (argb >>> 8) & 0xFF;
                        int blue = argb & 0xFF;

                        weightSum += weight;
                        alphaSum += alpha * weight;
                        redSum += red * weight;
                        greenSum += green * weight;
                        blueSum += blue * weight;
                    }
                }

                if (weightSum <= 0.0) {
                    outputPixels[outputRow + x] = 0xFF000000;
                    continue;
                }

                int alpha = (int)Math.round(alphaSum / weightSum);
                int red = (int)Math.round(redSum / weightSum);
                int green = (int)Math.round(greenSum / weightSum);
                int blue = (int)Math.round(blueSum / weightSum);

                outputPixels[outputRow + x] =
                    ((alpha & 0xFF) << 24)
                        | ((red & 0xFF) << 16)
                        | ((green & 0xFF) << 8)
                        | (blue & 0xFF);
            }
        }

        return createArgbImage(targetWidth, targetHeight, outputPixels);
    }

    private static double pixelCoverage(int pixelIndex, double min, double max) {
        double pixelMin = pixelIndex;
        double pixelMax = pixelIndex + 1.0;
        return Math.max(0.0, Math.min(pixelMax, max) - Math.max(pixelMin, min));
    }

    private static <T> RequiredArgumentBuilder<FabricClientCommandSource, T> withSuggestions(
        RequiredArgumentBuilder<FabricClientCommandSource, T> builder,
        String... suggestions
    ) {
        return builder.suggests((context, suggestionsBuilder) -> suggestValues(suggestionsBuilder, suggestions));
    }

    private static <T> RequiredArgumentBuilder<FabricClientCommandSource, T> withSuggestions(
        RequiredArgumentBuilder<FabricClientCommandSource, T> builder,
        SuggestionProvider<FabricClientCommandSource> provider
    ) {
        return builder.suggests(provider);
    }

    private static CompletableFuture<Suggestions> suggestValues(SuggestionsBuilder builder, String... values) {
        String remaining = builder.getRemainingLowerCase();
        for (String value : values) {
            if (remaining.isEmpty() || value.toLowerCase(Locale.ROOT).startsWith(remaining)) {
                builder.suggest(value);
            }
        }
        return builder.buildFuture();
    }

    private static CompletableFuture<Suggestions> suggestCurrentCoordinate(
        MinecraftClient client,
        SuggestionsBuilder builder,
        CoordinateSuggestionAxis axis
    ) {
        if (client.player == null) {
            return builder.buildFuture();
        }

        Vec3d eyePos = getStandingPlayerEyePos(client.player);
        double value = switch (axis) {
            case X -> eyePos.x;
            case Y -> eyePos.y;
            case Z -> eyePos.z;
        };
        builder.suggest(formatSuggestedDouble(value));
        return builder.buildFuture();
    }

    private static CompletableFuture<Suggestions> suggestCurrentRotation(
        MinecraftClient client,
        SuggestionsBuilder builder,
        boolean yaw
    ) {
        if (client.player == null) {
            return builder.buildFuture();
        }

        builder.suggest(formatSuggestedDouble(yaw ? client.player.getYaw() : client.player.getPitch()));
        return builder.buildFuture();
    }

    private static Vec3d getStandingPlayerEyePos(ClientPlayerEntity player) {
        return new Vec3d(player.getX(), player.getY() + player.getEyeHeight(EntityPose.STANDING), player.getZ());
    }

    private static String formatSuggestedDouble(double value) {
        String text = String.format(Locale.ROOT, "%.5f", value);
        int end = text.length();
        while (end > 0 && text.charAt(end - 1) == '0') {
            end--;
        }
        if (end > 0 && text.charAt(end - 1) == '.') {
            end--;
        }
        return text.substring(0, end);
    }

    private static String formatJsonDouble(double value) {
        if (!Double.isFinite(value)) {
            return "0";
        }
        return Double.toString(value);
    }

    private static ClickEvent.OpenUrl createOpenUrlClickEvent(String url) {
        return new ClickEvent.OpenUrl(URI.create(url));
    }

    private static void send(MinecraftClient client, String message) {
        if (client.player != null) {
            client.player.sendMessage(Text.literal(MESSAGE_PREFIX + message), false);
        }
    }

    private static int importPerspectiveClipboard(MinecraftClient client, PerspectiveImportMode mode) {
        String clipboard;
        try {
            clipboard = client.keyboard.getClipboard();
        } catch (RuntimeException exception) {
            send(client, "Could not read clipboard: " + exception.getMessage());
            return 0;
        }

        if (clipboard == null || clipboard.isBlank()) {
            send(client, "Clipboard is empty. Copy a PerspectiveReverser JSON state first.");
            return 0;
        }

        PerspectiveReverserState state;
        try {
            state = parsePerspectiveReverserState(clipboard);
        } catch (IllegalArgumentException exception) {
            send(client, "Clipboard does not contain a usable PerspectiveReverser state: " + exception.getMessage());
            return 0;
        }

        PerspectiveImportMode resolvedMode = mode;
        if (resolvedMode == PerspectiveImportMode.AUTO) {
            resolvedMode = state.looksLikePanorama() ? PerspectiveImportMode.PANORAMA : PerspectiveImportMode.SINGLE;
        }

        if (resolvedMode == PerspectiveImportMode.PANORAMA) {
            return PANORAMA_CONTROLLER.importPerspectiveState(client, state);
        }
        return SINGLE_CONTROLLER.importPerspectiveState(client, state);
    }

    private static PerspectiveReverserState parsePerspectiveReverserState(String text) {
        JsonObject root;
        try {
            JsonElement element = JsonParser.parseString(text);
            if (!element.isJsonObject()) {
                throw new IllegalArgumentException("root value is not an object");
            }
            root = element.getAsJsonObject();
        } catch (JsonParseException exception) {
            throw new IllegalArgumentException("invalid JSON");
        }

        int screenWidth = requiredInt(root, "screenWidth");
        int screenHeight = requiredInt(root, "screenHeight");
        if (screenWidth <= 0 || screenHeight <= 0) {
            throw new IllegalArgumentException("screenWidth and screenHeight must be positive");
        }

        JsonArray frames = optionalArray(root, "frames");
        int frameCount = frames != null ? frames.size() : 0;
        boolean panoramaFrameNames = false;
        double frameIntervalSeconds = 0.0;
        double previousTime = Double.NaN;
        if (frames != null) {
            for (JsonElement frameElement : frames) {
                if (!frameElement.isJsonObject()) {
                    continue;
                }
                JsonObject frame = frameElement.getAsJsonObject();
                String name = optionalString(frame, "name", "");
                if (name.toLowerCase(Locale.ROOT).contains("panorama")) {
                    panoramaFrameNames = true;
                }
                double time = optionalDouble(frame, "time", Double.NaN);
                if (Double.isFinite(time)) {
                    if (Double.isFinite(previousTime) && frameIntervalSeconds <= 0.0) {
                        double delta = time - previousTime;
                        if (delta > 1.0E-6) {
                            frameIntervalSeconds = delta;
                        }
                    }
                    previousTime = time;
                }
            }
        }

        return new PerspectiveReverserState(
            requiredDouble(root, "cameraX"),
            requiredDouble(root, "cameraY"),
            requiredDouble(root, "cameraZ"),
            requiredDouble(root, "cameraYaw"),
            requiredDouble(root, "cameraPitch"),
            requiredDouble(root, "cameraFov"),
            optionalDouble(root, "cameraYawSpeed", 0.0),
            screenWidth,
            screenHeight,
            positiveOptionalDouble(root, "imageScale", 1.0),
            positiveOptionalDouble(root, "imageScaleY", 1.0),
            optionalDouble(root, "frameCenterDx", 0.0),
            optionalDouble(root, "frameCenterDy", 0.0),
            frameCount,
            panoramaFrameNames,
            frameIntervalSeconds
        );
    }

    private static JsonArray optionalArray(JsonObject object, String name) {
        JsonElement element = object.get(name);
        return element != null && element.isJsonArray() ? element.getAsJsonArray() : null;
    }

    private static String optionalString(JsonObject object, String name, String fallback) {
        JsonElement element = object.get(name);
        if (element == null || element.isJsonNull()) {
            return fallback;
        }
        try {
            return element.getAsString();
        } catch (RuntimeException exception) {
            return fallback;
        }
    }

    private static int requiredInt(JsonObject object, String name) {
        double value = requiredDouble(object, name);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(name + " is outside integer range");
        }
        return (int)Math.round(value);
    }

    private static double requiredDouble(JsonObject object, String name) {
        JsonElement element = object.get(name);
        if (element == null || element.isJsonNull()) {
            throw new IllegalArgumentException("missing " + name);
        }
        return finiteDouble(element, name);
    }

    private static double optionalDouble(JsonObject object, String name, double fallback) {
        JsonElement element = object.get(name);
        if (element == null || element.isJsonNull()) {
            return fallback;
        }
        try {
            return finiteDouble(element, name);
        } catch (IllegalArgumentException exception) {
            return fallback;
        }
    }

    private static double positiveOptionalDouble(JsonObject object, String name, double fallback) {
        double value = optionalDouble(object, name, fallback);
        return value > 0.0 ? value : fallback;
    }

    private static double finiteDouble(JsonElement element, String name) {
        double value;
        try {
            value = element.getAsDouble();
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException(name + " is not a number");
        }
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " is not finite");
        }
        return value;
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> buildRootCommand(String root) {
        RequiredArgumentBuilder<FabricClientCommandSource, Double> startPitch =
            withSuggestions(argument("pitch", DoubleArgumentType.doubleArg(-90.0, 90.0)), CURRENT_PITCH_SUGGESTIONS)
                .executes(context -> PANORAMA_CONTROLLER.startAt(
                    context.getSource().getClient(),
                    DEFAULT_PANORAMA_INTERVAL_SECONDS,
                    DoubleArgumentType.getDouble(context, "x"),
                    DoubleArgumentType.getDouble(context, "y"),
                    DoubleArgumentType.getDouble(context, "z"),
                    (float)DoubleArgumentType.getDouble(context, "yaw"),
                    (float)DoubleArgumentType.getDouble(context, "pitch")
                ));
        RequiredArgumentBuilder<FabricClientCommandSource, Double> startYaw =
            withSuggestions(argument("yaw", DoubleArgumentType.doubleArg()), CURRENT_YAW_SUGGESTIONS).then(startPitch);
        RequiredArgumentBuilder<FabricClientCommandSource, Double> startZ =
            withSuggestions(argument("z", DoubleArgumentType.doubleArg()), CURRENT_Z_SUGGESTIONS).then(startYaw);
        RequiredArgumentBuilder<FabricClientCommandSource, Double> startY =
            withSuggestions(argument("y", DoubleArgumentType.doubleArg()), CURRENT_Y_SUGGESTIONS).then(startZ);
        RequiredArgumentBuilder<FabricClientCommandSource, Double> startX =
            withSuggestions(argument("x", DoubleArgumentType.doubleArg()), CURRENT_X_SUGGESTIONS).then(startY);

        RequiredArgumentBuilder<FabricClientCommandSource, Double> everyPitch =
            withSuggestions(argument("pitch", DoubleArgumentType.doubleArg(-90.0, 90.0)), CURRENT_PITCH_SUGGESTIONS)
                .executes(context -> PANORAMA_CONTROLLER.startAt(
                    context.getSource().getClient(),
                    DoubleArgumentType.getDouble(context, "intervalSeconds"),
                    DoubleArgumentType.getDouble(context, "x"),
                    DoubleArgumentType.getDouble(context, "y"),
                    DoubleArgumentType.getDouble(context, "z"),
                    (float)DoubleArgumentType.getDouble(context, "yaw"),
                    (float)DoubleArgumentType.getDouble(context, "pitch")
                ));
        RequiredArgumentBuilder<FabricClientCommandSource, Double> everyYaw =
            withSuggestions(argument("yaw", DoubleArgumentType.doubleArg()), CURRENT_YAW_SUGGESTIONS).then(everyPitch);
        RequiredArgumentBuilder<FabricClientCommandSource, Double> everyZ =
            withSuggestions(argument("z", DoubleArgumentType.doubleArg()), CURRENT_Z_SUGGESTIONS).then(everyYaw);
        RequiredArgumentBuilder<FabricClientCommandSource, Double> everyY =
            withSuggestions(argument("y", DoubleArgumentType.doubleArg()), CURRENT_Y_SUGGESTIONS).then(everyZ);
        RequiredArgumentBuilder<FabricClientCommandSource, Double> everyX =
            withSuggestions(argument("x", DoubleArgumentType.doubleArg()), CURRENT_X_SUGGESTIONS).then(everyY);
        RequiredArgumentBuilder<FabricClientCommandSource, Double> everyInterval =
            withSuggestions(argument("intervalSeconds", DoubleArgumentType.doubleArg(0.1)), INTERVAL_SUGGESTIONS)
                .executes(context -> PANORAMA_CONTROLLER.startAtPlayer(
                    context.getSource().getClient(),
                    DoubleArgumentType.getDouble(context, "intervalSeconds")
                ))
                .then(everyX);

        LiteralArgumentBuilder<FabricClientCommandSource> panoramaEvery = literal("every")
            .then(everyInterval);

        LiteralArgumentBuilder<FabricClientCommandSource> panoramaDownscale = literal("downscale")
            .executes(context -> PANORAMA_CONTROLLER.downscaleStatus(context.getSource().getClient()))
            .then(literal("off").executes(context -> PANORAMA_CONTROLLER.disableDownscale(context.getSource().getClient())))
            .then(withSuggestions(argument("factor", DoubleArgumentType.doubleArg(1.0, 64.0)), DOWNSCALE_FACTOR_SUGGESTIONS)
                .executes(context -> PANORAMA_CONTROLLER.setDownscale(
                    context.getSource().getClient(),
                    DoubleArgumentType.getDouble(context, "factor"),
                    null,
                    null
                ))
                .then(withSuggestions(argument("stage", StringArgumentType.word()), PANORAMA_STAGE_SUGGESTIONS)
                    .executes(context -> PANORAMA_CONTROLLER.setDownscale(
                        context.getSource().getClient(),
                        DoubleArgumentType.getDouble(context, "factor"),
                        StringArgumentType.getString(context, "stage"),
                        null
                    ))
                    .then(withSuggestions(argument("interpolation", StringArgumentType.word()), DOWNSCALE_INTERPOLATION_SUGGESTIONS)
                        .executes(context -> PANORAMA_CONTROLLER.setDownscale(
                            context.getSource().getClient(),
                            DoubleArgumentType.getDouble(context, "factor"),
                            StringArgumentType.getString(context, "stage"),
                            StringArgumentType.getString(context, "interpolation")
                        )))));

        LiteralArgumentBuilder<FabricClientCommandSource> panoramaNudge = literal("nudge")
            .executes(context -> PANORAMA_CONTROLLER.captureNudgeStatus(context.getSource().getClient()))
            .then(literal("off").executes(context -> PANORAMA_CONTROLLER.disableCaptureNudge(context.getSource().getClient())))
            .then(withSuggestions(argument("distance", DoubleArgumentType.doubleArg(-10.0, 10.0)), NUDGE_SUGGESTIONS)
                .executes(context -> PANORAMA_CONTROLLER.setCaptureNudge(
                    context.getSource().getClient(),
                    DoubleArgumentType.getDouble(context, "distance")
                )));

        LiteralArgumentBuilder<FabricClientCommandSource> panoramaResolutionCommand = literal("resolution")
            .executes(context -> PANORAMA_CONTROLLER.resolutionStatus(context.getSource().getClient()))
            .then(withSuggestions(argument("size", IntegerArgumentType.integer(16, 8192)), PANORAMA_RESOLUTION_SUGGESTIONS)
                .executes(context -> PANORAMA_CONTROLLER.setResolution(
                    context.getSource().getClient(),
                    IntegerArgumentType.getInteger(context, "size")
                )));

        RequiredArgumentBuilder<FabricClientCommandSource, Double> singlePitch =
            withSuggestions(argument("pitch", DoubleArgumentType.doubleArg(-90.0, 90.0)), CURRENT_PITCH_SUGGESTIONS)
                .executes(context -> SINGLE_CONTROLLER.startAt(
                    context.getSource().getClient(),
                    DEFAULT_SINGLE_INTERVAL_SECONDS,
                    DoubleArgumentType.getDouble(context, "x"),
                    DoubleArgumentType.getDouble(context, "y"),
                    DoubleArgumentType.getDouble(context, "z"),
                    (float)DoubleArgumentType.getDouble(context, "yaw"),
                    (float)DoubleArgumentType.getDouble(context, "pitch")
                ));
        RequiredArgumentBuilder<FabricClientCommandSource, Double> singleYaw =
            withSuggestions(argument("yaw", DoubleArgumentType.doubleArg()), CURRENT_YAW_SUGGESTIONS).then(singlePitch);
        RequiredArgumentBuilder<FabricClientCommandSource, Double> singleZ =
            withSuggestions(argument("z", DoubleArgumentType.doubleArg()), CURRENT_Z_SUGGESTIONS).then(singleYaw);
        RequiredArgumentBuilder<FabricClientCommandSource, Double> singleY =
            withSuggestions(argument("y", DoubleArgumentType.doubleArg()), CURRENT_Y_SUGGESTIONS).then(singleZ);
        RequiredArgumentBuilder<FabricClientCommandSource, Double> singleX =
            withSuggestions(argument("x", DoubleArgumentType.doubleArg()), CURRENT_X_SUGGESTIONS).then(singleY);

        RequiredArgumentBuilder<FabricClientCommandSource, Double> singleEveryPitch =
            withSuggestions(argument("pitch", DoubleArgumentType.doubleArg(-90.0, 90.0)), CURRENT_PITCH_SUGGESTIONS)
                .executes(context -> SINGLE_CONTROLLER.startAt(
                    context.getSource().getClient(),
                    DoubleArgumentType.getDouble(context, "intervalSeconds"),
                    DoubleArgumentType.getDouble(context, "x"),
                    DoubleArgumentType.getDouble(context, "y"),
                    DoubleArgumentType.getDouble(context, "z"),
                    (float)DoubleArgumentType.getDouble(context, "yaw"),
                    (float)DoubleArgumentType.getDouble(context, "pitch")
                ));
        RequiredArgumentBuilder<FabricClientCommandSource, Double> singleEveryYaw =
            withSuggestions(argument("yaw", DoubleArgumentType.doubleArg()), CURRENT_YAW_SUGGESTIONS).then(singleEveryPitch);
        RequiredArgumentBuilder<FabricClientCommandSource, Double> singleEveryZ =
            withSuggestions(argument("z", DoubleArgumentType.doubleArg()), CURRENT_Z_SUGGESTIONS).then(singleEveryYaw);
        RequiredArgumentBuilder<FabricClientCommandSource, Double> singleEveryY =
            withSuggestions(argument("y", DoubleArgumentType.doubleArg()), CURRENT_Y_SUGGESTIONS).then(singleEveryZ);
        RequiredArgumentBuilder<FabricClientCommandSource, Double> singleEveryX =
            withSuggestions(argument("x", DoubleArgumentType.doubleArg()), CURRENT_X_SUGGESTIONS).then(singleEveryY);
        RequiredArgumentBuilder<FabricClientCommandSource, Double> singleEveryInterval =
            withSuggestions(argument("intervalSeconds", DoubleArgumentType.doubleArg(0.1)), INTERVAL_SUGGESTIONS)
                .executes(context -> SINGLE_CONTROLLER.startAtPlayer(
                    context.getSource().getClient(),
                    DoubleArgumentType.getDouble(context, "intervalSeconds")
                ))
                .then(singleEveryX);

        LiteralArgumentBuilder<FabricClientCommandSource> singleResolutionCommand = literal("resolution")
            .executes(context -> SINGLE_CONTROLLER.resolutionStatus(context.getSource().getClient()))
            .then(withSuggestions(argument("width", IntegerArgumentType.integer(64, 4096)), SINGLE_RESOLUTION_SUGGESTIONS)
                .then(withSuggestions(argument("height", IntegerArgumentType.integer(64, 4096)), SINGLE_RESOLUTION_SUGGESTIONS)
                    .executes(context -> SINGLE_CONTROLLER.setResolution(
                        context.getSource().getClient(),
                        IntegerArgumentType.getInteger(context, "width"),
                        IntegerArgumentType.getInteger(context, "height")
                    ))));

        LiteralArgumentBuilder<FabricClientCommandSource> singleFovCommand = literal("fov")
            .executes(context -> SINGLE_CONTROLLER.fovStatus(context.getSource().getClient()))
            .then(withSuggestions(argument("degrees", DoubleArgumentType.doubleArg()), FOV_SUGGESTIONS)
                .executes(context -> SINGLE_CONTROLLER.setFov(
                    context.getSource().getClient(),
                    DoubleArgumentType.getDouble(context, "degrees")
                )));

        LiteralArgumentBuilder<FabricClientCommandSource> singleRenderPlayerCommand = literal("renderplayer")
            .executes(context -> SINGLE_CONTROLLER.renderPlayerStatus(context.getSource().getClient()))
            .then(literal("on").executes(context -> SINGLE_CONTROLLER.setRenderPlayerEnabled(context.getSource().getClient(), true)))
            .then(literal("off").executes(context -> SINGLE_CONTROLLER.setRenderPlayerEnabled(context.getSource().getClient(), false)));

        LiteralArgumentBuilder<FabricClientCommandSource> singleDownscaleCommand = literal("downscale")
            .executes(context -> SINGLE_CONTROLLER.downscaleStatus(context.getSource().getClient()))
            .then(literal("off").executes(context -> SINGLE_CONTROLLER.disableDownscale(context.getSource().getClient())))
            .then(withSuggestions(argument("factor", DoubleArgumentType.doubleArg(1.0, 64.0)), DOWNSCALE_FACTOR_SUGGESTIONS)
                .executes(context -> SINGLE_CONTROLLER.setDownscale(
                    context.getSource().getClient(),
                    DoubleArgumentType.getDouble(context, "factor"),
                    null,
                    null
                ))
                .then(withSuggestions(argument("stage", StringArgumentType.word()), SINGLE_STAGE_SUGGESTIONS)
                    .executes(context -> SINGLE_CONTROLLER.setDownscale(
                        context.getSource().getClient(),
                        DoubleArgumentType.getDouble(context, "factor"),
                        StringArgumentType.getString(context, "stage"),
                        null
                    ))
                    .then(withSuggestions(argument("interpolation", StringArgumentType.word()), DOWNSCALE_INTERPOLATION_SUGGESTIONS)
                        .executes(context -> SINGLE_CONTROLLER.setDownscale(
                            context.getSource().getClient(),
                            DoubleArgumentType.getDouble(context, "factor"),
                            StringArgumentType.getString(context, "stage"),
                            StringArgumentType.getString(context, "interpolation")
                        )))));

        LiteralArgumentBuilder<FabricClientCommandSource> jpegCompressionCommand = literal("jpeg")
            .executes(context -> SINGLE_CONTROLLER.enableJpegCompression(context.getSource().getClient()))
            .then(withSuggestions(
                argument("jpegAmount", IntegerArgumentType.integer(0, 100)),
                COMPRESSION_AMOUNT_SUGGESTIONS
            ).executes(context -> SINGLE_CONTROLLER.setJpegCompression(
                context.getSource().getClient(),
                IntegerArgumentType.getInteger(context, "jpegAmount")
            )));

        LiteralArgumentBuilder<FabricClientCommandSource> videoCompressionCommand = literal("video")
            .executes(context -> SINGLE_CONTROLLER.enableVideoCompression(context.getSource().getClient()))
            .then(withSuggestions(
                argument("videoAmount", IntegerArgumentType.integer(0, 100)),
                COMPRESSION_AMOUNT_SUGGESTIONS
            ).executes(context -> SINGLE_CONTROLLER.setVideoCompression(
                context.getSource().getClient(),
                IntegerArgumentType.getInteger(context, "videoAmount")
            )));

        LiteralArgumentBuilder<FabricClientCommandSource> oldYoutubeCompressionCommand = literal("youtube")
            .executes(context -> SINGLE_CONTROLLER.enableOldYoutubeCompression(context.getSource().getClient()))
            .then(withSuggestions(
                argument("youtubeAmount", IntegerArgumentType.integer(0, 100)),
                COMPRESSION_AMOUNT_SUGGESTIONS
            ).executes(context -> SINGLE_CONTROLLER.setOldYoutubeCompression(
                context.getSource().getClient(),
                IntegerArgumentType.getInteger(context, "youtubeAmount")
            )));

        LiteralArgumentBuilder<FabricClientCommandSource> singleCompressionCommand = literal("compression")
            .executes(context -> SINGLE_CONTROLLER.enableJpegCompression(context.getSource().getClient()))
            .then(literal("off").executes(context -> SINGLE_CONTROLLER.disableCompression(context.getSource().getClient())))
            .then(jpegCompressionCommand)
            .then(videoCompressionCommand)
            .then(oldYoutubeCompressionCommand)
            .then(withSuggestions(
                argument("amount", IntegerArgumentType.integer(0, 100)),
                COMPRESSION_AMOUNT_SUGGESTIONS
            ).executes(context -> SINGLE_CONTROLLER.setJpegCompression(
                context.getSource().getClient(),
                IntegerArgumentType.getInteger(context, "amount")
            )));

        LiteralArgumentBuilder<FabricClientCommandSource> singleCommand = literal("single")
            .executes(context -> SINGLE_CONTROLLER.startAtPlayer(
                context.getSource().getClient(),
                DEFAULT_SINGLE_INTERVAL_SECONDS
            ))
            .then(singleX)
            .then(literal("every").then(singleEveryInterval))
            .then(singleResolutionCommand)
            .then(singleFovCommand)
            .then(singleRenderPlayerCommand)
            .then(singleDownscaleCommand)
            .then(singleCompressionCommand)
            .then(literal("clipboard").executes(context -> importPerspectiveClipboard(
                context.getSource().getClient(),
                PerspectiveImportMode.SINGLE
            )))
            .then(literal("stop").executes(context -> SINGLE_CONTROLLER.stop(context.getSource().getClient(), true)))
            .then(literal("status").executes(context -> SINGLE_CONTROLLER.status(context.getSource().getClient())));

        return literal(root)
            .executes(context -> CAMERA_CONTROLLER.toggle(context.getSource().getClient()))
            .then(literal("clipboard").executes(context -> importPerspectiveClipboard(
                context.getSource().getClient(),
                PerspectiveImportMode.AUTO
            )))
            .then(singleCommand)
            .then(literal("panorama")
                .executes(context -> PANORAMA_CONTROLLER.startAtPlayer(
                    context.getSource().getClient(),
                    DEFAULT_PANORAMA_INTERVAL_SECONDS
                ))
                .then(startX)
                .then(panoramaEvery)
                .then(literal("clipboard").executes(context -> importPerspectiveClipboard(
                    context.getSource().getClient(),
                    PerspectiveImportMode.PANORAMA
                )))
                .then(literal("stop").executes(context -> PANORAMA_CONTROLLER.stop(context.getSource().getClient(), true)))
                .then(literal("status").executes(context -> PANORAMA_CONTROLLER.status(context.getSource().getClient())))
                .then(literal("mode")
                    .executes(context -> PANORAMA_CONTROLLER.modeStatus(context.getSource().getClient()))
                    .then(literal("smooth").executes(context -> PANORAMA_CONTROLLER.setPreciseCaptureMode(context.getSource().getClient(), false)))
                    .then(literal("precise").executes(context -> PANORAMA_CONTROLLER.setPreciseCaptureMode(context.getSource().getClient(), true))))
                .then(literal("renderplayer")
                    .executes(context -> PANORAMA_CONTROLLER.renderPlayerStatus(context.getSource().getClient()))
                    .then(literal("on").executes(context -> PANORAMA_CONTROLLER.setRenderPlayerEnabled(context.getSource().getClient(), true)))
                    .then(literal("off").executes(context -> PANORAMA_CONTROLLER.setRenderPlayerEnabled(context.getSource().getClient(), false))))
                .then(literal("export")
                    .executes(context -> PANORAMA_CONTROLLER.exportStatus(context.getSource().getClient()))
                    .then(literal("on").executes(context -> PANORAMA_CONTROLLER.setExportEnabled(context.getSource().getClient(), true)))
                    .then(literal("off").executes(context -> PANORAMA_CONTROLLER.setExportEnabled(context.getSource().getClient(), false))))
                .then(panoramaDownscale)
                .then(panoramaResolutionCommand)
                .then(panoramaNudge));
    }

    private static final class SpectatorCameraController {
        private boolean enabled;
        private OtherClientPlayerEntity cameraEntity;
        private ClientWorld cameraWorld;

        private boolean isCameraEntity(Entity entity) {
            return entity == cameraEntity;
        }

        private void tick(MinecraftClient client) {
            if (!enabled) {
                return;
            }

            if (client.player == null || client.world == null) {
                disable(client, false);
                return;
            }

            if (cameraEntity == null || cameraWorld != client.world) {
                createOrResetCamera(client.world, client.player);
            }

            keepCameraStanding();

            if (client.getCameraEntity() != cameraEntity) {
                client.setCameraEntity(cameraEntity);
            }
        }

        private int toggle(MinecraftClient client) {
            return enabled ? disable(client, true) : enable(client);
        }

        private int enable(MinecraftClient client) {
            if (client.player == null || client.world == null) {
                send(client, "Join a world first.");
                return 0;
            }

            createOrResetCamera(client.world, client.player);
            enabled = true;
            client.setCameraEntity(cameraEntity);
            send(client, "Enabled.");
            return 1;
        }

        private int disable(MinecraftClient client, boolean notify) {
            enabled = false;
            cameraEntity = null;
            cameraWorld = null;

            if (client.player != null) {
                client.setCameraEntity(client.player);
            } else {
                client.setCameraEntity(null);
            }

            if (notify) {
                send(client, "Disabled.");
            }
            return 1;
        }

        private int teleport(MinecraftClient client, double x, double y, double z) {
            if (!ensureActive(client)) {
                return 0;
            }

            teleportInternal(x, y, z, cameraEntity.getYaw(), cameraEntity.getPitch());
            send(client, String.format(Locale.ROOT, "Camera teleported to %.2f %.2f %.2f.", x, y, z));
            return 1;
        }

        private int printPosition(MinecraftClient client) {
            if (!ensureActive(client)) {
                return 0;
            }

            send(client, String.format(
                Locale.ROOT,
                "Camera at %.2f %.2f %.2f (yaw %.1f, pitch %.1f).",
                cameraEntity.getX(),
                cameraEntity.getY(),
                cameraEntity.getZ(),
                cameraEntity.getYaw(),
                cameraEntity.getPitch()
            ));
            return 1;
        }

        private boolean ensureActive(MinecraftClient client) {
            if (!enabled || cameraEntity == null) {
                send(client, "Enable camera first with /panshot.");
                return false;
            }

            if (client.player == null || client.world == null) {
                send(client, "Join a world first.");
                return false;
            }

            if (cameraWorld != client.world) {
                createOrResetCamera(client.world, client.player);
                client.setCameraEntity(cameraEntity);
            }
            return true;
        }

        private void createOrResetCamera(ClientWorld world, ClientPlayerEntity player) {
            if (cameraEntity == null || cameraWorld != world) {
                GameProfile profile = new GameProfile(CAMERA_PROFILE_ID, "spectator_camera");
                cameraEntity = new OtherClientPlayerEntity(world, profile);
                cameraWorld = world;
            }

            cameraEntity.noClip = true;
            cameraEntity.setNoGravity(true);
            keepCameraStanding();
            teleportInternal(player.getX(), player.getY(), player.getZ(), player.getYaw(), player.getPitch());
        }

        private void keepCameraStanding() {
            cameraEntity.setSneaking(false);
            cameraEntity.setPose(EntityPose.STANDING);
        }

        private void teleportInternal(double x, double y, double z, float yaw, float pitch) {
            cameraEntity.refreshPositionAndAngles(x, y, z, yaw, pitch);
            cameraEntity.setVelocity(Vec3d.ZERO);
        }

        private void send(MinecraftClient client, String message) {
            if (client.player != null) {
                client.player.sendMessage(Text.literal(MESSAGE_PREFIX + message), false);
            }
        }
    }

    private static final class PanoramaCaptureController implements PanoramaWebServer.StateProvider {
        private static final UUID PANORAMA_PROFILE_ID = UUID.fromString("4f83f6ac-6349-4f15-9f9b-4a0e5c2623ad");
        private static final UUID PANORAMA_RENDER_PLAYER_PROFILE_ID = UUID.fromString("2a89a050-bf8c-4187-b2c3-f1f008f6422f");
        private static final int PANORAMA_RENDER_PLAYER_ENTITY_ID = Integer.MIN_VALUE + 42;
        private static final int DEFAULT_PANORAMA_RESOLUTION = 1024;
        private static final int[] CUBEMAP_LAYOUT = {
            3, 1, 4,
            5, 0, 2
        };
        private static final String CUBEMAP_FILE_NAME = "panorama_cubemap.png";
        private static final double NUDGE_EPSILON = 1.0E-6;

        private enum DownscaleStage {
            FACES("faces"),
            CUBEMAP("cubemap");

            private final String label;

            DownscaleStage(String label) {
                this.label = label;
            }

            private static DownscaleStage parse(String token) {
                String normalized = token.toLowerCase(Locale.ROOT);
                return switch (normalized) {
                    case "faces", "face", "pre", "before", "before_stitch", "prestitch" -> FACES;
                    case "cubemap", "cube", "post", "after", "after_stitch", "poststitch" -> CUBEMAP;
                    default -> null;
                };
            }
        }

        private volatile boolean running;
        private long tickCounter;
        private long intervalTicks;
        private long nextCycleTick;
        private long cycleStartTick;
        private int completedCycles;
        private int activeFaceIndex = -1;
        private boolean cycleInProgress;
        private int facesScheduledInCycle;
        private int pendingFaceCaptures;
        private long captureSessionId;
        private Vec3d origin = Vec3d.ZERO;
        private float baseYaw;
        private float basePitch;
        private OtherClientPlayerEntity panoramaEntity;
        private ClientWorld panoramaWorld;
        private OtherClientPlayerEntity panoramaRenderPlayerEntity;
        private ClientWorld panoramaRenderPlayerWorld;
        private Framebuffer vanillaMainFramebuffer;
        private SimpleFramebuffer panoramaRenderFramebuffer;
        private final NativeImage[] capturedFaces = new NativeImage[6];
        private volatile byte[] latestCubemapBytes;
        private volatile long latestCubemapTimestamp;
        private volatile boolean exportToDisk;
        private volatile boolean preciseCaptureMode;
        private volatile boolean renderPlayerEnabled;
        private volatile double captureNudgeDistance;
        private volatile int panoramaResolution = DEFAULT_PANORAMA_RESOLUTION;
        private int cyclePanoramaResolution = DEFAULT_PANORAMA_RESOLUTION;
        private volatile double downscaleFactor = MIN_DOWNSCALE_FACTOR;
        private volatile DownscaleStage downscaleStage = DownscaleStage.CUBEMAP;
        private volatile DownscaleInterpolation downscaleInterpolation = DownscaleInterpolation.BICUBIC;
        private final ExecutorService stitchExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "panshot-stitch");
            thread.setDaemon(true);
            return thread;
        });
        private final AtomicBoolean stitchInFlight = new AtomicBoolean(false);
        private long lastSkippedStitchMessageTick = Long.MIN_VALUE;

        private void tick(MinecraftClient client) {
            if (!running) {
                return;
            }
            tickCounter++;

            if (client.player == null || client.world == null) {
                stopInternal(client, false, "Panorama capture stopped because no world is loaded.");
                return;
            }

            if (panoramaEntity == null || panoramaWorld != client.world) {
                ensurePanoramaEntity(client.world);
            }

            if (!cycleInProgress && tickCounter < nextCycleTick) {
                return;
            }

            try {
                if (!cycleInProgress) {
                    startCycle();
                }

                // 1.21.10 screenshot readback is async; avoid overlapping reads against the same render target.
                if (pendingFaceCaptures > 0) {
                    return;
                }

                if (facesScheduledInCycle >= 6) {
                    return;
                }

                if (!preciseCaptureMode && tickCounter < faceDueTick(facesScheduledInCycle)) {
                    return;
                }

                schedulePanoramaFace(client, facesScheduledInCycle);
            } catch (Exception exception) {
                stopInternal(client, false, null);
                send(client, "Panorama capture failed: " + exception.getMessage());
            }
        }

        private int startAtPlayer(MinecraftClient client, double intervalSeconds) {
            if (client.player == null) {
                send(client, "Join a world first.");
                return 0;
            }

            Vec3d eyePos = getStandingPlayerEyePos(client.player);
            return startAt(client, intervalSeconds, eyePos.x, eyePos.y, eyePos.z, client.player.getYaw(), 0.0f);
        }

        private int startAt(MinecraftClient client, double intervalSeconds, double x, double y, double z, float yaw, float pitch) {
            if (client.player == null || client.world == null) {
                send(client, "Join a world first.");
                return 0;
            }

            SINGLE_CONTROLLER.stop(client, false);
            origin = new Vec3d(x, y, z);
            baseYaw = yaw;
            basePitch = clampPitch(pitch);
            intervalTicks = Math.max(1L, Math.round(intervalSeconds * 20.0));
            captureSessionId++;
            running = true;
            completedCycles = 0;
            cycleInProgress = false;
            facesScheduledInCycle = 0;
            pendingFaceCaptures = 0;
            activeFaceIndex = -1;
            clearCapturedFaces();
            cyclePanoramaResolution = panoramaResolution;
            nextCycleTick = tickCounter;
            cycleStartTick = tickCounter;
            ensurePanoramaEntity(client.world);
            ensureFramebuffers(client, cyclePanoramaResolution);
            vanillaMainFramebuffer = ((MinecraftClientAccessor)client).spectatorcam$getFramebuffer();

            try {
                String viewerUrl = PANORAMA_WEB_SERVER.ensureStarted(this);
                sendViewerLink(client, viewerUrl);
            } catch (IOException exception) {
                send(client, "Panorama viewer failed to start: " + exception.getMessage());
            }

            send(client, String.format(
                Locale.ROOT,
                "Panorama capture started at %.3f %.3f %.3f every %.2f seconds (yaw %.1f, pitch %.1f, resolution %s, mode %s, downscale %s, nudge %s).",
                x,
                y,
                z,
                intervalTicks / 20.0,
                baseYaw,
                basePitch,
                describeActiveResolution(),
                preciseCaptureMode ? "precise" : "smooth",
                describeDownscale(),
                describeCaptureNudge()
            ));
            return 1;
        }

        private int importPerspectiveState(MinecraftClient client, PerspectiveReverserState state) {
            int importedResolution = clampResolution(Math.min(state.screenWidth(), state.screenHeight()));
            panoramaResolution = importedResolution;
            if (Math.abs(state.cameraYawSpeed()) >= 1.0E-6) {
                preciseCaptureMode = false;
            }

            int result = startAt(
                client,
                Math.max(0.1, state.panoramaIntervalSeconds()),
                state.cameraX(),
                state.cameraY(),
                state.cameraZ(),
                (float)state.cameraYaw(),
                (float)state.cameraPitch()
            );
            if (result > 0) {
                send(client, String.format(
                    Locale.ROOT,
                    "Imported PerspectiveReverser panorama from clipboard (%dx%d faces, fov %.1f).",
                    importedResolution,
                    importedResolution,
                    state.cameraFov()
                ));
            }
            return result;
        }

        private int stop(MinecraftClient client, boolean notify) {
            if (!running) {
                if (notify) {
                    send(client, "Panorama capture is not running.");
                }
                return 0;
            }

            stopInternal(client, notify, null);
            return 1;
        }

        private int status(MinecraftClient client) {
            if (!running) {
                send(client, "Panorama capture is not running.");
                return 1;
            }

            if (!preciseCaptureMode && cycleInProgress && activeFaceIndex >= 0) {
                send(client, String.format(
                    Locale.ROOT,
                    "Running: capturing face %d/6 at %.3f %.3f %.3f (yaw %.1f, pitch %.1f, resolution %s, mode %s, downscale %s, nudge %s).",
                    activeFaceIndex + 1,
                    origin.x,
                    origin.y,
                    origin.z,
                    baseYaw,
                    basePitch,
                    describeActiveResolution(),
                    preciseCaptureMode ? "precise" : "smooth",
                    describeDownscale(),
                    describeCaptureNudge()
                ));
                return 1;
            }

            double seconds = Math.max(0.0, (nextCycleTick - tickCounter) / 20.0);
            send(client, String.format(
                Locale.ROOT,
                "Running: next cycle in %.2f seconds from %.3f %.3f %.3f (yaw %.1f, pitch %.1f, resolution %s, mode %s, downscale %s, nudge %s).",
                seconds,
                origin.x,
                origin.y,
                origin.z,
                baseYaw,
                basePitch,
                describeActiveResolution(),
                preciseCaptureMode ? "precise" : "smooth",
                describeDownscale(),
                describeCaptureNudge()
            ));
            return 1;
        }

        private void startCycle() {
            cycleInProgress = true;
            cycleStartTick = tickCounter;
            facesScheduledInCycle = 0;
            pendingFaceCaptures = 0;
            activeFaceIndex = preciseCaptureMode ? -1 : 0;
            cyclePanoramaResolution = panoramaResolution;
            clearCapturedFaces();
        }

        private long faceDueTick(int faceIndex) {
            return cycleStartTick + (intervalTicks * faceIndex) / 6L;
        }

        private void schedulePanoramaFace(MinecraftClient client, int index) {
            long sessionId = captureSessionId;
            pendingFaceCaptures++;
            facesScheduledInCycle++;
            activeFaceIndex = facesScheduledInCycle >= 6 ? -1 : facesScheduledInCycle;

            try (RenderContext context = beginPanoramaRender(client)) {
                renderPanoramaFace(client, index, sessionId);
            } catch (Exception exception) {
                pendingFaceCaptures = Math.max(0, pendingFaceCaptures - 1);
                throw exception;
            }
        }

        private int exportStatus(MinecraftClient client) {
            send(client, "Panorama export is " + (exportToDisk ? "on" : "off") + ".");
            return 1;
        }

        private int setExportEnabled(MinecraftClient client, boolean enabled) {
            exportToDisk = enabled;
            send(client, "Panorama export " + (enabled ? "enabled" : "disabled") + ".");
            return 1;
        }

        private int modeStatus(MinecraftClient client) {
            send(client, "Panorama mode is " + (preciseCaptureMode ? "precise" : "smooth") + ".");
            return 1;
        }

        private int setPreciseCaptureMode(MinecraftClient client, boolean precise) {
            preciseCaptureMode = precise;
            captureSessionId++;
            cycleInProgress = false;
            facesScheduledInCycle = 0;
            pendingFaceCaptures = 0;
            cycleStartTick = 0L;
            activeFaceIndex = -1;
            clearCapturedFaces();
            send(client, "Panorama mode set to " + (precise ? "precise" : "smooth") + ".");
            return 1;
        }

        private int renderPlayerStatus(MinecraftClient client) {
            send(client, "Panorama renderplayer is " + (renderPlayerEnabled ? "on" : "off") + ".");
            return 1;
        }

        private int setRenderPlayerEnabled(MinecraftClient client, boolean enabled) {
            renderPlayerEnabled = enabled;
            if (client.world != null && !enabled) {
                removeEntityIfPresent(client.world, PANORAMA_RENDER_PLAYER_ENTITY_ID);
            }
            panoramaRenderPlayerEntity = null;
            panoramaRenderPlayerWorld = null;
            send(client, "Panorama renderplayer " + (enabled ? "enabled" : "disabled") + ".");
            return 1;
        }

        private int captureNudgeStatus(MinecraftClient client) {
            send(client, "Panorama nudge is " + describeCaptureNudge() + ".");
            return 1;
        }

        private int disableCaptureNudge(MinecraftClient client) {
            captureNudgeDistance = 0.0;
            send(client, "Panorama nudge disabled.");
            return 1;
        }

        private int setCaptureNudge(MinecraftClient client, double distance) {
            captureNudgeDistance = distance;
            send(client, "Panorama nudge set to " + describeCaptureNudge() + ".");
            return 1;
        }

        private int resolutionStatus(MinecraftClient client) {
            send(client, "Panorama resolution is " + describeConfiguredResolution() + ".");
            return 1;
        }

        private int setResolution(MinecraftClient client, int size) {
            panoramaResolution = clampResolution(size);
            if (running) {
                send(client, "Panorama resolution set to " + describeConfiguredResolution() + " (applies next cycle).");
            } else {
                send(client, "Panorama resolution set to " + describeConfiguredResolution() + ".");
            }
            return 1;
        }

        private int downscaleStatus(MinecraftClient client) {
            send(client, "Panorama downscale is " + describeDownscale() + ".");
            return 1;
        }

        private int disableDownscale(MinecraftClient client) {
            downscaleFactor = MIN_DOWNSCALE_FACTOR;
            send(client, "Panorama downscale disabled.");
            return 1;
        }

        private int setDownscale(MinecraftClient client, double factor, String stageToken, String interpolationToken) {
            DownscaleStage resolvedStage = downscaleStage;
            if (stageToken != null) {
                resolvedStage = DownscaleStage.parse(stageToken);
                if (resolvedStage == null) {
                    send(client, "Unknown downscale stage '" + stageToken + "'. Use: faces or cubemap.");
                    return 0;
                }
            }

            DownscaleInterpolation resolvedInterpolation = downscaleInterpolation;
            if (interpolationToken != null) {
                resolvedInterpolation = DownscaleInterpolation.parse(interpolationToken);
                if (resolvedInterpolation == null) {
                    send(client, "Unknown interpolation '" + interpolationToken + "'. Use: nearest, bilinear, bicubic, cubic, box, supersample.");
                    return 0;
                }
            }

            downscaleFactor = Math.max(MIN_DOWNSCALE_FACTOR, factor);
            downscaleStage = resolvedStage;
            downscaleInterpolation = resolvedInterpolation;
            send(client, "Panorama downscale set to " + describeDownscale() + ".");
            return 1;
        }

        private String describeDownscale() {
            double factor = downscaleFactor;
            if (factor <= MIN_DOWNSCALE_FACTOR) {
                return "off";
            }

            return String.format(
                Locale.ROOT,
                "%.2fx (%s, %s)",
                factor,
                downscaleStage.label,
                downscaleInterpolation.label
            );
        }

        private String describeCaptureNudge() {
            double nudge = captureNudgeDistance;
            if (Math.abs(nudge) <= NUDGE_EPSILON) {
                return "off";
            }

            return String.format(Locale.ROOT, "%+.4f blocks", nudge);
        }

        private String describeConfiguredResolution() {
            return panoramaResolution + "x" + panoramaResolution;
        }

        private String describeActiveResolution() {
            return cyclePanoramaResolution + "x" + cyclePanoramaResolution;
        }

        private int clampResolution(int size) {
            return Math.max(16, Math.min(8192, size));
        }

        private RenderContext beginPanoramaRender(MinecraftClient client) {
            int faceResolution = cyclePanoramaResolution;
            ensureFramebuffers(client, faceResolution);

            MinecraftClientAccessor clientAccessor = (MinecraftClientAccessor)client;
            Framebuffer currentFramebuffer = clientAccessor.spectatorcam$getFramebuffer();
            if (vanillaMainFramebuffer == null || vanillaMainFramebuffer == panoramaRenderFramebuffer) {
                vanillaMainFramebuffer = currentFramebuffer;
            }
            Framebuffer mainFramebuffer = currentFramebuffer == panoramaRenderFramebuffer && vanillaMainFramebuffer != null
                ? vanillaMainFramebuffer
                : currentFramebuffer;
            Entity previousCameraEntity = client.getCameraEntity();
            Perspective previousPerspective = client.options.getPerspective();
            int previousFov = client.options.getFov().getValue();
            boolean previousPanoramaMode = client.gameRenderer.isRenderingPanorama();
            Window window = client.getWindow();
            WindowAccessor windowAccessor = (WindowAccessor)(Object)window;
            int previousWindowWidth = window.getWidth();
            int previousWindowHeight = window.getHeight();
            int previousFramebufferWidth = window.getFramebufferWidth();
            int previousFramebufferHeight = window.getFramebufferHeight();
            ClientWorld renderPlayerWorld = null;
            boolean renderPlayerAdded = false;

            try {
                windowAccessor.spectatorcam$setWidth(faceResolution);
                windowAccessor.spectatorcam$setHeight(faceResolution);
                window.setFramebufferWidth(faceResolution);
                window.setFramebufferHeight(faceResolution);

                if (renderPlayerEnabled && client.player != null && client.world != null) {
                    removeEntityIfPresent(client.world, PANORAMA_RENDER_PLAYER_ENTITY_ID);
                    OtherClientPlayerEntity renderPlayer = ensureRenderPlayerEntity(client.world, client.player);
                    syncRenderPlayerEntityState(client.player, renderPlayer);
                    client.world.addEntity(renderPlayer);
                    renderPlayerWorld = client.world;
                    renderPlayerAdded = true;
                }

                clientAccessor.spectatorcam$setFramebuffer(panoramaRenderFramebuffer);
                client.setCameraEntity(panoramaEntity);
                client.options.setPerspective(Perspective.FIRST_PERSON);
                client.options.getFov().setValue(90);
                client.gameRenderer.setRenderingPanorama(true);

                return new RenderContext(
                    client,
                    clientAccessor,
                    mainFramebuffer,
                    previousCameraEntity,
                    previousPerspective,
                    previousFov,
                    previousPanoramaMode,
                    window,
                    windowAccessor,
                    previousWindowWidth,
                    previousWindowHeight,
                    previousFramebufferWidth,
                    previousFramebufferHeight,
                    renderPlayerWorld,
                    renderPlayerAdded
                );
            } catch (RuntimeException exception) {
                try {
                    clientAccessor.spectatorcam$setFramebuffer(mainFramebuffer);
                } catch (RuntimeException ignored) {
                    // Best-effort rollback.
                }
                try {
                    windowAccessor.spectatorcam$setWidth(previousWindowWidth);
                    windowAccessor.spectatorcam$setHeight(previousWindowHeight);
                    window.setFramebufferWidth(previousFramebufferWidth);
                    window.setFramebufferHeight(previousFramebufferHeight);
                } catch (RuntimeException ignored) {
                    // Best-effort rollback.
                }
                try {
                    client.options.getFov().setValue(previousFov);
                    client.options.setPerspective(previousPerspective);
                    if (previousCameraEntity != null) {
                        client.setCameraEntity(previousCameraEntity);
                    } else if (client.player != null) {
                        client.setCameraEntity(client.player);
                    } else {
                        client.setCameraEntity(null);
                    }
                } catch (RuntimeException ignored) {
                    // Best-effort rollback.
                }
                try {
                    client.gameRenderer.setRenderingPanorama(previousPanoramaMode);
                } catch (RuntimeException ignored) {
                    // Best-effort rollback.
                }
                if (renderPlayerAdded && renderPlayerWorld != null) {
                    removeEntityIfPresent(renderPlayerWorld, PANORAMA_RENDER_PLAYER_ENTITY_ID);
                }
                throw exception;
            }
        }

        private void renderPanoramaFace(MinecraftClient client, int index, long sessionId) {
            positionPanoramaEntity(yawForIndex(index), pitchForIndex(index));
            client.gameRenderer.renderWorld(RenderTickCounter.ONE);

            takeScreenshotAsyncFast(client, panoramaRenderFramebuffer, image -> onPanoramaFaceCaptured(client, sessionId, index, image));
        }

        private void onPanoramaFaceCaptured(MinecraftClient client, long sessionId, int index, NativeImage image) {
            boolean activeSession = sessionId == captureSessionId;
            try {
                if (!activeSession || !running) {
                    image.close();
                    return;
                }

                if (capturedFaces[index] != null) {
                    capturedFaces[index].close();
                }
                capturedFaces[index] = image;
            } finally {
                if (activeSession) {
                    pendingFaceCaptures = Math.max(0, pendingFaceCaptures - 1);
                }
            }

            if (!cycleInProgress || facesScheduledInCycle < 6 || pendingFaceCaptures > 0) {
                return;
            }

            cycleInProgress = false;
            activeFaceIndex = -1;
            completedCycles++;
            nextCycleTick = Math.max(tickCounter + 1L, cycleStartTick + intervalTicks);
            submitStitchJob(client, detachCapturedFaces());
        }

        private final class RenderContext implements AutoCloseable {
            private final MinecraftClient client;
            private final MinecraftClientAccessor clientAccessor;
            private final Framebuffer mainFramebuffer;
            private final Entity previousCameraEntity;
            private final Perspective previousPerspective;
            private final int previousFov;
            private final boolean previousPanoramaMode;
            private final Window window;
            private final WindowAccessor windowAccessor;
            private final int previousWindowWidth;
            private final int previousWindowHeight;
            private final int previousFramebufferWidth;
            private final int previousFramebufferHeight;
            private final ClientWorld renderPlayerWorld;
            private final boolean renderPlayerAdded;

            private RenderContext(
                MinecraftClient client,
                MinecraftClientAccessor clientAccessor,
                Framebuffer mainFramebuffer,
                Entity previousCameraEntity,
                Perspective previousPerspective,
                int previousFov,
                boolean previousPanoramaMode,
                Window window,
                WindowAccessor windowAccessor,
                int previousWindowWidth,
                int previousWindowHeight,
                int previousFramebufferWidth,
                int previousFramebufferHeight,
                ClientWorld renderPlayerWorld,
                boolean renderPlayerAdded
            ) {
                this.client = client;
                this.clientAccessor = clientAccessor;
                this.mainFramebuffer = mainFramebuffer;
                this.previousCameraEntity = previousCameraEntity;
                this.previousPerspective = previousPerspective;
                this.previousFov = previousFov;
                this.previousPanoramaMode = previousPanoramaMode;
                this.window = window;
                this.windowAccessor = windowAccessor;
                this.previousWindowWidth = previousWindowWidth;
                this.previousWindowHeight = previousWindowHeight;
                this.previousFramebufferWidth = previousFramebufferWidth;
                this.previousFramebufferHeight = previousFramebufferHeight;
                this.renderPlayerWorld = renderPlayerWorld;
                this.renderPlayerAdded = renderPlayerAdded;
            }

            @Override
            public void close() {
                client.gameRenderer.setRenderingPanorama(previousPanoramaMode);
                windowAccessor.spectatorcam$setWidth(previousWindowWidth);
                windowAccessor.spectatorcam$setHeight(previousWindowHeight);
                window.setFramebufferWidth(previousFramebufferWidth);
                window.setFramebufferHeight(previousFramebufferHeight);
                client.options.getFov().setValue(previousFov);
                client.options.setPerspective(previousPerspective);
                if (previousCameraEntity != null) {
                    client.setCameraEntity(previousCameraEntity);
                } else if (client.player != null) {
                    client.setCameraEntity(client.player);
                } else {
                    client.setCameraEntity(null);
                }
                clientAccessor.spectatorcam$setFramebuffer(mainFramebuffer);
                if (renderPlayerAdded && renderPlayerWorld != null) {
                    removeEntityIfPresent(renderPlayerWorld, PANORAMA_RENDER_PLAYER_ENTITY_ID);
                }
            }
        }

        private void submitStitchJob(MinecraftClient client, NativeImage[] faces) {
            if (!stitchInFlight.compareAndSet(false, true)) {
                closeFaces(faces);
                if (tickCounter - lastSkippedStitchMessageTick >= 100L) {
                    lastSkippedStitchMessageTick = tickCounter;
                    send(client, "Skipped one panorama stitch to keep frame time stable.");
                }
                return;
            }

            stitchExecutor.execute(() -> {
                try {
                    byte[] stitchedBytes = stitchCubemapBytes(faces);
                    long modifiedTime = System.currentTimeMillis();
                    boolean exportSnapshot = exportToDisk;
                    Path exportPath = null;
                    if (exportSnapshot) {
                        Path screenshotsDir = client.runDirectory.toPath().resolve(ScreenshotRecorder.SCREENSHOTS_DIRECTORY);
                        Files.createDirectories(screenshotsDir);
                        exportPath = screenshotsDir.resolve(CUBEMAP_FILE_NAME);
                        Files.write(exportPath, stitchedBytes);
                    }

                    latestCubemapBytes = stitchedBytes;
                    latestCubemapTimestamp = modifiedTime;
                } catch (Exception exception) {
                    client.execute(() -> send(client, "Panorama stitch failed: " + exception.getMessage()));
                } finally {
                    closeFaces(faces);
                    stitchInFlight.set(false);
                }
            });
        }

        private byte[] stitchCubemapBytes(NativeImage[] faces) throws IOException {
            double factor = downscaleFactor;
            DownscaleStage stage = downscaleStage;
            DownscaleInterpolation interpolation = downscaleInterpolation;
            int sourceFaceSize = faces[0].getWidth();
            int stitchedFaceSize = sourceFaceSize;
            if (factor > MIN_DOWNSCALE_FACTOR && stage == DownscaleStage.FACES) {
                stitchedFaceSize = scaledDimension(sourceFaceSize, factor);
            }

            BufferedImage stitched = createArgbImage(stitchedFaceSize * 3, stitchedFaceSize * 2);
            try {
                if (stitchedFaceSize == sourceFaceSize) {
                    copyFacesIntoCubemap(faces, stitched, stitchedFaceSize);
                } else {
                    drawResizedFacesIntoCubemap(faces, stitched, stitchedFaceSize, interpolation);
                }

                BufferedImage output = stitched;
                if (factor > MIN_DOWNSCALE_FACTOR && stage == DownscaleStage.CUBEMAP) {
                    int targetWidth = scaledDimension(stitched.getWidth(), factor);
                    int targetHeight = scaledDimension(stitched.getHeight(), factor);
                    output = resizeBufferedImage(stitched, targetWidth, targetHeight, interpolation);
                }

                try {
                    return encodePngBytes(output);
                } finally {
                    if (output != stitched) {
                        output.flush();
                    }
                }
            } finally {
                stitched.flush();
            }
        }

        private void copyFacesIntoCubemap(NativeImage[] faces, BufferedImage stitched, int faceSize) {
            for (int row = 0; row < 2; row++) {
                for (int col = 0; col < 3; col++) {
                    int faceIndex = CUBEMAP_LAYOUT[row * 3 + col];
                    try {
                        copyNativeImageToArgbImage(faces[faceIndex], stitched, col * faceSize, row * faceSize);
                    } finally {
                        closeFace(faces, faceIndex);
                    }
                }
            }
        }

        private void drawResizedFacesIntoCubemap(
            NativeImage[] faces,
            BufferedImage stitched,
            int faceSize,
            DownscaleInterpolation interpolation
        ) {
            Graphics2D stitchedGraphics = stitched.createGraphics();
            try {
                for (int row = 0; row < 2; row++) {
                    for (int col = 0; col < 3; col++) {
                        int faceIndex = CUBEMAP_LAYOUT[row * 3 + col];
                        try {
                            BufferedImage face = toBufferedImage(faces[faceIndex]);
                            try {
                                BufferedImage sourceForDraw = resizeBufferedImage(face, faceSize, faceSize, interpolation);
                                try {
                                    stitchedGraphics.drawImage(
                                        sourceForDraw,
                                        col * faceSize,
                                        row * faceSize,
                                        null
                                    );
                                } finally {
                                    if (sourceForDraw != face) {
                                        sourceForDraw.flush();
                                    }
                                }
                            } finally {
                                face.flush();
                            }
                        } finally {
                            closeFace(faces, faceIndex);
                        }
                    }
                }
            } finally {
                stitchedGraphics.dispose();
            }
        }

        private NativeImage[] detachCapturedFaces() {
            for (int i = 0; i < capturedFaces.length; i++) {
                if (capturedFaces[i] == null) {
                    throw new IllegalStateException("Missing captured face " + i);
                }
            }

            NativeImage[] cycleFaces = new NativeImage[capturedFaces.length];
            for (int i = 0; i < capturedFaces.length; i++) {
                cycleFaces[i] = capturedFaces[i];
                capturedFaces[i] = null;
            }
            return cycleFaces;
        }

        private float yawForIndex(int index) {
            return switch (index) {
                case 0, 4, 5 -> baseYaw;
                case 1 -> baseYaw + 90.0f;
                case 2 -> baseYaw + 180.0f;
                case 3 -> baseYaw + 270.0f;
                default -> throw new IllegalArgumentException("Unsupported panorama face index: " + index);
            };
        }

        private float pitchForIndex(int index) {
            return switch (index) {
                case 0, 1, 2, 3 -> basePitch;
                case 4 -> clampPitch(basePitch - 90.0f);
                case 5 -> clampPitch(basePitch + 90.0f);
                default -> throw new IllegalArgumentException("Unsupported panorama face index: " + index);
            };
        }

        private float clampPitch(float pitch) {
            return Math.max(-90.0f, Math.min(90.0f, pitch));
        }

        private void ensurePanoramaEntity(ClientWorld world) {
            if (panoramaEntity != null && panoramaWorld == world) {
                return;
            }

            panoramaEntity = new OtherClientPlayerEntity(world, new GameProfile(PANORAMA_PROFILE_ID, "panorama_camera"));
            panoramaEntity.noClip = true;
            panoramaEntity.setNoGravity(true);
            panoramaWorld = world;
        }

        private OtherClientPlayerEntity ensureRenderPlayerEntity(ClientWorld world, ClientPlayerEntity sourcePlayer) {
            if (panoramaRenderPlayerEntity != null && panoramaRenderPlayerWorld == world) {
                return panoramaRenderPlayerEntity;
            }
            panoramaRenderPlayerEntity = createRenderPlayerEntity(
                world,
                PANORAMA_RENDER_PLAYER_PROFILE_ID,
                PANORAMA_RENDER_PLAYER_ENTITY_ID,
                sourcePlayer
            );
            panoramaRenderPlayerWorld = world;
            return panoramaRenderPlayerEntity;
        }

        private void ensureFramebuffers(MinecraftClient client, int resolution) {
            if (panoramaRenderFramebuffer == null
                || panoramaRenderFramebuffer.textureWidth != resolution
                || panoramaRenderFramebuffer.textureHeight != resolution) {
                if (panoramaRenderFramebuffer != null) {
                    panoramaRenderFramebuffer.delete();
                }
                panoramaRenderFramebuffer = new SimpleFramebuffer("panshot-panorama", resolution, resolution, true);
            }
        }

        private void positionPanoramaEntity(float yaw, float pitch) {
            Vec3d captureOrigin = applyDirectionalNudge(origin, yaw, pitch, captureNudgeDistance);
            // `origin.y` is treated as camera eye Y; convert to entity base Y for vanilla camera math.
            double entityY = captureOrigin.y - panoramaEntity.getStandingEyeHeight();
            panoramaEntity.refreshPositionAndAngles(captureOrigin.x, entityY, captureOrigin.z, yaw, pitch);
            panoramaEntity.setYaw(yaw);
            panoramaEntity.setPitch(pitch);
            panoramaEntity.lastYaw = yaw;
            panoramaEntity.lastPitch = pitch;
            panoramaEntity.lastX = captureOrigin.x;
            panoramaEntity.lastY = entityY;
            panoramaEntity.lastZ = captureOrigin.z;
            panoramaEntity.setVelocity(Vec3d.ZERO);
            panoramaEntity.setHeadYaw(yaw);
            panoramaEntity.setBodyYaw(yaw);
            panoramaEntity.lastHeadYaw = yaw;
            panoramaEntity.lastBodyYaw = yaw;
        }

        private static Vec3d applyDirectionalNudge(Vec3d position, float yaw, float pitch, double distance) {
            if (Math.abs(distance) <= NUDGE_EPSILON) {
                return position;
            }

            Vec3d direction = directionFromYawPitch(yaw, pitch);
            return position.add(direction.multiply(distance));
        }

        private static Vec3d directionFromYawPitch(float yaw, float pitch) {
            double yawRadians = Math.toRadians(yaw);
            double pitchRadians = Math.toRadians(pitch);
            double pitchCos = Math.cos(pitchRadians);
            double x = -Math.sin(yawRadians) * pitchCos;
            double y = -Math.sin(pitchRadians);
            double z = Math.cos(yawRadians) * pitchCos;
            return new Vec3d(x, y, z);
        }

        private void stopInternal(MinecraftClient client, boolean notify, String reason) {
            captureSessionId++;
            running = false;
            intervalTicks = 0L;
            nextCycleTick = 0L;
            cycleStartTick = 0L;
            completedCycles = 0;
            cycleInProgress = false;
            facesScheduledInCycle = 0;
            pendingFaceCaptures = 0;
            activeFaceIndex = -1;
            clearCapturedFaces();
            panoramaEntity = null;
            panoramaWorld = null;
            panoramaRenderPlayerEntity = null;
            panoramaRenderPlayerWorld = null;
            if (vanillaMainFramebuffer != null) {
                try {
                    ((MinecraftClientAccessor)client).spectatorcam$setFramebuffer(vanillaMainFramebuffer);
                } catch (RuntimeException ignored) {
                    // Best-effort rollback.
                }
            }

            if (panoramaRenderFramebuffer != null) {
                panoramaRenderFramebuffer.delete();
                panoramaRenderFramebuffer = null;
            }

            if (reason != null) {
                send(client, reason);
            } else if (notify) {
                send(client, "Panorama capture stopped.");
            }
        }

        private void send(MinecraftClient client, String message) {
            if (client.player != null) {
                client.player.sendMessage(Text.literal(MESSAGE_PREFIX + message), false);
            }
        }

        private void sendViewerLink(MinecraftClient client, String url) {
            if (client.player != null) {
                Text link = Text.literal(url).styled(style -> style
                    .withUnderline(true)
                    .withClickEvent(createOpenUrlClickEvent(url)));
                client.player.sendMessage(Text.literal(MESSAGE_PREFIX + "Panorama viewer: ").append(link), false);
            }
        }

        private boolean isCameraEntity(Entity entity) {
            return entity == panoramaEntity;
        }

        private void clearCapturedFaces() {
            for (int i = 0; i < capturedFaces.length; i++) {
                if (capturedFaces[i] != null) {
                    capturedFaces[i].close();
                    capturedFaces[i] = null;
                }
            }
        }

        private void closeFaces(NativeImage[] faces) {
            for (int i = 0; i < faces.length; i++) {
                closeFace(faces, i);
            }
        }

        private void closeFace(NativeImage[] faces, int index) {
            if (faces[index] != null) {
                faces[index].close();
                faces[index] = null;
            }
        }

        @Override
        public boolean isPanoramaRunning() {
            return running;
        }

        @Override
        public byte[] getLatestCubemapBytes() {
            return latestCubemapBytes;
        }

        @Override
        public long getLatestCubemapTimestamp() {
            return latestCubemapTimestamp;
        }
    }

    private static final class SingleCaptureController implements SinglePreviewWebServer.StateProvider {
        private static final UUID SINGLE_PROFILE_ID = UUID.fromString("d5d2f96a-8f54-4f75-92f1-a4051512e53b");
        private static final UUID SINGLE_RENDER_PLAYER_PROFILE_ID = UUID.fromString("fb3c2f64-a8d6-4a65-b5fb-c6d58f2ce6ca");
        private static final int SINGLE_RENDER_PLAYER_ENTITY_ID = Integer.MIN_VALUE + 43;
        private static final int DEFAULT_SINGLE_WIDTH = 1024;
        private static final int DEFAULT_SINGLE_HEIGHT = 1024;
        private static final int MIN_SINGLE_DIMENSION = 64;
        private static final int MAX_SINGLE_DIMENSION = 4096;
        private static final double DEFAULT_SINGLE_FOV = 90.0;
        private static final int VANILLA_SINGLE_FOV_BASE = 90;

        private volatile boolean running;
        private long tickCounter;
        private long intervalTicks;
        private long nextCaptureTick;
        private int completedCaptures;
        private boolean capturePending;
        private long captureSessionId;
        private Vec3d origin = Vec3d.ZERO;
        private float yaw;
        private float pitch;
        private OtherClientPlayerEntity singleEntity;
        private ClientWorld singleWorld;
        private OtherClientPlayerEntity singleRenderPlayerEntity;
        private ClientWorld singleRenderPlayerWorld;
        private SimpleFramebuffer singleRenderFramebuffer;
        private int captureWidth = DEFAULT_SINGLE_WIDTH;
        private int captureHeight = DEFAULT_SINGLE_HEIGHT;
        private double captureFov = DEFAULT_SINGLE_FOV;
        private volatile ReferenceTransform referenceTransform;
        private volatile double downscaleFactor = MIN_DOWNSCALE_FACTOR;
        private volatile DownscaleInterpolation downscaleInterpolation = DownscaleInterpolation.BICUBIC;
        private volatile SingleCompressionMode compressionMode = SingleCompressionMode.OFF;
        private volatile int jpegCompressionAmount = DEFAULT_JPEG_COMPRESSION_AMOUNT;
        private volatile int videoCompressionAmount = DEFAULT_VIDEO_COMPRESSION_AMOUNT;
        private volatile int oldYoutubeCompressionAmount = DEFAULT_OLD_YOUTUBE_COMPRESSION_AMOUNT;
        private volatile boolean renderPlayerEnabled;
        private volatile byte[] latestImageBytes;
        private volatile long latestImageTimestamp;
        private final VideoCompressionFilter videoCompressionFilter = new VideoCompressionFilter();
        private final ExecutorService encodeExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "panshot-single-encode");
            thread.setDaemon(true);
            return thread;
        });
        private final AtomicBoolean encodeInFlight = new AtomicBoolean(false);
        private long lastSkippedEncodeMessageTick = Long.MIN_VALUE;

        private void tick(MinecraftClient client) {
            if (!running) {
                return;
            }
            tickCounter++;

            if (client.player == null || client.world == null) {
                stopInternal(client, false, "Single preview stopped because no world is loaded.");
                return;
            }

            if (singleEntity == null || singleWorld != client.world) {
                ensureSingleEntity(client.world);
            }

            if (capturePending || tickCounter < nextCaptureTick) {
                return;
            }

            try {
                long sessionId = captureSessionId;
                capturePending = true;
                nextCaptureTick = tickCounter + intervalTicks;
                captureSingleFrame(client, sessionId);
            } catch (Exception exception) {
                stopInternal(client, false, null);
                send(client, "Single preview capture failed: " + exception.getMessage());
            }
        }

        private int startAtPlayer(MinecraftClient client, double intervalSeconds) {
            if (client.player == null) {
                send(client, "Join a world first.");
                return 0;
            }

            Vec3d eyePos = getStandingPlayerEyePos(client.player);
            return startAt(client, intervalSeconds, eyePos.x, eyePos.y, eyePos.z, client.player.getYaw(), client.player.getPitch());
        }

        private int startAt(MinecraftClient client, double intervalSeconds, double x, double y, double z, float yaw, float pitch) {
            return startAt(client, intervalSeconds, x, y, z, yaw, pitch, null);
        }

        private int startAt(
            MinecraftClient client,
            double intervalSeconds,
            double x,
            double y,
            double z,
            float yaw,
            float pitch,
            ReferenceTransform importedReferenceTransform
        ) {
            if (client.player == null || client.world == null) {
                send(client, "Join a world first.");
                return 0;
            }

            PANORAMA_CONTROLLER.stop(client, false);
            origin = new Vec3d(x, y, z);
            this.yaw = yaw;
            this.pitch = clampPitch(pitch);
            referenceTransform = importedReferenceTransform;
            intervalTicks = Math.max(1L, Math.round(intervalSeconds * 20.0));
            captureSessionId++;
            encodeExecutor.execute(videoCompressionFilter::reset);
            running = true;
            completedCaptures = 0;
            capturePending = false;
            nextCaptureTick = tickCounter;
            ensureSingleEntity(client.world);
            ensureFramebuffers(captureWidth, captureHeight);

            try {
                String viewerUrl = SINGLE_WEB_SERVER.ensureStarted(this);
                sendViewerLink(client, viewerUrl);
            } catch (IOException exception) {
                send(client, "Single viewer failed to start: " + exception.getMessage());
            }

            send(client, String.format(
                Locale.ROOT,
                "Single preview started at %.3f %.3f %.3f every %.2f seconds (yaw %.1f, pitch %.1f, %dx%d, fov %s, downscale %s, compression %s, renderplayer %s).",
                x,
                y,
                z,
                intervalTicks / 20.0,
                this.yaw,
                this.pitch,
                captureWidth,
                captureHeight,
                formatFov(captureFov),
                describeDownscale(),
                describeCompression(),
                renderPlayerEnabled ? "on" : "off"
            ));
            return 1;
        }

        private int importPerspectiveState(MinecraftClient client, PerspectiveReverserState state) {
            captureWidth = clampDimension(state.screenWidth());
            captureHeight = clampDimension(state.screenHeight());
            double importedFov = state.cameraFov();
            if (!isRenderableFov(importedFov)) {
                send(client, "PerspectiveReverser single FOV must be a finite positive number.");
                return 0;
            }
            captureFov = importedFov;
            ReferenceTransform importedReferenceTransform = state.toReferenceTransform(captureWidth, captureHeight);

            int result = startAt(
                client,
                Math.max(0.1, state.singleIntervalSeconds()),
                state.cameraX(),
                state.cameraY(),
                state.cameraZ(),
                (float)state.cameraYaw(),
                (float)state.cameraPitch(),
                importedReferenceTransform
            );
            if (result > 0) {
                send(client, String.format(
                    Locale.ROOT,
                    "Imported PerspectiveReverser single setup from clipboard (%dx%d, fov %s, reference frame %dx%d).",
                    captureWidth,
                    captureHeight,
                    formatFov(captureFov),
                    importedReferenceTransform.screenWidth(),
                    importedReferenceTransform.screenHeight()
                ));
            }
            return result;
        }

        private int stop(MinecraftClient client, boolean notify) {
            if (!running) {
                if (notify) {
                    send(client, "Single preview is not running.");
                }
                return 0;
            }

            stopInternal(client, notify, null);
            return 1;
        }

        private int status(MinecraftClient client) {
            if (!running) {
                send(client, "Single preview is not running.");
                return 1;
            }

            double seconds = Math.max(0.0, (nextCaptureTick - tickCounter) / 20.0);
            send(client, String.format(
                Locale.ROOT,
                "Running: next frame in %.2f seconds from %.3f %.3f %.3f (yaw %.1f, pitch %.1f, frames %d, %dx%d, fov %s, downscale %s, compression %s, renderplayer %s).",
                seconds,
                origin.x,
                origin.y,
                origin.z,
                yaw,
                pitch,
                completedCaptures,
                captureWidth,
                captureHeight,
                formatFov(captureFov),
                describeDownscale(),
                describeCompression(),
                renderPlayerEnabled ? "on" : "off"
            ));
            return 1;
        }

        private int resolutionStatus(MinecraftClient client) {
            send(client, String.format(
                Locale.ROOT,
                "Single preview resolution is %dx%d.",
                captureWidth,
                captureHeight
            ));
            return 1;
        }

        private int setResolution(MinecraftClient client, int width, int height) {
            captureWidth = clampDimension(width);
            captureHeight = clampDimension(height);
            referenceTransform = null;
            send(client, String.format(
                Locale.ROOT,
                "Single preview resolution set to %dx%d.",
                captureWidth,
                captureHeight
            ));
            return 1;
        }

        private int fovStatus(MinecraftClient client) {
            send(client, String.format(Locale.ROOT, "Single preview FOV is %s.", formatFov(captureFov)));
            return 1;
        }

        private int setFov(MinecraftClient client, double fov) {
            if (!isRenderableFov(fov)) {
                send(client, "Single preview FOV must be a finite positive number.");
                return 0;
            }
            captureFov = fov;
            send(client, String.format(Locale.ROOT, "Single preview FOV set to %s.", formatFov(captureFov)));
            return 1;
        }

        private int downscaleStatus(MinecraftClient client) {
            send(client, "Single downscale is " + describeDownscale() + ".");
            return 1;
        }

        private int disableDownscale(MinecraftClient client) {
            downscaleFactor = MIN_DOWNSCALE_FACTOR;
            send(client, "Single downscale disabled.");
            return 1;
        }

        private int setDownscale(MinecraftClient client, double factor, String stageToken, String interpolationToken) {
            DownscaleInterpolation resolvedInterpolation = downscaleInterpolation;
            if (stageToken != null && SingleDownscaleStage.parse(stageToken) == null) {
                DownscaleInterpolation interpolationAlias = interpolationToken == null ? DownscaleInterpolation.parse(stageToken) : null;
                if (interpolationAlias != null) {
                    resolvedInterpolation = interpolationAlias;
                } else {
                    send(client, "Unknown downscale stage '" + stageToken + "'. Use: image, faces, or cubemap.");
                    return 0;
                }
            }

            if (interpolationToken != null) {
                resolvedInterpolation = DownscaleInterpolation.parse(interpolationToken);
                if (resolvedInterpolation == null) {
                    send(client, "Unknown interpolation '" + interpolationToken + "'. Use: nearest, bilinear, bicubic, cubic, box, supersample.");
                    return 0;
                }
            }

            downscaleFactor = Math.max(MIN_DOWNSCALE_FACTOR, factor);
            downscaleInterpolation = resolvedInterpolation;
            send(client, "Single downscale set to " + describeDownscale() + ".");
            return 1;
        }

        private String describeDownscale() {
            double factor = downscaleFactor;
            if (factor <= MIN_DOWNSCALE_FACTOR) {
                return "off";
            }

            return String.format(
                Locale.ROOT,
                "%.2fx (%s)",
                factor,
                downscaleInterpolation.label
            );
        }

        private int enableJpegCompression(MinecraftClient client) {
            compressionMode = SingleCompressionMode.JPEG;
            encodeExecutor.execute(videoCompressionFilter::reset);
            send(client, "Single compression enabled: " + describeCompression() + ". Applies to the next captured frame.");
            return 1;
        }

        private int setJpegCompression(MinecraftClient client, int compressionAmount) {
            jpegCompressionAmount = compressionAmount;
            compressionMode = SingleCompressionMode.JPEG;
            encodeExecutor.execute(videoCompressionFilter::reset);
            send(client, "Single compression set to " + describeCompression() + ". Applies to the next captured frame.");
            return 1;
        }

        private int enableVideoCompression(MinecraftClient client) {
            compressionMode = SingleCompressionMode.VIDEO;
            encodeExecutor.execute(videoCompressionFilter::reset);
            send(client, "Single compression enabled: " + describeCompression() + ". Applies to the next captured frame.");
            return 1;
        }

        private int setVideoCompression(MinecraftClient client, int compressionAmount) {
            videoCompressionAmount = compressionAmount;
            compressionMode = SingleCompressionMode.VIDEO;
            encodeExecutor.execute(videoCompressionFilter::reset);
            send(client, "Single compression set to " + describeCompression() + ". Applies to the next captured frame.");
            return 1;
        }

        private int enableOldYoutubeCompression(MinecraftClient client) {
            compressionMode = SingleCompressionMode.OLD_YOUTUBE;
            encodeExecutor.execute(videoCompressionFilter::reset);
            send(client, "Single compression enabled: " + describeCompression() + ". Applies to the next captured frame.");
            return 1;
        }

        private int setOldYoutubeCompression(MinecraftClient client, int compressionAmount) {
            oldYoutubeCompressionAmount = compressionAmount;
            compressionMode = SingleCompressionMode.OLD_YOUTUBE;
            encodeExecutor.execute(videoCompressionFilter::reset);
            send(client, "Single compression set to " + describeCompression() + ". Applies to the next captured frame.");
            return 1;
        }

        private int disableCompression(MinecraftClient client) {
            compressionMode = SingleCompressionMode.OFF;
            encodeExecutor.execute(videoCompressionFilter::reset);
            send(client, "Single compression disabled; using lossless PNG. Applies to the next captured frame.");
            return 1;
        }

        private String describeCompression() {
            return switch (compressionMode) {
                case OFF -> "off (lossless PNG)";
                case JPEG -> String.format(
                    Locale.ROOT,
                    "JPEG %d%% (quality %d%%)",
                    jpegCompressionAmount,
                    100 - jpegCompressionAmount
                );
                case VIDEO -> String.format(
                    Locale.ROOT,
                    "video %d%% (stateful low-bitrate emulation)",
                    videoCompressionAmount
                );
                case OLD_YOUTUBE -> String.format(
                    Locale.ROOT,
                    "YouTube %d%% (old 360p-style emulation)",
                    oldYoutubeCompressionAmount
                );
            };
        }

        private int renderPlayerStatus(MinecraftClient client) {
            send(client, "Single renderplayer is " + (renderPlayerEnabled ? "on" : "off") + ".");
            return 1;
        }

        private int setRenderPlayerEnabled(MinecraftClient client, boolean enabled) {
            renderPlayerEnabled = enabled;
            send(client, "Single renderplayer " + (enabled ? "enabled" : "disabled") + ".");
            return 1;
        }

        private void captureSingleFrame(MinecraftClient client, long sessionId) {
            try (RenderContext context = beginSingleRender(client)) {
                renderSingleFrame(client, sessionId);
            }
        }

        private RenderContext beginSingleRender(MinecraftClient client) {
            int width = captureWidth;
            int height = captureHeight;
            double targetFov = captureFov;
            float targetFovScale = fovScaleFor(targetFov);
            ensureFramebuffers(width, height);

            MinecraftClientAccessor clientAccessor = (MinecraftClientAccessor)client;
            GameRendererAccessor gameRendererAccessor = (GameRendererAccessor)client.gameRenderer;
            Framebuffer mainFramebuffer = clientAccessor.spectatorcam$getFramebuffer();
            Entity previousCameraEntity = client.getCameraEntity();
            Perspective previousPerspective = client.options.getPerspective();
            int previousFov = client.options.getFov().getValue();
            boolean previousRenderBlockOutline = gameRendererAccessor.spectatorcam$isRenderBlockOutline();
            float previousFovScale = gameRendererAccessor.spectatorcam$getFovScale();
            float previousOldFovScale = gameRendererAccessor.spectatorcam$getOldFovScale();
            boolean previousPanoramaMode = client.gameRenderer.isRenderingPanorama();
            boolean previousHudHidden = client.options.hudHidden;
            Window window = client.getWindow();
            WindowAccessor windowAccessor = (WindowAccessor)(Object)window;
            int previousWindowWidth = window.getWidth();
            int previousWindowHeight = window.getHeight();
            int previousFramebufferWidth = window.getFramebufferWidth();
            int previousFramebufferHeight = window.getFramebufferHeight();
            ClientWorld renderPlayerWorld = null;
            boolean renderPlayerAdded = false;

            windowAccessor.spectatorcam$setWidth(width);
            windowAccessor.spectatorcam$setHeight(height);
            window.setFramebufferWidth(width);
            window.setFramebufferHeight(height);

            clientAccessor.spectatorcam$setFramebuffer(singleRenderFramebuffer);

            client.setCameraEntity(singleEntity);
            client.options.setPerspective(Perspective.FIRST_PERSON);
            client.options.getFov().setValue(VANILLA_SINGLE_FOV_BASE);
            client.options.hudHidden = !renderPlayerEnabled;
            gameRendererAccessor.spectatorcam$setRenderBlockOutline(false);
            gameRendererAccessor.spectatorcam$setFovScale(targetFovScale);
            gameRendererAccessor.spectatorcam$setOldFovScale(targetFovScale);
            client.gameRenderer.setRenderingPanorama(false);
            if (renderPlayerEnabled && client.player != null && client.world != null) {
                removeEntityIfPresent(client.world, SINGLE_RENDER_PLAYER_ENTITY_ID);
                OtherClientPlayerEntity renderPlayer = ensureRenderPlayerEntity(client.world, client.player);
                syncRenderPlayerEntityState(client.player, renderPlayer);
                client.world.addEntity(renderPlayer);
                renderPlayerWorld = client.world;
                renderPlayerAdded = true;
            }

            return new RenderContext(
                client,
                clientAccessor,
                gameRendererAccessor,
                mainFramebuffer,
                previousCameraEntity,
                previousPerspective,
                previousFov,
                previousRenderBlockOutline,
                previousFovScale,
                previousOldFovScale,
                previousPanoramaMode,
                previousHudHidden,
                window,
                windowAccessor,
                previousWindowWidth,
                previousWindowHeight,
                previousFramebufferWidth,
                previousFramebufferHeight,
                renderPlayerWorld,
                renderPlayerAdded
            );
        }

        private void renderSingleFrame(MinecraftClient client, long sessionId) {
            positionSingleEntity(yaw, pitch);
            client.gameRenderer.renderWorld(RenderTickCounter.ONE);
            takeScreenshotAsyncFast(client, singleRenderFramebuffer, image -> onSingleFrameCaptured(client, sessionId, image));
        }

        private void onSingleFrameCaptured(MinecraftClient client, long sessionId, NativeImage image) {
            if (sessionId != captureSessionId || !running) {
                image.close();
                return;
            }

            completedCaptures++;
            capturePending = false;
            submitEncodeJob(client, image);
        }

        private final class RenderContext implements AutoCloseable {
            private final MinecraftClient client;
            private final MinecraftClientAccessor clientAccessor;
            private final GameRendererAccessor gameRendererAccessor;
            private final Framebuffer mainFramebuffer;
            private final Entity previousCameraEntity;
            private final Perspective previousPerspective;
            private final int previousFov;
            private final boolean previousRenderBlockOutline;
            private final float previousFovScale;
            private final float previousOldFovScale;
            private final boolean previousPanoramaMode;
            private final boolean previousHudHidden;
            private final Window window;
            private final WindowAccessor windowAccessor;
            private final int previousWindowWidth;
            private final int previousWindowHeight;
            private final int previousFramebufferWidth;
            private final int previousFramebufferHeight;
            private final ClientWorld renderPlayerWorld;
            private final boolean renderPlayerAdded;

            private RenderContext(
                MinecraftClient client,
                MinecraftClientAccessor clientAccessor,
                GameRendererAccessor gameRendererAccessor,
                Framebuffer mainFramebuffer,
                Entity previousCameraEntity,
                Perspective previousPerspective,
                int previousFov,
                boolean previousRenderBlockOutline,
                float previousFovScale,
                float previousOldFovScale,
                boolean previousPanoramaMode,
                boolean previousHudHidden,
                Window window,
                WindowAccessor windowAccessor,
                int previousWindowWidth,
                int previousWindowHeight,
                int previousFramebufferWidth,
                int previousFramebufferHeight,
                ClientWorld renderPlayerWorld,
                boolean renderPlayerAdded
            ) {
                this.client = client;
                this.clientAccessor = clientAccessor;
                this.gameRendererAccessor = gameRendererAccessor;
                this.mainFramebuffer = mainFramebuffer;
                this.previousCameraEntity = previousCameraEntity;
                this.previousPerspective = previousPerspective;
                this.previousFov = previousFov;
                this.previousRenderBlockOutline = previousRenderBlockOutline;
                this.previousFovScale = previousFovScale;
                this.previousOldFovScale = previousOldFovScale;
                this.previousPanoramaMode = previousPanoramaMode;
                this.previousHudHidden = previousHudHidden;
                this.window = window;
                this.windowAccessor = windowAccessor;
                this.previousWindowWidth = previousWindowWidth;
                this.previousWindowHeight = previousWindowHeight;
                this.previousFramebufferWidth = previousFramebufferWidth;
                this.previousFramebufferHeight = previousFramebufferHeight;
                this.renderPlayerWorld = renderPlayerWorld;
                this.renderPlayerAdded = renderPlayerAdded;
            }

            @Override
            public void close() {
                client.gameRenderer.setRenderingPanorama(previousPanoramaMode);
                windowAccessor.spectatorcam$setWidth(previousWindowWidth);
                windowAccessor.spectatorcam$setHeight(previousWindowHeight);
                window.setFramebufferWidth(previousFramebufferWidth);
                window.setFramebufferHeight(previousFramebufferHeight);
                client.options.getFov().setValue(previousFov);
                gameRendererAccessor.spectatorcam$setRenderBlockOutline(previousRenderBlockOutline);
                gameRendererAccessor.spectatorcam$setFovScale(previousFovScale);
                gameRendererAccessor.spectatorcam$setOldFovScale(previousOldFovScale);
                client.options.hudHidden = previousHudHidden;
                client.options.setPerspective(previousPerspective);
                if (previousCameraEntity != null) {
                    client.setCameraEntity(previousCameraEntity);
                } else if (client.player != null) {
                    client.setCameraEntity(client.player);
                } else {
                    client.setCameraEntity(null);
                }
                clientAccessor.spectatorcam$setFramebuffer(mainFramebuffer);
                if (renderPlayerAdded && renderPlayerWorld != null) {
                    removeEntityIfPresent(renderPlayerWorld, SINGLE_RENDER_PLAYER_ENTITY_ID);
                }
            }
        }

        private void submitEncodeJob(MinecraftClient client, NativeImage image) {
            if (!encodeInFlight.compareAndSet(false, true)) {
                image.close();
                if (tickCounter - lastSkippedEncodeMessageTick >= 100L) {
                    lastSkippedEncodeMessageTick = tickCounter;
                    send(client, "Skipped one single-frame update to keep frame time stable.");
                }
                return;
            }

            SingleCompressionMode capturedCompressionMode = compressionMode;
            int capturedCompressionAmount = switch (capturedCompressionMode) {
                case VIDEO -> videoCompressionAmount;
                case OLD_YOUTUBE -> oldYoutubeCompressionAmount;
                default -> jpegCompressionAmount;
            };
            encodeExecutor.execute(() -> {
                try (NativeImage capturedImage = image) {
                    latestImageBytes = encodeSingleFrameBytes(
                        capturedImage,
                        capturedCompressionMode,
                        capturedCompressionAmount
                    );
                    latestImageTimestamp = System.currentTimeMillis();
                } catch (Exception exception) {
                    boolean statefulCompressionDisabled = (
                        capturedCompressionMode == SingleCompressionMode.VIDEO
                            || capturedCompressionMode == SingleCompressionMode.OLD_YOUTUBE
                    ) && compressionMode == capturedCompressionMode;
                    if (statefulCompressionDisabled) {
                        compressionMode = SingleCompressionMode.OFF;
                    }
                    client.execute(() -> send(
                        client,
                        "Single preview encode failed: "
                            + exception.getMessage()
                            + (statefulCompressionDisabled ? " Stateful compression was disabled." : "")
                    ));
                } finally {
                    encodeInFlight.set(false);
                }
            });
        }

        private byte[] encodeSingleFrameBytes(
            NativeImage image,
            SingleCompressionMode capturedCompressionMode,
            int compressionAmount
        ) throws IOException {
            BufferedImage source = toBufferedImage(image);
            BufferedImage output = source;
            try {
                double factor = downscaleFactor;
                if (factor > MIN_DOWNSCALE_FACTOR) {
                    int targetWidth = scaledDimension(source.getWidth(), factor);
                    int targetHeight = scaledDimension(source.getHeight(), factor);
                    output = resizeBufferedImage(source, targetWidth, targetHeight, downscaleInterpolation);
                }
                return switch (capturedCompressionMode) {
                    case OFF -> {
                        videoCompressionFilter.reset();
                        yield encodePngBytes(output);
                    }
                    case JPEG -> {
                        videoCompressionFilter.reset();
                        yield ImageEncoder.encodeJpeg(output, compressionAmount);
                    }
                    case VIDEO -> ImageEncoder.encodeJpeg(
                        videoCompressionFilter.apply(output, compressionAmount),
                        VIDEO_TRANSPORT_JPEG_COMPRESSION_AMOUNT
                    );
                    case OLD_YOUTUBE -> ImageEncoder.encodeJpeg(
                        videoCompressionFilter.applyOldYoutube(output, compressionAmount),
                        VideoCompressionFilter.oldYoutubeJpegCompressionAmount(compressionAmount)
                    );
                };
            } finally {
                if (output != source) {
                    output.flush();
                }
                source.flush();
            }
        }

        private float clampPitch(float value) {
            return Math.max(-90.0f, Math.min(90.0f, value));
        }

        private int clampDimension(int value) {
            return Math.max(MIN_SINGLE_DIMENSION, Math.min(MAX_SINGLE_DIMENSION, value));
        }

        private boolean isRenderableFov(double fov) {
            if (!Double.isFinite(fov) || fov <= 0.0) {
                return false;
            }

            float scale = fovScaleFor(fov);
            return Float.isFinite(scale) && scale > 0.0f;
        }

        private float fovScaleFor(double fov) {
            return (float)(fov / (double)VANILLA_SINGLE_FOV_BASE);
        }

        private String formatFov(double fov) {
            double absolute = Math.abs(fov);
            if (absolute > 0.0 && (absolute < 0.00001 || absolute >= 100000.0)) {
                return String.format(Locale.ROOT, "%.6g", fov);
            }

            String text = String.format(Locale.ROOT, "%.5f", fov);
            int end = text.length();
            while (end > 0 && text.charAt(end - 1) == '0') {
                end--;
            }
            if (end > 0 && text.charAt(end - 1) == '.') {
                end--;
            }
            return text.substring(0, end);
        }

        private void ensureSingleEntity(ClientWorld world) {
            if (singleEntity != null && singleWorld == world) {
                return;
            }

            singleEntity = new OtherClientPlayerEntity(world, new GameProfile(SINGLE_PROFILE_ID, "single_preview_camera"));
            singleEntity.noClip = true;
            singleEntity.setNoGravity(true);
            singleWorld = world;
        }

        private OtherClientPlayerEntity ensureRenderPlayerEntity(ClientWorld world, ClientPlayerEntity sourcePlayer) {
            if (singleRenderPlayerEntity != null && singleRenderPlayerWorld == world) {
                return singleRenderPlayerEntity;
            }

            singleRenderPlayerEntity = createRenderPlayerEntity(
                world,
                SINGLE_RENDER_PLAYER_PROFILE_ID,
                SINGLE_RENDER_PLAYER_ENTITY_ID,
                sourcePlayer
            );
            singleRenderPlayerWorld = world;
            return singleRenderPlayerEntity;
        }

        private void ensureFramebuffers(int width, int height) {
            if (singleRenderFramebuffer == null
                || singleRenderFramebuffer.textureWidth != width
                || singleRenderFramebuffer.textureHeight != height) {
                if (singleRenderFramebuffer != null) {
                    singleRenderFramebuffer.delete();
                }
                singleRenderFramebuffer = new SimpleFramebuffer("panshot-single", width, height, true);
            }
        }

        private void positionSingleEntity(float yaw, float pitch) {
            double entityY = origin.y - singleEntity.getStandingEyeHeight();
            singleEntity.refreshPositionAndAngles(origin.x, entityY, origin.z, yaw, pitch);
            singleEntity.setYaw(yaw);
            singleEntity.setPitch(pitch);
            singleEntity.lastYaw = yaw;
            singleEntity.lastPitch = pitch;
            singleEntity.lastX = origin.x;
            singleEntity.lastY = entityY;
            singleEntity.lastZ = origin.z;
            singleEntity.setVelocity(Vec3d.ZERO);
            singleEntity.setHeadYaw(yaw);
            singleEntity.setBodyYaw(yaw);
            singleEntity.lastHeadYaw = yaw;
            singleEntity.lastBodyYaw = yaw;
        }

        private void stopInternal(MinecraftClient client, boolean notify, String reason) {
            captureSessionId++;
            running = false;
            intervalTicks = 0L;
            nextCaptureTick = 0L;
            completedCaptures = 0;
            capturePending = false;
            singleEntity = null;
            singleWorld = null;
            singleRenderPlayerEntity = null;
            singleRenderPlayerWorld = null;

            if (singleRenderFramebuffer != null) {
                singleRenderFramebuffer.delete();
                singleRenderFramebuffer = null;
            }
            encodeExecutor.execute(videoCompressionFilter::reset);

            if (reason != null) {
                send(client, reason);
            } else if (notify) {
                send(client, "Single preview stopped.");
            }
        }

        private void send(MinecraftClient client, String message) {
            if (client.player != null) {
                client.player.sendMessage(Text.literal(MESSAGE_PREFIX + message), false);
            }
        }

        private void sendViewerLink(MinecraftClient client, String url) {
            if (client.player != null) {
                Text link = Text.literal(url).styled(style -> style
                    .withUnderline(true)
                    .withClickEvent(createOpenUrlClickEvent(url)));
                client.player.sendMessage(Text.literal(MESSAGE_PREFIX + "Single viewer: ").append(link), false);
            }
        }

        private boolean isCameraEntity(Entity entity) {
            return entity == singleEntity;
        }

        @Override
        public boolean isSingleRunning() {
            return running;
        }

        @Override
        public byte[] getLatestImageBytes() {
            return latestImageBytes;
        }

        @Override
        public long getLatestImageTimestamp() {
            return latestImageTimestamp;
        }

        @Override
        public String getReferenceTransformJson() {
            ReferenceTransform transform = referenceTransform;
            return transform == null ? null : transform.toJson();
        }
    }
}
