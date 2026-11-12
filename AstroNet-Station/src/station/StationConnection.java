package station;

import com.google.gson.JsonObject;
import common.AstronomicalEvent;
import common.JsonUtil;
import common.Message;
import common.MessageType;
import common.StationInfo;
import common.VerificationResult;
import common.VerificationResult.FinalReport;
import config.NetworkConfig;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NoRouteToHostException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The station's TCP connection to the central server (client side of client-server).
 *
 * Threads involved:
 *   main / console thread  - sends alerts chosen in the menu
 *   Receiver-ASTRO-0X      - blocks on readLine() and handles every message from the server
 *   Verifier-N             - performs follow-up observations (so the receiver never blocks)
 *   Heartbeat-ASTRO-0X     - sends a HEARTBEAT every 30 s
 *   Simulator-ASTRO-0X     - (ObservationSimulator) may send alerts at any time
 *
 * Because several threads write to the same socket, send() is synchronized: one whole
 * JSON line is always written before the next one starts.
 */
public class StationConnection {

    private final StationInfo info;
    private ObservationSimulator simulator;

    private Socket socket;
    private BufferedReader reader;
    private PrintWriter writer;
    private Thread receiverThread;
    private ScheduledExecutorService heartbeatScheduler;

    /** Follow-up observations run here, so the receiver thread keeps reading messages. */
    private final AtomicInteger verifierCounter = new AtomicInteger();
    private final ExecutorService verificationWorkers = Executors.newFixedThreadPool(2, task -> {
        Thread thread = new Thread(task, "Verifier-" + verifierCounter.incrementAndGet());
        thread.setDaemon(true);
        return thread;
    });

    private final CountDownLatch registrationLatch = new CountDownLatch(1);
    private volatile boolean connected;
    private volatile boolean registered;
    private volatile boolean closingByUser;
    private volatile String registrationError;
    private volatile String lastFinalResult = "none yet";
    private String serverHost;
    private int serverPort;

    private final AtomicInteger alertsSent = new AtomicInteger();
    private final AtomicInteger followUpsReceived = new AtomicInteger();
    private final AtomicInteger responsesSent = new AtomicInteger();
    private final AtomicInteger finalResultsReceived = new AtomicInteger();

    public StationConnection(StationInfo info) {
        this.info = info;
    }

    public void setSimulator(ObservationSimulator simulator) {
        this.simulator = simulator;
    }

    /**
     * Opens the TCP connection, starts the receiver thread and registers.
     * Returns true when the server answered with REGISTER_ACK.
     */
    public boolean connect(String host, int port) {
        this.serverHost = host;
        this.serverPort = port;
        try {
            InetAddress serverAddress = InetAddress.getByName(host);
            AstroStation.log("Connecting to server " + serverAddress.getHostAddress() + ":" + port + " (TCP)...");

            // Create the client Socket and connect (TCP three-way handshake), with a time limit.
            socket = new Socket();
            socket.connect(new InetSocketAddress(serverAddress, port), NetworkConfig.CONNECT_TIMEOUT_MS);
            socket.setKeepAlive(true);
            socket.setTcpNoDelay(true);

            InputStream inputStream = socket.getInputStream();
            OutputStream outputStream = socket.getOutputStream();
            reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
            writer = new PrintWriter(new BufferedWriter(new OutputStreamWriter(outputStream, StandardCharsets.UTF_8)), false);
            connected = true;

            AstroStation.log("TCP connection established. Local endpoint " + socket.getLocalAddress().getHostAddress()
                    + ":" + socket.getLocalPort() + "  ->  server endpoint " + socket.getInetAddress().getHostAddress()
                    + ":" + socket.getPort());

            // Receiver thread: created with a Runnable (method reference), started once.
            receiverThread = new Thread(this::receiveLoop, "Receiver-" + info.getStationId());
            receiverThread.setDaemon(true);
            AstroStation.log("Receiver thread created, state = " + receiverThread.getState());
            receiverThread.start();
            AstroStation.log("Receiver thread started, state = " + receiverThread.getState());

            send(Message.create(MessageType.REGISTER, info.getStationId(), info));
            AstroStation.log("REGISTER sent as " + info + ". Waiting for REGISTER_ACK...");

            if (!registrationLatch.await(10, TimeUnit.SECONDS)) {
                AstroStation.log("No answer to REGISTER within 10 s.");
                return false;
            }
            if (!registered) {
                AstroStation.log("Registration refused: " + (registrationError == null ? "connection closed" : registrationError));
                return false;
            }
            startHeartbeats();
            return true;

        } catch (UnknownHostException e) {
            AstroStation.log("Unknown host '" + host + "'.");
        } catch (SocketTimeoutException e) {
            AstroStation.log("Connection timed out after " + NetworkConfig.CONNECT_TIMEOUT_MS / 1000
                    + " s - wrong IP address, different network, or a firewall is blocking port " + port + ".");
        } catch (ConnectException e) {
            AstroStation.log("Connection refused by " + host + ":" + port + " - the server is not running or uses another port.");
        } catch (NoRouteToHostException e) {
            AstroStation.log("No route to host " + host + " - check the network / VPN connection.");
        } catch (IOException e) {
            AstroStation.log("Network error while connecting: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return false;
    }

    /** Runs on the receiver thread: reads one NDJSON line at a time until the connection ends. */
    private void receiveLoop() {
        try {
            String line;
            while ((line = reader.readLine()) != null) {   // blocks until the server sends a line
                Message message = JsonUtil.parseMessage(line);
                if (message == null) {
                    AstroStation.log("Ignoring invalid line from server: " + JsonUtil.abbreviate(line, 120));
                    continue;
                }
                handleMessage(message, line);
            }
            if (!closingByUser) {
                AstroStation.log("The server closed the TCP connection.");
            }
        } catch (IOException e) {
            if (!closingByUser) {
                AstroStation.log("Connection to server lost: " + e.getMessage());
            }
        } finally {
            boolean wasConnected = connected;
            connected = false;
            registrationLatch.countDown();   // never leave connect() waiting
            stopHeartbeats();
            if (wasConnected && registered && !closingByUser) {
                if (simulator != null && simulator.isRunning()) {
                    simulator.stop();
                }
                AstroStation.log("DISCONNECTED from server. Choose option 6 to exit and restart the station to reconnect.");
            }
            AstroStation.log("Receiver thread ending -> state will be TERMINATED.");
        }
    }

    private void handleMessage(Message message, String rawLine) {
        switch (message.getMessageType()) {
            case REGISTER_ACK:
                registered = true;
                AstroStation.logBlock(List.of(
                        "REGISTER_ACK received: " + message.getPayloadString("message"),
                        "  Stations currently connected: " + message.getPayloadString("connectedStations")));
                registrationLatch.countDown();
                break;

            case FOLLOW_UP_REQUEST:
                handleFollowUpRequest(message, rawLine);
                break;

            case FINAL_RESULT:
                handleFinalResult(message);
                break;

            case ERROR:
                String reason = message.getPayloadString("reason");
                AstroStation.log("ERROR from server: " + reason);
                if (!registered) {
                    registrationError = reason;
                    registrationLatch.countDown();
                }
                break;

            case DISCONNECT:
                AstroStation.log("Server sent DISCONNECT: " + message.getPayloadString("reason"));
                break;

            case HEARTBEAT:
                break;

            default:
                AstroStation.log("Unexpected message from server: " + message);
        }
    }

    private void handleFollowUpRequest(Message message, String rawLine) {
        AstronomicalEvent event = message.getPayloadAs(AstronomicalEvent.class);
        if (event == null) {
            AstroStation.log("Invalid FOLLOW_UP_REQUEST payload ignored.");
            return;
        }
        followUpsReceived.incrementAndGet();
        AstroStation.logBlock(List.of(
                ">>> FOLLOW_UP_REQUEST received for " + event.getEventId() + " (reported by " + event.getReportingStationId() + ")",
                "    raw NDJSON: " + JsonUtil.abbreviate(rawLine, 160),
                "    Target: RA=" + String.format("%.4f", event.getRa()) + " deg, Dec="
                        + String.format("%+.4f", event.getDec()) + " deg, type " + event.getEventType()));

        // The observation takes a few seconds, so it runs on a worker thread.
        // The receiver thread immediately goes back to reading the next message.
        verificationWorkers.execute(() -> {
            VerificationResult result = simulator.performFollowUp(event);
            boolean sent = send(Message.create(MessageType.VERIFICATION_RESPONSE, info.getStationId(), result));
            if (sent) {
                responsesSent.incrementAndGet();
                AstroStation.log("<<< VERIFICATION_RESPONSE sent for " + event.getEventId() + ": " + result.getStatus()
                        + (result.getNote() == null ? "" : " - " + result.getNote()));
            } else {
                AstroStation.log("Could not send VERIFICATION_RESPONSE for " + event.getEventId() + " (not connected).");
            }
        });
    }

    private void handleFinalResult(Message message) {
        FinalReport report = message.getPayloadAs(FinalReport.class);
        if (report == null) {
            AstroStation.log("Invalid FINAL_RESULT payload ignored.");
            return;
        }
        finalResultsReceived.incrementAndGet();
        lastFinalResult = report.getEventId() + " -> " + report.getDecision();

        String role;
        if (info.getStationId().equals(report.getReportingStationId())) {
            role = "this station REPORTED the event";
        } else if (report.getExpectedStations().contains(info.getStationId())) {
            role = "this station was asked to VERIFY";
        } else {
            role = "this station was not involved";
        }

        List<String> lines = new ArrayList<>();
        lines.add("##################################################################");
        lines.add("FINAL_RESULT " + report.getEventId() + " (" + report.getEventType() + ") from "
                + report.getReportingStationId() + "  [" + role + "]");
        lines.add("  RA=" + String.format("%.4f", report.getRa()) + " deg, Dec=" + String.format("%+.4f", report.getDec()) + " deg");
        lines.add("  DECISION: " + report.getDecision());
        lines.add("  " + report.getReason());
        lines.add("  " + report.describeCounts());
        for (VerificationResult result : report.getResponses()) {
            lines.add("    " + result.describe());
        }
        lines.add("##################################################################");
        AstroStation.logBlock(lines);
    }

    /** Sends an OBSERVATION_ALERT. Called by the simulator thread or the console thread. */
    public boolean sendAlert(AstronomicalEvent event) {
        boolean sent = send(Message.create(MessageType.OBSERVATION_ALERT, info.getStationId(), event));
        if (sent) {
            alertsSent.incrementAndGet();
            AstroStation.log("OBSERVATION_ALERT sent to server: " + event.describe());
        } else {
            AstroStation.log("OBSERVATION_ALERT NOT sent - station is not connected.");
        }
        return sent;
    }

    /**
     * Writes one message as one NDJSON line. synchronized = only one thread at a time
     * may write to the socket, so JSON lines from different threads never interleave.
     */
    public synchronized boolean send(Message message) {
        if (!connected || writer == null) {
            return false;
        }
        writer.print(JsonUtil.toLine(message));
        writer.print('\n');   // explicit '\n' framing, identical on Windows and Linux
        writer.flush();       // send now, do not wait for the buffer to fill
        return !writer.checkError();
    }

    private void startHeartbeats() {
        heartbeatScheduler = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "Heartbeat-" + info.getStationId());
            thread.setDaemon(true);
            return thread;
        });
        heartbeatScheduler.scheduleAtFixedRate(() -> {
            JsonObject payload = new JsonObject();
            payload.addProperty("status", "OPERATIONAL");
            payload.addProperty("simulationRunning", simulator != null && simulator.isRunning());
            send(new Message(MessageType.HEARTBEAT, info.getStationId(), payload));
        }, NetworkConfig.HEARTBEAT_INTERVAL_SECONDS, NetworkConfig.HEARTBEAT_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    private void stopHeartbeats() {
        if (heartbeatScheduler != null) {
            heartbeatScheduler.shutdownNow();
        }
    }

    /** Sends DISCONNECT, closes the socket and stops all helper threads. */
    public void disconnect() {
        closingByUser = true;
        if (connected) {
            send(Message.createText(MessageType.DISCONNECT, info.getStationId(), "reason", "Station operator exit"));
            AstroStation.log("DISCONNECT sent to server.");
        }
        connected = false;
        stopHeartbeats();
        verificationWorkers.shutdownNow();
        try {
            if (socket != null) {
                socket.close();   // unblocks readLine() in the receiver thread
            }
        } catch (IOException ignored) {
            // already closed
        }
        if (receiverThread != null) {
            try {
                receiverThread.join(2000);
                AstroStation.log("Receiver thread state after close: " + receiverThread.getState());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Console output for menu option 4. */
    public void printStatus() {
        List<String> lines = new ArrayList<>();
        lines.add("Connection status:");
        lines.add("  Connected            : " + connected + (registered ? " (registered)" : ""));
        lines.add("  Server endpoint      : " + serverHost + ":" + serverPort);
        if (socket != null && socket.isConnected()) {
            lines.add("  Local socket endpoint: " + socket.getLocalAddress().getHostAddress() + ":" + socket.getLocalPort());
            lines.add("  Remote socket endpoint: " + socket.getInetAddress().getHostAddress() + ":" + socket.getPort());
        }
        lines.add("  Alerts sent          : " + alertsSent.get());
        lines.add("  Follow-ups received  : " + followUpsReceived.get());
        lines.add("  Responses sent       : " + responsesSent.get());
        lines.add("  Final results        : " + finalResultsReceived.get() + "  (last: " + lastFinalResult + ")");
        lines.add("Thread states in this station process:");
        lines.add("  " + Thread.currentThread().getName() + " (this console thread): " + Thread.currentThread().getState());
        if (receiverThread != null) {
            lines.add("  " + receiverThread.getName() + ": " + receiverThread.getState()
                    + "   (blocked in readLine() on the socket is reported as RUNNABLE)");
        }
        lines.add("  " + simulator.getThreadDescription());
        AstroStation.logBlock(lines);
    }

    public boolean isConnected() {
        return connected;
    }

    /** true if the server answered REGISTER with an ERROR (e.g. duplicate station ID). */
    public boolean wasRegistrationRefused() {
        return registrationError != null;
    }
}
