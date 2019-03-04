/*
 * Copyright (c) 2019. Edit By pompip.cn
 */

package cn.pompip.androidcontrol;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.websocketx.*;
import io.netty.util.CharsetUtil;
import org.apache.commons.lang3.ArrayUtils;

/**
 * Created by harry on 2017/4/18.
 */
public class WSSocketHandler extends SimpleChannelInboundHandler<Object> {

    WebSocketServerHandshaker handshaker;
    WebsocketEvent event;
    byte binaryCache[] = new byte[0];

    public WSSocketHandler(WebsocketEvent event) {
        this.event = event;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        if (event != null) {
            event.onConnect(ctx);
        }
    }
    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        if (event != null) {
            event.onDisconnect(ctx);
        }
    }
    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof FullHttpRequest) {
            handleHttpRequest(ctx, ((FullHttpRequest) msg));
        } else if (msg instanceof WebSocketFrame) {
            handlerWebSocketFrame(ctx, (WebSocketFrame) msg);
        }
    }
    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) throws Exception {
        ctx.flush();
    }

    private void handlerWebSocketFrame(ChannelHandlerContext ctx, WebSocketFrame frame) {
        // 判断是否关闭链路的指令
        if (frame instanceof CloseWebSocketFrame) {
            handshaker.close(ctx.channel(), (CloseWebSocketFrame) frame.retain());
            return;
        }
        // 判断是否ping消息
        if (frame instanceof PingWebSocketFrame) {
            ctx.channel().write(new PongWebSocketFrame(frame.content().retain()));
            return;
        }

        if (frame instanceof TextWebSocketFrame) {
            if (event != null) {
                event.onTextMessage(ctx, ((TextWebSocketFrame) frame).text());
            }
        } else if (frame instanceof BinaryWebSocketFrame || frame instanceof ContinuationWebSocketFrame) {
            if (event != null) {
                ByteBuf byteBuf = frame.content();
                byte[] bytes = new byte[byteBuf.readableBytes()];
                byteBuf.getBytes(0, bytes);
                binaryCache = ArrayUtils.addAll(binaryCache, bytes);
                if (frame.isFinalFragment()) {
                    byte[] b = binaryCache;
                    binaryCache = new byte[0];
                    event.onBinaryMessage(ctx, b);
                }
            }
        }
    }

    private void handleHttpRequest(ChannelHandlerContext ctx, FullHttpRequest req) {
        if (!req.getDecoderResult().isSuccess()
                || (!"websocket".equals(req.headers().get("Upgrade")))) {
            if (event != null) {
                DefaultFullHttpResponse response = event.onHttpRequest(ctx, req);
                if (response != null) {
                    sendHttpResponse(ctx, req, response);
                    return;
                }
            }
            sendHttpResponse(ctx, req, new DefaultFullHttpResponse(
                    HttpVersion.HTTP_1_1, HttpResponseStatus.BAD_REQUEST));
            return;
        }
        WebSocketServerHandshakerFactory wsFactory = new WebSocketServerHandshakerFactory(
                null, null, true, Integer.MAX_VALUE, true);
        handshaker = wsFactory.newHandshaker(req);
        if (handshaker == null) {
            WebSocketServerHandshakerFactory.sendUnsupportedWebSocketVersionResponse(ctx.channel());
        } else {
            handshaker.handshake(ctx.channel(), req);
        }
    }

    private static void sendHttpResponse(ChannelHandlerContext ctx, FullHttpRequest req, DefaultFullHttpResponse res) {
        // 返回应答给客户端
        if (res.getStatus().code() != 200) {
            ByteBuf buf = Unpooled.copiedBuffer(res.getStatus().toString(),
                    CharsetUtil.UTF_8);
            res.content().writeBytes(buf);
            buf.release();
        }
        // 如果是非Keep-Alive，关闭连接
        ChannelFuture f = ctx.channel().writeAndFlush(res);
        if (!isKeepAlive(req) || res.getStatus().code() != 200) {
            f.addListener(ChannelFutureListener.CLOSE);
        }
    }

    private static boolean isKeepAlive(FullHttpRequest req) {
        return false;
    }
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause)
            throws Exception {
        cause.printStackTrace();
        ctx.close();
    }
}
