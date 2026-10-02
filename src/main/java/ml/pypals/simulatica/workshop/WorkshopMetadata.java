package ml.pypals.simulatica.workshop;

import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.HolderSet;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.DefaultedRegistry;
import net.minecraft.core.DefaultedMappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.Connection;
import net.minecraft.resources.ResourceKey;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.fabricmc.fabric.impl.networking.client.ClientNetworkingImpl;
import ml.pypals.simulatica.mixin.workshop.WorkshopDownloadedPackAccessor;
import ml.pypals.simulatica.mixin.workshop.WorkshopPackManagerAccessor;
import net.minecraft.tags.TagKey;
import net.minecraft.tags.TagLoader;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Connection-owned copies of the mutable metadata on otherwise shared built-in values. */
public final class WorkshopMetadata {
    private static volatile RegistryAccess.Frozen remoteStatic;
    private static final ThreadLocal<PackOwner> PACK_OWNER = new ThreadLocal<>();

    private WorkshopMetadata() {}

    public static void capture() {
        remoteStatic = new RegistryAccess.ImmutableRegistryAccess(
                BuiltInRegistries.REGISTRY.stream().map(WorkshopMetadata::copy).toList()).freeze();
        var source = (WorkshopDownloadedPackAccessor) Minecraft.getInstance().getDownloadedPackSource();
        var remote = WorkshopSession.remote();
        var owner = new PackOwner(remote.connection(), remote.generation());
        ((WorkshopPackManagerAccessor) source.simulatica$manager()).simulatica$packs()
                .forEach(pack -> ((OwnedPack) pack).simulatica$packOwner(owner));
        ((ReloadSource) source).simulatica$adoptReloadOwner(owner);
    }

    private static <T> Registry<T> copy(Registry<T> source) {
        MappedRegistry<T> copy = source instanceof DefaultedRegistry<T> defaulted
                ? new DefaultedMappedRegistry<>(defaulted.getDefaultKey().toString(), source.key(), source.registryLifecycle(), false)
                : new MappedRegistry<>(source.key(), source.registryLifecycle());
        ((ComponentRegistry) copy).simulatica$staticClone(true);
        // Registry iteration is ID order: network IDs must remain unchanged.
        source.forEach(value -> {
            var key = source.getResourceKey(value).orElseThrow();
            var holder = copy.register(key, value, source.registrationInfo(key).orElseThrow());
            holder.bindComponents(source.getOrThrow(key).components());
        });
        Map<TagKey<T>, List<Holder<T>>> tags = new HashMap<>();
        source.getTags().forEach(tag -> tags.put(tag.key(), tag.stream()
                .map(holder -> (Holder<T>) copy.getOrThrow(holder.unwrapKey().orElseThrow())).toList()));
        copy.bindTags(tags);
        return copy.freeze();
    }

    public static RegistryAccess.Frozen staticAccess(RegistryAccess.Frozen original) {
        return WorkshopSession.isRemoteScope() && remoteStatic != null ? remoteStatic : original;
    }

    public static <T> Registry<T> registry(Registry<T> original) {
        if (!WorkshopSession.isRemoteScope() || remoteStatic == null) return null;
        var remote = remoteStatic.lookup(original.key()).orElse(null);
        return remote == original ? null : remote;
    }

    public static <T> Holder.Reference<T> holder(Holder.Reference<T> original) {
        if (!WorkshopSession.isRemoteScope() || remoteStatic == null || !original.isBound()) return null;
        var registry = remoteStatic.lookup(original.key().registryKey()).orElse(null);
        if (registry == null) return null;
        var remote = registry.get(original.key()).orElse(null);
        return remote == original ? null : remote;
    }

    /** Wire holders must retain the identity used by bootstrap maps such as entity attributes. */
    @SuppressWarnings("unchecked")
    public static <T> Holder<T> canonicalWireHolder(Holder<T> holder) {
        if (!(holder instanceof Holder.Reference<T> reference)) return holder;
        Registry<T> builtin = (Registry<T>) BuiltInRegistries.REGISTRY
                .getValue(reference.key().registryKey().identifier());
        if (builtin == null) return holder;
        var canonical = builtin.get(reference.key()).orElse(null);
        return canonical != null && canonical.value() == reference.value() ? canonical : holder;
    }

    public static <T> HolderSet.Named<T> tag(HolderSet.Named<T> original) {
        if (!WorkshopSession.isRemoteScope() || remoteStatic == null) return null;
        var registry = remoteStatic.lookup(original.key().registry()).orElse(null);
        if (registry == null) return null;
        var remote = registry.get(original.key()).orElse(null);
        return remote == original ? null : remote;
    }

    public static <T> TagLoader.LoadResult<T> retargetTags(Registry<T> registry, TagLoader.LoadResult<T> tags) {
        Map<TagKey<T>, List<Holder<T>>> contents = new HashMap<>();
        tags.tags().forEach((key, values) -> contents.put(key, values.stream()
                .map(holder -> (Holder<T>) registry.getOrThrow(holder.unwrapKey().orElseThrow())).toList()));
        return new TagLoader.LoadResult<>(registry.key(), contents);
    }

    @SuppressWarnings("unchecked")
    public static <T> Registry.PendingTags<T> mirrorForegroundTags(Registry<T> clone, TagLoader.LoadResult<T> tags,
            Registry.PendingTags<T> pending) {
        if (WorkshopSession.isActive() || !(clone instanceof ComponentRegistry owned) || !owned.simulatica$staticClone()
                || !foregroundOwns(clone)) return pending;
        Registry<T> builtin = (Registry<T>) BuiltInRegistries.REGISTRY.getValue(clone.key().identifier());
        if (builtin == null || tags.tags().values().stream().flatMap(List::stream).anyMatch(holder ->
                holder.unwrapKey().map(key -> builtin.get(key)
                        .filter(original -> original.value() == holder.value()).isEmpty()).orElse(true))) return pending;
        return new Registry.PendingTags<>() {
            @Override public ResourceKey<? extends Registry<? extends T>> key() { return pending.key(); }
            @Override public HolderLookup.RegistryLookup<T> lookup() { return pending.lookup(); }
            @Override public int size() { return pending.size(); }
            @Override public void apply() {
                pending.apply();
                if (!WorkshopSession.isActive() && foregroundOwns(clone)) {
                    builtin.prepareTagReload(retargetTags(builtin, tags)).apply();
                }
            }
        };
    }

    private static boolean foregroundOwns(Registry<?> registry) {
        var foreground = Minecraft.getInstance().getConnection();
        return foreground != null && foreground.getConnection().isConnected()
                && foreground.registryAccess().lookup(registry.key()).orElse(null) == registry;
    }

    /** Restore the remote metadata before putting its world back on screen. */
    public static void restore() {
        // A failed local configuration may leave its singleton behind; rebind without replaying JOIN.
        ClientNetworkingImpl.setClientConfigurationAddon(null);
        var remote = WorkshopSession.remote();
        ClientNetworkingImpl.setClientPlayAddon(remote != null && remote.connected() && remote.connection().isConnected()
                && remote.connection().getPacketListener() instanceof ClientPacketListener play
                ? ClientNetworkingImpl.getAddon(play) : null);
        if (remoteStatic != null) BuiltInRegistries.REGISTRY.forEach(WorkshopMetadata::restoreRegistry);
    }

    private static <T> void restoreRegistry(Registry<T> original) {
        var remote = remoteStatic.lookup(original.key()).orElseThrow();
        Map<TagKey<T>, List<Holder<T>>> tags = new HashMap<>();
        remote.getTags().forEach(tag -> tags.put(tag.key(), tag.stream()
                .map(holder -> (Holder<T>) original.getOrThrow(holder.unwrapKey().orElseThrow())).toList()));
        original.prepareTagReload(new TagLoader.LoadResult<>(original.key(), tags)).apply();
        remote.listElements().forEach(holder -> original.getOrThrow(holder.key()).bindComponents(holder.components()));
        ((ComponentRegistry) original).simulatica$invalidateComponents();
    }

    public static void clear() {
        remoteStatic = null;
        PACK_OWNER.remove();
    }

    public interface ComponentRegistry {
        void simulatica$invalidateComponents();
        boolean simulatica$staticClone();
        void simulatica$staticClone(boolean clone);
    }

    public static void invalidateComponentLookups() {
        if (WorkshopSession.isRemoteScope() && remoteStatic != null) {
            remoteStatic.registries().forEach(entry -> ((ComponentRegistry) entry.value()).simulatica$invalidateComponents());
        }
    }

    public record PackOwner(Connection connection, long generation) {
        public boolean current() {
            var remote = WorkshopSession.remote();
            return remote == null ? connection.isConnected()
                    : remote.active(connection) && remote.generation() == generation;
        }
    }

    public interface OwnedPack {
        UUID simulatica$packId();
        PackOwner simulatica$packOwner();
        void simulatica$packOwner(PackOwner owner);
    }

    public interface OwnedReload {
        PackOwner simulatica$reloadOwner();
        boolean simulatica$ownsPack(Object pack);
        void simulatica$adoptOwner(PackOwner owner);
    }

    public interface ReloadSource {
        PackOwner simulatica$failedReloadOwner();
        void simulatica$abortReload();
        void simulatica$finishReload();
        void simulatica$adoptReloadOwner(PackOwner owner);
    }

    public static boolean currentPack(Object pack) {
        PackOwner owner = ((OwnedPack) pack).simulatica$packOwner();
        return owner == null || owner.current();
    }

    public static PackOwner capturePackOwner() {
        var remote = WorkshopSession.remote();
        return WorkshopSession.isRemoteScope() && remote != null
                ? new PackOwner(WorkshopSession.sourceConnection(), remote.generation()) : null;
    }

    public static UUID packId(Object pack, UUID id) {
        PackOwner owner = ((OwnedPack) pack).simulatica$packOwner();
        if (owner == null) PACK_OWNER.remove(); else PACK_OWNER.set(owner);
        return id;
    }

    public static PackOwner takePackOwner() {
        PackOwner owner = PACK_OWNER.get();
        PACK_OWNER.remove();
        return owner;
    }
}
