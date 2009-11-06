package org.littleshoot.proxy;

import java.io.UnsupportedEncodingException;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;

import org.apache.commons.lang.StringUtils;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Default authentication manager that simply processes each authentication
 * handler in the order it was added.
 * 
 * See: http://tools.ietf.org/html/rfc2617
 */
public class DefaultProxyAuthorizationManager implements
    ProxyAuthorizationManager {

    private final Logger m_log = LoggerFactory.getLogger(getClass());
    private final Collection<ProxyAuthorizationHandler> m_handlers =
        new LinkedList<ProxyAuthorizationHandler>();
    
    public void addHandler(final ProxyAuthorizationHandler pah) {
        this.m_handlers.add(pah);
    }

    public boolean handleProxyAuthorization(final HttpRequest request,
        final ChannelHandlerContext ctx) {
        if (!request.containsHeader("Proxy-Authorization")) {
            if (!m_handlers.isEmpty()) {
                rejectRequest(ctx);
                return false;
            }
            return true;
        }
        
        final List<String> values = request.getHeaders("Proxy-Authorization");
        final String fullValue = values.iterator().next();
        final String value =
            StringUtils.substringAfter(fullValue, "Basic ").trim();
        final byte[] decodedValue = Base64.decode(value);
        try {
            final String decodedString = new String(decodedValue, "UTF-8");
            final String userName = StringUtils.substringBefore(decodedString, ":");
            final String password = StringUtils.substringAfter(decodedString, ":");
            for (final ProxyAuthorizationHandler handler : this.m_handlers) {
                if (!handler.authenticate(userName, password)) {
                    rejectRequest(ctx);
                    return false;
                }
            }
        }
        catch (final UnsupportedEncodingException e) {
            m_log.error("Could not decode?", e);
        }
        
        m_log.info("Got proxy authorization!");
        // We need to remove the header before sending the request on.
        final String authentication = 
            request.getHeader("Proxy-Authorization");
        m_log.info(authentication);
        request.removeHeader("Proxy-Authorization");
        return true;
    }

    private void rejectRequest(final ChannelHandlerContext ctx) {
        final String statusLine = "HTTP/1.1 407 Proxy Authentication Required\r\n";
        final String headers = 
            "Date: "+ProxyUtils.httpDate()+"\r\n"+
            "Proxy-Authenticate: Basic realm=\"Restricted Files\"\r\n"+
            "Content-Length: 415\r\n"+
            "Content-Type: text/html; charset=iso-8859-1\r\n" +
            "\r\n";
        
        final String responseBody = 
            "<!DOCTYPE HTML PUBLIC \"-//IETF//DTD HTML 2.0//EN\">\n"+
            "<html><head>\n"+
            "<title>407 Proxy Authentication Required</title>\n"+
            "</head><body>\n"+
            "<h1>Proxy Authentication Required</h1>\n"+
            "<p>This server could not verify that you\n"+
            "are authorized to access the document\n"+
            "requested.  Either you supplied the wrong\n"+
            "credentials (e.g., bad password), or your\n"+
            "browser doesn't understand how to supply\n"+
            "the credentials required.</p>\n"+
            "</body></html>\n";
        m_log.info("Content-Length is really: "+responseBody.length());
        ProxyUtils.writeResponse(ctx.getChannel(), statusLine, headers, responseBody);
    }
}
