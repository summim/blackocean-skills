package com.blackocean.skills.client.fx;

import com.blackocean.skills.client.SkillsClientConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ViewportEvent;

/**
 * 화면 흔들림 (클라이언트 설정에서 끄거나 세기를 바꿀 수 있음).
 * <ul>
 *   <li>흔들림: 부드러운 노이즈(값 노이즈 2겹)로 위아래·좌우·기울기를 흔들고, 매 틱 줄어든다.
 *       여러 폭발이 겹치면 가장 센 것 + 나머지 조금.</li>
 *   <li>밀림(kick): 폭발 반대쪽으로 시점이 한 번 밀렸다가 용수철처럼 돌아옴.</li>
 * </ul>
 */
public final class CameraShake {
    /** 흔들림 최대 각도 (도) — 아무리 겹쳐도 이 이상은 안 흔들림 */
    private static final float MAX_AMP = 7f;
    private static final float MAX_KICK = 6f;

    private static float amp, prevAmp;
    private static int ticks;
    /** 밀림 용수철 (도, 도/틱) */
    private static float kickP, kickY, velP, velY, prevKickP, prevKickY;

    private CameraShake() {}

    /** 흔들기 (도). 이미 흔들리는 중이면 센 쪽 + 약한 쪽의 일부 */
    static void add(float degrees) {
        if (!(degrees > 0f)) return;
        float hi = Math.max(amp, degrees), lo = Math.min(amp, degrees);
        amp = Math.min(MAX_AMP, hi + lo * 0.25f);
    }

    /**
     * source 에서 일어난 흔들림: 내 위치가 radius 안이면 거리에 따라 줄어듦 (가까울수록 셈).
     * @param degrees 바로 옆에서의 세기 (도)
     */
    public static void addAt(Vec3 source, float degrees, float radius) {
        Entity me = Minecraft.getInstance().player;
        if (me == null || radius <= 0f) return;
        double d = me.position().distanceTo(source);
        if (d >= radius) return;
        float f = (float) (1.0 - d / radius);
        add(degrees * f * f * (3f - 2f * f) * 0.6f + degrees * f * 0.4f);
    }

    /** 시점을 한 번 밀어 냄 (도). 양수 pitch = 아래로, 양수 yaw = 오른쪽으로 */
    public static void kick(float pitchDeg, float yawDeg) {
        velP = clamp(velP + pitchDeg * 0.9f, -MAX_KICK, MAX_KICK);
        velY = clamp(velY + yawDeg * 0.9f, -MAX_KICK, MAX_KICK);
    }

    /** source 에서 밀려나는 느낌: 폭발 반대쪽으로 시점이 튐 (radius 밖이면 없음) */
    public static void kickFrom(Vec3 source, float degrees, float radius) {
        Minecraft mc = Minecraft.getInstance();
        Entity me = mc.player;
        if (me == null || radius <= 0f) return;
        Vec3 eye = me.getEyePosition();
        Vec3 d = eye.subtract(source);
        double dist = d.length();
        if (dist >= radius) return;
        float f = (float) (1.0 - dist / radius);
        float s = degrees * f;
        if (dist < 0.5) {
            kick(-s, 0f); // 바로 위·아래 → 위로 튐
            return;
        }
        d = d.scale(1.0 / dist);
        Vec3 look = me.getViewVector(1f);
        Vec3 right = look.cross(new Vec3(0, 1, 0));
        if (right.lengthSqr() < 1.0E-4) right = new Vec3(1, 0, 0);
        right = right.normalize();
        // 아래에서 터지면 고개가 들리고(위로 = pitch 음수), 옆에서 터지면 반대쪽으로 돌아감
        float pitch = (float) (-Math.max(0.3, d.y) * s);
        float yaw = (float) (d.dot(right) * s * 0.6);
        kick(pitch, yaw);
    }

    static void tick() {
        ticks++;
        prevAmp = amp;
        // 처음엔 빠르게, 작아질수록 천천히 줄어듦 → 끝이 부드러움
        amp = amp * 0.86f - 0.012f;
        if (amp < 0.02f) amp = 0f;

        prevKickP = kickP;
        prevKickY = kickY;
        // 용수철: 밀린 만큼 되돌아오는 힘 + 감쇠
        velP = (velP - kickP * 0.45f) * 0.6f;
        velY = (velY - kickY * 0.45f) * 0.6f;
        kickP = clamp(kickP + velP, -MAX_KICK, MAX_KICK);
        kickY = clamp(kickY + velY, -MAX_KICK, MAX_KICK);
        if (Math.abs(kickP) < 0.005f && Math.abs(velP) < 0.005f) kickP = velP = 0f;
        if (Math.abs(kickY) < 0.005f && Math.abs(velY) < 0.005f) kickY = velY = 0f;
    }

    static void clear() {
        amp = prevAmp = 0f;
        kickP = kickY = velP = velY = prevKickP = prevKickY = 0f;
    }

    /** 지금 흔들림 세기 (도). 다른 연출이 참고용으로 씀 */
    public static float intensity() {
        return amp;
    }

    public static void onCameraAngles(ViewportEvent.ComputeCameraAngles event) {
        if (amp <= 0f && prevAmp <= 0f && kickP == 0f && kickY == 0f && prevKickP == 0f && prevKickY == 0f) return;
        if (!SkillsClientConfig.CAMERA_SHAKE.get()) return;
        float strength = SkillsClientConfig.SHAKE_STRENGTH.get().floatValue();
        if (strength <= 0f) return;
        float pt = (float) event.getPartialTick();
        float a = (prevAmp + (amp - prevAmp) * pt) * strength;
        double t = ticks + pt;
        float pitch = 0f, yaw = 0f, roll = 0f;
        if (a > 0f) {
            // 센 흔들림일수록 조금 더 빨리 떨림
            double f = 0.42 + 0.05 * Math.min(4f, a);
            pitch = noise(t * f, 11) * a * 0.62f;
            yaw = noise(t * f, 47) * a * 0.5f;
            roll = noise(t * f * 0.8, 91) * a * 0.32f;
        }
        pitch += (prevKickP + (kickP - prevKickP) * pt) * strength;
        yaw += (prevKickY + (kickY - prevKickY) * pt) * strength;
        event.setPitch(event.getPitch() + pitch);
        event.setYaw(event.getYaw() + yaw);
        event.setRoll(event.getRoll() + roll);
    }

    /** 부드러운 노이즈 -1 ~ 1 (값 노이즈 2겹) */
    private static float noise(double t, int seed) {
        return valueNoise(t, seed) * 0.72f + valueNoise(t * 2.3 + 17.0, seed + 5) * 0.28f;
    }

    private static float valueNoise(double t, int seed) {
        double fl = Math.floor(t);
        int i = (int) fl;
        float f = (float) (t - fl);
        float u = f * f * (3f - 2f * f);
        float a = hash(i, seed), b = hash(i + 1, seed);
        return a + (b - a) * u;
    }

    private static float hash(int n, int seed) {
        int h = n * 374761393 + seed * 668265263;
        h = (h ^ (h >>> 13)) * 1274126177;
        h ^= h >>> 16;
        return ((h & 0xFFFF) / 32767.5f) - 1f;
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
