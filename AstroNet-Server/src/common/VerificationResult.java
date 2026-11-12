package common;

import java.util.ArrayList;
import java.util.List;

/**
 * The answer of ONE station to a FOLLOW_UP_REQUEST (payload of VERIFICATION_RESPONSE).
 *
 * The nested class {@link FinalReport} is the server's overall decision for an event
 * (payload of FINAL_RESULT).
 *
 * NOTE: this file is identical in AstroNet-Server and AstroNet-Station.
 */
public class VerificationResult {

    /** Result of one station's independent follow-up observation. */
    public enum Status {
        /** The station also measured a significant brightening (> 3x baseline). */
        CONFIRMED,
        /** The station observed the target but saw no significant brightening. */
        NOT_DETECTED,
        /** The station could not observe (weather, target below horizon...). NOT a negative result. */
        UNAVAILABLE
    }

    private String eventId;
    private String stationId;
    private Status status;
    private double measuredBrightness;
    private double baselineBrightness;
    private double confidence;
    private String timestamp;
    private String note;

    /** No-argument constructor required by Gson. */
    public VerificationResult() {
    }

    public VerificationResult(String eventId, String stationId, Status status, double measuredBrightness,
                              double baselineBrightness, double confidence, String timestamp, String note) {
        this.eventId = eventId;
        this.stationId = stationId;
        this.status = status;
        this.measuredBrightness = measuredBrightness;
        this.baselineBrightness = baselineBrightness;
        this.confidence = confidence;
        this.timestamp = timestamp;
        this.note = note;
    }

    public String describe() {
        if (status == Status.UNAVAILABLE) {
            return String.format("%s -> %s (%s)", stationId, status, note);
        }
        return String.format("%s -> %s (measured %.3f vs baseline %.3f, confidence %.2f) %s",
                stationId, status, measuredBrightness, baselineBrightness, confidence, note == null ? "" : note);
    }

    public String getEventId() {
        return eventId;
    }

    public String getStationId() {
        return stationId;
    }

    public void setStationId(String stationId) {
        this.stationId = stationId;
    }

    public Status getStatus() {
        return status;
    }

    public double getMeasuredBrightness() {
        return measuredBrightness;
    }

    public double getBaselineBrightness() {
        return baselineBrightness;
    }

    public double getConfidence() {
        return confidence;
    }

    public String getTimestamp() {
        return timestamp;
    }

    public String getNote() {
        return note;
    }

    /**
     * Final decision of the server for one event (payload of FINAL_RESULT).
     */
    public static class FinalReport {

        /** Possible overall outcomes. */
        public enum Decision {
            /** At least two independent stations confirmed the event. */
            LIKELY_CONFIRMED,
            /** All expected stations answered, enough usable observations, and nobody confirmed. */
            NOT_CONFIRMED,
            /** Not enough evidence (too few responses, timeout, too many UNAVAILABLE...). */
            INCONCLUSIVE
        }

        private String eventId;
        private String eventType;
        private String reportingStationId;
        private double ra;
        private double dec;
        private Decision decision;
        private String reason;
        private List<String> expectedStations = new ArrayList<>();
        private int confirmedCount;
        private int notDetectedCount;
        private int unavailableCount;
        private int noResponseCount;
        private List<VerificationResult> responses = new ArrayList<>();
        private String decidedAt;

        /** No-argument constructor required by Gson. */
        public FinalReport() {
        }

        public FinalReport(AstronomicalEvent event, Decision decision, String reason,
                           List<String> expectedStations, List<VerificationResult> responses,
                           int confirmedCount, int notDetectedCount, int unavailableCount, int noResponseCount) {
            this.eventId = event.getEventId();
            this.eventType = event.getEventType();
            this.reportingStationId = event.getReportingStationId();
            this.ra = event.getRa();
            this.dec = event.getDec();
            this.decision = decision;
            this.reason = reason;
            this.expectedStations = new ArrayList<>(expectedStations);
            this.responses = new ArrayList<>(responses);
            this.confirmedCount = confirmedCount;
            this.notDetectedCount = notDetectedCount;
            this.unavailableCount = unavailableCount;
            this.noResponseCount = noResponseCount;
            this.decidedAt = Message.utcNow();
        }

        public String describeCounts() {
            return String.format("CONFIRMED=%d  NOT_DETECTED=%d  UNAVAILABLE=%d  NO_RESPONSE=%d  (asked %d stations)",
                    confirmedCount, notDetectedCount, unavailableCount, noResponseCount, expectedStations.size());
        }

        public String getEventId() {
            return eventId;
        }

        public String getEventType() {
            return eventType;
        }

        public String getReportingStationId() {
            return reportingStationId;
        }

        public double getRa() {
            return ra;
        }

        public double getDec() {
            return dec;
        }

        public Decision getDecision() {
            return decision;
        }

        public String getReason() {
            return reason;
        }

        public List<String> getExpectedStations() {
            return expectedStations;
        }

        public int getConfirmedCount() {
            return confirmedCount;
        }

        public int getNotDetectedCount() {
            return notDetectedCount;
        }

        public int getUnavailableCount() {
            return unavailableCount;
        }

        public int getNoResponseCount() {
            return noResponseCount;
        }

        public List<VerificationResult> getResponses() {
            return responses;
        }

        public String getDecidedAt() {
            return decidedAt;
        }
    }
}
