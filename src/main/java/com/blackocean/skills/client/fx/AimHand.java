package com.blackocean.skills.client.fx;

import com.blackocean.skills.SkillAim;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * 모으는 연출이 실제 공격이 나가는 자리와 같은 곳에 모이도록, 서버(SkillAim.handPos)와 같은 손 위치를
 * 고정된 시전 방향(ChargeFx.aimDir) 기준으로 계산한다. 눈 위치만 프레임 보간.
 * 서버 쪽 손: 바람 칼날·물 구슬·수류 분사 = 오른쪽 0.3 (SkillServer.projectile / ServerEffects.Jet).
 */
final class AimHand {
    /** 서버가 투사체·물줄기를 내보내는 손 (오른쪽) */
    static final double SIDE = 0.3;
    private static final Vec3 UP = new Vec3(0, 1, 0);

    private AimHand() {}

    /**
     * 고정된 조준 방향 기준의 손 위치 (벽 앞이면 서버처럼 벽 앞으로 당김).
     * 프레임마다 불리므로 광선 검사를 하지 않음: 벽까지 거리는 ChargeFx 가 틱마다 한 번 재 둔 값(origin)을 씀.
     */
    static Vec3 hand(FxManager.ChargeFx c, float pt, double side) {
        return c.origin(pt, side, 0);
    }

    /** 서버와 똑같은 손 위치 (광선 검사 1번 — 발사 순간 등 한 번만 쓸 것) */
    static Vec3 handExact(FxManager.ChargeFx c, float pt, double side) {
        Entity e = c.caster;
        Vec3 eye = e.getEyePosition(pt);
        Vec3 look = c.aimDir();
        if (e instanceof Player p) return SkillAim.handPos(p.level(), p, eye, look, side, 0);
        Vec3 right = look.cross(UP);
        right = right.lengthSqr() < 1.0E-4 ? new Vec3(1, 0, 0) : right.normalize();
        return eye.add(look.scale(1.6)).add(right.scale(side)).add(0, -0.3, 0);
    }

    /** 투사체가 처음 보인 틱: 직전 위치 = 서버가 만든 자리 (손) */
    static Vec3 spawnPoint(FxManager.ProjectileFx p) {
        return new Vec3(p.proj.xo, p.proj.yo, p.proj.zo);
    }
}
