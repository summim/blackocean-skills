package com.blackocean.skills.client;

import com.blackocean.skills.SkillsRegistry;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;

/** 날아가는 주먹을 따라다니는 "화르르르" 반복 소리. 적중하면 짧게 줄어들며 꺼진다. */
public class FireFistTravelSound extends AbstractTickableSoundInstance {
    private static final float BASE_VOLUME = 1.4f;
    private final Entity entity;
    private boolean fading;
    private float fade = 1f;

    public FireFistTravelSound(Entity entity) {
        super(SkillsRegistry.FIRE_FIST_TRAVEL.get(), SoundSource.PLAYERS, SoundInstance.createUnseededRandom());
        this.entity = entity;
        this.looping = true;
        this.delay = 0;
        this.volume = BASE_VOLUME;
        this.x = entity.getX();
        this.y = entity.getY();
        this.z = entity.getZ();
    }

    public void fadeOut() {
        fading = true;
    }

    @Override
    public boolean canStartSilent() {
        return true;
    }

    @Override
    public void tick() {
        if (fading || entity.isRemoved()) {
            fade -= 0.34f;
            volume = BASE_VOLUME * Math.max(0f, fade);
            if (fade <= 0f) stop();
            return;
        }
        x = entity.getX();
        y = entity.getY();
        z = entity.getZ();
    }
}
