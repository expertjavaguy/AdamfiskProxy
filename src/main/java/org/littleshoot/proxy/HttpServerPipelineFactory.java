package org.littleshoot.proxy;

import static org.jboss.netty.channel.Channels.pipeline;

import java.util.Map;
import java.util.concurrent.Executors;

import org.jboss.netty.channel.ChannelPipeline;
import org.jboss.netty.channel.ChannelPipelineFactory;
import org.jboss.netty.channel.group.ChannelGroup;
import org.jboss.netty.channel.socket.ClientSocketChannelFactory;
import org.jboss.netty.channel.socket.nio.NioClientSocketChannelFactory;
import org.jboss.netty.handler.codec.http.HttpRequestDecoder;

/**
 * Factory for creating pipelines for incoming requests to our listening
 * socket.
 */
public class HttpServerPipelineFactory implements ChannelPipelineFactory {
    
    private final ProxyAuthorizationManager authenticationManager;
    private final ChannelGroup channelGroup;
    private final Map<String, HttpFilter> filters;
    private final String chainProxyHostAndPort;
    
    private final ClientSocketChannelFactory clientSocketChannelFactory =
        new NioClientSocketChannelFactory(
            Executors.newCachedThreadPool(),
            Executors.newCachedThreadPool());
    private final ProxyCacheManager cacheManager = 
        new DefaultProxyCacheManager();

    /**
     * Creates a new pipeline factory with the specified class for processing
     * proxy authentication.
     * 
     * @param authorizationManager The manager for proxy authentication.
     * @param channelGroup The group that keeps track of open channels.
     * @param filters HTTP filters to apply.
     * @param chainProxyHostAndPort upstream proxy server host and port or null if none used.
     */
    public HttpServerPipelineFactory(
        final ProxyAuthorizationManager authorizationManager, 
        final ChannelGroup channelGroup, 
        final Map<String, HttpFilter> filters,
        final String chainProxyHostAndPort) {
        this.authenticationManager = authorizationManager;
        this.channelGroup = channelGroup;
        this.filters = filters;
        this.chainProxyHostAndPort = chainProxyHostAndPort;
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            public void run() {
                clientSocketChannelFactory.releaseExternalResources();
            }
        }));
    }
    
    public HttpServerPipelineFactory(
            final ProxyAuthorizationManager authorizationManager, 
            final ChannelGroup channelGroup, 
            final Map<String, HttpFilter> filters) {
    	this(authorizationManager, channelGroup, filters, null);
    }
    
    public ChannelPipeline getPipeline() throws Exception {
        final ChannelPipeline pipeline = pipeline();

        // Uncomment the following line if you want HTTPS
        //SSLEngine engine = SecureChatSslContextFactory.getServerContext().createSSLEngine();
        //engine.setUseClientMode(false);
        //pipeline.addLast("ssl", new SslHandler(engine));
        
        // We want to allow longer request lines, headers, and chunks respectively.
        pipeline.addLast("decoder", new HttpRequestDecoder());
        pipeline.addLast("encoder", new ProxyHttpResponseEncoder(cacheManager));
        pipeline.addLast("handler", 
            new HttpRequestHandler(this.cacheManager, authenticationManager, 
                this.channelGroup, this.filters, 
                this.clientSocketChannelFactory,
                this.chainProxyHostAndPort));
        return pipeline;
    }
}
