package station;

import common.AstronomicalEvent;
import common.StationInfo;
import udp.UDPClient;

import java.util.List;
import java.util.Scanner;

/**
 * Console menu of a station. Runs on the main thread.
 *
 * While the user is reading the menu, the receiver thread and the simulator thread
 * keep working in the background - that is why server messages can appear at any time.
 */
public class StationConsole {

    private final StationInfo info;
    private final StationConnection connection;
    private final ObservationSimulator simulator;
    private final Scanner scanner;
    private final String serverIp;
    private final int udpPort;

    public StationConsole(StationInfo info, StationConnection connection, ObservationSimulator simulator,
                          Scanner scanner, String serverIp, int udpPort) {
        this.info = info;
        this.connection = connection;
        this.simulator = simulator;
        this.scanner = scanner;
        this.serverIp = serverIp;
        this.udpPort = udpPort;
    }

    public void run() {
        while (true) {
            printMenu();
            if (!scanner.hasNextLine()) {          // input closed (e.g. Ctrl+D / Ctrl+Z)
                exitStation();
                return;
            }
            String choice = scanner.nextLine().trim();
            switch (choice) {
                case "1":
                    toggleSimulation();
                    break;
                case "2":
                    sendTestAlert();
                    break;
                case "3":
                    printStationInfo();
                    break;
                case "4":
                    connection.printStatus();
                    break;
                case "5":
                    UDPClient.sendTestDatagrams(info.getStationId(), serverIp, udpPort);
                    break;
                case "6":
                    exitStation();
                    return;
                case "":
                    break;   // just show the menu again
                default:
                    System.out.println("Please enter a number from 1 to 6.");
            }
        }
    }

    private void printMenu() {
        String simulationLabel = simulator.isRunning()
                ? "Stop observation simulation (running)"
                : "Start observation simulation";
        System.out.println();
        System.out.println("------------- " + info + " - " + (connection.isConnected() ? "CONNECTED" : "OFFLINE") + " -------------");
        System.out.println(" 1. " + simulationLabel);
        System.out.println(" 2. Send a test astronomical alert");
        System.out.println(" 3. Display station information");
        System.out.println(" 4. Display connection status");
        System.out.println(" 5. Send UDP test message");
        System.out.println(" 6. Disconnect and exit");
        System.out.println(" (Server messages may appear at any time - press Enter to show this menu again.)");
        System.out.print("Select option: ");
    }

    private void toggleSimulation() {
        if (simulator.isRunning()) {
            simulator.stop();
            return;
        }
        if (!connection.isConnected()) {
            System.out.println("Not connected to the server - restart the station to reconnect.");
            return;
        }
        simulator.start();
    }

    private void sendTestAlert() {
        if (!connection.isConnected()) {
            System.out.println("Not connected to the server - alert not sent.");
            return;
        }
        AstronomicalEvent event = simulator.createTestEvent();
        AstroStation.log("TEST: simulated strong brightening detected by " + info.getStationId());
        connection.sendAlert(event);
    }

    private void printStationInfo() {
        AstroStation.logBlock(List.of(
                "Station information:",
                "  Station ID : " + info.getStationId(),
                "  Location   : " + info.getLocation(),
                "  Latitude   : " + info.getLatitude() + " deg",
                "  Longitude  : " + info.getLongitude() + " deg",
                "  Visible Dec: about " + Math.round(info.getLatitude() - 75) + " to "
                        + Math.round(info.getLatitude() + 75) + " deg (altitude > 15 deg)",
                "  Detection  : brightness > " + EventDetector.DETECTION_THRESHOLD + " x baseline",
                "  Simulation : " + (simulator.isRunning() ? "running" : "stopped")));
    }

    private void exitStation() {
        if (simulator.isRunning()) {
            simulator.stop();
        }
        connection.disconnect();
    }
}
