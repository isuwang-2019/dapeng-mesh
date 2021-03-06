package com.github.dapeng.gateway.netty;

import com.github.dapeng.gateway.http.GetUrlController;
import com.github.dapeng.gateway.http.HttpProcessorUtils;
import com.github.dapeng.gateway.http.MeshHealthStatus;
import com.github.dapeng.gateway.netty.handler.AuthenticationHandler;
import com.github.dapeng.gateway.netty.handler.HttpRequestHandler;
import com.github.dapeng.gateway.netty.handler.ServerProcessHandler;
import com.github.dapeng.gateway.util.Constants;
import com.github.dapeng.gateway.util.SysEnvUtil;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpRequestDecoder;
import io.netty.handler.codec.http.HttpResponseEncoder;
import io.netty.util.concurrent.DefaultThreadFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

import static com.github.dapeng.core.helper.SoaSystemEnvProperties.SOA_SHUTDOWN_TIMEOUT;

/**
 * @author maple NettyHttpServer
 * @since 2018年08月23日 上午9:54
 */
public class NettyHttpServer {
    private static final Logger logger = LoggerFactory.getLogger(NettyHttpServer.class);

    /**
     * Synchronization monitor for the "refresh" and "destroy"
     */
    private final Object startupShutdownMonitor = new Object();

    /**
     * Reference to the JVM shutdown hook, if registered
     */
    private Thread shutdownHook;

    private final int port;

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;


    public NettyHttpServer(int port) {
        this.port = port > 0 ? port : 0;
    }

    /**
     * start
     */
    public void start() {
        // eventGroup
        bossGroup = new NioEventLoopGroup(1, new DefaultThreadFactory("netty-server-boss-group", Boolean.TRUE));
        workerGroup = new NioEventLoopGroup(Constants.DEFAULT_IO_THREADS, new DefaultThreadFactory("netty-server-worker-group", Boolean.TRUE));

        // sharable handler
        HttpRequestHandler httpRequestHandler = new HttpRequestHandler();
        ServerProcessHandler serverProcessHandler = new ServerProcessHandler();

        AuthenticationHandler authenticationHandler = Boolean.parseBoolean(SysEnvUtil.OPEN_AUTH_ENABLE) ? new AuthenticationHandler() : null;

        try {
            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap
                    .group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) throws Exception {
                            ChannelPipeline ph = ch.pipeline();
                            //处理http服务的关键handler
                            ph.addLast("encoder", new HttpResponseEncoder());
                            ph.addLast("decoder", new HttpRequestDecoder());
                            ph.addLast("aggregator", new HttpObjectAggregator(10 * 1024 * 1024));
                            // 服务端业务逻辑
                            ph.addLast("requestHandler", httpRequestHandler);

                            if (Boolean.parseBoolean(SysEnvUtil.OPEN_AUTH_ENABLE)) {
                                ph.addLast("authenticationHandler", authenticationHandler);
                            }
                            ph.addLast("serverHandler", serverProcessHandler);
                        }

                    })
                    .option(ChannelOption.SO_BACKLOG, 1024)
                    .childOption(ChannelOption.SO_KEEPALIVE, Boolean.TRUE)
                    .childOption(ChannelOption.TCP_NODELAY, Boolean.TRUE)
                    .childOption(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT);


            ChannelFuture future = bootstrap.bind(port).sync();

            logger.info("NettyServer start listen at {}", port);
            future.channel().closeFuture().sync();
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
        } finally {
            bossGroup.shutdownGracefully();
            workerGroup.shutdownGracefully();
        }
    }

    @SuppressWarnings("AlibabaAvoidManuallyCreateThread")
    public void registerShutdownHook() {
        if (this.shutdownHook == null) {
            // No shutdown hook registered yet.
            this.shutdownHook = new Thread(() -> {
                synchronized (startupShutdownMonitor) {
                    GetUrlController.status = MeshHealthStatus.YELLOW;
                    logger.info("sleep 15s, wait nginx health check remove this gateway");
                    try {
                        TimeUnit.SECONDS.sleep(15);
                    } catch (InterruptedException ignored) {
                    }
                    logger.info("ready to shutdown this gateway!");
                    //重试，保证请求处理完成
                    retryCompareCounter();
                    if (bossGroup != null) {
                        bossGroup.shutdownGracefully();
                    }
                    if (workerGroup != null) {
                        workerGroup.shutdownGracefully();
                    }
                    logger.info("end to shutdown this gateway!");
                }
            }, "netty-server-shutdownHook-thread");
            Runtime.getRuntime().addShutdownHook(this.shutdownHook);
        }
    }

    public void retryCompareCounter() {
        if (logger.isDebugEnabled()) {
            logger.debug(getClass().getSimpleName() + "Retry to ensure requests processing is complete");
        }

        logger.warn("尚余[" + HttpProcessorUtils.getRequestCounter().intValue() + "]个请求还未处理..."
                + (HttpProcessorUtils.getRequestCounter().intValue() > 0 ? "现在最多等待[" + SOA_SHUTDOWN_TIMEOUT + "ms]" : ""));

        long sleepTime = 2000;
        long retry = SOA_SHUTDOWN_TIMEOUT / sleepTime;

        do {
            if (HttpProcessorUtils.getRequestCounter().intValue() <= 0) {
                break;
            } else {
                try {
                    if (logger.isDebugEnabled()) {
                        logger.debug("requests haven't been processed  completely, sleep " + sleepTime + "ms");
                    }
                    Thread.sleep(sleepTime);
                } catch (InterruptedException e) {
                    logger.error(e.getMessage(), e);
                }
            }
        } while (--retry > 0);

        if (HttpProcessorUtils.getRequestCounter().intValue() != 0) {
            logger.warn(retry + "次等待共[" + SOA_SHUTDOWN_TIMEOUT + "ms]之后，尚余[" + HttpProcessorUtils.getRequestCounter().intValue() + "]个请求还未处理完，gateway即将关闭...");
        }
    }
}
