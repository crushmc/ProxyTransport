package org.nethergames.proxytransport.decoder;

import com.nukkitx.network.VarInts;
import com.nukkitx.network.util.DisconnectReason;
import com.nukkitx.protocol.bedrock.BedrockPacket;
import com.nukkitx.protocol.bedrock.BedrockPacketCodec;
import com.nukkitx.protocol.bedrock.exception.PacketSerializeException;
import com.nukkitx.protocol.bedrock.packet.NetworkStackLatencyPacket;
import com.nukkitx.protocol.bedrock.wrapper.compression.SnappyCompression;
import com.nukkitx.protocol.util.Zlib;
import dev.waterdog.waterdogpe.ProxyServer;
import dev.waterdog.waterdogpe.network.session.CompressionAlgorithm;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.util.ReferenceCountUtil;
import org.nethergames.proxytransport.ProxyTransport;
import org.nethergames.proxytransport.impl.TransportDownstreamSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.UUID;
import java.util.zip.DataFormatException;

/**
 * This decoder handles the logic of receiving packets from the downstream server and passing them to the upstream client
 */
public class PacketDecoder extends SimpleChannelInboundHandler<ByteBuf> {

    private final static int MAX_BUFFER_SIZE = 4 * 1024 * 1024;

    private final TransportDownstreamSession session;
    private final Logger debugLogger = LoggerFactory.getLogger("DebugLogger");

    public PacketDecoder(TransportDownstreamSession session) {
        this.session = session;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext channelHandlerContext, ByteBuf compressed) {
        Collection<BedrockPacket> packets = new ArrayList<>();
        BedrockPacketCodec codec = this.session.getPlayer().getProtocol().getCodec();
        ByteBuf decompressed = null;
        try {
            compressed.markReaderIndex();
            decompressed = channelHandlerContext.alloc().buffer();

            var compression = this.session.getCompression();
            if (compression == null) {
                Zlib.RAW.inflate(compressed, decompressed, MAX_BUFFER_SIZE);
            } else {
                switch (compression.getBedrockCompression()) {
                    case ZLIB -> Zlib.RAW.inflate(compressed, decompressed, MAX_BUFFER_SIZE);
                    case SNAPPY -> SnappyCompression.INSTANCE.decompress(compressed, decompressed, MAX_BUFFER_SIZE);
                    default -> throw new DataFormatException("Unknown compression provided.");
                }
            }

            decompressed.markReaderIndex();

            while (decompressed.isReadable()) {
                int length = VarInts.readUnsignedInt(decompressed);
                ByteBuf packetBuffer = decompressed.readSlice(length);
                if (!packetBuffer.isReadable()) {
                    throw new DataFormatException("Packet cannot be empty");
                }

                try {
                    int header = VarInts.readUnsignedInt(packetBuffer);
                    int packetId = header & 1023;
                    BedrockPacket packet = codec.tryDecode(packetBuffer, packetId, this.session.getPlayer().getUpstream());
                    packet.setPacketId(packetId);
                    packet.setSenderId(header >>> 10 & 3);
                    packet.setClientId(header >>> 12 & 3);

                    if (packet instanceof NetworkStackLatencyPacket) {
                        if (((NetworkStackLatencyPacket) packet).getTimestamp() == 0) {
                            this.session.handleNetworkStackPacket();
                        }
                    }
                    packets.add(packet);
                } catch (PacketSerializeException serializeException) {
                    ProxyTransport.getEventAdapter().downstreamException(this.session, serializeException, packetBuffer);
                    this.session.getPlayer().getLogger().error("Error while decoding a packet for " + this.session.getPlayer().getName(), serializeException);
                }
            }
            compressed.resetReaderIndex();

            this.session.getBatchHandler().handle(this.session.getPacketHandler(), compressed.retain(), packets, CompressionAlgorithm.ZLIB);
        } catch (Throwable t) {
            ProxyTransport.getEventAdapter().downstreamException(this.session, t, null);
            this.debugLogger.warn("Debug data for {} (playerVersion={}, codecVersion={}, totalFrameSizeCompressed={})", this.session.getPlayer().getName(), this.session.getPlayer().getProtocol().getProtocol(), codec.getProtocolVersion(), compressed.readableBytes());
            this.session.getPlayer().getLogger().error("Error while decoding a packet for " + this.session.getPlayer().getName(), t);
            String id = UUID.randomUUID().toString();
            StringBuilder builder = new StringBuilder();
            builder.append(ByteBufUtil.prettyHexDump(compressed));

            builder.append("======== Begin Of Base64 data ========");
            builder.append(Base64.getEncoder().encodeToString(compressed.array()));
            String formattedDump = builder.toString();
            if (ProxyTransport.getEventAdapter().bufferDump(id, formattedDump)) {
                debugLogger.info("Packet dump for {} saved with id {}", session.getPlayer().getName(), id);
            }
            throw new RuntimeException("Unable to inflate buffer data", t);
        } finally {
            ReferenceCountUtil.safeRelease(compressed);
            ReferenceCountUtil.safeRelease(decompressed);
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        super.channelInactive(ctx);

        this.session.disconnect(DisconnectReason.CLOSED_BY_REMOTE_PEER);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        ProxyTransport.getEventAdapter().downstreamException(this.session, cause, null);

        this.session.getPlayer().getLogger().error("Pipeline threw exception for player " + this.session.getPlayer().getName(), cause);
        this.session.disconnect(DisconnectReason.BAD_PACKET);

        ProxyServer.getInstance().getLogger().logException(cause);
    }
}
