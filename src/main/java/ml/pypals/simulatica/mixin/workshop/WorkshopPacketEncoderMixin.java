package ml.pypals.simulatica.mixin.workshop;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import ml.pypals.simulatica.workshop.WorkshopSession;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketEncoder;
import net.minecraft.network.protocol.Packet;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(PacketEncoder.class)
public class WorkshopPacketEncoderMixin {
    @WrapMethod(method = "encode(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;Lio/netty/buffer/ByteBuf;)V")
    private void simulatica$encodeScope(ChannelHandlerContext context, Packet<?> packet, ByteBuf buffer, Operation<Void> original) {
        Connection connection = context.pipeline().get(Connection.class);
        if (connection == null) { original.call(context, packet, buffer); return; }
        WorkshopSession.enterMetadataScope(connection);
        try { original.call(context, packet, buffer); }
        finally { WorkshopSession.leaveMetadataScope(); }
    }
}
