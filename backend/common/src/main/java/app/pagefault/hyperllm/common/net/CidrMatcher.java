package app.pagefault.hyperllm.common.net;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

/**
 * Utility for checking IPv4/IPv6 client addresses against PostgreSQL {@code INET}/{@code CIDR} allowlists.
 */
public final class CidrMatcher {

    private CidrMatcher() {
    }

    public static boolean isAllowed(String clientIp, List<String> allowedCidrs) {
        if (allowedCidrs == null || allowedCidrs.isEmpty()) {
            return true;
        }
        if (clientIp == null || clientIp.isBlank()) {
            return false;
        }
        InetAddress clientAddr = parseAddress(clientIp.trim());
        if (clientAddr == null) {
            return false;
        }
        for (String cidr : allowedCidrs) {
            if (cidr != null && matches(clientAddr, cidr.trim())) {
                return true;
            }
        }
        return false;
    }

    public static boolean matches(InetAddress address, String cidr) {
        int slash = cidr.indexOf('/');
        String ipPart = (slash >= 0) ? cidr.substring(0, slash) : cidr;
        InetAddress network = parseAddress(ipPart);
        if (network == null) {
            return false;
        }
        byte[] addrBytes = address.getAddress();
        byte[] netBytes = network.getAddress();
        if (addrBytes.length != netBytes.length) {
            return false;
        }
        int prefixLength = (slash >= 0)
                ? parsePrefix(cidr.substring(slash + 1), addrBytes.length * 8)
                : addrBytes.length * 8;
        if (prefixLength < 0) {
            return false;
        }
        int fullBytes = prefixLength / 8;
        for (int i = 0; i < fullBytes; i++) {
            if (addrBytes[i] != netBytes[i]) {
                return false;
            }
        }
        int remBits = prefixLength % 8;
        if (remBits == 0) {
            return true;
        }
        int mask = (0xFF << (8 - remBits)) & 0xFF;
        return (addrBytes[fullBytes] & mask) == (netBytes[fullBytes] & mask);
    }

    private static int parsePrefix(String raw, int maxBits) {
        try {
            int val = Integer.parseInt(raw.trim());
            return (val >= 0 && val <= maxBits) ? val : -1;
        } catch (NumberFormatException ex) {
            return -1;
        }
    }

    private static InetAddress parseAddress(String ip) {
        try {
            return InetAddress.getByName(ip);
        } catch (UnknownHostException ex) {
            return null;
        }
    }
}
