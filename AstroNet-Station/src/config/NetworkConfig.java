package config;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Network settings shared by the server and the stations.
 *
 * The TCP and UDP ports are DEFAULTS. Both can be changed at start-up
 * (command-line arguments or console prompts), so nothing is hard-coded to localhost.
 *
 * NOTE: this file is identical in AstroNet-Server and AstroNet-Station.
 */
public final class NetworkConfig {

    /** Server binds to all network interfaces so stations on OTHER computers can reach it. */
    public static final String BIND_ADDRESS = "0.0.0.0";

    /** Default TCP port for the main (reliable) AstroNet protocol. */
    public static final int DEFAULT_TCP_PORT = 5000;

    /** Default UDP port for the optional heartbeat / status datagram demo. */
    public static final int DEFAULT_UDP_PORT = 5001;

    /** The network has exactly five stations. */
    public static final int MAX_STATIONS = 5;

    /** Station gives up connecting after this time (milliseconds). */
    public static final int CONNECT_TIMEOUT_MS = 5000;

    /** A new connection must send REGISTER within this time (milliseconds). */
    public static final int REGISTRATION_TIMEOUT_MS = 30000;

    /** How long the server waits for verification responses (seconds). */
    public static final int VERIFICATION_TIMEOUT_SECONDS = 15;

    /** Stations send a TCP HEARTBEAT this often (seconds). */
    public static final int HEARTBEAT_INTERVAL_SECONDS = 30;

    /** Maximum size of one UDP datagram used in the demo (bytes). */
    public static final int UDP_BUFFER_SIZE = 1024;

    /** Sender ID used by the central server in every message. */
    public static final String SERVER_ID = "SERVER";

    private NetworkConfig() {
        // constants only
    }

    /** Parses a port number; returns defaultPort if the text is empty or invalid. */
    public static int parsePort(String text, int defaultPort) {
        if (text == null || text.isBlank()) {
            return defaultPort;
        }
        try {
            int port = Integer.parseInt(text.trim());
            if (port >= 1 && port <= 65535) {
                return port;
            }
        } catch (NumberFormatException ignored) {
            // fall through to default
        }
        System.out.println("Invalid port '" + text + "', using default " + defaultPort);
        return defaultPort;
    }

    /**
     * Lists the IPv4 addresses of this computer's active, non-loopback network interfaces.
     * These are the addresses other computers on the LAN/VPN can use to reach this machine.
     */
    public static List<String> getLanIpAddresses() {
        List<String> addresses = new ArrayList<>();
        try {
            for (NetworkInterface networkInterface : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!networkInterface.isUp() || networkInterface.isLoopback()) {
                    continue;
                }
                for (InetAddress address : Collections.list(networkInterface.getInetAddresses())) {
                    if (address instanceof Inet4Address && !address.isLoopbackAddress()) {
                        addresses.add(address.getHostAddress() + "   (" + networkInterface.getDisplayName() + ")");
                    }
                }
            }
        } catch (SocketException e) {
            addresses.add("Could not read network interfaces: " + e.getMessage());
        }
        return addresses;
    }
}
