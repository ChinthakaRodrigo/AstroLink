package station;

import common.AstronomicalEvent;
import common.Observation;

/**
 * Decides whether an observation is "unusual".
 *
 * Detection rule: if the current brightness is MORE THAN 3 TIMES the baseline
 * (previous) brightness of the same sky region, it is a possible astronomical event.
 */
public class EventDetector {

    public static final double DETECTION_THRESHOLD = 3.0;

    /** true if current brightness > 3 x baseline brightness. */
    public boolean isSignificantBrightening(double currentBrightness, double baselineBrightness) {
        return baselineBrightness > 0 && currentBrightness > baselineBrightness * DETECTION_THRESHOLD;
    }

    /**
     * Simple confidence model (0.05 - 0.99): the further above the threshold,
     * the more confident the station is that the brightening is real.
     */
    public double calculateConfidence(double brightnessRatio) {
        double confidence;
        if (brightnessRatio > DETECTION_THRESHOLD) {
            confidence = 0.60 + (brightnessRatio - DETECTION_THRESHOLD) * 0.06;
        } else {
            confidence = 0.50 * brightnessRatio / DETECTION_THRESHOLD;
        }
        return round(Math.max(0.05, Math.min(0.99, confidence)));
    }

    /** Very simple classification by how strong the brightening is. */
    public String classify(double brightnessRatio) {
        if (brightnessRatio >= 8.0) {
            return "SUPERNOVA_CANDIDATE";
        } else if (brightnessRatio >= 5.0) {
            return "NOVA_CANDIDATE";
        } else {
            return "TRANSIENT_BRIGHTENING";
        }
    }

    /** Returns an AstronomicalEvent if the observation is unusual, otherwise null. */
    public AstronomicalEvent analyze(Observation observation, String localEventId) {
        if (!isSignificantBrightening(observation.getBrightness(), observation.getPreviousBrightness())) {
            return null;
        }
        double ratio = observation.getBrightnessRatio();
        return new AstronomicalEvent(localEventId, classify(ratio), observation.getStationId(),
                observation.getRa(), observation.getDec(), observation.getTimestamp(),
                observation.getBrightness(), observation.getPreviousBrightness(), calculateConfidence(ratio));
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
