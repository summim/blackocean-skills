package com.blackocean.skills;

import com.blackocean.skills.fire.FireFistProjectile;
import net.minecraft.core.registries.Registries;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class SkillsRegistry {
    public static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(Registries.ENTITY_TYPE, SkillsMod.MODID);
    public static final DeferredRegister<SoundEvent> SOUNDS = DeferredRegister.create(Registries.SOUND_EVENT, SkillsMod.MODID);

    /** 화염 주먹 투사체. 눈에 보이는 모양은 클라이언트 연출(FxManager)이 그리고, 엔티티 자체는 판정만 한다. */
    public static final DeferredHolder<EntityType<?>, EntityType<FireFistProjectile>> FIRE_FIST = ENTITIES.register("fire_fist",
            () -> EntityType.Builder.<FireFistProjectile>of(FireFistProjectile::new, MobCategory.MISC)
                    .sized(1.4f, 1.4f)
                    .clientTrackingRange(10)
                    .updateInterval(1)
                    .noSave()
                    .fireImmune()
                    .build("fire_fist"));

    public static final DeferredHolder<SoundEvent, SoundEvent> FIRE_FIST_CHARGE = sound("fire_fist.charge");
    public static final DeferredHolder<SoundEvent, SoundEvent> FIRE_FIST_LAUNCH = sound("fire_fist.launch");
    public static final DeferredHolder<SoundEvent, SoundEvent> FIRE_FIST_TRAVEL = sound("fire_fist.travel");
    public static final DeferredHolder<SoundEvent, SoundEvent> FIRE_FIST_IMPACT = sound("fire_fist.impact");
    public static final DeferredHolder<SoundEvent, SoundEvent> FIRE_FIST_CRACKLE = sound("fire_fist.crackle");

    private static DeferredHolder<SoundEvent, SoundEvent> sound(String name) {
        return SOUNDS.register(name, () -> SoundEvent.createVariableRangeEvent(SkillsMod.id(name)));
    }

    private SkillsRegistry() {}
}
