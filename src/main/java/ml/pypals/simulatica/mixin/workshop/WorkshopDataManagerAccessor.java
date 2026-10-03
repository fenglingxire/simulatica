package ml.pypals.simulatica.mixin.workshop;

import fi.dy.masa.litematica.data.DataManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = DataManager.class, remap = false)
public interface WorkshopDataManagerAccessor {
    @Accessor("canSave")
    static boolean simulatica$canSave() { throw new AssertionError(); }

    @Accessor("canSave")
    static void simulatica$canSave(boolean value) { throw new AssertionError(); }
}
