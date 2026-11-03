package com.blackocean.skills.fire;

import com.blackocean.skills.SkillsConfig;
import com.blackocean.skills.SkillsRegistry;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/** 불꽃 탄환 한 발. 판정 방식은 화염 주먹과 같고 수치만 작다. */
public class FireBulletProjectile extends FireFistProjectile {
    public FireBulletProjectile(EntityType<? extends FireBulletProjectile> type, Level level) {
        super(type, level);
    }

    public static FireBulletProjectile createBullet(ServerLevel level, ServerPlayer owner, Vec3 pos, Vec3 dir) {
        return setup(new FireBulletProjectile(SkillsRegistry.FIRE_BULLET.get(), level), owner, pos, dir);
    }

    @Override protected double speed() { return SkillsConfig.BULLETS_SPEED.get(); }
    @Override protected double range() { return SkillsConfig.BULLETS_RANGE.get(); }
    @Override protected double hitRadius() { return 0.45; }
    @Override protected double directDamage() { return SkillsConfig.BULLETS_DAMAGE.get(); }
    @Override protected double splashDamage() { return SkillsConfig.BULLETS_SPLASH_DAMAGE.get(); }
    @Override protected double splashRadius() { return SkillsConfig.BULLETS_SPLASH_RADIUS.get(); }
    @Override protected double knockback() { return 0.3; }
    @Override protected float terrainPower() { return 1.0f; }
    @Override protected SoundEvent impactSound() { return SkillsRegistry.FIRE_BULLETS_IMPACT.get(); }
    @Override protected float impactVolume() { return 2.0f; }
    @Override public int fxKind() { return 1; }
}
