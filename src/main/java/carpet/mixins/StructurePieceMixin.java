package carpet.mixins;

import carpet.settings.CarpetSettings;
import net.minecraft.class_4538;
import net.minecraft.structure.StructurePiece;
import net.minecraft.world.Heightmap;
import net.minecraft.world.ViewableWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(StructurePiece.class)
public class StructurePieceMixin
{
    @Redirect(method = "isUnderSeaLevel", at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/class_4538;getLightLevel(Lnet/minecraft/world/Heightmap$Type;II)I"
    ))
    private int getTop(class_4538 class_4538, Heightmap.Type var1, int var2, int var3)
    {
        return class_4538.getLightLevel(CarpetSettings.skipGenerationChecks?Heightmap.Type.OCEAN_FLOOR:Heightmap.Type.OCEAN_FLOOR_WG, var2, var3);
    }
}
