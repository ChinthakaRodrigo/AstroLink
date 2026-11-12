package demo;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Stand-alone teaching demo for the lecture topics
 * "Creating Threads" and "Thread Life Cycle". It does not need the network or Gson.
 *
 * Run:  java -cp out demo.ThreadLifecycleDemo
 *
 * Part 1 - the two ways of creating a thread:
 *          (a) extending Thread      (b) implementing Runnable
 * Part 2 - every official java.lang.Thread.State:
 *          NEW, RUNNABLE, BLOCKED, WAITING, TIMED_WAITING, TERMINATED
 *          (The lecture's "RUNNING" state is not a separate Java state: a thread that is
 *           actually executing on a CPU core is still reported as RUNNABLE.)
 * Part 3 - concurrency with an ExecutorService (several "stations" handled at once).
 */
public class ThreadLifecycleDemo {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static final Object TELESCOPE_LOCK = new Object();

    public static void main(String[] args) throws InterruptedException {
        System.out.println("=== AstroNet thread demo (process id " + ProcessHandle.current().pid() + ") ===");
        System.out.println("One PROCESS (this JVM) can contain many THREADS that share its memory.\n");

        partOneCreatingThreads();
        partTwoLifeCycleStates();
        partThreeConcurrency();

        System.out.println("\n=== Demo finished ===");
    }

    // ------------------------------------------------------------------ Part 1

    /** Creation method 1: EXTEND the Thread class and override run(). */
    static class TelescopeCalibrationThread extends Thread {
        TelescopeCalibrationThread() {
            super("Calibration-Thread");
        }

        @Override
        public void run() {
            log("Calibrating telescope mirrors (inside a subclass of Thread)...");
            pause(300);
            log("Calibration done.");
        }
    }

    /** Creation method 2: IMPLEMENT Runnable and give it to a Thread object. */
    static class SkyScanTask implements Runnable {
        @Override
        public void run() {
            log("Scanning the sky (Runnable task executed by a Thread)...");
            pause(300);
            log("Scan done.");
        }
    }

    private static void partOneCreatingThreads() throws InterruptedException {
        System.out.println("--- Part 1: creating threads ---");

        Thread calibration = new TelescopeCalibrationThread();
        log("Method 1 (extends Thread) created  -> state " + calibration.getState());
        calibration.start();
        log("Method 1 started                   -> state " + calibration.getState());
        calibration.join();   // main thread WAITS until calibration finishes
        log("Method 1 finished (after join())   -> state " + calibration.getState());

        Thread scan = new Thread(new SkyScanTask(), "SkyScan-Thread");
        log("Method 2 (implements Runnable) created -> state " + scan.getState());
        scan.start();
        log("Method 2 started                       -> state " + scan.getState());
        scan.join();
        log("Method 2 finished                      -> state " + scan.getState());

        // Runnable is a functional interface, so a lambda also works (used in AstroNet).
        Thread lambdaThread = new Thread(() -> log("Hello from a lambda Runnable"), "Lambda-Thread");
        lambdaThread.start();
        lambdaThread.join();
        System.out.println();
    }

    // ------------------------------------------------------------------ Part 2

    private static void partTwoLifeCycleStates() throws InterruptedException {
        System.out.println("--- Part 2: Thread.State life cycle ---");

        // NEW -> RUNNABLE -> TIMED_WAITING -> TERMINATED
        Thread sleeper = new Thread(() -> pause(800), "Sleeping-Observer");
        log("Created, not started yet         : " + sleeper.getState());
        sleeper.start();
        log("Right after start()              : " + sleeper.getState() + "   (RUNNABLE = ready OR actually running)");
        Thread.sleep(200);
        log("While inside Thread.sleep(800)   : " + sleeper.getState());
        sleeper.join();
        log("After run() has returned         : " + sleeper.getState());

        // WAITING: wait() with no time limit until another thread calls notify()
        Thread waiter = new Thread(() -> {
            synchronized (TELESCOPE_LOCK) {
                try {
                    TELESCOPE_LOCK.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }, "Waiting-Observer");
        waiter.start();
        Thread.sleep(200);
        log("Inside lock.wait()               : " + waiter.getState());
        synchronized (TELESCOPE_LOCK) {
            TELESCOPE_LOCK.notifyAll();
        }
        waiter.join();
        log("After notifyAll()                : " + waiter.getState());

        // BLOCKED: trying to enter a synchronized block whose lock another thread holds
        Thread blocked;
        synchronized (TELESCOPE_LOCK) {   // main thread holds the telescope lock
            blocked = new Thread(() -> {
                synchronized (TELESCOPE_LOCK) {
                    log("Finally got the telescope lock");
                }
            }, "Blocked-Observer");
            blocked.start();
            Thread.sleep(200);
            log("Waiting for a held monitor lock  : " + blocked.getState());
        }                                 // main thread releases the lock here
        blocked.join();
        log("After the lock was released      : " + blocked.getState());
        System.out.println();
    }

    // ------------------------------------------------------------------ Part 3

    private static void partThreeConcurrency() throws InterruptedException {
        System.out.println("--- Part 3: concurrency with an ExecutorService ---");
        int cores = Runtime.getRuntime().availableProcessors();
        log("CPU cores available: " + cores + " -> with more than one core, threads can run in PARALLEL;");
        log("on one core they still run CONCURRENTLY (interleaved by the scheduler).");

        ExecutorService pool = Executors.newFixedThreadPool(3);
        String[] stations = {"ASTRO-02", "ASTRO-03", "ASTRO-04"};
        for (String station : stations) {
            pool.execute(() -> {
                for (int step = 1; step <= 3; step++) {
                    log("handling " + station + " step " + step);
                    pause(100);
                }
            });
        }
        pool.shutdown();
        pool.awaitTermination(5, TimeUnit.SECONDS);
        log("All station tasks finished. Notice the interleaved output: no task waited for another.");
    }

    // ------------------------------------------------------------------ helpers

    private static synchronized void log(String message) {
        System.out.printf("[%s] [%-18s] %s%n", LocalTime.now().format(TIME), Thread.currentThread().getName(), message);
    }

    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
