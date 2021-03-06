package com.github.monkeywie.proxyee.handler;

import com.github.monkeywie.proxyee.crt.CertPool;
import com.github.monkeywie.proxyee.exception.HttpProxyExceptionHandle;
import com.github.monkeywie.proxyee.intercept.HttpProxyIntercept;
import com.github.monkeywie.proxyee.intercept.HttpProxyInterceptInitializer;
import com.github.monkeywie.proxyee.intercept.HttpProxyInterceptPipeline;
import com.github.monkeywie.proxyee.proxy.ProxyConfig;
import com.github.monkeywie.proxyee.proxy.ProxyHandleFactory;
import com.github.monkeywie.proxyee.server.HttpProxyServer;
import com.github.monkeywie.proxyee.server.HttpProxyServerConfig;
import com.github.monkeywie.proxyee.server.auth.HttpAuthContext;
import com.github.monkeywie.proxyee.server.auth.HttpProxyAuthenticationProvider;
import com.github.monkeywie.proxyee.server.auth.model.HttpToken;
import com.github.monkeywie.proxyee.util.ProtoUtil;
import com.github.monkeywie.proxyee.util.ProtoUtil.RequestProto;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.handler.proxy.ProxyHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.resolver.NoopAddressResolverGroup;
import io.netty.util.ReferenceCountUtil;

import java.net.InetSocketAddress;
import java.net.URL;
import java.util.LinkedList;
import java.util.List;

public class HttpProxyServerHandler extends ChannelInboundHandlerAdapter {

    private ChannelFuture cf;
    private RequestProto requestProto;
    private int status = 0;
    private final HttpProxyServerConfig serverConfig;
    private final ProxyConfig proxyConfig;
    private final HttpProxyInterceptInitializer interceptInitializer;
    private HttpProxyInterceptPipeline interceptPipeline;
    private final HttpProxyExceptionHandle exceptionHandle;
    private List requestList;
    private boolean isConnect;

    public HttpProxyServerConfig getServerConfig() {
        return serverConfig;
    }

    public HttpProxyInterceptPipeline getInterceptPipeline() {
        return interceptPipeline;
    }

    public HttpProxyExceptionHandle getExceptionHandle() {
        return exceptionHandle;
    }

    public HttpProxyServerHandler(HttpProxyServerConfig serverConfig, HttpProxyInterceptInitializer interceptInitializer, ProxyConfig proxyConfig, HttpProxyExceptionHandle exceptionHandle) {
        this.serverConfig = serverConfig;
        this.proxyConfig = proxyConfig;
        this.interceptInitializer = interceptInitializer;
        this.exceptionHandle = exceptionHandle;
    }

    @Override
    public void channelRead(final ChannelHandlerContext ctx, final Object msg) throws Exception {
        if (msg instanceof HttpRequest) {
            HttpRequest request = (HttpRequest) msg;
            // ????????????????????????host?????????????????????????????????
            if (status == 0) {
                requestProto = ProtoUtil.getRequestProto(request);
                if (requestProto == null) { // bad request
                    ctx.channel().close();
                    return;
                }
                // ??????????????????
                if (serverConfig.getHttpProxyAcceptHandler() != null
                        && !serverConfig.getHttpProxyAcceptHandler().onAccept(request, ctx.channel())) {
                    status = 2;
                    ctx.channel().close();
                    return;
                }
                // ??????????????????
                if (!authenticate(ctx, request)) {
                    status = 2;
                    ctx.channel().close();
                    return;
                }
                status = 1;
                if ("CONNECT".equalsIgnoreCase(request.method().name())) {// ??????????????????
                    status = 2;
                    HttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpProxyServer.SUCCESS);
                    ctx.writeAndFlush(response);
                    ctx.channel().pipeline().remove("httpCodec");
                    // fix issue #42
                    ReferenceCountUtil.release(msg);
                    return;
                }
            }
            interceptPipeline = buildPipeline();
            interceptPipeline.setRequestProto(requestProto.copy());
            // fix issue #27
            if (request.uri().indexOf("/") != 0) {
                URL url = new URL(request.uri());
                request.setUri(url.getFile());
            }
            interceptPipeline.beforeRequest(ctx.channel(), request);
        } else if (msg instanceof HttpContent) {
            if (status != 2) {
                interceptPipeline.beforeRequest(ctx.channel(), (HttpContent) msg);
            } else {
                ReferenceCountUtil.release(msg);
                status = 1;
            }
        } else { // ssl???websocket???????????????
            if (serverConfig.isHandleSsl()) {
                ByteBuf byteBuf = (ByteBuf) msg;
                if (byteBuf.getByte(0) == 22) {// ssl??????
                    requestProto.setSsl(true);
                    int port = ((InetSocketAddress) ctx.channel().localAddress()).getPort();
                    SslContext sslCtx = SslContextBuilder
                            .forServer(serverConfig.getServerPriKey(), CertPool.getCert(port, requestProto.getHost(), serverConfig)).build();
                    ctx.pipeline().addFirst("httpCodec", new HttpServerCodec());
                    ctx.pipeline().addFirst("sslHandle", sslCtx.newHandler(ctx.alloc()));
                    // ???????????????pipeline????????????????????????http??????
                    ctx.pipeline().fireChannelRead(msg);
                    return;
                }
            }
            handleProxyData(ctx.channel(), msg, false);
        }
    }

    @Override
    public void channelUnregistered(ChannelHandlerContext ctx) throws Exception {
        if (cf != null) {
            cf.channel().close();
        }
        ctx.channel().close();
        if (serverConfig.getHttpProxyAcceptHandler() != null) {
            serverConfig.getHttpProxyAcceptHandler().onClose(ctx.channel());
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        if (cf != null) {
            cf.channel().close();
        }
        ctx.channel().close();
        exceptionHandle.beforeCatch(ctx.channel(), cause);
    }

    private boolean authenticate(ChannelHandlerContext ctx, HttpRequest request) {
        if (serverConfig.getAuthenticationProvider() != null) {
            HttpProxyAuthenticationProvider authProvider = serverConfig.getAuthenticationProvider();
            HttpToken httpToken = authProvider.authenticate(request.headers().get(HttpHeaderNames.PROXY_AUTHORIZATION));
            if (httpToken == null) {
                HttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpProxyServer.UNAUTHORIZED);
                response.headers().set(HttpHeaderNames.PROXY_AUTHENTICATE, authProvider.authType() + " realm=\"" + authProvider.authRealm() + "\"");
                ctx.writeAndFlush(response);
                return false;
            }
            HttpAuthContext.setToken(ctx.channel(), httpToken);
        }
        return true;
    }

    private void handleProxyData(Channel channel, Object msg, boolean isHttp) throws Exception {
        if (cf == null) {
            // connection?????? ??????HttpContent??????????????????
            if (isHttp && !(msg instanceof HttpRequest)) {
                return;
            }
            if (interceptPipeline == null) {
                interceptPipeline = buildOnlyConnectPipeline();
                interceptPipeline.setRequestProto(requestProto.copy());
            }
            interceptPipeline.beforeConnect(channel);

            // by default we use the proxy config set in the pipeline
            ProxyHandler proxyHandler = ProxyHandleFactory.build(interceptPipeline.getProxyConfig() == null ?
                    proxyConfig : interceptPipeline.getProxyConfig());

            RequestProto requestProto = interceptPipeline.getRequestProto();
            if (isHttp) {
                HttpRequest httpRequest = (HttpRequest) msg;
                // ??????requestProto???????????????
                RequestProto newRP = ProtoUtil.getRequestProto(httpRequest);
                if (!newRP.equals(requestProto)) {
                    // ??????Host?????????
                    if ((requestProto.getSsl() && requestProto.getPort() == 443)
                            || (!requestProto.getSsl() && requestProto.getPort() == 80)) {
                        httpRequest.headers().set(HttpHeaderNames.HOST, requestProto.getHost());
                    } else {
                        httpRequest.headers().set(HttpHeaderNames.HOST, requestProto.getHost() + ":" + requestProto.getPort());
                    }
                }
            }

            /*
             * ??????SSL client hello???Server Name Indication extension(SNI??????) ?????????????????????client
             * hello??????SNI????????????????????????Received fatal alert: handshake_failure(????????????)
             * ?????????https://cdn.mdn.mozilla.net/static/img/favicon32.7f3da72dcea1.png
             */
            ChannelInitializer channelInitializer = isHttp ? new HttpProxyInitializer(channel, requestProto, proxyHandler)
                    : new TunnelProxyInitializer(channel, proxyHandler);
            Bootstrap bootstrap = new Bootstrap();
            bootstrap.group(serverConfig.getProxyLoopGroup()) // ???????????????
                    .channel(NioSocketChannel.class) // ??????NioSocketChannel?????????????????????channel???
                    .handler(channelInitializer);
            if (proxyHandler != null) {
                // ?????????????????????DNS?????????
                bootstrap.resolver(NoopAddressResolverGroup.INSTANCE);
            } else {
                bootstrap.resolver(serverConfig.resolver());
            }
            requestList = new LinkedList();
            cf = bootstrap.connect(requestProto.getHost(), requestProto.getPort());
            cf.addListener((ChannelFutureListener) future -> {
                if (future.isSuccess()) {
                    future.channel().writeAndFlush(msg);
                    synchronized (requestList) {
                        requestList.forEach(obj -> future.channel().writeAndFlush(obj));
                        requestList.clear();
                        isConnect = true;
                    }
                } else {
                    requestList.forEach(obj -> ReferenceCountUtil.release(obj));
                    requestList.clear();
                    getExceptionHandle().beforeCatch(channel, future.cause());
                    future.channel().close();
                    channel.close();
                }
            });
        } else {
            synchronized (requestList) {
                if (isConnect) {
                    cf.channel().writeAndFlush(msg);
                } else {
                    requestList.add(msg);
                }
            }
        }
    }

    private HttpProxyInterceptPipeline buildPipeline() {
        HttpProxyInterceptPipeline interceptPipeline = new HttpProxyInterceptPipeline(new HttpProxyIntercept() {
            @Override
            public void beforeRequest(Channel clientChannel, HttpRequest httpRequest, HttpProxyInterceptPipeline pipeline)
                    throws Exception {
                handleProxyData(clientChannel, httpRequest, true);
            }

            @Override
            public void beforeRequest(Channel clientChannel, HttpContent httpContent, HttpProxyInterceptPipeline pipeline)
                    throws Exception {
                handleProxyData(clientChannel, httpContent, true);
            }

            @Override
            public void afterResponse(Channel clientChannel, Channel proxyChannel, HttpResponse httpResponse,
                                      HttpProxyInterceptPipeline pipeline) throws Exception {
                clientChannel.writeAndFlush(httpResponse);
                if (HttpHeaderValues.WEBSOCKET.toString().equals(httpResponse.headers().get(HttpHeaderNames.UPGRADE))) {
                    // websocket??????????????????
                    proxyChannel.pipeline().remove("httpCodec");
                    clientChannel.pipeline().remove("httpCodec");
                }
            }

            @Override
            public void afterResponse(Channel clientChannel, Channel proxyChannel, HttpContent httpContent,
                                      HttpProxyInterceptPipeline pipeline) throws Exception {
                clientChannel.writeAndFlush(httpContent);
            }
        });
        interceptInitializer.init(interceptPipeline);
        return interceptPipeline;
    }

    // fix issue #186: ?????????https???????????????????????????????????????????????????????????????????????????????????????
    private HttpProxyInterceptPipeline buildOnlyConnectPipeline() {
        HttpProxyInterceptPipeline interceptPipeline = new HttpProxyInterceptPipeline(new HttpProxyIntercept());
        interceptInitializer.init(interceptPipeline);
        return interceptPipeline;
    }
}
