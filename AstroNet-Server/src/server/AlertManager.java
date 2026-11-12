package server;

import common.AstronomicalEvent;
import common.Message;
import common.MessageType;
import config.NetworkConfig;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Processes OBSERVATION_ALERT messages.
 *
 * For each alert it:
 *   1. validates the event,
 *   2. assigns a network-wide unique event ID,
 *   3. finds the other connected stations (the reporter is NOT an independent verifier),
 *   4. starts tracking in the VerificationManager,
 *   5. sends a FOLLOW_UP_REQUEST to each of those stations.
 *
 * handleAlert() may be called by several handler threads at once (two stations can
 * report events simultaneously). The AtomicInteger makes event IDs unique without locks.
 */
public class AlertManager {

    private final StationManager stationManager;
    private final VerificationManager verificationManager;
    private final AtomicInteger eventCounter = new AtomicInteger();
    private final String datePart = LocalDate.now(ZoneOffset.UTC).format(DateTimeFormatter.BASIC_ISO_DATE);

    public AlertManager(StationManager stationManager, VerificationManager verificationManager) {
        this.stationManager = stationManager;
        this.verificationManager = verificationManager;
    }

    public void handleAlert(AstronomicalEvent event, String reportingStationId) {
        if (event == null || !event.hasValidValues()) {
            AstroServer.log("Invalid OBSERVATION_ALERT from " + reportingStationId + " rejected.");
            stationManager.sendTo(reportingStationId, Message.createText(MessageType.ERROR, NetworkConfig.SERVER_ID,
                    "reason", "Invalid observation alert: RA must be 0-360, Dec -90..+90, brightness > 0"));
            return;
        }

        // The server, not the station, owns the official event ID.
        String eventId = String.format("EVT-%s-%04d", datePart, eventCounter.incrementAndGet());
        event.setStationEventId(event.getStationEventId() != null ? event.getStationEventId() : event.getEventId());
        event.setEventId(eventId);
        event.setReportingStationId(reportingStationId);

        List<String> verifiers = new ArrayList<>(stationManager.getConnectedStationIds());
        verifiers.remove(reportingStationId);

        AstroServer.logBlock(List.of(
                "******************************************************************",
                "OBSERVATION_ALERT received from " + reportingStationId
                        + " (station event " + event.getStationEventId() + ")",
                "  Assigned event ID : " + eventId,
                "  Event             : " + event.describe(),
                "  Observed at (UTC) : " + event.getTimestamp(),
                "  Independent verifiers: " + (verifiers.isEmpty() ? "none connected" : verifiers),
                "******************************************************************"));

        // Start tracking BEFORE sending, so a very fast response can never arrive "too early".
        verificationManager.startVerification(event, verifiers);

        Message request = Message.create(MessageType.FOLLOW_UP_REQUEST, NetworkConfig.SERVER_ID, event);
        for (String stationId : verifiers) {
            boolean sent = stationManager.sendTo(stationId, request);
            if (sent) {
                AstroServer.log("FOLLOW_UP_REQUEST " + eventId + " -> " + stationId
                        + " (observe RA=" + String.format("%.4f", event.getRa())
                        + ", Dec=" + String.format("%+.4f", event.getDec()) + ")");
            } else {
                AstroServer.log("FOLLOW_UP_REQUEST " + eventId + " -> " + stationId + " FAILED (station unreachable)");
                verificationManager.handleStationDisconnected(stationId);
            }
        }
    }
}
