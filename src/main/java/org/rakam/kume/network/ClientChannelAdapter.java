package org.rakam.kume.network;

import com.google.common.cache.Cache;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import org.rakam.kume.transport.Packet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentMap;


public class ClientChannelAdapter extends ChannelInboundHandlerAdapter {
    final static Logger LOGGER = LoggerFactory.getLogger(ClientChannelAdapter.class);

    final ConcurrentMap<Integer, CompletableFuture<Object>> messageHandlers;

    public ClientChannelAdapter(Cache<Integer, CompletableFuture<Object>> messageHandlers) {
        this.messageHandlers = messageHandlers.asMap();
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        Packet read = (Packet) msg;
        CompletableFuture<Object> ifPresent = messageHandlers.remove(read.sequence);
        if (ifPresent != null) {
            ifPresent.complete(read.getData());
        } else {
            LOGGER.warn("unhandled packet {}", msg);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        super.exceptionCaught(ctx, cause);
    }
}
