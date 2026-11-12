package common;

/**
 * A possible astronomical event detected by a station.
 *
 * Used as the payload of OBSERVATION_ALERT (station -> server) and
 * FOLLOW_UP_REQUEST (server -> other stations).
 *
 * The station gives the event a local ID (stationEventId). The server then assigns
 * the official, network-wide unique eventId (e.g. EVT-20261113-0001) so that responses
 * for different events are never mixed.
 *
 * NOTE: this file is identical in AstroNet-Server and AstroNet-Station.
 */
public class AstronomicalEvent {

    private String eventId;             // official ID assigned by the server
    private String stationEventId;      // local ID assigned by the reporting station
    private String eventType;           // e.g. TRANSIENT_BRIGHTENING, NOVA_CANDIDATE
    private String reportingStationId;
    private double ra;                  // Right Ascension, decimal degrees 0..360
    private double dec;                 // Declination, decimal degrees -90..+90
    private String timestamp;           // UTC ISO-8601 time of the observation
    private double brightness;
    private double previousBrightness;
    private double confidence;          // 0.0 - 1.0

    /** No-argument constructor required by Gson. */
    public AstronomicalEvent() {
    }

    public AstronomicalEvent(String stationEventId, String eventType, String reportingStationId,
                             double ra, double dec, String timestamp,
                             double brightness, double previousBrightness, double confidence) {
        this.eventId = stationEventId;
        this.stationEventId = stationEventId;
        this.eventType = eventType;
        this.reportingStationId = reportingStationId;
        this.ra = ra;
        this.dec = dec;
        this.timestamp = timestamp;
        this.brightness = brightness;
        this.previousBrightness = previousBrightness;
        this.confidence = confidence;
    }

    /** Basic sanity check of the coordinate and brightness values. */
    public boolean hasValidValues() {
        return ra >= 0.0 && ra < 360.0
                && dec >= -90.0 && dec <= 90.0
                && brightness > 0.0 && previousBrightness > 0.0
                && confidence >= 0.0 && confidence <= 1.0;
    }

    public double getBrightnessRatio() {
        return previousBrightness <= 0 ? 0.0 : brightness / previousBrightness;
    }

    /** One-line human readable description for console output. */
    public String describe() {
        return String.format("%s %s at RA=%.4f deg, Dec=%+.4f deg | brightness %.3f (baseline %.3f, x%.2f) | confidence %.2f",
                eventId, eventType, ra, dec, brightness, previousBrightness, getBrightnessRatio(), confidence);
    }

    public String getEventId() {
        return eventId;
    }

    public void setEventId(String eventId) {
        this.eventId = eventId;
    }

    public String getStationEventId() {
        return stationEventId;
    }

    public void setStationEventId(String stationEventId) {
        this.stationEventId = stationEventId;
    }

    public String getEventType() {
        return eventType;
    }

    public String getReportingStationId() {
        return reportingStationId;
    }

    public void setReportingStationId(String reportingStationId) {
        this.reportingStationId = reportingStationId;
    }

    public double getRa() {
        return ra;
    }

    public double getDec() {
        return dec;
    }

    public String getTimestamp() {
        return timestamp;
    }

    public double getBrightness() {
        return brightness;
    }

    public double getPreviousBrightness() {
        return previousBrightness;
    }

    public double getConfidence() {
        return confidence;
    }
}
