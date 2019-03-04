/*
 * Copyright (c) 2019. Edit By pompip.cn
 */

package cn.pompip.androidcontrol.server;

import com.alibaba.fastjson.JSONObject;
import cn.pompip.adb.AdbDevice;
import cn.pompip.adb.AdbServer;
import cn.pompip.androidcontrol.*;
import cn.pompip.androidcontrol.client.LocalClient;
import cn.pompip.androidcontrol.message.BinaryMessage;
import cn.pompip.androidcontrol.message.FileMessage;
import cn.pompip.minicap.*;
import cn.pompip.util.Constant;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import org.apache.commons.lang3.StringUtils;
import org.apache.log4j.Logger;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;

/**
 * Created by harry on 2017/4/18.
 */
public class LocalServer extends BaseServer {
    private static Logger logger = Logger.getLogger(LocalServer.class);
    
    private int port = -1;
    List<Protocol> protocolList;

    public LocalServer(int port) {
        listen(port);
        protocolList = new LinkedList<Protocol>();
    }

    public void listen(int port) {
        this.port = port;
    }

    public void start() throws InterruptedException {
        EventLoopGroup bossGroup = new NioEventLoopGroup();
        EventLoopGroup workerGroup = new NioEventLoopGroup();

        ServerBootstrap bootstrap = new ServerBootstrap();
        bootstrap.group(bossGroup, workerGroup).
                channel(NioServerSocketChannel.class).
                childOption(ChannelOption.SO_KEEPALIVE, true).
                childHandler(new ChildChannel(new LocalServerWebsocketEventImp()));
        System.out.println("LocalServer will start at port: " + port);
        System.out.println("--------\r\n");
        ChannelFuture future = bootstrap.bind(port).sync();
        future.channel().closeFuture().sync();
    }

    private class LocalServerWebsocketEventImp extends WebsocketEvent {

        @Override
        public void onConnect(ChannelHandlerContext ctx) {
            logger.info("Websocket new connection!" + ctx.channel().remoteAddress());
        }

        @Override
        public void onDisconnect(ChannelHandlerContext ctx) {
            for (Protocol protocol : protocolList) {
                if (protocol.getBroswerSocket() != null && protocol.getBroswerSocket() == ctx) {
                    protocol.broswerDisconnect();
                    protocol.close();
                    protocolList.remove(protocol);
                    break;
                }

                if (protocol.getClientSocket() != null && protocol.getClientSocket() == ctx) {
                    protocol.clientDisconnect();
                    protocol.close();
                    protocolList.remove(protocol);
                    break;
                }
            }
            logger.info("Websocket lost connection!" + ctx.channel().remoteAddress());
        }

        @Override
        public void onTextMessage(ChannelHandlerContext ctx, String text) {
            Command command = Command.ParseCommand(text);
            if (command != null) {
                if (command.getSchem() != Command.Schem.WAITTING &&
                        command.getSchem() != Command.Schem.INPUT &&
                        command.getSchem() != Command.Schem.KEYEVENT) {
                    logger.info(command.getCommandString());
                }
                switch (command.getSchem()) {
                    case WAIT:
                        initLocalClient(ctx, command);
                        break;
                    case START:
                    case WAITTING:
                    case TOUCH:
                    case KEYEVENT:
                    case INPUT:
                    case PUSH:
                        executeCommand(ctx, command);
                        break;
                    case SHOT:
                        sendShot(ctx, command);
                        break;
                    case DEVICES:
                        sendDevicesJson(ctx);
                        break;
                }
            } else {
                // Invalid Commands
            }
        }

        @Override
        public void onBinaryMessage(ChannelHandlerContext ctx, byte[] data) {
            int headlen = (data[1] & 0xFF) << 8 | (data[0] & 0xFF);
            String infoJSON = new String(data, 2, headlen);
            BinaryMessage message = BinaryMessage.parse(infoJSON);

            if (message.getType().equals("file")) {
                FileMessage fileMessage = (FileMessage) message;
                File file = Constant.getTmpFile(fileMessage.name);
                if (fileMessage.offset == 0 && file.exists()) {
                    file.delete();
                }
                System.out.println(infoJSON);
                try {
                    FileOutputStream os = new FileOutputStream(file, true);
                    byte[] bs = Arrays.copyOfRange(data, 2 + headlen, data.length);
                    os.write(bs);
                    os.close();
                } catch (FileNotFoundException e) {
                    e.printStackTrace();
                } catch (IOException e) {
                    e.printStackTrace();
                }
                if (fileMessage.offset + fileMessage.packagesize == fileMessage.filesize) {
                    ctx.channel().writeAndFlush(new TextWebSocketFrame("message://upload file success"));
                }
            }
        }

        @Override
        public DefaultFullHttpResponse onHttpRequest(ChannelHandlerContext ctx, FullHttpRequest req) {
            DefaultFullHttpResponse response = onHttp(req);
            response.headers().add("Access-Control-Allow-Origin", "*");
            response.headers().add("Server", "AnroidControl-LocalServer");
            return response;
        }

        void initLocalClient(final ChannelHandlerContext ctx, Command command) {

            String sn = command.getString("sn", null);
            String key = command.getString("key", null);

            // 没有sn，默认第一个设备
            if (StringUtils.isEmpty(sn)) {
                AdbDevice iDevice = AdbServer.server().getFirstDevice();
                if (iDevice == null) {
                    ctx.channel().close();
                    return;
                }
                sn = iDevice.getIDevice().getSerialNumber();
            }

            JSONObject obj = new JSONObject();
            obj.put("sn", sn);
            obj.put("key", key);

            ctx.channel().writeAndFlush(new TextWebSocketFrame("open://" + obj.toJSONString()));

            Protocol protocol = new Protocol();
            protocol.setSn(sn);
            protocol.setKey(key);
            protocol.setBroswerSocket(ctx);
            protocolList.add(protocol);

            LocalClient localClient = new LocalClient(protocol);
            protocol.setLocalClient(localClient);
        }

        void executeCommand(ChannelHandlerContext ctx, Command command) {
            Protocol protocol = null;
            // 寻找与之匹配的protocol
            for (Protocol p : protocolList) {
                if (p.getBroswerSocket() != null && p.getBroswerSocket() == ctx) {
                    protocol = p;
                    break;
                }
                if (p.getClientSocket() != null && p.getClientSocket() == ctx) {
                    protocol = p;
                    break;
                }
            }
            if (protocol != null) {
                protocol.getLocalClient().executeCommand(ctx, command);
            }
        }

        void sendDevicesJson(ChannelHandlerContext ctx) {
            ctx.channel().writeAndFlush(new TextWebSocketFrame(getDevicesJSON()));
        }

        void sendShot(ChannelHandlerContext ctx, Command command) {
            String sn = command.getString("sn", null);
            Minicap cap = new Minicap(sn);
            ctx.channel().writeAndFlush(new BinaryWebSocketFrame(Unpooled.copiedBuffer(cap.takeScreenShot())));
        }

        DefaultFullHttpResponse onHttp(FullHttpRequest req) {
            String uri = req.uri();
            logger.info(uri);
            String uriPath = uri.substring(uri.indexOf("/") + 1);
            if (uriPath.startsWith("shot")) {
                // 获取serialNumber
                String[] s = uriPath.split("/");
                if (s.length == 2) {
                    return new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, Unpooled.copiedBuffer(new Minicap(s[1]).takeScreenShot()));
                } else {
                    return new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.NOT_FOUND);
                }
            } else if (uriPath.startsWith("devices")) {
                return new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, Unpooled.copiedBuffer(getDevicesJSON().getBytes()));
            }
            return new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.NOT_FOUND);
        }

    }

    private Protocol findProtocolByBrowser(ChannelHandlerContext ctx) {
        for (Protocol protocol : protocolList) {
            if (protocol.getBroswerSocket() != null && protocol.getBroswerSocket() == ctx) {
                return protocol;
            }
        }
        return null;
    }

    private Protocol findProtocolByClient(ChannelHandlerContext ctx) {
        for (Protocol protocol : protocolList) {
            if (protocol.getClientSocket() != null && protocol.getClientSocket() == ctx) {
                return protocol;
            }
        }
        return null;
    }

}
