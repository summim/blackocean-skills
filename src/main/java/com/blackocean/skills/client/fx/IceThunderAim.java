package com.blackocean.skills.client.fx;

import com.blackocean.skills.SkillAim;
import com.blackocean.skills.client.ClientSkillStats;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * 얼음·천둥 준비 연출이 서버와 똑같은 자리·방향을 쓰게 하는 도우미.
 * <ul>
 *   <li>{@link #hand}: 서버 SkillAim.handPos 와 같은 손 위치 (= 서버가 투사체를 만드는 바로 그 자리), 고정된 조준 방향 기준</li>
 *   <li>{@link #shotDir}: 서버 SkillAim.aimDirection 과 같은 발사 방향 (손에서 조준점으로). 틱마다 한 번만 계산해 둠</li>
 * </ul>
 * 덕분에 모으는 위치 → 손에서 나가는 순간 → 날아가는 투사체가 한 줄로 이어진다.
 */
final class IceThunderAim {
    private static final Vec3 UP = new Vec3(0, 1, 0);
    /** 준비 연출마다 마지막으로 계산한 발사 방향 (틱 번호와 함께) */
    private static final Map<FxManager.ChargeFx, Vec3> DIRS = new IdentityHashMap<>();
    private static final Map<FxManager.ChargeFx, Integer> SEEN = new IdentityHashMap<>();
    private static int clock;

    private IceThunderAim() {}

    static void tick() {
        clock++;
        if (!SEEN.isEmpty()) {
            SEEN.entrySet().removeIf(en -> {
                boolean stale = clock - en.getValue() > 2;
                if (stale) DIRS.remove(en.getKey());
                return stale;
            });
        }
    }

    static void clear() {
        DIRS.clear();
        SEEN.clear();
    }

    /** 고정 조준 방향의 오른쪽 (서버 handPos 와 같은 계산) */
    static Vec3 right(Vec3 look) {
        Vec3 r = look.cross(UP);
        return r.lengthSqr() < 1.0E-4 ? new Vec3(1, 0, 0) : r.normalize();
    }

    /** 수평 방향의 오른쪽 */
    static Vec3 flatRight(Vec3 flat) {
        return new Vec3(-flat.z, 0, flat.x);
    }

    /**
     * 서버가 투사체를 만드는 손 위치 (side: + 오른쪽). 벽 앞이면 서버처럼 벽 앞으로 당김.
     * 프레임마다 불리므로 광선 검사 없이 ChargeFx 가 틱마다 잰 벽 거리(origin)를 씀.
     */
    static Vec3 hand(FxManager.ChargeFx c, float pt, double side) {
        return c.origin(pt, side, 0);
    }

    /**
     * 서버와 같은 발사 방향 (손 → 조준점). tickCharge 에서 {@link #update} 로 틱마다 갱신, 그리기에서는 저장된 값만 읽음.
     */
    static Vec3 shotDir(FxManager.ChargeFx c) {
        Vec3 d = DIRS.get(c);
        return d != null ? d : c.aimDir();
    }

    static void update(FxManager.ChargeFx c, double side) {
        Entity e = c.caster;
        Vec3 look = c.aimDir();
        Vec3 d = look;
        if (e instanceof Player p) {
            Vec3 eye = e.getEyePosition(1f);
            Vec3 start = SkillAim.handPos(e.level(), p, eye, look, side, 0);
            d = SkillAim.aimDirection(e.level(), p, eye, look, start, ClientSkillStats.range(c.skill));
        }
        DIRS.put(c, d);
        SEEN.put(c, clock);
    }

    /** 이 사람이 내 화면의 1인칭 주인공인지 (자기 몸 연출이 시야를 가리지 않게) */
    static boolean firstPersonSelf(Entity e) {
        Minecraft mc = Minecraft.getInstance();
        return e == mc.player && mc.options.getCameraType().isFirstPerson();
    }

    /** 이번 틱이 준비의 마지막 틱(= 손에서 나가는 순간)인지. FxManager 가 이 틱 뒤에 준비 연출을 지운다 (투사체 스킬) */
    static boolean releasing(FxManager.ChargeFx c) {
        return c.age >= c.duration && (c.confirmed || !c.predicted);
    }
}
