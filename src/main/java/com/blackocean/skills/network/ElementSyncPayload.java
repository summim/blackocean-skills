package com.blackocean.skills.network;

import com.blackocean.skills.SkillsMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** 서버 → 본인: 내 능력 (-1 = 없음). 스킬 칸 아이콘과 1~5 키가 이걸로 바뀐다. */
public record ElementSyncPayload(int element) implements CustomPacketPayload {
    public static final Type<ElementSyncPayload> TYPE = new Type<>(SkillsMod.id("element_sync"));
    public static final StreamCodec<FriendlyByteBuf, ElementSyncPayload> CODEC = StreamCodec.<FriendlyByteBuf, ElementSyncPayload>of(
            (buf, p) -> buf.writeVarInt(p.element() + 1),
            buf -> new ElementSyncPayload(buf.readVarInt() - 1));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
