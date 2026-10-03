package dev.ghost.nearbyim.core;
import java.net.*;
import dev.ghost.nearbyim.i18n.LocalizedIllegalArgumentException;
import dev.ghost.nearbyim.i18n.UiText;
public final class LocalEndpoint {
    public final InetAddress address; public final int port;
    private LocalEndpoint(InetAddress address, int port) { this.address = address; this.port = port; }
    public static LocalEndpoint parse(String text) {
        if (text == null) throw new LocalizedIllegalArgumentException(UiText.of("endpointRequired"));
        String input = text.trim(), host, portText;
        if (input.startsWith("[")) {
            int end = input.indexOf(']');
            if (end < 2 || end + 1 >= input.length() || input.charAt(end + 1) != ':') throw bad();
            host = input.substring(1, end); portText = input.substring(end + 2);
            if (!host.contains(":") || !host.matches("[0-9a-fA-F:.]+")) throw bad();
        } else {
            int split = input.lastIndexOf(':'); if (split < 1) throw bad();
            host = input.substring(0, split); portText = input.substring(split + 1);
            if (!host.matches("[0-9.]+")) throw bad();
            String[] octets = host.split("\\.", -1); if (octets.length != 4) throw bad();
            for (String octet : octets) {
                if (octet.isEmpty() || octet.length() > 3 || (octet.length() > 1 && octet.startsWith("0"))) throw bad();
                try { if (Integer.parseInt(octet) > 255) throw bad(); } catch (NumberFormatException e) { throw bad(); }
            }
        }
        try {
            if (!portText.matches("[0-9]{1,5}")) throw bad();
            int port = Integer.parseInt(portText); if (port < 1 || port > 65535) throw bad();
            InetAddress address = InetAddress.getByName(host);
            if (!isLocal(address)) throw new LocalizedIllegalArgumentException(UiText.of("localAddressOnly"));
            return new LocalEndpoint(address, port);
        } catch (UnknownHostException | NumberFormatException e) { throw bad(); }
    }
    public static boolean isLocal(InetAddress address) {
        byte[] bytes = address.getAddress();
        return !address.isAnyLocalAddress() && !address.isLoopbackAddress() && !address.isMulticastAddress()
                && (address.isSiteLocalAddress() || address.isLinkLocalAddress()
                    || (bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc));
    }
    private static IllegalArgumentException bad() { return new LocalizedIllegalArgumentException(UiText.of("invalidEndpoint")); }
}
