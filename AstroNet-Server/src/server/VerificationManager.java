package server;

import common.AstronomicalEvent;
import common.Message;
import common.MessageType;
import common.VerificationResult;
import common.VerificationResult.FinalReport;
import common.VerificationResult.FinalReport.Decision;
import common.VerificationResult.Status;
import config.NetworkConfig;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Collects VERIFICATION_RESPONSE messages and decides the final result of each event.
 *
 * Several events can be verified at the same time. Each event has its own
 * PendingVerification object, stored in a ConcurrentHashMap keyed by event ID,
 * so responses for different events are never mixed.
 *
 * Decision rules:
 *   - at least 2 independent CONFIRMED                          -> LIKELY_CONFIRMED
 *   - every asked station answered, 0 CONFIRMED and at least
 *     2 usable (NOT_DETECTED) observations                       -> NOT_CONFIRMED
 *   - anything else (timeout, too few usable answers, ...)       -> INCONCLUSIVE
 * UNAVAILABLE (e.g. bad weather) is never counted as a negative observation.
 *
 * An event is finalized when all asked stations have answered (or disconnected),
 * or when the timeout expires - whichever happens first.
 */
public class VerificationManager {

    private static final int MIN_CONFIRMATIONS = 2;
    private static final int MIN_USABLE_FOR_NEGATIVE = 2;

    private final StationManager stationManager;
    private final Map<String, PendingVerification> pending = new ConcurrentHashMap<>();
    private final List<FinalReport> completed = new CopyOnWriteArrayList<>();

    /** One background thread that fires the verification timeouts. */
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "Verification-Timer");
        thread.setDaemon(true);
        return thread;
    });

    public VerificationManager(StationManager stationManager) {
        this.stationManager = stationManager;
    }

    /** Starts tracking an event. Called by AlertManager before the follow-up requests are sent. */
    public void startVerification(AstronomicalEvent event, List<String> verifierIds) {
        PendingVerification verification = new PendingVerification(event, verifierIds);
        pending.put(event.getEventId(), verification);

        if (verifierIds.isEmpty()) {
            finalizeEvent(verification, "No other stations are connected, so no independent verification is possible");
            return;
        }
        verification.timeoutTask = timer.schedule(() -> onTimeout(event.getEventId()),
                NetworkConfig.VERIFICATION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        AstroServer.log("Verification of " + event.getEventId() + " started: waiting for " + verifierIds.size()
                + " station(s), timeout " + NetworkConfig.VERIFICATION_TIMEOUT_SECONDS + " s.");
    }

    /** Called by a ClientHandler thread when a VERIFICATION_RESPONSE arrives. */
    public void handleResponse(VerificationResult result, String fromStationId) {
        if (result.getEventId() == null || result.getStatus() == null) {
            AstroServer.log("Incomplete VERIFICATION_RESPONSE from " + fromStationId + " ignored.");
            return;
        }
        PendingVerification verification = pending.get(result.getEventId());
        if (verification == null) {
            AstroServer.log("Late/unknown VERIFICATION_RESPONSE for " + result.getEventId() + " from " + fromStationId
                    + " ignored (event already finalized or never existed).");
            return;
        }
        // Trust the identity of the TCP connection, not the text inside the payload.
        result.setStationId(fromStationId);

        RecordOutcome outcome = verification.record(fromStationId, result);
        switch (outcome) {
            case DUPLICATE:
                AstroServer.log("Duplicate response for " + result.getEventId() + " from " + fromStationId + " ignored.");
                return;
            case NOT_EXPECTED:
                AstroServer.log("Response for " + result.getEventId() + " from " + fromStationId
                        + " ignored (station was not asked - the reporting station cannot verify its own event).");
                return;
            case ALREADY_FINAL:
                AstroServer.log("Response for " + result.getEventId() + " from " + fromStationId + " arrived after the decision - ignored.");
                return;
            case ACCEPTED:
            default:
                break;
        }

        AstroServer.log("VERIFICATION_RESPONSE " + result.getEventId() + ": " + result.describe());
        AstroServer.log("  Progress " + verification.progressText());

        if (verification.isComplete()) {
            finalizeEvent(verification, "All asked stations have answered");
        }
    }

    /** A station disconnected: stop waiting for its answers. */
    public void handleStationDisconnected(String stationId) {
        for (PendingVerification verification : pending.values()) {
            if (verification.markUnreachable(stationId)) {
                AstroServer.log(stationId + " can no longer answer " + verification.event.getEventId()
                        + " (disconnected). Progress " + verification.progressText());
                if (verification.isComplete()) {
                    finalizeEvent(verification, "All remaining stations have answered");
                }
            }
        }
    }

    private void onTimeout(String eventId) {
        PendingVerification verification = pending.get(eventId);
        if (verification != null) {
            AstroServer.log("TIMEOUT: " + NetworkConfig.VERIFICATION_TIMEOUT_SECONDS + " s elapsed for " + eventId
                    + ". Progress " + verification.progressText());
            finalizeEvent(verification, "Verification timeout (" + NetworkConfig.VERIFICATION_TIMEOUT_SECONDS + " s)");
        }
    }

    /** Decides and broadcasts the result. Runs at most once per event (AtomicBoolean guard). */
    private void finalizeEvent(PendingVerification verification, String trigger) {
        if (!verification.finalized.compareAndSet(false, true)) {
            return;   // another thread already finalized this event
        }
        pending.remove(verification.event.getEventId());
        ScheduledFuture<?> timeout = verification.timeoutTask;
        if (timeout != null) {
            timeout.cancel(false);
        }

        FinalReport report = verification.buildReport(trigger);
        completed.add(report);

        List<String> lines = new ArrayList<>();
        lines.add("==================================================================");
        lines.add("FINAL RESULT for " + report.getEventId() + " (" + report.getEventType() + ", reported by "
                + report.getReportingStationId() + ")");
        lines.add("  Decision : " + report.getDecision());
        lines.add("  Reason   : " + report.getReason());
        lines.add("  Counts   : " + report.describeCounts());
        for (VerificationResult response : report.getResponses()) {
            lines.add("    " + response.describe());
        }
        lines.add("==================================================================");
        AstroServer.logBlock(lines);

        Message message = Message.create(MessageType.FINAL_RESULT, NetworkConfig.SERVER_ID, report);
        int delivered = stationManager.broadcast(message);
        AstroServer.log("FINAL_RESULT " + report.getEventId() + " broadcast to " + delivered + " connected station(s).");
    }

    /** Console output for the 'events' command. */
    public void printEvents() {
        List<String> lines = new ArrayList<>();
        lines.add("Pending verifications: " + pending.size());
        for (PendingVerification verification : pending.values()) {
            lines.add("  " + verification.event.getEventId() + " from " + verification.event.getReportingStationId()
                    + "  " + verification.progressText());
        }
        lines.add("Completed events: " + completed.size());
        for (FinalReport report : completed) {
            lines.add(String.format("  %s %-22s by %s -> %-16s %s", report.getEventId(), report.getEventType(),
                    report.getReportingStationId(), report.getDecision(), report.describeCounts()));
        }
        AstroServer.logBlock(lines);
    }

    public void shutdown() {
        timer.shutdownNow();
    }

    // ------------------------------------------------------------------ per-event state

    private enum RecordOutcome { ACCEPTED, DUPLICATE, NOT_EXPECTED, ALREADY_FINAL }

    /**
     * State of one event under verification. All methods that touch the collections
     * are synchronized on this object, because responses arrive on different handler threads.
     */
    private static class PendingVerification {
        final AstronomicalEvent event;
        final Set<String> expected;
        final Set<String> unreachable = new LinkedHashSet<>();
        final Map<String, VerificationResult> responses = new LinkedHashMap<>();
        final AtomicBoolean finalized = new AtomicBoolean(false);
        volatile ScheduledFuture<?> timeoutTask;

        PendingVerification(AstronomicalEvent event, List<String> expectedStations) {
            this.event = event;
            this.expected = new LinkedHashSet<>(expectedStations);
        }

        synchronized RecordOutcome record(String stationId, VerificationResult result) {
            if (finalized.get()) {
                return RecordOutcome.ALREADY_FINAL;
            }
            if (!expected.contains(stationId)) {
                return RecordOutcome.NOT_EXPECTED;
            }
            if (responses.containsKey(stationId)) {
                return RecordOutcome.DUPLICATE;   // only ONE response per station is counted
            }
            responses.put(stationId, result);
            return RecordOutcome.ACCEPTED;
        }

        synchronized boolean markUnreachable(String stationId) {
            return !finalized.get() && expected.contains(stationId)
                    && !responses.containsKey(stationId) && unreachable.add(stationId);
        }

        synchronized boolean isComplete() {
            return responses.size() + unreachable.size() >= expected.size();
        }

        synchronized int count(Status status) {
            int total = 0;
            for (VerificationResult result : responses.values()) {
                if (result.getStatus() == status) {
                    total++;
                }
            }
            return total;
        }

        synchronized String progressText() {
            List<String> waitingFor = new ArrayList<>();
            for (String id : expected) {
                if (!responses.containsKey(id) && !unreachable.contains(id)) {
                    waitingFor.add(id);
                }
            }
            return String.format("%s: %d/%d answered | CONFIRMED=%d NOT_DETECTED=%d UNAVAILABLE=%d | waiting for %s",
                    event.getEventId(), responses.size(), expected.size(), count(Status.CONFIRMED),
                    count(Status.NOT_DETECTED), count(Status.UNAVAILABLE),
                    waitingFor.isEmpty() ? "nobody" : waitingFor);
        }

        synchronized FinalReport buildReport(String trigger) {
            int confirmed = count(Status.CONFIRMED);
            int notDetected = count(Status.NOT_DETECTED);
            int unavailable = count(Status.UNAVAILABLE);
            int noResponse = expected.size() - responses.size();
            boolean allAnswered = noResponse == 0;

            Decision decision;
            String reason;
            if (confirmed >= MIN_CONFIRMATIONS) {
                decision = Decision.LIKELY_CONFIRMED;
                reason = confirmed + " independent stations confirmed the brightening.";
            } else if (allAnswered && confirmed == 0 && notDetected >= MIN_USABLE_FOR_NEGATIVE) {
                decision = Decision.NOT_CONFIRMED;
                reason = "All asked stations answered and none of the " + notDetected
                        + " usable observations detected the event.";
            } else {
                decision = Decision.INCONCLUSIVE;
                if (expected.isEmpty()) {
                    reason = "No independent stations were available.";
                } else if (confirmed == 1) {
                    reason = "Only 1 confirmation (" + MIN_CONFIRMATIONS + " required).";
                } else if (!allAnswered) {
                    reason = noResponse + " station(s) did not answer.";
                } else {
                    reason = "Too few usable observations (UNAVAILABLE is not counted as negative).";
                }
            }
            reason = reason + " [" + trigger + "]";
            return new FinalReport(event, decision, reason, new ArrayList<>(expected),
                    new ArrayList<>(responses.values()), confirmed, notDetected, unavailable, noResponse);
        }
    }
}
