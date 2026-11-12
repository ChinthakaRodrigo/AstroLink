package udp;

import config.NetworkConfig;
import server.AstroServer;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * OPTIONAL UDP demonstration module (server side).
 *
 * UDP is CONNECTIONLESS: there is no accept(), no handshake and no stream.
 * One DatagramSocket receives independent DatagramPackets from any station.
 * Each packet carries its sender's IP address and port, which we use to send a reply.
 *
 * UDP does not guarantee delivery or ordering, so the server checks the sequence
 * numbers and reports missing or out-of-order datagrams.
 *
 * This class EXTENDS Thread (thread creation method 1): it is a single long-running
 * background job that is started exactly once, so extending Thread is simple and clear.
 */
public class UDPServer extends Thread {

    private final int port;
    private volatile boolean running = true;
    private volatile DatagramSocket socket;

    /** Highest sequence number seen per station (thread-safe map). */
    private final Map<String, Long> highestSequence = new ConcurrentHashMap<>();

    public UDPServer(int port) {
        super("UDP-Server");
        this.port = port;
        setDaemon(true);   // does not keep the JVM alive on its own
    }

    @Override
    public void run() {
        try (DatagramSocket datagramSocket = new DatagramSocket(port, InetAddress.getByName(NetworkConfig.BIND_ADDRESS))) {
            socket = datagramSocket;
            AstroServer.log("UDP DatagramSocket listening on " + NetworkConfig.BIND_ADDRESS + ":" + port
                    + " (connectionless - no accept())");
            byte[] buffer = new byte[NetworkConfig.UDP_BUFFER_SIZE];

            while (running) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                datagramSocket.receive(packet);   // blocks until ONE datagram arrives
                handleDatagram(datagramSocket, packet);
            }
        } catch (SocketException e) {
            if (running) {
                AstroServer.log("UDP socket error on port " + port + ": " + e.getMessage());
            }
        } catch (IOException e) {
            AstroServer.log("UDP I/O error: " + e.getMessage());
        }
        AstroServer.log("UDP-Server thread finishing -> state will be TERMINATED.");
    }

    private void handleDatagram(DatagramSocket datagramSocket, DatagramPacket packet) {
        String sender = packet.getAddress().getHostAddress() + ":" + packet.getPort();
        UDPMessage message = UDPMessage.fromBytes(packet.getData(), packet.getOffset(), packet.getLength());
        if (message == null || message.getStationId() == null) {
            AstroServer.log("UDP datagram from " + sender + " is not valid AstroNet JSON (" + packet.getLength() + " bytes) - ignored.");
            return;
        }

        AstroServer.log("UDP datagram from " + sender + " (" + packet.getLength() + " bytes): " + message);
        checkSequence(message);

        // Reply to the address/port stored in the received packet.
        try {
            UDPMessage ack = new UDPMessage("ACK", NetworkConfig.SERVER_ID, message.getSequenceNumber(),
                    "RECEIVED", "Server received UDP datagram #" + message.getSequenceNumber());
            byte[] data = ack.toBytes();
            datagramSocket.send(new DatagramPacket(data, data.length, packet.getAddress(), packet.getPort()));
        } catch (IOException e) {
            AstroServer.log("Could not send UDP ACK to " + sender + ": " + e.getMessage());
        }
    }

    /** Detects lost or re-ordered datagrams using sequence numbers. */
    private void checkSequence(UDPMessage message) {
        String stationId = message.getStationId();
        long sequence = message.getSequenceNumber();
        Long previousHighest = highestSequence.get(stationId);

        if (previousHighest == null) {
            highestSequence.put(stationId, sequence);
            return;
        }
        if (sequence <= previousHighest) {
            AstroServer.log("  UDP ORDERING: #" + sequence + " from " + stationId + " arrived AFTER #" + previousHighest
                    + " (out of order or duplicate) - UDP does not guarantee ordering.");
            return;
        }
        if (sequence > previousHighest + 1) {
            AstroServer.log("  UDP GAP: expected #" + (previousHighest + 1) + " from " + stationId + " but got #" + sequence
                    + " -> " + (sequence - previousHighest - 1) + " datagram(s) missing so far (lost or delayed).");
        }
        highestSequence.put(stationId, sequence);
    }

    /** Stops the thread by closing the socket (this unblocks receive()). */
    public void shutdown() {
        running = false;
        DatagramSocket current = socket;
        if (current != null) {
            current.close();
        }
    }
}
