package com.alexmcclellan.betapt.render;

import net.minecraft.entity.mob.MobEntity;

/**
 * Camera position and direction, matching what Beta's own renderer uses.
 *
 * Beta (and every Minecraft since) uses: +X east, +Y up, +Z south. Yaw 0 looks
 * south (+Z), yaw 90 looks west (-X). Pitch is positive looking down. Position and
 * angles are blended between the last two game ticks using tickDelta, exactly like
 * GameRenderer.applyCameraTransform, so motion stays smooth at high frame rates.
 */
public final class Camera {
    public double x, y, z;
    public float fx, fy, fz; // forward
    public float rx, ry, rz; // right
    public float ux, uy, uz; // up
    public float tanHalfFov = Float.NaN; // known when read from Beta's matrices

    /**
     * The camera exactly as Beta set it up this frame, read back from OpenGL's matrices
     * right after GameRenderer.renderWorld. That includes view bobbing, the hurt tilt and
     * third-person (F5) view, so the path-traced world lines up with everything Beta still
     * draws (entities, particles, hand).
     *
     * The modelview matrix maps "world position minus the camera entity's position" to eye
     * space: eye = R * p + t. Its rows are the camera's right, up and backward directions,
     * and the eye itself sits at -R^T t.
     */
    public static Camera fromGl(MobEntity e, float tickDelta) {
        java.nio.FloatBuffer mv = org.lwjgl.BufferUtils.createFloatBuffer(16), pr = org.lwjgl.BufferUtils.createFloatBuffer(16);
        org.lwjgl.opengl.GL11.glGetFloat(org.lwjgl.opengl.GL11.GL_MODELVIEW_MATRIX, mv);
        org.lwjgl.opengl.GL11.glGetFloat(org.lwjgl.opengl.GL11.GL_PROJECTION_MATRIX, pr);
        float[] m = new float[16];
        mv.get(m);
        // column-major: element (row r, column c) is m[c * 4 + r]
        double[] r0 = {m[0], m[4], m[8]}, r1 = {m[1], m[5], m[9]}, r2 = {m[2], m[6], m[10]};
        double tx = m[12], ty = m[13], tz = m[14];
        Camera c = new Camera();
        double ex = -(r0[0] * tx + r1[0] * ty + r2[0] * tz);
        double ey = -(r0[1] * tx + r1[1] * ty + r2[1] * tz);
        double ez = -(r0[2] * tx + r1[2] * ty + r2[2] * tz);
        c.x = e.prevX + (e.x - e.prevX) * tickDelta + ex;
        c.y = e.prevY + (e.y - e.prevY) * tickDelta + ey;
        c.z = e.prevZ + (e.z - e.prevZ) * tickDelta + ez;
        double l0 = Math.sqrt(r0[0] * r0[0] + r0[1] * r0[1] + r0[2] * r0[2]);
        double l1 = Math.sqrt(r1[0] * r1[0] + r1[1] * r1[1] + r1[2] * r1[2]);
        double l2 = Math.sqrt(r2[0] * r2[0] + r2[1] * r2[1] + r2[2] * r2[2]);
        c.rx = (float) (r0[0] / l0); c.ry = (float) (r0[1] / l0); c.rz = (float) (r0[2] / l0);
        c.ux = (float) (r1[0] / l1); c.uy = (float) (r1[1] / l1); c.uz = (float) (r1[2] / l1);
        c.fx = (float) (-r2[0] / l2); c.fy = (float) (-r2[1] / l2); c.fz = (float) (-r2[2] / l2);
        c.tanHalfFov = 1f / pr.get(5);
        EntityCapture.setView(m, e.prevX + (e.x - e.prevX) * tickDelta, e.prevY + (e.y - e.prevY) * tickDelta,
                e.prevZ + (e.z - e.prevZ) * tickDelta);
        projZ[0] = pr.get(10); projZ[1] = pr.get(14); projZ[2] = pr.get(11); projZ[3] = pr.get(15);
        return c;
    }

    /** The projection rows that turn eye-space z into depth: (P22, P23, P32, P33). */
    public static final float[] projZ = {0, 0, 0, 0};

    public static Camera from(MobEntity e, float tickDelta) {
        Camera c = new Camera();
        float eyeOffset = e.eyeHeight - 1.62f; // 0 for the player, as in applyCameraTransform
        c.x = e.lastX + (e.x - e.lastX) * tickDelta;
        c.y = e.lastY + (e.y - e.lastY) * tickDelta - eyeOffset;
        c.z = e.lastZ + (e.z - e.lastZ) * tickDelta;

        c.setAngles(e.lastYaw + (e.yaw - e.lastYaw) * tickDelta, e.lastPitch + (e.pitch - e.lastPitch) * tickDelta);
        return c;
    }

    /** A camera at an exact eye position and angle (used by the autotest so every shot matches). */
    public static Camera at(double x, double y, double z, float yawDeg, float pitchDeg) {
        Camera c = new Camera();
        c.x = x; c.y = y; c.z = z;
        c.setAngles(yawDeg, pitchDeg);
        return c;
    }

    private void setAngles(float yawDeg, float pitchDeg) {
        Camera c = this;
        double yaw = Math.toRadians(yawDeg);
        double pitch = Math.toRadians(pitchDeg);
        double cp = Math.cos(pitch);
        c.fx = (float) (-Math.sin(yaw) * cp);
        c.fy = (float) (-Math.sin(pitch));
        c.fz = (float) (Math.cos(yaw) * cp);

        // right = forward x worldUp, normalized. Never degenerate since |pitch| <= 90 is clamped by Beta.
        double rx = -c.fz, rz = c.fx;
        double len = Math.sqrt(rx * rx + rz * rz);
        if (len < 1e-6) { rx = -Math.cos(yaw); rz = -Math.sin(yaw); len = 1; }
        c.rx = (float) (rx / len);
        c.ry = 0f;
        c.rz = (float) (rz / len);

        // up = right x forward
        c.ux = c.ry * c.fz - c.rz * c.fy;
        c.uy = c.rz * c.fx - c.rx * c.fz;
        c.uz = c.rx * c.fy - c.ry * c.fx;
    }
}
