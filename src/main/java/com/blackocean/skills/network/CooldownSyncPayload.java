package com.blackocean.skills.network;

import com.blackocean.skills.SkillsMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * 서버 → 시전자 본인: 스킬 하나의 쿨타임 알림.
 * 클라이언트는 이걸로 스킬 칸의 쿨타임 표시를 맞추고, 쿨타임 중에는 가짜 준비 연출을 시작하지 않는다.
 *
 * @param skill          Skill 번호 (-1 = 모든 스킬)
 * @param remainingTicks 남은 틱 (0 = 바로 사용 가능)
 * @param totalTicks     전체 쿨타임 틱 (표시 비율용)
 * @param denied         true 면 방금 요청이 거절됨 → 미리 시작한 준비 연출을 즉시 지움
 */
public record CooldownSyncPayload(int skill, int remainingTicks, int totalTicks, boolean denied) implements CustomPacketPayload {
    public static final Type<CooldownSyncPayload> TYPE = new Type<>(SkillsMod.id("skill_cooldown"));
    public static final StreamCodec<FriendlyByteBuf, CooldownSyncPayload> CODEC = StreamCodec.<FriendlyByteBuf, CooldownSyncPayload>of(
            (buf, p) -> {
                buf.writeVarInt(p.skill() + 1);
                buf.writeVarInt(p.remainingTicks());
                buf.writeVarInt(p.totalTicks());
                buf.writeBoolean(p.denied());
            },
            buf -> new CooldownSyncPayload(buf.readVarInt() - 1, buf.readVarInt(), buf.readVarInt(), buf.readBoolean()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
