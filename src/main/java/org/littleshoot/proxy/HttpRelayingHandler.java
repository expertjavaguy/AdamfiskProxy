package org.littleshoot.proxy;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.lang.StringUtils;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.channel.ChannelFutureListener;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ChannelStateEvent;
import org.jboss.netty.channel.ExceptionEvent;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.SimpleChannelUpstreamHandler;
import org.jboss.netty.channel.group.ChannelGroup;
import org.jboss.netty.handler.codec.http.DefaultHttpResponse;
import org.jboss.netty.handler.codec.http.HttpChunk;
import org.jboss.netty.handler.codec.http.HttpHeaders;
import org.jboss.netty.handler.codec.http.HttpMessage;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.jboss.netty.handler.codec.http.HttpResponse;
import org.jboss.netty.handler.codec.http.HttpVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Class that simply relays traffic from a remote server the proxy is 
 * connected to back to the browser.
 */
public class HttpRelayingHandler extends SimpleChannelUpstreamHandler {
    
    private final Logger log = 
        LoggerFactory.getLogger(HttpRelayingHandler.class);
    
    private volatile boolean readingChunks;
    
    private final Channel browserToProxyChannel;

    private final ChannelGroup channelGroup;

    private final HttpFilter httpFilter;

    private final HttpRequest httpRequest;
    
    private HttpResponse httpResponse;

    //private final String originalUri;
    
    /**
     * Creates a new {@link HttpRelayingHandler} with the specified connection
     * to the browser.
     * 
     * @param browserToProxyChannel The browser connection.
     * @param channelGroup Keeps track of channels to close on shutdown.
     */
    public HttpRelayingHandler(final Channel browserToProxyChannel, 
        final ChannelGroup channelGroup, 
        final HttpRequest httpRequest) {
        this (browserToProxyChannel, channelGroup, new NoOpHttpFilter(), 
            httpRequest);
    }

    /**
     * Creates a new {@link HttpRelayingHandler} with the specified connection
     * to the browser.
     * 
     * @param browserToProxyChannel The browser connection.
     * @param channelGroup Keeps track of channels to close on shutdown.
     * @param filter The HTTP filter.
     */
    public HttpRelayingHandler(final Channel browserToProxyChannel,
        final ChannelGroup channelGroup, final HttpFilter filter,
        final HttpRequest httpRequest) {
        this.browserToProxyChannel = browserToProxyChannel;
        this.channelGroup = channelGroup;
        this.httpFilter = filter;
        this.httpRequest = httpRequest;
    }

    @Override
    public void messageReceived(final ChannelHandlerContext ctx, 
        final MessageEvent e) throws Exception {
        
        final Object messageToWrite;
        
        if (!readingChunks) {
            final HttpResponse hr = (HttpResponse) e.getMessage();
            httpResponse = hr;
            final HttpResponse response;
            
            // Double check the Transfer-Encoding, since it gets tricky.
            final String te = hr.getHeader(HttpHeaders.Names.TRANSFER_ENCODING);
            if (StringUtils.isNotBlank(te) && 
                te.equalsIgnoreCase(HttpHeaders.Values.CHUNKED)) {
                if (hr.getProtocolVersion() != HttpVersion.HTTP_1_1) {
                    log.warn("Fixing HTTP version.");
                    response = ProxyUtils.copyMutableResponseFields(hr, 
                        new DefaultHttpResponse(HttpVersion.HTTP_1_1, hr.getStatus()));
                    if (!response.containsHeader(HttpHeaders.Names.TRANSFER_ENCODING)) {
                        log.info("Adding chunked encoding header");
                        response.addHeader(HttpHeaders.Names.TRANSFER_ENCODING, 
                            HttpHeaders.Values.CHUNKED);
                    }
                }
                else {
                    response = hr;
                }
            }
            else {
                response = hr;
            }

            if (response.isChunked()) {
                log.info("Starting to read chunks");
                readingChunks = true;
            }
            final HttpResponse filtered = 
                this.httpFilter.filterResponse(response);
            messageToWrite = filtered;
            
            log.info("Headers sent to browser: ");
            ProxyUtils.printHeaders((HttpMessage) messageToWrite);
        } else {
            log.info("Processing a chunk");
            final HttpChunk chunk = (HttpChunk) e.getMessage();
            
            // TODO: Figure out a way to cache chunks. Possibly append the 
            // chunk number after the request URL, and store the chunk using
            // that as the key? 
            if (chunk.isLast()) {
                readingChunks = false;
            }
            messageToWrite = chunk;
        }
        
        if (browserToProxyChannel.isOpen()) {
            // At this point, the HTTP request for this given request and
            // response has of course been written, and it's in the encoder.
            final ChannelFuture ch = 
                browserToProxyChannel.write(
                    new ProxyHttpResponse(httpRequest, httpResponse, 
                        messageToWrite));
            

            final ChannelFutureListener cfl = 
                ProxyUtils.newWriteListener(httpRequest, httpResponse, 
                    messageToWrite);
            ch.addListener(cfl);
        }
        else {
            log.info("Channel not open. Connected? {}", 
                browserToProxyChannel.isConnected());
            // This will undoubtedly happen anyway, but just in case.
            if (e.getChannel().isOpen()) {
                log.info("Closing channel to remove server");
                e.getChannel().close();
            }
        }
    }
    
    @Override
    public void channelOpen(final ChannelHandlerContext ctx, 
        final ChannelStateEvent cse) throws Exception {
        final Channel ch = cse.getChannel();
        log.info("New channel opened from proxy to web: {}", ch);
        this.channelGroup.add(ch);
    }

    @Override
    public void channelClosed(final ChannelHandlerContext ctx, 
        final ChannelStateEvent e) throws Exception {
        log.info("Got closed event on proxy -> web connection: {}",
            e.getChannel());
        
        // This is vital this take place here and only here. If we handle this
        // in other listeners, it's possible to get close events before
        // we actually receive the HTTP response, in which case the response
        // might never get back to the browser. It has to do with the order
        // listeners are called in, but apparently the 
        closeOnFlush(browserToProxyChannel);
    }

    @Override
    public void exceptionCaught(final ChannelHandlerContext ctx, 
        final ExceptionEvent e) throws Exception {
        log.warn("Caught exception on proxy -> web connection: "+
            e.getChannel(), e.getCause());
        if (e.getChannel().isOpen()) {
            closeOnFlush(e.getChannel());
        }
    }

    /**
     * Closes the specified channel after all queued write requests are flushed.
     */
    private void closeOnFlush(final Channel ch) {
        log.info("Closing channel on flush: {}", ch);
        if (ch.isConnected()) {
            ch.write(ChannelBuffers.EMPTY_BUFFER).addListener(
                ChannelFutureListener.CLOSE);
        }
    }
}
