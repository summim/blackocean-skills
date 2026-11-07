package com.blackocean.skills.item;

import com.blackocean.skills.Element;
import com.blackocean.skills.PlayerElements;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.ChatFormatting;

import java.util.List;

/** 능력 열매: 먹으면 그 능력을 얻는다 (서버 설정 fruitSwap=false 면 이미 능력이 있을 때 안 바뀜). */
public class ElementFruitItem extends Item {
    public final Element element;

    public ElementFruitItem(Element element, Properties props) {
        super(props);
        this.element = element;
    }

    @Override
    public ItemStack finishUsingItem(ItemStack stack, Level level, LivingEntity entity) {
        if (!level.isClientSide && entity instanceof ServerPlayer p && !PlayerElements.eatFruit(p, element)) {
            // 받지 못함 → 열매를 먹지 않은 것으로 (클라이언트가 먼저 줄인 개수를 되돌림)
            p.containerMenu.sendAllDataToRemote();
            return stack;
        }
        return super.finishUsingItem(stack, level, entity);
    }

    @Override
    public boolean isFoil(ItemStack stack) {
        return true;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext ctx, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("item.blackocean_skills.fruit.tooltip", Component.translatable(element.translationKey()))
                .withStyle(ChatFormatting.GOLD));
    }
}
