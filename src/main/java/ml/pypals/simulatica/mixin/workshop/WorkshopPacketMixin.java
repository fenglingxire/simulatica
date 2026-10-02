package ml.pypals.simulatica.mixin.workshop;

import com.llamalad7.mixinextras.injector.wrapoperation.*;
import ml.pypals.simulatica.workshop.WorkshopSession;
import net.minecraft.network.*;
import net.minecraft.network.protocol.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(targets = "net.minecraft.network.PacketProcessor$ListenerAndPacket")
public class WorkshopPacketMixin {
    @WrapOperation(method = "handle", at = @At(value = "INVOKE", target = "Lnet/minecraft/network/protocol/Packet;handle(Lnet/minecraft/network/PacketListener;)V"))
    private void workshop$owner(Packet<?> packet, PacketListener listener, Operation<Void> original) {
        WorkshopSession.runFor(listener, () -> original.call(packet, listener));
    }
}
