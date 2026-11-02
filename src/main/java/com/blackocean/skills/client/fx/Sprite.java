package com.blackocean.skills.client.fx;

import net.minecraft.world.phys.Vec3;

/** 가벼운 연출용 입자 하나 (게임 파티클 시스템과 별개, 서버와 통신 없음). */
final class Sprite {
    enum Kind { FLAME, GLOW, SPARK, EMBER, SMOKE }

    final Kind kind;
    double x, y, z, px, py, pz;
    double vx, vy, vz;
    float size0, size1;
    int age, life;
    float rot;
    float r = 1, g = 1, b = 1, alpha = 1;
    float gravity;        // 양수 = 떨어짐, 음수 = 떠오름
    float drag = 1f;      // 매 틱 속도에 곱함
    float sway;           // 좌우 흔들림 (불씨)
    int frameOffset;

    Sprite(Kind kind, Vec3 p, Vec3 v, float size0, float size1, int life) {
        this.kind = kind;
        this.x = this.px = p.x;
        this.y = this.py = p.y;
        this.z = this.pz = p.z;
        this.vx = v.x;
        this.vy = v.y;
        this.vz = v.z;
        this.size0 = size0;
        this.size1 = size1;
        this.life = Math.max(1, life);
    }

    Sprite color(float r, float g, float b, float a) {
        this.r = r; this.g = g; this.b = b; this.alpha = a;
        return this;
    }

    Sprite physics(float gravity, float drag) {
        this.gravity = gravity; this.drag = drag;
        return this;
    }

    /** @return true 면 수명이 끝나서 지워야 함 */
    boolean tick() {
        px = x; py = y; pz = z;
        age++;
        vy -= gravity;
        vx *= drag; vy *= drag; vz *= drag;
        if (sway != 0) {
            vx += Math.sin(age * 0.45 + frameOffset) * sway;
            vz += Math.cos(age * 0.37 + frameOffset) * sway;
        }
        x += vx; y += vy; z += vz;
        return age >= life;
    }

    Vec3 pos(float pt) {
        return new Vec3(px + (x - px) * pt, py + (y - py) * pt, pz + (z - pz) * pt);
    }

    Vec3 vel() {
        return new Vec3(vx, vy, vz);
    }

    /** 0 → 1 수명 진행도 */
    float t(float pt) {
        return Math.min(1f, (age + pt) / life);
    }

    float size(float pt) {
        float t = t(pt);
        return size0 + (size1 - size0) * t;
    }

    /** 빠르게 나타났다가 천천히 사라지는 밝기 곡선. */
    static float fadeInOut(float t, float in) {
        return t < in ? t / in : 1f - (t - in) / (1f - in);
    }
}
