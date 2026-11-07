package com.blackocean.skills.client.fx;

import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.phys.Vec3;

/**
 * 여러 스킬이 겹칠 때 소리·번쩍임이 쌓이지 않게 하는 공용 제한.
 * <ul>
 *   <li>소리: 같은 소리는 3칸 안에서 2틱에 한 번 (서버 SkillSounds 와 같음), 전체 한 틱에 6개까지</li>
 *   <li>화면 번쩍임: 한 틱에 더해지는 밝기 합을 제한 (ScreenFx 가 씀)</li>
 * </ul>
 */
public final class FxLimits {
    private static final int SLOTS = 32;
    private static final SoundEvent[] SND = new SoundEvent[SLOTS];
    private static final double[] SX = new double[SLOTS], SY = new double[SLOTS], SZ = new double[SLOTS];
    private static final long[] ST = new long[SLOTS];
    private static int next;
    private static long tick = Long.MIN_VALUE;
    private static int soundsThisTick;
    private static float flashThisTick;

    private FxLimits() {}

    private static void roll(long now) {
        if (now != tick) {
            tick = now;
            soundsThisTick = 0;
            flashThisTick = 0f;
        }
    }

    public static boolean allowSound(long now, SoundEvent s, Vec3 p) {
        roll(now);
        if (soundsThisTick >= 6) return false;
        for (int i = 0; i < SLOTS; i++) {
            // 서버 SkillSounds 와 같은 규칙: 같은 소리가 3칸 안에서 2틱(같은 틱·다음 틱) 안에 또 나면 하나로
            if (SND[i] == s && now >= ST[i] && now - ST[i] < 2) {
                double dx = SX[i] - p.x, dy = SY[i] - p.y, dz = SZ[i] - p.z;
                if (dx * dx + dy * dy + dz * dz < 9.0) return false;
            }
        }
        SND[next] = s;
        SX[next] = p.x;
        SY[next] = p.y;
        SZ[next] = p.z;
        ST[next] = now;
        next = (next + 1) % SLOTS;
        soundsThisTick++;
        return true;
    }

    /**
     * 화면 번쩍임 세기를 받아서 이번 틱에 허락된 만큼만 돌려줌 (한 틱 합계 최대 0.6).
     */
    public static float allowFlash(long now, float strength) {
        roll(now);
        float room = Math.max(0f, 0.6f - flashThisTick);
        float ok = Math.min(room, Math.max(0f, strength));
        flashThisTick += ok;
        return ok;
    }
}
