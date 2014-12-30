package org.rakam.kume;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.concurrent.EventExecutor;
import org.rakam.kume.service.Service;
import org.rakam.kume.transport.Packet;
import org.rakam.kume.transport.PacketDecoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Created by buremba <Burak Emre Kabakcı> on 15/11/14 02:34.
 */
public class ServerChannelAdapter extends ChannelInboundHandlerAdapter {
    List<Service> services;
    Cluster eventBus;
    final static Logger LOGGER = LoggerFactory.getLogger(PacketDecoder.class);
    NioEventLoopGroupArray eventExecutors = eventBus.eventExecutors;

    public ServerChannelAdapter(Cluster bus) {
        this.services = bus.getServices();
        this.eventBus = bus;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        LOGGER.trace("server {} got message {}", ctx.channel().localAddress(), msg);

        Packet read = (Packet) msg;
        Object o = read.getData();
        RemoteOperationContext ctx1 = new RemoteOperationContext(ctx, read.service, read.sequence, eventBus);
        Service service = services.get(read.service);

        EventExecutor executor = eventExecutors.getChild(read.service);

        if (o instanceof Request) {
            executor.execute(() -> service.handle(ctx1, (Request) o));
        } else {
            executor.execute(() -> service.handle(ctx1, o));
        }
    }
}