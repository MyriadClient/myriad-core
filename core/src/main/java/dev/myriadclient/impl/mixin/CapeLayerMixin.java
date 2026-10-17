package dev.myriadclient.impl.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.myriadclient.impl.cosmetic.MyriadCape;
import net.minecraft.client.renderer.entity.layers.CapeLayer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Myriad's cape has a torn hem, so it's drawn with holes (cutout) where Minecraft draws capes solid. */
@Mixin(CapeLayer.class)
public abstract class CapeLayerMixin {
	@WrapOperation(method = "submit", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/rendertype/RenderTypes;entitySolid(Lnet/minecraft/resources/Identifier;)Lnet/minecraft/client/renderer/rendertype/RenderType;"))
	private RenderType myriad$tornCape(Identifier texture, Operation<RenderType> original) {
		return MyriadCape.isMine(texture) ? RenderTypes.entityCutout(texture) : original.call(texture);
	}
}
