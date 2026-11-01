package com.emirchat.net;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Finds the LAN addresses a peer could use to reach this machine. */
public final class LocalAddresses {
    private LocalAddresses() {}

    /** Private IPv4 addresses of interfaces that are up, most likely first (Wi-Fi/Ethernet before VPNs). */
    public static List<String> lan() {
        List<String> result = new ArrayList<>();
        try {
            for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nif.isUp() || nif.isLoopback() || nif.isVirtual() || nif.isPointToPoint()) continue;
                for (InetAddress address : Collections.list(nif.getInetAddresses())) {
                    if (address instanceof Inet4Address && address.isSiteLocalAddress()) {
                        result.add(address.getHostAddress());
                    }
                }
            }
        } catch (SocketException e) {
            // fall through with whatever we found
        }
        return result;
    }
}
