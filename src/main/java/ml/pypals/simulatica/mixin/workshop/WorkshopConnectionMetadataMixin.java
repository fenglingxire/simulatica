package ml.pypals.simulatica.mixin.workshop;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.netty.channel.ChannelHandlerContext;
import ml.pypals.simulatica.workshop.WorkshopSession;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(Connection.class)
public class WorkshopConnectionMetadataMixin {
    @WrapMethod(method = "channelRead0(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;)V")
    private void simulatica$dispatchScope(ChannelHandlerContext context, Packet<?> packet, Operation<Void> original) {
        WorkshopSession.enterMetadataScope((Connection) (Object) this);
        try { original.call(context, packet); }
        finally { WorkshopSession.leaveMetadataScope(); }
    }

    @WrapMethod(method = "channelInactive")
    private void simulatica$inactiveScope(ChannelHandlerContext context, Operation<Void> original) {
        WorkshopSession.enterMetadataScope((Connection) (Object) this);
        try { original.call(context); }
        finally { WorkshopSession.leaveMetadataScope(); }
    }

    @WrapMethod(method = "handleDisconnection")
    private void simulatica$disconnectionScope(Operation<Void> original) {
        WorkshopSession.handleLocalDisconnection((Connection) (Object) this);
        WorkshopSession.enterMetadataScope((Connection) (Object) this);
        try { original.call(); }
        finally { WorkshopSession.leaveMetadataScope(); }
    }
}
