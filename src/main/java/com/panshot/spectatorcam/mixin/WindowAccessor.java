package com.panshot.spectatorcam.mixin;

import net.minecraft.client.util.Window;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Window.class)
public interface WindowAccessor {
    @Mutable
    @Accessor("width")
    void spectatorcam$setWidth(int width);

    @Mutable
    @Accessor("height")
    void spectatorcam$setHeight(int height);
}
