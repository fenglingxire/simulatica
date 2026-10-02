package ml.pypals.simulatica.mixin.workshop;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import ml.pypals.simulatica.workshop.WorkshopSession;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketDecoder;
import org.spongepowered.asm.mixin.Mixin;

import java.util.List;

@Mixin(PacketDecoder.class)
public class WorkshopPacketDecoderMixin {
    @WrapMethod(method = "decode")
    private void simulatica$decodeScope(ChannelHandlerContext context, ByteBuf buffer, List<Object> output, Operation<Void> original) {
        Connection connection = context.pipeline().get(Connection.class);
        if (connection == null) { original.call(context, buffer, output); return; }
        WorkshopSession.enterMetadataScope(connection);
        try { original.call(context, buffer, output); }
        finally { WorkshopSession.leaveMetadataScope(); }
    }
}
