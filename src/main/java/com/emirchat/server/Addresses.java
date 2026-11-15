package com.emirchat.server;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.HexFormat;

/** How client addresses are counted for limits. */
final class Addresses {
    private Addresses() {}

    /**
     * The key an address is limited under: an IPv4 address as it is, an IPv6 address by its /64
     * network. One IPv6 customer usually has a whole /64 (2^64 addresses), so limiting single
     * addresses there would limit nothing.
     */
    static String key(InetAddress address) {
        if (address instanceof Inet6Address) {
            return HexFormat.of().formatHex(address.getAddress(), 0, 8) + "::/64";
        }
        return address.getHostAddress();
    }

    /**
     * Connections from this computer itself (an administrator, a health check, tests) are not
     * counted against the per-address limits.
     */
    static boolean local(InetAddress address) {
        return address.isLoopbackAddress();
    }
}
