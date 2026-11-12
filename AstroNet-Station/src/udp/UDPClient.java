package udp;

import config.NetworkConfig;
import station.AstroStation;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * OPTIONAL UDP demonstration module (station side).
 *
 * Sends a few small JSON heartbeat datagrams to the server's UDP port.
 *   - No connection is made: every DatagramPacket carries the destination IP and port itself.
 *   - Delivery is NOT guaranteed: replies are awaited only briefly; missing replies are normal.
 *   - Ordering is NOT guaranteed: to make this visible on a fast LAN, the demo deliberately
 *     sends one datagram out of order and skips one sequence number (simulated loss).
 *     The server detects both using the sequence numbers.
 *
 * Alerts and verification responses are NEVER sent over UDP - they use the reliable TCP link.
 */
public final class UDPClient {

    private static final int DATAGRAMS_PER_TEST = 5;
    private static final int REPLY_TIMEOUT_MS = 1500;

    /** Sequence numbers keep increasing across tests, like a real heartbeat stream. */
    private static final AtomicLong SEQUENCE = new AtomicLong();

    private UDPClient() {
    }

    public static void sendTestDatagrams(String stationId, String serverIp, int udpPort) {
        // A new DatagramSocket on a random free local port; closed automatically (try-with-resources).
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(REPLY_TIMEOUT_MS);
            InetAddress serverAddress = InetAddress.getByName(serverIp);
            AstroStation.log("UDP test: local DatagramSocket port " + socket.getLocalPort() + " -> server "
                    + serverAddress.getHostAddress() + ":" + udpPort + " (no connection is established)");

            long base = SEQUENCE.get();
            // Send order: base+1, base+2, base+4, base+3, base+6  (base+5 intentionally skipped)
            long[] sendOrder = {base + 1, base + 2, base + 4, base + 3, base + 6};
            SEQUENCE.set(base + 6);

            for (long sequence : sendOrder) {
                UDPMessage message = new UDPMessage("HEARTBEAT", stationId, sequence, "OPERATIONAL",
                        "UDP status datagram #" + sequence);
                byte[] data = message.toBytes();
                DatagramPacket packet = new DatagramPacket(data, data.length, serverAddress, udpPort);
                socket.send(packet);   // returns immediately - UDP does not wait for the receiver
                AstroStation.log("UDP sent datagram #" + sequence + " (" + data.length + " bytes)");
            }
            AstroStation.log("UDP note: #" + (base + 5) + " was intentionally NOT sent (simulated loss) and #"
                    + (base + 3) + " was sent after #" + (base + 4) + " (simulated re-ordering).");

            int replies = 0;
            byte[] buffer = new byte[NetworkConfig.UDP_BUFFER_SIZE];
            for (int i = 0; i < DATAGRAMS_PER_TEST; i++) {
                DatagramPacket reply = new DatagramPacket(buffer, buffer.length);
                try {
                    socket.receive(reply);   // waits at most REPLY_TIMEOUT_MS
                    UDPMessage ack = UDPMessage.fromBytes(reply.getData(), reply.getOffset(), reply.getLength());
                    replies++;
                    AstroStation.log("UDP reply from " + reply.getAddress().getHostAddress() + ":" + reply.getPort()
                            + " -> " + (ack == null ? "(invalid)" : ack.getText()));
                } catch (SocketTimeoutException e) {
                    break;
                }
            }
            AstroStation.log("UDP test finished: " + DATAGRAMS_PER_TEST + " datagrams sent, " + replies
                    + " replies received. UDP gives no delivery or ordering guarantee"
                    + (replies == 0 ? " (no replies: is UDP port " + udpPort + " open in the server firewall?)." : "."));
        } catch (IOException e) {
            AstroStation.log("UDP test failed: " + e.getMessage());
        }
    }
}
