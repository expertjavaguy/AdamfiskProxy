package org.littleshoot.proxy;

import org.jboss.netty.channel.ChannelHandler;
import org.jboss.netty.handler.ssl.SslHandler;

public class SslHandshakeHandler implements HandshakeHandler {

    private final String id;
    private final SslHandler sslHandler;

    public SslHandshakeHandler(final String id, final SslHandler sslHandler) {
        this.id = id;
        this.sslHandler = sslHandler;
    }

    @Override
    public ChannelHandler getChannelHandler() {
        return this.sslHandler;
    }

    @Override
    public String getId() {
        return this.id;
    }

}
