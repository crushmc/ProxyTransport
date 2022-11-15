package org.nethergames.proxytransport.impl.handler;

import com.nukkitx.protocol.bedrock.packet.ItemComponentPacket;
import dev.waterdog.waterdogpe.network.session.DownstreamClient;
import dev.waterdog.waterdogpe.player.ProxiedPlayer;
import dev.waterdog.waterdogpe.utils.exceptions.CancelSignalException;

public class ConnectedDownstreamHandler extends dev.waterdog.waterdogpe.network.downstream.ConnectedDownstreamHandler {

    private boolean itemComponentPacketSent = false;

    public ConnectedDownstreamHandler(ProxiedPlayer player, DownstreamClient client) {
        super(player, client);
    }

    @Override
    public boolean handle(ItemComponentPacket packet) {
        if (!itemComponentPacketSent) {
            itemComponentPacketSent = true;
            return true;
        }
        throw CancelSignalException.CANCEL;
    }
}
