package com.panshot.spectatorcam;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
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
import net.minecraft.text.ClickEvent;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

public final class SpectatorCamClient implements ClientModInitializer {
    private static final String MESSAGE_PREFIX = "[PanShot] ";
    private static final double DEFAULT_PANORAMA_INTERVAL_SECONDS = 10.0;
    private static final UUID CAMERA_PROFILE_ID = UUID.fromString("f0d6643c-af19-4e1e-948d-a5d2d7e2f27b");
    private static final PanoramaWebServer PANORAMA_WEB_SERVER = new PanoramaWebServer();
    private static final PanoramaCaptureController PANORAMA_CONTROLLER = new PanoramaCaptureController();
    private static final SpectatorCameraController CAMERA_CONTROLLER = new SpectatorCameraController();

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            CAMERA_CONTROLLER.tick(client);
            PANORAMA_CONTROLLER.tick(client);
        });
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> registerCommands(dispatcher));
    }

    private static void registerCommands(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(buildRootCommand("panshot"));
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> buildRootCommand(String root) {
        RequiredArgumentBuilder<FabricClientCommandSource, Double> startPitch =
            argument("pitch", DoubleArgumentType.doubleArg(-90.0, 90.0))
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
            argument("yaw", DoubleArgumentType.doubleArg()).then(startPitch);
        RequiredArgumentBuilder<FabricClientCommandSource, Double> startZ =
            argument("z", DoubleArgumentType.doubleArg()).then(startYaw);
        RequiredArgumentBuilder<FabricClientCommandSource, Double> startY =
            argument("y", DoubleArgumentType.doubleArg()).then(startZ);
        RequiredArgumentBuilder<FabricClientCommandSource, Double> startX =
            argument("x", DoubleArgumentType.doubleArg()).then(startY);

        RequiredArgumentBuilder<FabricClientCommandSource, Double> everyPitch =
            argument("pitch", DoubleArgumentType.doubleArg(-90.0, 90.0))
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
            argument("yaw", DoubleArgumentType.doubleArg()).then(everyPitch);
        RequiredArgumentBuilder<FabricClientCommandSource, Double> everyZ =
            argument("z", DoubleArgumentType.doubleArg()).then(everyYaw);
        RequiredArgumentBuilder<FabricClientCommandSource, Double> everyY =
            argument("y", DoubleArgumentType.doubleArg()).then(everyZ);
        RequiredArgumentBuilder<FabricClientCommandSource, Double> everyX =
            argument("x", DoubleArgumentType.doubleArg()).then(everyY);
        RequiredArgumentBuilder<FabricClientCommandSource, Double> everyInterval =
            argument("intervalSeconds", DoubleArgumentType.doubleArg(0.1))
                .executes(context -> PANORAMA_CONTROLLER.startAtPlayer(
                    context.getSource().getClient(),
                    DoubleArgumentType.getDouble(context, "intervalSeconds")
                ))
                .then(everyX);

        LiteralArgumentBuilder<FabricClientCommandSource> panoramaStart = literal("start")
            .executes(context -> PANORAMA_CONTROLLER.startAtPlayer(
                context.getSource().getClient(),
                DEFAULT_PANORAMA_INTERVAL_SECONDS
            ))
            .then(startX)
            .then(literal("every").then(everyInterval));

        return literal(root)
            .executes(context -> CAMERA_CONTROLLER.toggle(context.getSource().getClient()))
            .then(literal("where").executes(context -> CAMERA_CONTROLLER.printPosition(context.getSource().getClient())))
            .then(literal("panorama")
                .then(panoramaStart)
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
                    .then(literal("off").executes(context -> PANORAMA_CONTROLLER.setExportEnabled(context.getSource().getClient(), false)))))
            .then(literal("tp")
                .then(argument("x", DoubleArgumentType.doubleArg())
                    .then(argument("y", DoubleArgumentType.doubleArg())
                        .then(argument("z", DoubleArgumentType.doubleArg())
                            .executes(context -> CAMERA_CONTROLLER.teleport(
                                context.getSource().getClient(),
                                        DoubleArgumentType.getDouble(context, "x"),
                                        DoubleArgumentType.getDouble(context, "y"),
                                DoubleArgumentType.getDouble(context, "z")
                            ))))));
    }

    private static final class SpectatorCameraController {
        private boolean enabled;
        private OtherClientPlayerEntity cameraEntity;
        private ClientWorld cameraWorld;

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
            send(client, "Enabled. Teleport with /panshot tp <x> <y> <z>.");
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
            teleportInternal(player.getX(), player.getY(), player.getZ(), player.getYaw(), player.getPitch());
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
        private static final int PANORAMA_RESOLUTION = 1024;
        private static final int CLEAR_COLOR_AND_DEPTH = 0x4100;
        private static final int[] CUBEMAP_LAYOUT = {
            3, 1, 4,
            5, 0, 2
        };
        private static final String CUBEMAP_FILE_NAME = "panorama_cubemap.png";

        private volatile boolean running;
        private long tickCounter;
        private long intervalTicks;
        private long nextCycleTick;
        private int completedCycles;
        private int activeFaceIndex = -1;
        private Vec3d origin = Vec3d.ZERO;
        private float baseYaw;
        private float basePitch;
        private OtherClientPlayerEntity panoramaEntity;
        private ClientWorld panoramaWorld;
        private OtherClientPlayerEntity panoramaRenderPlayerEntity;
        private ClientWorld panoramaRenderPlayerWorld;
        private SimpleFramebuffer panoramaRenderFramebuffer;
        private final NativeImage[] capturedFaces = new NativeImage[6];
        private volatile byte[] latestCubemapBytes;
        private volatile long latestCubemapTimestamp;
        private volatile boolean exportToDisk;
        private volatile boolean preciseCaptureMode;
        private volatile boolean renderPlayerEnabled;
        private final ExecutorService stitchExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "panshot-stitch");
            thread.setDaemon(true);
            return thread;
        });
        private final AtomicBoolean stitchInFlight = new AtomicBoolean(false);
        private long lastSkippedStitchMessageTick = Long.MIN_VALUE;

        private void tick(MinecraftClient client) {
            tickCounter++;
            if (!running) {
                return;
            }

            if (client.player == null || client.world == null) {
                stopInternal(client, false, "Panorama capture stopped because no world is loaded.");
                return;
            }

            if (panoramaEntity == null || panoramaWorld != client.world) {
                ensurePanoramaEntity(client.world);
            }

            if (tickCounter < nextCycleTick) {
                return;
            }

            try {
                if (preciseCaptureMode) {
                    capturePanoramaCycle(client);
                    completedCycles++;
                    nextCycleTick = tickCounter + intervalTicks;
                    submitStitchJob(client, detachCapturedFaces(), completedCycles);
                } else {
                    if (activeFaceIndex < 0) {
                        activeFaceIndex = 0;
                    }
                    capturePanoramaFace(client, activeFaceIndex);
                    activeFaceIndex++;
                    if (activeFaceIndex >= 6) {
                        activeFaceIndex = -1;
                        completedCycles++;
                        nextCycleTick = tickCounter + intervalTicks;
                        submitStitchJob(client, detachCapturedFaces(), completedCycles);
                    }
                }
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

            Vec3d eyePos = client.player.getEyePos();
            return startAt(client, intervalSeconds, eyePos.x, eyePos.y, eyePos.z, client.player.getYaw(), 0.0f);
        }

        private int startAt(MinecraftClient client, double intervalSeconds, double x, double y, double z, float yaw, float pitch) {
            if (client.player == null || client.world == null) {
                send(client, "Join a world first.");
                return 0;
            }

            origin = new Vec3d(x, y, z);
            baseYaw = yaw;
            basePitch = clampPitch(pitch);
            intervalTicks = Math.max(1L, Math.round(intervalSeconds * 20.0));
            running = true;
            completedCycles = 0;
            activeFaceIndex = -1;
            clearCapturedFaces();
            nextCycleTick = tickCounter;
            ensurePanoramaEntity(client.world);
            ensureFramebuffers(client);

            try {
                String viewerUrl = PANORAMA_WEB_SERVER.ensureStarted(this);
                sendViewerLink(client, viewerUrl);
            } catch (IOException exception) {
                send(client, "Panorama viewer failed to start: " + exception.getMessage());
            }

            send(client, String.format(
                Locale.ROOT,
                "Panorama capture started at %.3f %.3f %.3f every %.2f seconds (yaw %.1f, pitch %.1f, mode %s).",
                x,
                y,
                z,
                intervalTicks / 20.0,
                baseYaw,
                basePitch,
                preciseCaptureMode ? "precise" : "smooth"
            ));
            return 1;
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

            if (!preciseCaptureMode && activeFaceIndex >= 0) {
                send(client, String.format(
                    Locale.ROOT,
                    "Running: capturing face %d/6 at %.3f %.3f %.3f (yaw %.1f, pitch %.1f, mode %s).",
                    activeFaceIndex + 1,
                    origin.x,
                    origin.y,
                    origin.z,
                    baseYaw,
                    basePitch,
                    preciseCaptureMode ? "precise" : "smooth"
                ));
                return 1;
            }

            double seconds = Math.max(0.0, (nextCycleTick - tickCounter) / 20.0);
            send(client, String.format(
                Locale.ROOT,
                "Running: next cycle in %.2f seconds from %.3f %.3f %.3f (yaw %.1f, pitch %.1f, mode %s).",
                seconds,
                origin.x,
                origin.y,
                origin.z,
                baseYaw,
                basePitch,
                preciseCaptureMode ? "precise" : "smooth"
            ));
            return 1;
        }

        private void capturePanoramaFace(MinecraftClient client, int index) {
            try (RenderContext context = beginPanoramaRender(client)) {
                renderPanoramaFace(client, index);
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
            send(client, "Panorama renderplayer " + (enabled ? "enabled" : "disabled") + ".");
            return 1;
        }

        private void capturePanoramaCycle(MinecraftClient client) {
            try (RenderContext context = beginPanoramaRender(client)) {
                for (int index = 0; index < 6; index++) {
                    renderPanoramaFace(client, index);
                }
            }
        }

        private RenderContext beginPanoramaRender(MinecraftClient client) {
            ensureFramebuffers(client);

            MinecraftClientAccessor clientAccessor = (MinecraftClientAccessor)client;
            Framebuffer mainFramebuffer = clientAccessor.spectatorcam$getFramebuffer();
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

            windowAccessor.spectatorcam$setWidth(PANORAMA_RESOLUTION);
            windowAccessor.spectatorcam$setHeight(PANORAMA_RESOLUTION);
            window.setFramebufferWidth(PANORAMA_RESOLUTION);
            window.setFramebufferHeight(PANORAMA_RESOLUTION);

            clientAccessor.spectatorcam$setFramebuffer(panoramaRenderFramebuffer);
            panoramaRenderFramebuffer.beginWrite(true);
            RenderSystem.viewport(0, 0, PANORAMA_RESOLUTION, PANORAMA_RESOLUTION);

            client.setCameraEntity(panoramaEntity);
            client.options.setPerspective(Perspective.FIRST_PERSON);
            client.options.getFov().setValue(90);
            client.gameRenderer.setRenderingPanorama(true);
            client.worldRenderer.scheduleTerrainUpdate();
            if (renderPlayerEnabled && client.player != null && client.world != null) {
                OtherClientPlayerEntity renderPlayer = ensureRenderPlayerEntity(client.world, client.player);
                syncRenderPlayerEntity(client.player, renderPlayer);
                client.world.addEntity(renderPlayer);
                renderPlayerWorld = client.world;
                renderPlayerAdded = true;
            }

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
        }

        private void renderPanoramaFace(MinecraftClient client, int index) {
            positionPanoramaEntity(yawForIndex(index), pitchForIndex(index));
            RenderSystem.clearColor(0.0f, 0.0f, 0.0f, 0.0f);
            RenderSystem.clear(CLEAR_COLOR_AND_DEPTH, MinecraftClient.IS_SYSTEM_MAC);
            client.gameRenderer.renderWorld(RenderTickCounter.ONE);

            NativeImage face = ScreenshotRecorder.takeScreenshot(panoramaRenderFramebuffer);
            if (capturedFaces[index] != null) {
                capturedFaces[index].close();
            }
            capturedFaces[index] = face;
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
                client.worldRenderer.scheduleTerrainUpdate();
                if (renderPlayerAdded && renderPlayerWorld != null) {
                    renderPlayerWorld.removeEntity(PANORAMA_RENDER_PLAYER_ENTITY_ID, Entity.RemovalReason.DISCARDED);
                }
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
                mainFramebuffer.beginWrite(true);
                RenderSystem.viewport(0, 0, mainFramebuffer.textureWidth, mainFramebuffer.textureHeight);
            }
        }

        private void submitStitchJob(MinecraftClient client, NativeImage[] faces, int cycleNumber) {
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

                    Path finalExportPath = exportPath;
                    client.execute(() -> {
                        if (finalExportPath == null) {
                            send(client, String.format(Locale.ROOT, "Panorama cycle %d captured.", cycleNumber));
                        } else {
                            send(client, String.format(
                                Locale.ROOT,
                                "Panorama cycle %d captured (%s).",
                                cycleNumber,
                                finalExportPath.getFileName()
                            ));
                        }
                    });
                } catch (Exception exception) {
                    client.execute(() -> send(client, "Panorama stitch failed: " + exception.getMessage()));
                } finally {
                    closeFaces(faces);
                    stitchInFlight.set(false);
                }
            });
        }

        private byte[] stitchCubemapBytes(NativeImage[] faces) throws IOException {
            try (NativeImage stitched = new NativeImage(PANORAMA_RESOLUTION * 3, PANORAMA_RESOLUTION * 2, false)) {
                for (int row = 0; row < 2; row++) {
                    for (int col = 0; col < 3; col++) {
                        int faceIndex = CUBEMAP_LAYOUT[row * 3 + col];
                        faces[faceIndex].copyRect(
                            stitched,
                            0,
                            0,
                            col * PANORAMA_RESOLUTION,
                            row * PANORAMA_RESOLUTION,
                            PANORAMA_RESOLUTION,
                            PANORAMA_RESOLUTION,
                            false,
                            false
                        );
                    }
                }
                return stitched.getBytes();
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

            GameProfile sourceProfile = sourcePlayer.getGameProfile();
            GameProfile renderProfile = new GameProfile(PANORAMA_RENDER_PLAYER_PROFILE_ID, sourceProfile.getName());
            renderProfile.getProperties().putAll(sourceProfile.getProperties());
            panoramaRenderPlayerEntity = new OtherClientPlayerEntity(world, renderProfile);
            panoramaRenderPlayerEntity.setId(PANORAMA_RENDER_PLAYER_ENTITY_ID);
            panoramaRenderPlayerWorld = world;
            return panoramaRenderPlayerEntity;
        }

        private void syncRenderPlayerEntity(ClientPlayerEntity source, OtherClientPlayerEntity target) {
            target.refreshPositionAndAngles(source.getX(), source.getY(), source.getZ(), source.getYaw(), source.getPitch());
            target.setYaw(source.getYaw());
            target.setPitch(source.getPitch());
            target.prevYaw = source.prevYaw;
            target.prevPitch = source.prevPitch;
            target.prevX = source.prevX;
            target.prevY = source.prevY;
            target.prevZ = source.prevZ;
            target.setVelocity(source.getVelocity());
            target.setOnGround(source.isOnGround());
            target.setSneaking(source.isSneaking());
            target.setSprinting(source.isSprinting());
            target.setSwimming(source.isSwimming());
            target.setPose(source.getPose());
            target.setHeadYaw(source.getHeadYaw());
            target.setBodyYaw(source.getBodyYaw());
            target.prevHeadYaw = source.prevHeadYaw;
            target.prevBodyYaw = source.prevBodyYaw;
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

        private void ensureFramebuffers(MinecraftClient client) {
            if (panoramaRenderFramebuffer == null
                || panoramaRenderFramebuffer.textureWidth != PANORAMA_RESOLUTION
                || panoramaRenderFramebuffer.textureHeight != PANORAMA_RESOLUTION) {
                if (panoramaRenderFramebuffer != null) {
                    panoramaRenderFramebuffer.delete();
                }
                panoramaRenderFramebuffer = new SimpleFramebuffer(PANORAMA_RESOLUTION, PANORAMA_RESOLUTION, true, MinecraftClient.IS_SYSTEM_MAC);
                panoramaRenderFramebuffer.setClearColor(0.0f, 0.0f, 0.0f, 0.0f);
            }
        }

        private void positionPanoramaEntity(float yaw, float pitch) {
            // `origin.y` is treated as camera eye Y; convert to entity base Y for vanilla camera math.
            double entityY = origin.y - panoramaEntity.getStandingEyeHeight();
            panoramaEntity.refreshPositionAndAngles(origin.x, entityY, origin.z, yaw, pitch);
            panoramaEntity.setYaw(yaw);
            panoramaEntity.setPitch(pitch);
            panoramaEntity.prevYaw = yaw;
            panoramaEntity.prevPitch = pitch;
            panoramaEntity.prevX = origin.x;
            panoramaEntity.prevY = entityY;
            panoramaEntity.prevZ = origin.z;
            panoramaEntity.setVelocity(Vec3d.ZERO);
            panoramaEntity.setHeadYaw(yaw);
            panoramaEntity.setBodyYaw(yaw);
            panoramaEntity.prevHeadYaw = yaw;
            panoramaEntity.prevBodyYaw = yaw;
        }

        private void stopInternal(MinecraftClient client, boolean notify, String reason) {
            running = false;
            intervalTicks = 0L;
            nextCycleTick = 0L;
            completedCycles = 0;
            activeFaceIndex = -1;
            clearCapturedFaces();
            panoramaEntity = null;
            panoramaWorld = null;
            panoramaRenderPlayerEntity = null;
            panoramaRenderPlayerWorld = null;

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
                    .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, url)));
                client.player.sendMessage(Text.literal(MESSAGE_PREFIX + "Panorama viewer: ").append(link), false);
            }
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
                if (faces[i] != null) {
                    faces[i].close();
                    faces[i] = null;
                }
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
}
