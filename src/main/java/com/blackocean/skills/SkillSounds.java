package com.blackocean.skills;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * 스킬 소리 재생 도우미 (서버).
 *
 * <p>Minecraft 의 재생 볼륨은 1 에서 잘린다: 1 보다 큰 값은 소리를 키우지 않고 <b>들리는 거리</b>만 늘린다
 * (서버 전송 범위 = 16 × 볼륨, 클라이언트 감쇠 거리 = sounds.json attenuation_distance(기본 16) × 볼륨).
 * 그래서 소리 크기는 파일 마스터링(art_source/generators/master_sounds.py)이 정하고, 여기서는 반경(블록)만 정한다.
 * 감쇠는 선형이라 반경의 절반에서 약 -6dB.
 */
public final class SkillSounds {
    private SkillSounds() {}

    // ---- 들리는 반경 (블록) ----
    /** 작은 스킬 모으기 */
    public static final float R_CHARGE = 24f;
    /** 아주 자주 나는 소리 (연사·파편·가시) */
    public static final float R_RAPID = 28f;
    /** 작은 적중 */
    public static final float R_HIT = 32f;
    /** 발사·시전 */
    public static final float R_CAST = 36f;
    /** 스킬 폭발·광역 */
    public static final float R_IMPACT = 56f;
    /** 큰 폭발 (화염 주먹·망치·해일) */
    public static final float R_BIG = 64f;
    /** 궁극기 모으기·알림·중간 단계 */
    public static final float R_ULT_CHARGE = 96f;
    /** 궁극기 최종 타격 (멀리서도 들림) */
    public static final float R_ULT = 128f;

    /** 반경 → 재생 볼륨 */
    public static float vol(float radius) {
        return radius / 16f;
    }

    /** 모두에게 */
    public static void play(Level level, Vec3 at, String name, float radius, float pitch) {
        play(level, null, at, name, radius, pitch);
    }

    /** except 를 뺀 모두에게 (본인 화면에서 이미 튼 소리) */
    public static void play(Level level, @Nullable Player except, Vec3 at, String name, float radius, float pitch) {
        level.playSound(except, at.x, at.y, at.z, SkillsRegistry.snd(name), SoundSource.PLAYERS, vol(radius), pitch);
    }

    // ---- 겹침 방지 ----
    private static final Map<String, Long> LAST = new HashMap<>();

    /**
     * 같은 소리가 같은 자리(cell 블록 칸)에서 gapTicks 안에 또 나면 건너뜀 (파편 7발이 한꺼번에 벽에 맞는 경우 등).
     * @return 실제로 틀었으면 true
     */
    public static boolean playLimited(ServerLevel level, Vec3 at, String name, float radius, float pitch, int gapTicks, double cell) {
        long now = level.getGameTime();
        String key = name + '|' + level.dimension().location() + '|'
                + (int) Math.floor(at.x / cell) + ',' + (int) Math.floor(at.y / cell) + ',' + (int) Math.floor(at.z / cell);
        Long last = LAST.get(key);
        if (last != null && now - last < gapTicks && now >= last) return false;
        if (LAST.size() > 512) LAST.values().removeIf(t -> now - t > 40 || t > now);
        LAST.put(key, now);
        play(level, null, at, name, radius, pitch);
        return true;
    }
}
