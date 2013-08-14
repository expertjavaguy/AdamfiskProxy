package org.littleshoot.proxy.newstyle;

public enum ConnectionState {
    CONNECTING, // Connection attempting to connect
    AWAITING_PROXY_AUTHENTICATION, // Connected but waiting for proxy
    // authentication,
    AWAITING_INITIAL, // Connected and awaiting initial message (e.g.
                      // HttpRequest or HttpResponse)
    AWAITING_CHUNK, // Connected and awaiting HttpContent chunk
    TUNNELING, // Connected and tunneling raw ByteBufs
    DISCONNECT_REQUESTED, // We've asked the client to disconnect, but it hasn't
                          // yet
    DISCONNECTED // Disconnected
}
