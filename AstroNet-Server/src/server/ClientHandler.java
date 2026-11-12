package server;

import com.google.gson.JsonObject;
import common.AstronomicalEvent;
import common.JsonUtil;
import common.Message;
import common.MessageType;
import common.StationInfo;
import common.VerificationResult;
import config.NetworkConfig;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

/**
 * Handles the TCP conversation with ONE station.
 *
 * Implements Runnable (thread creation method 2) so it can be submitted to the
 * server's ExecutorService. Each connected station is served by its own handler
 * running on its own thread, therefore a slow or silent station never blocks the others.
 *
 * Protocol: newline-delimited JSON (one Message per line), UTF-8.
 *   1. The first line must be REGISTER.
 *   2. Afterwards the station may send OBSERVATION_ALERT, VERIFICATION_RESPONSE,
 *      HEARTBEAT and DISCONNECT.
 */
public class ClientHandler implements Runnable {

    private final Socket socket;
    private final StationManager stationManager;
    private final AlertManager alertManager;
    private final VerificationManager verificationManager;
    private final String remoteEndpoint;

    private BufferedReader reader;
    private PrintWriter writer;
    private volatile StationInfo stationInfo;
    private volatile boolean active = true;

    public ClientHandler(Socket socket, StationManager stationManager,
                         AlertManager alertManager, VerificationManager verificationManager) {
        this.socket = socket;
        this.stationManager = stationManager;
        this.alertManager = alertManager;
        this.verificationManager = verificationManager;
        this.remoteEndpoint = socket.getInetAddress().getHostAddress() + ":" + socket.getPort();
    }

    @Override
    public void run() {
        Thread currentThread = Thread.currentThread();
        String originalThreadName = currentThread.getName();
        AstroServer.log("Handler task started for " + remoteEndpoint + " (thread state = " + currentThread.getState() + ")");

        try {
            // Byte streams of the socket wrapped into character streams (UTF-8).
            InputStream inputStream = socket.getInputStream();
            OutputStream outputStream = socket.getOutputStream();
            reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
            writer = new PrintWriter(new BufferedWriter(new OutputStreamWriter(outputStream, StandardCharsets.UTF_8)), false);
            socket.setKeepAlive(true);
            socket.setTcpNoDelay(true);

            if (!performRegistration()) {
                return;   // finally{} closes the socket
            }
            currentThread.setName("Handler-" + stationInfo.getStationId());

            // Main receive loop: readLine() BLOCKS this thread (only this one) until a line arrives.
            String line;
            while (active && (line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                Message message = JsonUtil.parseMessage(line);
                if (message == null) {
                    AstroServer.log("Invalid JSON from " + getStationId() + ": " + JsonUtil.abbreviate(line, 120));
                    sendError("Invalid JSON message - expected one AstroNet message per line");
                    continue;
                }
                handleMessage(message, line);
            }
            if (active) {
                AstroServer.log(getStationId() + " closed the TCP connection (end of stream).");
            }
        } catch (SocketTimeoutException e) {
            AstroServer.log("No REGISTER received from " + remoteEndpoint + " within "
                    + NetworkConfig.REGISTRATION_TIMEOUT_MS / 1000 + " s - closing connection.");
        } catch (IOException e) {
            if (active) {
                AstroServer.log("Connection with " + getStationId() + " lost: " + e.getMessage());
            }
        } catch (RuntimeException e) {
            AstroServer.log("Unexpected error in handler for " + getStationId() + ": " + e);
        } finally {
            cleanup();
            AstroServer.log("Handler task for " + remoteEndpoint + " finished; pool thread is released.");
            currentThread.setName(originalThreadName);
        }
    }

    /** Reads and validates the first message, which must be REGISTER. */
    private boolean performRegistration() throws IOException {
        socket.setSoTimeout(NetworkConfig.REGISTRATION_TIMEOUT_MS);   // do not wait forever for REGISTER
        String line = reader.readLine();
        if (line == null) {
            AstroServer.log(remoteEndpoint + " disconnected before registering.");
            return false;
        }
        Message message = JsonUtil.parseMessage(line);
        if (message == null || message.getMessageType() != MessageType.REGISTER) {
            sendError("The first message must be REGISTER");
            AstroServer.log("Rejected " + remoteEndpoint + ": first message was not REGISTER.");
            return false;
        }

        StationInfo requested = message.getPayloadAs(StationInfo.class);
        String requestedId = (requested != null && requested.getStationId() != null)
                ? requested.getStationId() : message.getSenderId();
        StationInfo info = StationInfo.forStationId(requestedId);
        if (info == null) {
            sendError("Unknown station ID '" + requestedId + "'. Valid IDs: " + StationInfo.KNOWN_STATION_IDS);
            AstroServer.log("Rejected " + remoteEndpoint + ": unknown station ID " + requestedId);
            return false;
        }

        info.setIpAddress(socket.getInetAddress().getHostAddress());
        info.setConnectedAt(Message.utcNow());
        info.setLastHeartbeat(Message.utcNow());

        String error = stationManager.register(info.getStationId(), this);
        if (error != null) {
            sendError(error);
            AstroServer.log("Rejected " + remoteEndpoint + ": " + error);
            return false;
        }
        stationInfo = info;
        socket.setSoTimeout(0);   // registered: from now on wait for messages without a time limit

        JsonObject ack = new JsonObject();
        ack.addProperty("accepted", true);
        ack.addProperty("stationId", info.getStationId());
        ack.addProperty("message", "Welcome " + info + ". You are connected to the AstroNet central server.");
        ack.addProperty("connectedStations", String.join(", ", stationManager.getConnectedStationIds()));
        ack.addProperty("verificationTimeoutSeconds", NetworkConfig.VERIFICATION_TIMEOUT_SECONDS);
        send(new Message(MessageType.REGISTER_ACK, NetworkConfig.SERVER_ID, ack));

        AstroServer.log("REGISTERED " + info + " from " + remoteEndpoint + " -> REGISTER_ACK sent. Connected stations ("
                + stationManager.count() + "/" + NetworkConfig.MAX_STATIONS + "): " + stationManager.getConnectedStationIds());
        return true;
    }

    /** Dispatches one received message according to its type. */
    private void handleMessage(Message message, String rawLine) {
        String stationId = getStationId();
        if (message.getSenderId() != null && !stationId.equals(message.getSenderId())) {
            AstroServer.log("Warning: message claims sender " + message.getSenderId()
                    + " but connection belongs to " + stationId + " - using " + stationId);
        }

        switch (message.getMessageType()) {
            case OBSERVATION_ALERT:
                AstroServer.log("<- raw NDJSON from " + stationId + ": " + JsonUtil.abbreviate(rawLine, 200));
                AstronomicalEvent event = message.getPayloadAs(AstronomicalEvent.class);
                alertManager.handleAlert(event, stationId);
                break;

            case VERIFICATION_RESPONSE:
                VerificationResult result = message.getPayloadAs(VerificationResult.class);
                if (result == null) {
                    sendError("VERIFICATION_RESPONSE payload is invalid");
                } else {
                    verificationManager.handleResponse(result, stationId);
                }
                break;

            case HEARTBEAT:
                // Heartbeats are recorded silently to keep the console readable ('list' shows them).
                stationInfo.setLastHeartbeat(Message.utcNow());
                break;

            case DISCONNECT:
                String reason = message.getPayloadString("reason");
                AstroServer.log(stationId + " sent DISCONNECT (" + (reason == null ? "no reason" : reason) + ").");
                active = false;
                break;

            case REGISTER:
                sendError("Already registered as " + stationId);
                break;

            default:
                sendError("Stations are not allowed to send " + message.getMessageType());
                AstroServer.log("Unexpected " + message.getMessageType() + " from " + stationId + " rejected.");
        }
    }

    /**
     * Sends one message as one NDJSON line. synchronized because several threads may
     * write to the same station at the same time (e.g. two handler threads forwarding
     * follow-up requests, plus the timer thread broadcasting a final result).
     */
    public synchronized boolean send(Message message) {
        if (writer == null || socket.isClosed()) {
            return false;
        }
        writer.print(JsonUtil.toLine(message));
        writer.print('\n');          // explicit '\n' - identical framing on Windows and Linux
        writer.flush();              // push the bytes into the TCP connection now
        return !writer.checkError(); // PrintWriter hides IOExceptions; checkError() reveals them
    }

    private void sendError(String reason) {
        send(Message.createText(MessageType.ERROR, NetworkConfig.SERVER_ID, "reason", reason));
    }

    /** Called by the server when shutting down: tell the station, then close the socket. */
    public void closeConnection(String reason) {
        send(Message.createText(MessageType.DISCONNECT, NetworkConfig.SERVER_ID, "reason", reason));
        active = false;
        closeSocket();
    }

    /** Removes the station from the network and closes the socket (runs in finally{}). */
    private void cleanup() {
        active = false;
        StationInfo info = stationInfo;
        if (info != null && stationManager.unregister(info.getStationId(), this)) {
            AstroServer.log("DISCONNECTED " + info + ". Connected stations (" + stationManager.count() + "/"
                    + NetworkConfig.MAX_STATIONS + "): " + stationManager.getConnectedStationIds());
            verificationManager.handleStationDisconnected(info.getStationId());
        }
        closeSocket();
    }

    private void closeSocket() {
        try {
            socket.close();   // also closes the socket's input and output streams
        } catch (IOException ignored) {
            // nothing more we can do
        }
    }

    public String getStationId() {
        StationInfo info = stationInfo;
        return info == null ? remoteEndpoint : info.getStationId();
    }

    public StationInfo getStationInfo() {
        return stationInfo;
    }
}
