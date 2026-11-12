package station;

import common.AstronomicalEvent;
import common.Message;
import common.Observation;
import common.StationInfo;
import common.VerificationResult;
import common.VerificationResult.Status;

import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Simulates the station's telescope.
 *
 * 1. Continuous sky survey (runs on its own thread, independent of the receiver thread):
 *    every 3-6 seconds it observes a random sky position. About 8% of observations are
 *    sudden brightenings; the EventDetector decides whether to send an OBSERVATION_ALERT.
 *
 * 2. Follow-up observations requested by the server (performFollowUp).
 *
 * Implements Runnable (thread creation method 2): the task is separated from the thread,
 * and because a Java Thread can only be started ONCE, a NEW Thread object is created every
 * time the survey is (re)started, while the same Runnable is reused.
 *
 * Coordinates are decimal degrees: RA 0..360, Dec -90..+90.
 */
public class ObservationSimulator implements Runnable {

    private static final double SPONTANEOUS_EVENT_PROBABILITY = 0.08;
    private static final double BAD_WEATHER_PROBABILITY = 0.15;
    private static final double MINIMUM_ALTITUDE_DEGREES = 15.0;
    private static final int MIN_INTERVAL_MS = 3000;
    private static final int MAX_INTERVAL_MS = 6000;
    private static final long ALERT_COOLDOWN_MS = 30000;

    private final StationInfo info;
    private final StationConnection connection;
    private final EventDetector detector = new EventDetector();
    private final AtomicInteger observationCount = new AtomicInteger();
    private final AtomicInteger localEventCounter = new AtomicInteger();

    private volatile boolean running;
    private volatile Thread simulationThread;
    private volatile long lastAlertTimeMillis;

    public ObservationSimulator(StationInfo info, StationConnection connection) {
        this.info = info;
        this.connection = connection;
    }

    // ------------------------------------------------------------------ start / stop

    /** Starts the survey on a new thread. Returns false if it is already running. */
    public synchronized boolean start() {
        if (running) {
            return false;
        }
        running = true;
        Thread thread = new Thread(this, "Simulator-" + info.getStationId());
        thread.setDaemon(true);
        AstroStation.log("Simulation thread created, state = " + thread.getState());   // NEW
        thread.start();
        AstroStation.log("Simulation thread started, state = " + thread.getState());   // RUNNABLE
        simulationThread = thread;
        return true;
    }

    /** Stops the survey and waits for the thread to end. */
    public void stop() {
        Thread thread;
        synchronized (this) {
            running = false;
            thread = simulationThread;
        }
        if (thread == null) {
            return;
        }
        thread.interrupt();   // wakes the thread up from Thread.sleep()
        if (thread != Thread.currentThread()) {
            try {
                thread.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        AstroStation.log("Simulation thread stopped, state = " + thread.getState());   // TERMINATED
    }

    public boolean isRunning() {
        return running;
    }

    public String getThreadDescription() {
        Thread thread = simulationThread;
        if (thread == null) {
            return "Simulator thread: not created yet (simulation never started)";
        }
        return thread.getName() + ": " + thread.getState()
                + (thread.getState() == Thread.State.TIMED_WAITING ? "   (sleeping between observations)" : "");
    }

    // ------------------------------------------------------------------ continuous survey

    @Override
    public void run() {
        AstroStation.log("Sky survey running (state " + Thread.currentThread().getState()
                + "). Observing every " + MIN_INTERVAL_MS / 1000 + "-" + MAX_INTERVAL_MS / 1000 + " s.");
        try {
            while (running) {
                // TIMED_WAITING while the simulated telescope moves to the next field.
                Thread.sleep(ThreadLocalRandom.current().nextInt(MIN_INTERVAL_MS, MAX_INTERVAL_MS + 1));
                if (!running) {
                    break;
                }
                surveyOnce();
            }
        } catch (InterruptedException e) {
            // stop() interrupted the sleep - leave the loop
        } finally {
            AstroStation.log("Sky survey thread finishing after " + observationCount.get()
                    + " observations -> state will be TERMINATED.");
        }
    }

    private void surveyOnce() {
        Observation observation = observeRandomSky();
        int number = observationCount.incrementAndGet();
        AstronomicalEvent event = detector.analyze(observation, nextLocalEventId());

        if (event == null) {
            AstroStation.log(String.format("obs #%d %s -> normal", number, observation));
            return;
        }
        AstroStation.log(String.format("obs #%d %s -> UNUSUAL BRIGHTENING (> %.0fx baseline)!",
                number, observation, EventDetector.DETECTION_THRESHOLD));

        long now = System.currentTimeMillis();
        if (now - lastAlertTimeMillis < ALERT_COOLDOWN_MS) {
            AstroStation.log("Alert suppressed (cool-down: max one automatic alert per "
                    + ALERT_COOLDOWN_MS / 1000 + " s).");
            return;
        }
        lastAlertTimeMillis = now;
        connection.sendAlert(event);
    }

    /** Observes a random point of the sky that is visible from this station's latitude. */
    private Observation observeRandomSky() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        double ra = random.nextDouble(0.0, 360.0);
        double minDec = Math.max(-90.0, info.getLatitude() - 60.0);
        double maxDec = Math.min(90.0, info.getLatitude() + 60.0);
        double dec = random.nextDouble(minDec, maxDec);
        double baseline = random.nextDouble(0.5, 2.0);

        double brightness;
        if (random.nextDouble() < SPONTANEOUS_EVENT_PROBABILITY) {
            brightness = baseline * random.nextDouble(3.2, 9.0);    // sudden brightening
        } else {
            brightness = baseline * random.nextDouble(0.85, 1.25);  // normal noise
        }
        Observation observation = new Observation(info.getStationId(), round4(ra), round4(dec),
                round3(brightness), round3(baseline), 0.0, Message.utcNow());
        observation.setConfidence(detector.calculateConfidence(observation.getBrightnessRatio()));
        return observation;
    }

    /**
     * Creates a strong test event (menu option 2) near the celestial equator,
     * so the target is above the horizon for all five stations.
     */
    public AstronomicalEvent createTestEvent() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        double baseline = random.nextDouble(1.0, 1.5);
        double brightness = baseline * random.nextDouble(6.0, 8.0);
        Observation observation = new Observation(info.getStationId(), round4(random.nextDouble(0.0, 360.0)),
                round4(random.nextDouble(-15.0, 15.0)), round3(brightness), round3(baseline), 0.0, Message.utcNow());
        observation.setConfidence(detector.calculateConfidence(observation.getBrightnessRatio()));
        return detector.analyze(observation, nextLocalEventId());
    }

    // ------------------------------------------------------------------ follow-up observation

    /**
     * Independently observes the coordinates in a FOLLOW_UP_REQUEST.
     * Runs on a Verifier worker thread (never on the receiver thread).
     */
    public VerificationResult performFollowUp(AstronomicalEvent event) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        AstroStation.log(String.format("Pointing telescope at RA=%.4f, Dec=%+.4f for %s ...",
                event.getRa(), event.getDec(), event.getEventId()));
        try {
            Thread.sleep(random.nextInt(1000, 3500));   // simulated slew + exposure time
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return unavailable(event, "Observation interrupted");
        }

        // 1. Is the target high enough above this station's horizon?
        //    Maximum altitude of an object = 90 - |latitude - declination|.
        double maxAltitude = 90.0 - Math.abs(info.getLatitude() - event.getDec());
        if (maxAltitude < MINIMUM_ALTITUDE_DEGREES) {
            return unavailable(event, String.format("Target too low from %s (max altitude %.1f deg)",
                    info.getLocation(), maxAltitude));
        }
        // 2. Simulated weather.
        if (random.nextDouble() < BAD_WEATHER_PROBABILITY) {
            return unavailable(event, "Cloud cover over " + info.getLocation());
        }

        // 3. Is the event "real"? All stations derive the same answer from the event ID, so
        //    independent stations agree about the sky, but each adds its own measurement noise.
        double realProbability = 0.35 + 0.6 * event.getConfidence();
        boolean eventIsReal = new Random(event.getEventId().hashCode()).nextDouble() < realProbability;

        double baseline = event.getPreviousBrightness() * random.nextDouble(0.9, 1.1);
        double measuredRatio = eventIsReal
                ? event.getBrightnessRatio() * random.nextDouble(0.55, 1.15)
                : random.nextDouble(0.85, 1.6);
        double measured = baseline * measuredRatio;

        boolean detected = detector.isSignificantBrightening(measured, baseline);
        Status status = detected ? Status.CONFIRMED : Status.NOT_DETECTED;
        String note = String.format("x%.2f of baseline seen from %s", measuredRatio, info.getLocation());
        AstroStation.log("Follow-up observation of " + event.getEventId() + " complete: " + status + " (" + note + ")");

        return new VerificationResult(event.getEventId(), info.getStationId(), status, round3(measured),
                round3(baseline), detector.calculateConfidence(measuredRatio), Message.utcNow(), note);
    }

    private VerificationResult unavailable(AstronomicalEvent event, String reason) {
        AstroStation.log("Follow-up observation of " + event.getEventId() + ": UNAVAILABLE (" + reason + ")");
        return new VerificationResult(event.getEventId(), info.getStationId(), Status.UNAVAILABLE,
                0.0, 0.0, 0.0, Message.utcNow(), reason);
    }

    private String nextLocalEventId() {
        return String.format("%s-LOCAL-%04d", info.getStationId(), localEventCounter.incrementAndGet());
    }

    private static double round3(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }

    private static double round4(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }
}
