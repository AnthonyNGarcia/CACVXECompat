package com.cacxve.mixin;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(AbstractContainerScreen.class)
public interface AbstractContainerScreenAccessor
{
    @Accessor("imageHeight")
    void cacxvecompat$setImageHeight(int height);

    @Accessor("topPos")
    void cacxvecompat$setTopPos(int top);
}