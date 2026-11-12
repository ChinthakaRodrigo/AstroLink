package common;

/**
 * One simulated measurement of a point in the sky.
 *
 * Coordinates use the equatorial system in DECIMAL DEGREES everywhere in the project:
 *   Right Ascension (RA): 0.0 to 360.0 degrees
 *   Declination   (Dec): -90.0 to +90.0 degrees
 *
 * Brightness is a simulated relative flux value (no physical unit is needed).
 *
 * NOTE: this file is identical in AstroNet-Server and AstroNet-Station.
 */
public class Observation {

    private String stationId;
    private double ra;
    private double dec;
    private double brightness;          // current measured brightness
    private double previousBrightness;  // baseline brightness of the same region
    private double confidence;          // 0.0 - 1.0
    private String timestamp;           // UTC ISO-8601

    /** No-argument constructor required by Gson. */
    public Observation() {
    }

    public Observation(String stationId, double ra, double dec, double brightness,
                       double previousBrightness, double confidence, String timestamp) {
        this.stationId = stationId;
        this.ra = ra;
        this.dec = dec;
        this.brightness = brightness;
        this.previousBrightness = previousBrightness;
        this.confidence = confidence;
        this.timestamp = timestamp;
    }

    /** current / baseline brightness. A value above 3.0 means "unusual" in this project. */
    public double getBrightnessRatio() {
        return previousBrightness <= 0 ? 0.0 : brightness / previousBrightness;
    }

    public String getStationId() {
        return stationId;
    }

    public double getRa() {
        return ra;
    }

    public double getDec() {
        return dec;
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

    public void setConfidence(double confidence) {
        this.confidence = confidence;
    }

    public String getTimestamp() {
        return timestamp;
    }

    @Override
    public String toString() {
        return String.format("RA=%.4f Dec=%+.4f brightness=%.3f baseline=%.3f (x%.2f)",
                ra, dec, brightness, previousBrightness, getBrightnessRatio());
    }
}
