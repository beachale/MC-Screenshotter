package com.panshot.spectatorcam.mixin;

import com.panshot.spectatorcam.SpectatorCamClient;
import net.minecraft.client.render.Camera;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityPose;
import net.minecraft.world.BlockView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Camera.class)
public abstract class CameraMixin {
    @Shadow
    private float cameraY;

    @Shadow
    private float lastCameraY;

    @Unique
    private boolean spectatorcam$restoreCameraHeight;

    @Unique
    private float spectatorcam$previousCameraY;

    @Unique
    private float spectatorcam$previousLastCameraY;

    @Inject(method = "update", at = @At("HEAD"))
    private void spectatorcam$useStandingCameraHeight(
        BlockView area,
        Entity focusedEntity,
        boolean thirdPerson,
        boolean inverseView,
        float tickProgress,
        CallbackInfo callbackInfo
    ) {
        if (SpectatorCamClient.isPanShotCameraEntity(focusedEntity)) {
            spectatorcam$restoreCameraHeight = true;
            spectatorcam$previousCameraY = cameraY;
            spectatorcam$previousLastCameraY = lastCameraY;
            float eyeHeight = focusedEntity.getEyeHeight(EntityPose.STANDING);
            cameraY = eyeHeight;
            lastCameraY = eyeHeight;
        }
    }

    @Inject(method = "update", at = @At("RETURN"))
    private void spectatorcam$restoreCameraHeight(
        BlockView area,
        Entity focusedEntity,
        boolean thirdPerson,
        boolean inverseView,
        float tickProgress,
        CallbackInfo callbackInfo
    ) {
        if (spectatorcam$restoreCameraHeight) {
            cameraY = spectatorcam$previousCameraY;
            lastCameraY = spectatorcam$previousLastCameraY;
            spectatorcam$restoreCameraHeight = false;
        }
    }
}
