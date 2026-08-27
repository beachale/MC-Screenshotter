package com.panshot.spectatorcam;

import net.minecraft.util.math.Vec3d;

public final class PanoramaFollowTargetSelfTest {
    private static final double EPSILON = 1.0E-9;

    private PanoramaFollowTargetSelfTest() {
    }

    public static void main(String[] args) {
        Vec3d origin = Vec3d.ZERO;
        require(PanoramaFollowTarget.from(origin, 0.0f, 0.0f, origin) == null, "A coincident player must not create a target.");

        assertTarget(origin, 0.0f, 0.0f, new Vec3d(0.0, 0.0, 1.0), -180.0, 0.0);
        assertTarget(origin, 0.0f, 0.0f, new Vec3d(-1.0, 0.0, 0.0), 90.0, 0.0);
        assertTarget(origin, 0.0f, 0.0f, new Vec3d(1.0, 0.0, 0.0), -90.0, 0.0);
        assertTarget(origin, 0.0f, 0.0f, new Vec3d(0.0, 1.0, 1.0), -180.0, 45.0);
        assertTarget(origin, 90.0f, 0.0f, new Vec3d(-1.0, 0.0, 0.0), -180.0, 0.0);
        assertTarget(origin, 0.0f, 30.0f, new Vec3d(0.0, 0.0, 1.0), -180.0, 30.0);

        PanoramaFollowTarget wrapped = PanoramaFollowTarget.from(origin, 170.0f, 0.0f, direction(-170.0));
        require(wrapped != null, "Wrapped target was unavailable.");
        requireClose(wrapped.yaw(), 160.0, "Yaw did not include the cubemap viewer transform.");
        require(wrapped.toJson().contains("\"yaw\":160.0"), "Target JSON did not include yaw.");
        require(wrapped.toJson().contains("\"pitch\":0.0"), "Target JSON did not include pitch.");

        System.out.println("Panorama follow self-test passed.");
    }

    private static Vec3d direction(double yaw) {
        double radians = Math.toRadians(yaw);
        return new Vec3d(-Math.sin(radians), 0.0, Math.cos(radians));
    }

    private static void assertTarget(
        Vec3d origin,
        float cameraYaw,
        float cameraPitch,
        Vec3d player,
        double expectedYaw,
        double expectedPitch
    ) {
        PanoramaFollowTarget target = PanoramaFollowTarget.from(origin, cameraYaw, cameraPitch, player);
        require(target != null, "Expected an available follow target.");
        requireClose(target.yaw(), expectedYaw, "Unexpected follow yaw.");
        requireClose(target.pitch(), expectedPitch, "Unexpected follow pitch.");
    }

    private static void requireClose(double actual, double expected, String message) {
        if (Math.abs(actual - expected) > EPSILON) {
            throw new AssertionError(message + " Expected " + expected + " but got " + actual + ".");
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
