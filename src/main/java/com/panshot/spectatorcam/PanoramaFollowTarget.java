package com.panshot.spectatorcam;

import net.minecraft.util.math.Vec3d;

record PanoramaFollowTarget(double yaw, double pitch) {
    private static final double MIN_DISTANCE_SQUARED = 1.0E-8;

    static PanoramaFollowTarget from(
        Vec3d cameraPosition,
        float cameraYaw,
        float cameraPitch,
        Vec3d playerPosition
    ) {
        double x = playerPosition.x - cameraPosition.x;
        double y = playerPosition.y - cameraPosition.y;
        double z = playerPosition.z - cameraPosition.z;
        double horizontalDistance = Math.hypot(x, z);
        if (x * x + y * y + z * z < MIN_DISTANCE_SQUARED) {
            return null;
        }

        double targetYaw = Math.toDegrees(Math.atan2(-x, z));
        double relativeYaw = wrapDegrees(cameraYaw - targetYaw + 180.0);
        double relativePitch = clamp(
            Math.toDegrees(Math.atan2(y, horizontalDistance)) + cameraPitch,
            -90.0,
            90.0
        );
        return new PanoramaFollowTarget(relativeYaw, relativePitch);
    }

    String toJson() {
        return "{\"yaw\":" + Double.toString(yaw) + ",\"pitch\":" + Double.toString(pitch) + "}";
    }

    private static double wrapDegrees(double value) {
        double wrapped = value % 360.0;
        if (wrapped >= 180.0) {
            wrapped -= 360.0;
        } else if (wrapped < -180.0) {
            wrapped += 360.0;
        }
        return wrapped;
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
