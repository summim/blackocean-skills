package com.blackocean.skills;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
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
 *
 * <p><b>겹침 방지 (모든 재생에 적용)</b>: 여러 사람이 한자리에서 동시에 스킬을 쓸 때 같은 소리가 쌓여 소음이 되지 않게
 * <ul>
 *   <li>같은 소리가 가까운 자리(기본 3칸)에서 아주 짧은 시간(기본 2틱 = 같은 틱·다음 틱) 안에 또 나면 하나로 합침
 *       — 연사 탄환처럼 2틱마다 나는 한 사람의 소리는 그대로 남음</li>
 *   <li>24칸 구역마다 한 틱에 작은 소리(반경 &lt; R_IMPACT) 6개, 전체 12개까지. 큰 폭발·궁극기는 작은 소리가 꽉 차도 들어감</li>
 * </ul>
 */
public final class SkillSounds {
    private SkillSounds() {}

    // ---- 들리는 반경 (블록) ----
    /** 지속 피해 틱·퍼지는 적중 (0.5초마다 반복 → 가장 작고 가까이만) */
    public static final float R_TICK = 20f;
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

    // ---- 타격 직전 소리 ----
    /** common.heavy_whoosh 길이 (틱): 무거운 공격이 맞기 이만큼 전에 틀면 바람 끝 = 타격 순간 */
    public static final int WHOOSH_TICKS = 6;
    /** common.ult_swell 길이 (틱): 궁극기 마지막 타격 이만큼 전에 틀면 차오름 끝 = 타격 순간 */
    public static final int SWELL_TICKS = 16;

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
        if (!allow(level, at, name, radius, 2, 3.0)) return;
        emit(level, except, at, name, radius, pitch);
    }

    private static void emit(Level level, @Nullable Player except, Vec3 at, String name, float radius, float pitch) {
        level.playSound(except, at.x, at.y, at.z, SkillsRegistry.snd(name), SoundSource.PLAYERS, vol(radius), pitch);
    }

    /**
     * 같은 소리가 같은 자리(cell 블록 안)에서 gapTicks 안에 또 나면 건너뜀 (파편 7발이 한꺼번에 벽에 맞는 경우 등).
     * @return 실제로 틀었으면 true
     */
    public static boolean playLimited(ServerLevel level, Vec3 at, String name, float radius, float pitch, int gapTicks, double cell) {
        if (!allow(level, at, name, radius, Math.max(1, gapTicks), cell)) return false;
        emit(level, null, at, name, radius, pitch);
        return true;
    }

    /**
     * 지속 피해·퍼지는 판정의 "맞은 순간" 소리: 작은 반경(R_TICK), 같은 소리는 cell 칸 안에서 gapTicks 에 한 번.
     * 한 틱에 여러 명이 맞아도 한 번만 나게 해서 쌓이지 않음.
     */
    public static void tick(ServerLevel level, Vec3 at, String name, float pitch, int gapTicks, double cell) {
        playLimited(level, at, name, R_TICK, pitch, gapTicks, cell);
    }

    // ---- 겹침 방지 ----
    private static final int SLOTS = 256;
    private static final String[] R_NAME = new String[SLOTS];
    @SuppressWarnings("unchecked")
    private static final ResourceKey<Level>[] R_DIM = new ResourceKey[SLOTS];
    private static final double[] RX = new double[SLOTS], RY = new double[SLOTS], RZ = new double[SLOTS];
    private static final long[] RT = new long[SLOTS];
    private static final int[] R_GAP = new int[SLOTS];
    private static int next;

    /** 이번 틱 구역별 개수: [작은 소리, 전체] */
    private static final Map<Long, int[]> BUDGET = new HashMap<>();
    private static long budgetTick = Long.MIN_VALUE;
    static final int MINOR_PER_AREA = 6, ALL_PER_AREA = 12;
    static final double AREA = 24.0;

    private static long now(Level level) {
        MinecraftServer s = level.getServer();
        return s != null ? s.getTickCount() : level.getGameTime();
    }

    private static boolean allow(Level level, Vec3 at, String name, float radius, int gapTicks, double cell) {
        if (level.isClientSide()) return true;
        long now = now(level);
        ResourceKey<Level> dim = level.dimension();
        double c2 = cell * cell;
        for (int i = 0; i < SLOTS; i++) {
            String n = R_NAME[i];
            if (n == null || RT[i] > now || now - RT[i] >= Math.max(gapTicks, R_GAP[i])) continue;
            if (R_DIM[i] != dim || !n.equals(name)) continue;
            double dx = RX[i] - at.x, dy = RY[i] - at.y, dz = RZ[i] - at.z;
            if (dx * dx + dy * dy + dz * dz < c2) return false;
        }
        if (now != budgetTick) {
            budgetTick = now;
            BUDGET.clear();
        }
        long key = ((long) Math.floorDiv((int) Math.floor(at.x), (int) AREA) << 32)
                ^ (Math.floorDiv((int) Math.floor(at.z), (int) AREA) & 0xffffffffL) ^ ((long) dim.location().hashCode() << 20);
        int[] b = BUDGET.computeIfAbsent(key, k -> new int[2]);
        boolean minor = radius < R_IMPACT;
        if (b[1] >= ALL_PER_AREA || (minor && b[0] >= MINOR_PER_AREA)) return false;
        if (minor) b[0]++;
        b[1]++;
        R_NAME[next] = name;
        R_DIM[next] = dim;
        RX[next] = at.x;
        RY[next] = at.y;
        RZ[next] = at.z;
        RT[next] = now;
        R_GAP[next] = gapTicks;
        next = (next + 1) % SLOTS;
        return true;
    }

    /** 서버가 멈출 때 */
    public static void clear() {
        java.util.Arrays.fill(R_NAME, null);
        java.util.Arrays.fill(R_DIM, null);
        BUDGET.clear();
        budgetTick = Long.MIN_VALUE;
    }
}
