package com.blackocean.skills.network;

import com.blackocean.skills.Skill;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

/**
 * 연출 신호 종류와 보내는 도우미 (서버).
 * <pre>
 * 종류        pos             vec            size        size2         duration    target
 * IMPACT     맞은 곳          맞은 면 방향    폭발 반지름   1=허공에서 끝  투사체 id     맞은 생물
 * PIERCE     꿰뚫은 곳        날아가는 방향   -           -             투사체 id     맞은 생물
 * ERUPT      기둥 바닥         -             반지름        높이           유지 틱      -
 * BURST      발밑 바닥         -             반지름        -             -           -
 * BOLT       번개 시작         번개 끝        굵기(1=보통)  -             -           맞은 생물
 * BLINK      출발(몸 가운데)   도착(몸 가운데) 범위         -             -           -
 * STRIKE     낙뢰 떨어진 바닥  -              범위         1=거대        -           -
 * STORM      폭풍 중심 바닥    -              반지름        -             유지 틱      -
 * DASH       출발             방향           범위          거리          돌진 틱      -
 * TORNADO    중심 바닥         -             반지름        높이           유지 틱      -
 * JET        -                -             길이          굵기           분사 틱      -
 * PRISON     중심 바닥         -             반지름        -             유지 틱      -
 * WAVE       출발 바닥         방향           폭 절반       거리           진행 틱      -
 * DRAGON     출발             도착(바닥)      충돌 반지름   소용돌이 틱     비행 틱      -
 * FIELD      중심 바닥         -             반지름        -             유지 틱      -
 * ULT_CAST   시전자 위치        -             -            -             -           -
 * TRANSFORM  먹은 사람 위치     -             -            -             -           능력 번호
 * </pre>
 */
public final class SkillEvents {
    public static final int IMPACT = 0, PIERCE = 1, ERUPT = 2, BURST = 3, BOLT = 4, BLINK = 5, STRIKE = 6, STORM = 7,
            DASH = 8, TORNADO = 9, JET = 10, PRISON = 11, WAVE = 12, DRAGON = 13, FIELD = 14, ULT_CAST = 15, TRANSFORM = 16;

    private SkillEvents() {}

    public static void send(ServerLevel level, @Nullable Skill skill, int type, @Nullable Entity caster, Vec3 pos,
                            Vec3 vec, float size, float size2, int duration, int target, double radius) {
        PacketDistributor.sendToPlayersNear(level, null, pos.x, pos.y, pos.z, radius,
                new SkillEventPayload(skill == null ? -1 : skill.ordinal(), type, caster == null ? -1 : caster.getId(),
                        pos.x, pos.y, pos.z, (float) vec.x, (float) vec.y, (float) vec.z, size, size2, duration, target));
    }

    public static void send(ServerLevel level, Skill skill, int type, @Nullable Entity caster, Vec3 pos, Vec3 vec,
                            float size, float size2, int duration, int target) {
        send(level, skill, type, caster, pos, vec, size, size2, duration, target, skill.ultimate() ? 192.0 : 128.0);
    }
}
