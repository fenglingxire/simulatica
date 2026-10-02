package ml.pypals.simulatica.mixin.workshop;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntitySectionStorage;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(PersistentEntitySectionManager.class)
public interface WorkshopEntitySectionAccessor {
    @Accessor("sectionStorage") EntitySectionStorage<Entity> simulatica$sections();
}
