package autismclient.security;

import java.net.InetAddress;
import java.net.UnknownHostException;

public final class AutismProtectorLocalAddressUtil {

    public static volatile String serverAddress;

    private AutismProtectorLocalAddressUtil() {
    }

    public static boolean isLocalAddress(String host) throws UnknownHostException {
        if (host == null) return false;
        for (InetAddress address : InetAddress.getAllByName(host)) {
            if (address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isSiteLocalAddress()
                || address.isLinkLocalAddress()) {
                return true;
            }
        }
        return false;
    }

    public static boolean shouldBlock(String host) {
        try {
            if (!isLocalAddress(host)) return false;

            return !isLocalAddress(serverAddress);
        } catch (UnknownHostException unresolved) {

            return false;
        }
    }
}
