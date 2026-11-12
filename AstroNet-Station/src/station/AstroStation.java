package station;

import common.StationInfo;
import config.NetworkConfig;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Scanner;

/**
 * AstroNet observation station (Project B) - entry point.
 *
 * The SAME program runs on five computers; each computer chooses a different station ID.
 * The station is the CLIENT: it initiates the TCP connection to the central server.
 *
 * Usage (all arguments optional - missing values are asked on the console):
 *   java -cp "out;lib/gson-2.13.2.jar" station.AstroStation [stationId] [serverIp] [tcpPort] [udpPort]
 *   e.g. station.AstroStation ASTRO-01 192.168.1.10 5000 5001
 */
public class AstroStation {

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");

    public static void main(String[] args) {
        // One Scanner for System.in is shared by the start-up prompts and the menu.
        Scanner scanner = new Scanner(System.in);

        System.out.println("==================================================================");
        System.out.println("   AstroNet - Distributed Astronomical Observation Network");
        System.out.println("   OBSERVATION STATION");
        System.out.println("==================================================================");

        String stationId = args.length > 0 ? StationInfo.normalizeStationId(args[0]) : null;
        if (stationId == null) {
            stationId = askStationId(scanner);
        }
        String serverIp = args.length > 1 ? args[1].trim() : askServerIp(scanner);
        int tcpPort = args.length > 2
                ? NetworkConfig.parsePort(args[2], NetworkConfig.DEFAULT_TCP_PORT)
                : askPort(scanner);
        int udpPort = NetworkConfig.parsePort(args.length > 3 ? args[3] : null, NetworkConfig.DEFAULT_UDP_PORT);

        StationInfo info = StationInfo.forStationId(stationId);
        log("Station identity: " + info + ", latitude " + info.getLatitude() + ", longitude " + info.getLongitude());

        StationConnection connection = new StationConnection(info);
        ObservationSimulator simulator = new ObservationSimulator(info, connection);
        connection.setSimulator(simulator);

        if (!connection.connect(serverIp, tcpPort)) {
            if (connection.wasRegistrationRefused()) {
                log("The server refused this station. If the station ID is already in use, restart and choose another ID.");
                connection.disconnect();
                System.exit(1);
                return;
            }
            log("Could not join the AstroNet network. Checklist:");
            log("  1. Is AstroServer running on the server computer?");
            log("  2. Is " + serverIp + " the server's LAN/VPN IP address (printed by the server)?");
            log("  3. Is TCP port " + tcpPort + " allowed through the server's firewall?");
            log("  4. Are both computers on the same Wi-Fi/LAN or VPN? Try: ping " + serverIp);
            connection.disconnect();
            System.exit(1);
            return;
        }

        new StationConsole(info, connection, simulator, scanner, serverIp, udpPort).run();

        log("Station " + stationId + " stopped. Goodbye.");
        System.exit(0);
    }

    /** Thread-safe console logging: [time] [thread name] message. */
    public static synchronized void log(String message) {
        System.out.printf("[%s] [%-20s] %s%n", LocalTime.now().format(TIME_FORMAT),
                Thread.currentThread().getName(), message);
    }

    /** Prints several lines without output from other threads in between. */
    public static synchronized void logBlock(Iterable<String> lines) {
        for (String line : lines) {
            log(line);
        }
    }

    private static String readLineOrExit(Scanner scanner) {
        if (!scanner.hasNextLine()) {
            System.out.println("Input closed - exiting.");
            System.exit(0);
        }
        return scanner.nextLine().trim();
    }

    private static String askStationId(Scanner scanner) {
        System.out.println("Choose this computer's station identity:");
        for (String id : StationInfo.KNOWN_STATION_IDS) {
            StationInfo info = StationInfo.forStationId(id);
            System.out.println("   " + id.substring(id.length() - 1) + ". " + info);
        }
        while (true) {
            System.out.print("Station (1-5 or ASTRO-0X): ");
            String id = StationInfo.normalizeStationId(readLineOrExit(scanner));
            if (id != null) {
                return id;
            }
            System.out.println("Invalid station. Enter a number from 1 to 5.");
        }
    }

    private static String askServerIp(Scanner scanner) {
        while (true) {
            System.out.print("Enter the central server IP address (e.g. 192.168.1.10): ");
            String ip = readLineOrExit(scanner);
            if (ip.isEmpty()) {
                System.out.println("The server IP address is required. The server prints it when it starts.");
                continue;
            }
            try {
                InetAddress address = InetAddress.getByName(ip);   // validates IP text or resolves a host name
                if (address.isLoopbackAddress()) {
                    System.out.println("Note: " + ip + " is the loopback address - it only works if the server runs on THIS computer.");
                }
                return ip;
            } catch (UnknownHostException e) {
                System.out.println("'" + ip + "' is not a valid IP address or known host name. Try again.");
            }
        }
    }

    private static int askPort(Scanner scanner) {
        System.out.print("Server TCP port [" + NetworkConfig.DEFAULT_TCP_PORT + "]: ");
        return NetworkConfig.parsePort(readLineOrExit(scanner), NetworkConfig.DEFAULT_TCP_PORT);
    }
}
