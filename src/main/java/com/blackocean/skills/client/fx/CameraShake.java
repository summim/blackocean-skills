package com.blackocean.skills.client.fx;

import com.blackocean.skills.client.SkillsClientConfig;
import net.neoforged.neoforge.client.event.ViewportEvent;

/** 가까운 폭발에서 화면을 짧게 흔든다 (클라이언트 설정에서 끌 수 있음). */
public final class CameraShake {
    private static float amp;
    private static int ticks;

    private CameraShake() {}

    static void add(float degrees) {
        amp = Math.max(amp, degrees);
    }

    static void tick() {
        ticks++;
        amp *= 0.78f;
        if (amp < 0.02f) amp = 0f;
    }

    static void clear() {
        amp = 0f;
    }

    public static void onCameraAngles(ViewportEvent.ComputeCameraAngles event) {
        if (amp <= 0f || !SkillsClientConfig.CAMERA_SHAKE.get()) return;
        double t = ticks + event.getPartialTick();
        event.setPitch(event.getPitch() + (float) (Math.sin(t * 2.9) * amp * 0.6));
        event.setYaw(event.getYaw() + (float) (Math.sin(t * 3.7 + 1.3) * amp * 0.5));
        event.setRoll(event.getRoll() + (float) (Math.sin(t * 4.3 + 2.1) * amp * 0.4));
    }
}
