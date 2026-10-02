package ml.pypals.simulatica.workshop;

import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.phys.Vec3;
import java.lang.reflect.Method;
import java.lang.reflect.Constructor;
import java.util.Map;

/** The installed Masa versions use either vanilla NBT or CompoundData at these two boundaries. */
public final class MasaNbt {
    private static final Method ENTITY_NBT;
    private static final Constructor<?> ENTITY;
    private static final Method FROM_NBT, TO_NBT;
    static {
        try {
            ENTITY_NBT = LitematicaSchematic.EntityInfo.class.getMethod("nbt");
            Class<?> tagType = ENTITY_NBT.getReturnType();
            ENTITY = LitematicaSchematic.EntityInfo.class.getConstructor(Vec3.class, tagType);
            if (tagType == CompoundTag.class) { FROM_NBT = TO_NBT = null; }
            else {
                Class<?> converter = Class.forName("fi.dy.masa.malilib.util.data.tag.converter.DataConverterNbt");
                FROM_NBT = converter.getMethod("fromVanillaCompound", CompoundTag.class);
                TO_NBT = converter.getMethod("toVanillaCompound", tagType);
            }
        } catch (ReflectiveOperationException failure) { throw new ExceptionInInitializerError(failure); }
    }
    private MasaNbt() {}
    public static CompoundTag tag(Object value) {
        if (value == null) return null;
        if (value instanceof CompoundTag nativeTag) return nativeTag.copy();
        try { return ((CompoundTag) TO_NBT.invoke(null, value)).copy(); }
        catch (ReflectiveOperationException failure) { throw new IllegalStateException("无法读取 Masa NBT", failure); }
    }
    private static Object encode(CompoundTag value) {
        try { return FROM_NBT == null ? value.copy() : FROM_NBT.invoke(null, value.copy()); }
        catch (ReflectiveOperationException failure) { throw new IllegalStateException("无法写入 Masa NBT", failure); }
    }
    public static CompoundTag entityTag(LitematicaSchematic.EntityInfo entity) {
        try { return tag(ENTITY_NBT.invoke(entity)); }
        catch (ReflectiveOperationException failure) { throw new IllegalStateException("无法读取 Masa 实体", failure); }
    }
    public static LitematicaSchematic.EntityInfo entity(Vec3 pos, CompoundTag nbt) {
        try { return (LitematicaSchematic.EntityInfo) ENTITY.newInstance(pos, encode(nbt)); }
        catch (ReflectiveOperationException failure) { throw new IllegalStateException("无法写入 Masa 实体", failure); }
    }
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void putBlockEntity(Map<BlockPos, ?> map, BlockPos pos, CompoundTag tag) {
        ((Map)map).put(pos, encode(tag));
    }
}
