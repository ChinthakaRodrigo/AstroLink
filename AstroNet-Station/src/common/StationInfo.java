package common;

import java.util.List;

/**
 * Identity and status of one observation station.
 *
 * The five station identities are simulated. The real computers running them
 * can be anywhere; only the station ID decides which identity a computer uses.
 *
 * NOTE: this file is identical in AstroNet-Server and AstroNet-Station.
 */
public class StationInfo {

    /** The only station IDs accepted by the server. (Static fields are not serialized by Gson.) */
    public static final List<String> KNOWN_STATION_IDS =
            List.of("ASTRO-01", "ASTRO-02", "ASTRO-03", "ASTRO-04", "ASTRO-05");

    private String stationId;
    private String location;
    private double latitude;      // decimal degrees, + = north
    private double longitude;     // decimal degrees, + = east
    private String ipAddress;     // filled in by the server from the socket
    private String connectedAt;   // UTC, filled in by the server
    private String lastHeartbeat; // UTC, updated by the server

    /** No-argument constructor required by Gson. */
    public StationInfo() {
    }

    public StationInfo(String stationId, String location, double latitude, double longitude) {
        this.stationId = stationId;
        this.location = location;
        this.latitude = latitude;
        this.longitude = longitude;
    }

    /** Returns a new StationInfo for a known station ID, or null if the ID is unknown. */
    public static StationInfo forStationId(String stationId) {
        if (stationId == null) {
            return null;
        }
        switch (stationId.trim().toUpperCase()) {
            case "ASTRO-01":
                return new StationInfo("ASTRO-01", "Sri Lanka", 7.8731, 80.7718);
            case "ASTRO-02":
                return new StationInfo("ASTRO-02", "India", 20.5937, 78.9629);
            case "ASTRO-03":
                return new StationInfo("ASTRO-03", "Australia", -25.2744, 133.7751);
            case "ASTRO-04":
                return new StationInfo("ASTRO-04", "Japan", 36.2048, 138.2529);
            case "ASTRO-05":
                return new StationInfo("ASTRO-05", "South Africa", -30.5595, 22.9375);
            default:
                return null;
        }
    }

    /**
     * Converts user input such as "1", "01", "astro-1" or "ASTRO-01" into "ASTRO-01".
     * Returns null if the input does not correspond to a known station.
     */
    public static String normalizeStationId(String input) {
        if (input == null) {
            return null;
        }
        String text = input.trim().toUpperCase().replace("ASTRO-", "").replace("ASTRO", "");
        try {
            int number = Integer.parseInt(text);
            String id = String.format("ASTRO-%02d", number);
            return KNOWN_STATION_IDS.contains(id) ? id : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public String getStationId() {
        return stationId;
    }

    public String getLocation() {
        return location;
    }

    public double getLatitude() {
        return latitude;
    }

    public double getLongitude() {
        return longitude;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public void setIpAddress(String ipAddress) {
        this.ipAddress = ipAddress;
    }

    public String getConnectedAt() {
        return connectedAt;
    }

    public void setConnectedAt(String connectedAt) {
        this.connectedAt = connectedAt;
    }

    public String getLastHeartbeat() {
        return lastHeartbeat;
    }

    public void setLastHeartbeat(String lastHeartbeat) {
        this.lastHeartbeat = lastHeartbeat;
    }

    @Override
    public String toString() {
        return stationId + " (" + location + ")";
    }
}
