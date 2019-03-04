/*
 * Copyright (c) 2019. Edit By pompip.cn
 */

package cn.pompip.androidcontrol;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;

/**
 * Created by harry on 2017/4/18.
 */
public abstract class WebsocketEvent {
    public abstract void onConnect(ChannelHandlerContext ctx);
    public abstract void onDisconnect(ChannelHandlerContext ctx);
    public abstract void onTextMessage(ChannelHandlerContext ctx, String text);
    public abstract void onBinaryMessage(ChannelHandlerContext ctx, byte[] data);
    public DefaultFullHttpResponse onHttpRequest(ChannelHandlerContext ctx, FullHttpRequest req) {
        return null;
    }
}
