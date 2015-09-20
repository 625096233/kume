package org.rakam.kume.network;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldPrepender;
import org.rakam.kume.Cluster;
import org.rakam.kume.transport.PacketDecoder;
import org.rakam.kume.transport.PacketEncoder;
import org.rakam.kume.util.ThrowableNioEventLoopGroup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.net.SocketAddress;

/**
 * Created by buremba <Burak Emre Kabakcı> on 03/01/15 15:43.
 */
public class TCPServerHandler {
    final static Logger LOGGER = LoggerFactory.getLogger(TCPServerHandler.class);
    private final Channel server;

    public TCPServerHandler(EventLoopGroup bossGroup, EventLoopGroup workerGroup, ThrowableNioEventLoopGroup eventExecutor, Cluster cluster, InetSocketAddress serverAddress) throws InterruptedException {
        ChannelFuture bind = new ServerBootstrap()
                .childOption(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
                .group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .option(ChannelOption.AUTO_READ, false)
                .option(ChannelOption.SO_BACKLOG, 100)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    public void initChannel(SocketChannel ch) throws Exception {
                        ChannelPipeline p = ch.pipeline();
                        p.addLast("frameDecoder", new LengthFieldBasedFrameDecoder(1048576, 0, 4, 0, 4));
                        p.addLast("packetDecoder", new PacketDecoder());
                        p.addLast("frameEncoder", new LengthFieldPrepender(Integer.BYTES));
                        p.addLast("packetEncoder", new PacketEncoder());
                        p.addLast(new ServerChannelAdapter(cluster, eventExecutor));
                    }
                }).bind(serverAddress);

        server = bind.sync()
                .addListener(future -> {
                    if (!future.isSuccess()) {
                        LOGGER.error("Failed to bind {}", bind.channel().localAddress());
                    }
                }).channel();
    }

    public ChannelFuture waitForClose() throws InterruptedException {
        return server.closeFuture().sync();
    }

    public SocketAddress localAddress() {
        return server.localAddress();
    }

    public void setAutoRead(boolean b) {
        server.config().setAutoRead(b);
    }

    public void close() throws InterruptedException {
        server.close().sync();
    }
}
