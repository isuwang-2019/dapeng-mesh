package com.github.dapeng.handler;

import com.github.dapeng.core.SoaCode;
import com.github.dapeng.config.ContainerStatus;
import com.github.dapeng.match.AntPathMatcher;
import com.github.dapeng.match.PathMatcher;
import com.github.dapeng.request.RequestParser;
import com.github.dapeng.util.PostUtil;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.*;
import io.netty.util.CharsetUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * desc: NettyHttpServerHandler
 *
 * @author hz.lei
 * @since 2018年08月23日 上午10:01
 */
public class NettyHttpServerHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
    private Logger logger = LoggerFactory.getLogger(getClass());
    private PathMatcher pathMatcher = new AntPathMatcher();


    private final String DEFAULT_MATCH = "/api/{serviceName:[\\s\\S]*}/{version:[\\s\\S]*}/{methodName:[\\s\\S]*}/{parameter:[\\s\\S]*}";


    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest httpRequest) throws Exception {
        // doService
        try {
            doService(httpRequest, ctx);
        } catch (Exception e) {
            logger.error("处理请求失败!" + e.getMessage(), e);
        } finally {
            //释放请求
            httpRequest.release();
        }
    }

    protected void doService(FullHttpRequest request, ChannelHandlerContext ctx) throws Exception {
        dispatchRequest(request, ctx);

        checkRequestType(request, ctx);

        Map<String, String> requestParams = RequestParser.fastParse(request);
        if (logger.isDebugEnabled()) {
            StringBuilder logBuilder = new StringBuilder();
            requestParams.forEach((k, v) -> logBuilder.append("[K: ").append(k).append(", V: ").append(v).append("\n"));
            logger.debug("request参数信息: " + logBuilder.toString());
        }
        // buildRequest


    }

    private void dispatchRequest(FullHttpRequest request, ChannelHandlerContext ctx) {
        HttpMethod method = request.method();
        boolean isGet = HttpMethod.GET.equals(method);
        if (isGet || HttpMethod.HEAD.equals(method)) {
            handlerGetAndHead(request, ctx);
        }
        boolean isPost = HttpMethod.POST.equals(method);
        if (isPost) {
            handlerPostRequest(request, ctx);

        }


    }

    private void handlerPostRequest(FullHttpRequest request, ChannelHandlerContext ctx) {
        String uri = request.uri();
        if (pathMatcher.match(DEFAULT_MATCH, uri)) {
            Map<String, String> pathVariableMap = pathMatcher.extractUriTemplateVariables(DEFAULT_MATCH, request.uri());
            String serviceName = pathVariableMap.get("serviceName");
            String version = pathVariableMap.get("version");
            String methodName = pathVariableMap.get("methodName");
            String parameter = pathVariableMap.get("parameter");

            CompletableFuture<String> jsonResponse = (CompletableFuture<String>) PostUtil.postAsync(serviceName, version, methodName, parameter, request);

            jsonResponse.whenComplete((result, ex) -> {
                if (ex != null) {
                    String resp = String.format("{\"responseCode\":\"%s\", \"responseMsg\":\"%s\", \"success\":\"%s\", \"status\":0}", SoaCode.ServerUnKnown.getCode(), ex.getMessage(), "{}");
                    send(ctx, resp, HttpResponseStatus.OK);
                } else {
                    if (result.contains("status")) {
                        send(ctx, result, HttpResponseStatus.OK);
                        return;
                    }
                    String response = "{}".equals(result) ? "{\"status\":1}" : result.substring(0, result.lastIndexOf('}')) + ",\"status\":1}";
                    send(ctx, response, HttpResponseStatus.OK);
                }
            });
        }

    }


    private void handlerGetAndHead(FullHttpRequest request, ChannelHandlerContext ctx) {
        String uri = request.uri();
        if ("/health/check".equals(uri)) {
            logger.debug("health check,container status: " + ContainerStatus.GREEN);
            send(ctx, "GateWay is running", HttpResponseStatus.OK);
        } else {
            logger.debug("not support url request, uri: {}", uri);
            send(ctx, "不支持的请求类型", HttpResponseStatus.OK);
        }
    }


    /**
     * call rpc
     */
    private void callRpc() {


    }


    private void checkRequestType(FullHttpRequest request, ChannelHandlerContext ctx) {
        //获取参数
        ByteBuf buf = request.content();

        String body = buf.toString(CharsetUtil.UTF_8);

        //获取请求方法
        HttpMethod method = request.method();


        String path = request.uri();

        logger.info("path:{}", path);
        String result;
        //如果不是这个路径，就直接返回错误
        if (!"/test".equalsIgnoreCase(path)) {
            result = "非法请求!";
            send(ctx, result, HttpResponseStatus.BAD_REQUEST);
            return;
        }
        System.out.println("接收到:" + method + " 请求");
        //如果是GET请求
        if (HttpMethod.GET.equals(method)) {
            //接受到的消息，做业务逻辑处理...
            System.out.println("body:" + body);
            result = "GET请求";
            send(ctx, result, HttpResponseStatus.OK);
            return;
        }
        //如果是POST请求
        if (HttpMethod.POST.equals(method)) {
            //接受到的消息，做业务逻辑处理...
            System.out.println("body:" + body);
            result = "POST请求";
            send(ctx, result, HttpResponseStatus.OK);
            return;
        }

        //如果是PUT请求
        if (HttpMethod.PUT.equals(method)) {
            //接受到的消息，做业务逻辑处理...
            System.out.println("body:" + body);
            result = "PUT请求";
            send(ctx, result, HttpResponseStatus.OK);
            return;
        }
        //如果是DELETE请求
        if (HttpMethod.DELETE.equals(method)) {
            //接受到的消息，做业务逻辑处理...
            System.out.println("body:" + body);
            result = "DELETE请求";
            send(ctx, result, HttpResponseStatus.OK);
            return;
        }

    }


    private void send(ChannelHandlerContext ctx, String context, HttpResponseStatus status) {
        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, Unpooled.copiedBuffer(context, CharsetUtil.UTF_8));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=UTF-8");
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        logger.info("连接的客户端地址:{}", ctx.channel().remoteAddress());
        ctx.writeAndFlush("客户端" + InetAddress.getLocalHost().getHostName() + "成功与服务端建立连接！ ");
        super.channelActive(ctx);
    }
}
