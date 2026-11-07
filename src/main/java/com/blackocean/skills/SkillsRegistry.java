package com.blackocean.skills;

import com.blackocean.skills.entity.SkillProjectile;
import com.blackocean.skills.item.ElementFruitItem;
import com.mojang.serialization.Codec;
import net.minecraft.core.registries.Registries;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

import java.util.HashMap;
import java.util.Map;

public final class SkillsRegistry {
    public static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(Registries.ENTITY_TYPE, SkillsMod.MODID);
    public static final DeferredRegister<SoundEvent> SOUNDS = DeferredRegister.create(Registries.SOUND_EVENT, SkillsMod.MODID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(SkillsMod.MODID);
    public static final DeferredRegister<AttachmentType<?>> ATTACHMENTS = DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, SkillsMod.MODID);

    /** 모든 투사체 스킬 (화염 주먹·불꽃 탄환·작열의 태양·진공 참격·수탄). 모양은 클라이언트 연출이 그린다. */
    public static final DeferredHolder<EntityType<?>, EntityType<SkillProjectile>> SKILL_PROJECTILE = ENTITIES.register("skill_projectile",
            () -> EntityType.Builder.<SkillProjectile>of(SkillProjectile::new, MobCategory.MISC)
                    .sized(0.8f, 0.8f)
                    .clientTrackingRange(10)
                    .updateInterval(1)
                    .noSave()
                    .fireImmune()
                    .build("skill_projectile"));

    /** 플레이어 능력 (""=아직 안 정함 → 서버 기본값, "none"=없음). 죽어도 유지, 저장됨. */
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<String>> ELEMENT = ATTACHMENTS.register("element",
            () -> AttachmentType.builder(() -> "").serialize(Codec.STRING).copyOnDeath().build());

    /** 능력 열매 4종 */
    public static final Map<Element, DeferredItem<ElementFruitItem>> FRUITS = new HashMap<>();

    static {
        for (Element e : Element.VALUES) {
            FRUITS.put(e, ITEMS.register(e.id + "_fruit", () -> new ElementFruitItem(e, new Item.Properties()
                    .stacksTo(16)
                    .rarity(Rarity.EPIC)
                    .food(new FoodProperties.Builder().nutrition(4).saturationModifier(0.6f).alwaysEdible().build()))));
        }
    }

    // ---- 소리 ----
    private static final Map<String, DeferredHolder<SoundEvent, SoundEvent>> SOUND_MAP = new HashMap<>();

    public static final DeferredHolder<SoundEvent, SoundEvent> FIRE_FIST_CHARGE = sound("fire_fist.charge");
    public static final DeferredHolder<SoundEvent, SoundEvent> FIRE_FIST_LAUNCH = sound("fire_fist.launch");
    public static final DeferredHolder<SoundEvent, SoundEvent> FIRE_FIST_TRAVEL = sound("fire_fist.travel");
    public static final DeferredHolder<SoundEvent, SoundEvent> FIRE_FIST_IMPACT = sound("fire_fist.impact");
    public static final DeferredHolder<SoundEvent, SoundEvent> FIRE_FIST_CRACKLE = sound("fire_fist.crackle");

    static {
        for (String n : new String[]{
                "fire_bullets.shot", "fire_bullets.impact", "fire_pillar.warn", "fire_pillar.erupt", "flame_ring.charge", "flame_ring.burst",
                "fire.sun_charge", "fire.sun_impact",
                "lightning.charge", "lightning.zap", "lightning.thunder", "lightning.blink", "lightning.discharge", "lightning.storm_charge",
                "wind.charge", "wind.blade", "wind.dash", "wind.tornado", "wind.burst",
                "water.charge", "water.shot", "water.splash", "water.jet", "water.wave", "water.dragon",
                "common.ultimate", "common.transform"}) {
            sound(n);
        }
    }

    private static DeferredHolder<SoundEvent, SoundEvent> sound(String name) {
        DeferredHolder<SoundEvent, SoundEvent> h = SOUNDS.register(name, () -> SoundEvent.createVariableRangeEvent(SkillsMod.id(name)));
        SOUND_MAP.put(name, h);
        return h;
    }

    /** 이름으로 소리 ("lightning.zap" 등) */
    public static SoundEvent snd(String name) {
        DeferredHolder<SoundEvent, SoundEvent> h = SOUND_MAP.get(name);
        if (h == null) throw new IllegalArgumentException("unknown sound " + name);
        return h.get();
    }

    private SkillsRegistry() {}
}
