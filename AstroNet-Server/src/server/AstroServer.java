package server;

import config.NetworkConfig;
import udp.UDPServer;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.BindException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * AstroNet central server (Project A) - entry point.
 *
 * CLIENT-SERVER ARCHITECTURE: the server is passive. It creates a ServerSocket,
 * listens on a known port and WAITS for stations (clients) to connect.
 *
 * THREADS used by this process:
 *   main                - accept() loop: waits for new TCP connections
 *   ClientHandler-N     - one task per connected station (from an ExecutorService),
 *                         renamed to "Handler-ASTRO-0X" after registration
 *   Verification-Timer  - fires the 15 second verification timeouts
 *   UDP-Server          - receives optional UDP datagrams (class extends Thread)
 *   Server-Console      - reads operator commands (list, events, threads, quit)
 *
 * Usage: java -cp "out;lib/gson-2.13.2.jar" server.AstroServer [tcpPort] [udpPort]
 */
public class AstroServer {

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final int tcpPort;
    private final int udpPort;

    private final StationManager stationManager = new StationManager();
    private final VerificationManager verificationManager = new VerificationManager(stationManager);
    private final AlertManager alertManager = new AlertManager(stationManager, verificationManager);

    /**
     * Thread pool for client handlers. A cached pool creates a new thread when all
     * existing ones are busy, so every station gets its own thread and no station
     * waits for another. Threads get readable names for the console output.
     */
    private final AtomicInteger handlerThreadCounter = new AtomicInteger();
    private final ExecutorService clientPool = Executors.newCachedThreadPool(
            task -> new Thread(task, "ClientHandler-" + handlerThreadCounter.incrementAndGet()));

    private final AtomicBoolean shutdownStarted = new AtomicBoolean(false);
    private volatile boolean running = true;
    private volatile ServerSocket serverSocket;
    private UDPServer udpServer;

    public AstroServer(int tcpPort, int udpPort) {
        this.tcpPort = tcpPort;
        this.udpPort = udpPort;
    }

    public static void main(String[] args) {
        int tcpPort = NetworkConfig.parsePort(args.length > 0 ? args[0] : null, NetworkConfig.DEFAULT_TCP_PORT);
        int udpPort = NetworkConfig.parsePort(args.length > 1 ? args[1] : null, NetworkConfig.DEFAULT_UDP_PORT);
        new AstroServer(tcpPort, udpPort).start();
    }

    /** Thread-safe console logging: [time] [thread name] message. */
    public static synchronized void log(String message) {
        System.out.printf("[%s] [%-20s] %s%n", LocalTime.now().format(TIME_FORMAT),
                Thread.currentThread().getName(), message);
    }

    /** Prints a block of lines without other threads' output appearing in the middle. */
    public static synchronized void logBlock(List<String> lines) {
        for (String line : lines) {
            log(line);
        }
    }

    public void start() {
        printBanner();

        // Optional UDP demo module. UDPServer EXTENDS Thread (creation method 1).
        udpServer = new UDPServer(udpPort);
        log("UDP-Server thread created, state = " + udpServer.getState());   // NEW
        udpServer.start();
        log("UDP-Server thread started, state = " + udpServer.getState());   // RUNNABLE

        // Operator console runs on its own thread so it never blocks accept().
        Thread consoleThread = new Thread(this::runConsole, "Server-Console");
        consoleThread.setDaemon(true);
        consoleThread.start();

        // Ctrl+C -> close sockets cleanly.
        Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown, "Shutdown-Hook"));

        try (ServerSocket listener = new ServerSocket()) {
            listener.setReuseAddress(true);
            // Bind to 0.0.0.0 = all interfaces, so computers on the LAN/VPN can connect.
            listener.bind(new InetSocketAddress(InetAddress.getByName(NetworkConfig.BIND_ADDRESS), tcpPort));
            serverSocket = listener;

            log("TCP ServerSocket bound to " + NetworkConfig.BIND_ADDRESS + ":" + tcpPort
                    + "  (socket endpoint = IP address + port)");
            printLanAddresses();
            log("Type 'help' for server commands.");

            while (running) {
                log("accept() is BLOCKING - main thread waits for the next station to connect...");
                // accept() blocks this thread until a client completes the TCP three-way handshake.
                // It then returns a NEW Socket dedicated to that client; the ServerSocket keeps listening.
                Socket clientSocket = listener.accept();

                log("TCP connection accepted: remote endpoint " + clientSocket.getInetAddress().getHostAddress()
                        + ":" + clientSocket.getPort() + " -> local endpoint "
                        + clientSocket.getLocalAddress().getHostAddress() + ":" + clientSocket.getLocalPort());

                // Hand the socket to a separate task so the main thread can go back to accept().
                ClientHandler handler = new ClientHandler(clientSocket, stationManager, alertManager, verificationManager);
                clientPool.execute(handler);
            }
        } catch (BindException e) {
            log("ERROR: cannot bind TCP port " + tcpPort + " - is another program (or another server) using it? "
                    + e.getMessage());
        } catch (SocketException e) {
            if (running) {
                log("Server socket error: " + e.getMessage());
            }
        } catch (IOException e) {
            log("Server I/O error: " + e.getMessage());
        } finally {
            shutdown();
        }
    }

    /** Stops everything exactly once (called by 'quit', by Ctrl+C, or after a fatal error). */
    public void shutdown() {
        if (!shutdownStarted.compareAndSet(false, true)) {
            return;
        }
        running = false;
        log("Shutting down server...");
        stationManager.disconnectAll("Server is shutting down");
        try {
            if (serverSocket != null) {
                serverSocket.close();   // makes the blocked accept() throw SocketException
            }
        } catch (IOException ignored) {
            // already closed
        }
        if (udpServer != null) {
            udpServer.shutdown();
        }
        verificationManager.shutdown();
        clientPool.shutdownNow();
        log("Server stopped.");
    }

    private void printBanner() {
        System.out.println("==================================================================");
        System.out.println("   AstroNet - Distributed Astronomical Observation Network");
        System.out.println("   CENTRAL SERVER   (TCP port " + tcpPort + ", UDP demo port " + udpPort + ")");
        System.out.println("==================================================================");
        log("Server started. Java " + System.getProperty("java.version") + " on " + System.getProperty("os.name"));
    }

    /** Shows which IP addresses stations should type in. Demonstrates InetAddress. */
    private void printLanAddresses() {
        try {
            InetAddress localHost = InetAddress.getLocalHost();
            log("InetAddress.getLocalHost(): " + localHost.getHostName() + " / " + localHost.getHostAddress());
        } catch (UnknownHostException e) {
            log("InetAddress.getLocalHost() failed: " + e.getMessage());
        }
        List<String> addresses = NetworkConfig.getLanIpAddresses();
        log("Stations on other computers must connect to one of these LAN/VPN IP addresses:");
        if (addresses.isEmpty()) {
            log("   (no active network interface found - connect this computer to a network)");
        }
        for (String address : addresses) {
            log("   -> " + address + "   TCP port " + tcpPort);
        }
        log("Do NOT use 'localhost'/127.0.0.1 on the station computers - it means 'this same computer'.");
    }

    // ------------------------------------------------------------------ operator console

    private void runConsole() {
        BufferedReader console = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        try {
            String line;
            while (running && (line = console.readLine()) != null) {
                String command = line.trim().toLowerCase();
                switch (command) {
                    case "":
                        break;
                    case "help":
                        printHelp();
                        break;
                    case "list":
                    case "stations":
                        stationManager.printStations();
                        break;
                    case "events":
                        verificationManager.printEvents();
                        break;
                    case "threads":
                        printThreads();
                        break;
                    case "quit":
                    case "exit":
                        shutdown();
                        System.exit(0);
                        return;
                    default:
                        log("Unknown command '" + command + "'. Type 'help'.");
                }
            }
        } catch (IOException e) {
            log("Console input closed: " + e.getMessage());
        }
        // End of input (e.g. server started in the background on Linux) - the server keeps running.
    }

    private void printHelp() {
        logBlock(List.of(
                "Server commands:",
                "  list     - show connected stations",
                "  events   - show pending and completed event verifications",
                "  threads  - show all application threads and their Thread.State",
                "  quit     - disconnect all stations and stop the server"));
    }

    /** Shows the live threads of this process and their Thread.State (thread life cycle demo). */
    private void printThreads() {
        List<Thread> threads = Thread.getAllStackTraces().keySet().stream()
                .filter(t -> t.getThreadGroup() != null && "main".equals(t.getThreadGroup().getName()))
                .sorted(Comparator.comparing(Thread::getName))
                .collect(Collectors.toList());
        List<String> lines = new java.util.ArrayList<>();
        lines.add("Threads of this server PROCESS (one JVM process, many threads sharing its memory):");
        for (Thread thread : threads) {
            lines.add(String.format("  %-22s state=%-14s daemon=%s", thread.getName(), thread.getState(), thread.isDaemon()));
        }
        lines.add("  Note: a thread blocked in accept()/readLine() on a socket is reported as RUNNABLE by Java,");
        lines.add("  a thread in sleep()/timed wait is TIMED_WAITING, an idle pool thread is WAITING/TIMED_WAITING.");
        lines.add("  CPU cores available for parallel execution: " + Runtime.getRuntime().availableProcessors());
        logBlock(lines);
    }
}
