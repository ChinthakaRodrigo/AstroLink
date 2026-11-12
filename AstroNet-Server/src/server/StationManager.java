package server;

import common.Message;
import common.StationInfo;
import config.NetworkConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps the list of connected stations.
 *
 * Many handler threads read and change this list at the same time, so it uses a
 * ConcurrentHashMap (a thread-safe collection). register() is additionally
 * synchronized so that the "is it full / is the ID taken?" check and the insert
 * happen as one atomic step.
 */
public class StationManager {

    private final Map<String, ClientHandler> stations = new ConcurrentHashMap<>();

    /** Registers a station. Returns null on success, or an error text. */
    public synchronized String register(String stationId, ClientHandler handler) {
        if (stations.containsKey(stationId)) {
            return "Station ID " + stationId + " is already connected. Each computer must use a different station ID.";
        }
        if (stations.size() >= NetworkConfig.MAX_STATIONS) {
            return "Server is full (" + NetworkConfig.MAX_STATIONS + " stations).";
        }
        stations.put(stationId, handler);
        return null;
    }

    /** Removes a station only if it is still mapped to this handler. */
    public boolean unregister(String stationId, ClientHandler handler) {
        return stations.remove(stationId, handler);
    }

    public boolean isConnected(String stationId) {
        return stations.containsKey(stationId);
    }

    public int count() {
        return stations.size();
    }

    /** Sorted list of connected station IDs. */
    public List<String> getConnectedStationIds() {
        List<String> ids = new ArrayList<>(stations.keySet());
        ids.sort(String::compareTo);
        return ids;
    }

    /** Sends a message to one station. Returns false if the station is not connected. */
    public boolean sendTo(String stationId, Message message) {
        ClientHandler handler = stations.get(stationId);
        return handler != null && handler.send(message);
    }

    /** Sends a message to every connected station. Returns the number of successful sends. */
    public int broadcast(Message message) {
        int delivered = 0;
        for (ClientHandler handler : stations.values()) {
            if (handler.send(message)) {
                delivered++;
            }
        }
        return delivered;
    }

    /** Tells every station that the server is closing and closes their sockets. */
    public void disconnectAll(String reason) {
        for (ClientHandler handler : new ArrayList<>(stations.values())) {
            handler.closeConnection(reason);
        }
    }

    /** Console output for the 'list' command. */
    public void printStations() {
        List<String> lines = new ArrayList<>();
        lines.add("Connected stations (" + count() + "/" + NetworkConfig.MAX_STATIONS + "):");
        if (stations.isEmpty()) {
            lines.add("  (none)");
        }
        lines.add(String.format("  %-9s %-13s %-16s %-21s %-21s", "ID", "Location", "IP address", "Connected (UTC)", "Last heartbeat"));
        for (String id : getConnectedStationIds()) {
            ClientHandler handler = stations.get(id);
            if (handler == null || handler.getStationInfo() == null) {
                continue;
            }
            StationInfo info = handler.getStationInfo();
            lines.add(String.format("  %-9s %-13s %-16s %-21s %-21s", info.getStationId(), info.getLocation(),
                    info.getIpAddress(), info.getConnectedAt(), info.getLastHeartbeat()));
        }
        AstroServer.logBlock(lines);
    }
}
