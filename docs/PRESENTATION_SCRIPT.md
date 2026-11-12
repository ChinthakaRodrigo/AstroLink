# AstroNet: presentation script (about 8–10 minutes)

## 1. Introduction (1 min)

"Good morning. Our project is **AstroNet**, a distributed astronomical observation network simulation written in Java 17.
Five observation stations (Sri Lanka, India, Australia, Japan and South Africa) run on five different computers. A central server runs on a sixth computer. When one station sees a sudden brightening in the sky, the server asks the other stations to check the same coordinates independently, collects their answers, and announces whether the event is confirmed.
The telescopes are simulated, but the networking is real: every message travels over TCP/IP between separate machines."

## 2. Architecture (1.5 min)

*Show the server and station consoles side by side.*

"We built two independent projects with no Maven or Gradle, just the JDK and the Gson JAR in a `lib` folder.

* **AstroNet-Server** is the server in a client–server architecture. It binds a `ServerSocket` to `0.0.0.0` on port 5000 and waits.
* **AstroNet-Station** is the client. The same program runs on five computers; the user picks a station ID and types the server's IP address. We never assume localhost, because on a different computer localhost would point to the station itself.

Every TCP message uses one JSON envelope (`messageType`, `messageId`, `senderId`, `timestamp`, `payload`), serialized by Gson. We send exactly one JSON object per line (NDJSON), which gives us a simple protocol: the receiver just calls `readLine()`."

## 3. Live demo: connection (1.5 min)

1. Start the server. "The server prints its LAN IP addresses and then says `accept() is BLOCKING`. The main thread is suspended inside `accept()` until a client finishes the TCP handshake."
2. Start ASTRO-01 to ASTRO-05. "Each `accept()` returns a new `Socket`, and its endpoint (IP and port) is printed. The socket goes to a `ClientHandler`, and the main thread immediately returns to `accept()`."
3. Type `list` on the server. "All five are registered. Each has its own handler thread, and the station list is a `ConcurrentHashMap` because many threads use it."

## 4. Live demo: event verification (2 min)

1. On ASTRO-01, choose **2 – Send a test astronomical alert**.
2. Server: "The alert arrives as one JSON line. The server assigns the global ID `EVT-…-0001` and sends a FOLLOW_UP_REQUEST to the four *other* stations. The reporting station does not count as an independent verifier."
3. Stations: "Each station receives the request on its **receiver thread**, and the observation runs on a separate **verifier thread**, so the station can still receive messages. A station answers CONFIRMED, NOT_DETECTED, or UNAVAILABLE. UNAVAILABLE means cloud cover or a target below that country's horizon, and it is not counted as a negative."
4. Server: "Progress is shown as answers arrive. With two or more confirmations the result is LIKELY_CONFIRMED. If everyone answered and nobody confirmed, it is NOT_CONFIRMED. Otherwise, including a 15-second timeout, it is INCONCLUSIVE. The FINAL_RESULT is broadcast to all stations."
5. Concurrency: "Now ASTRO-02 and ASTRO-03 both press 2 at the same time." Show two event IDs verified in parallel without their responses mixing. "Each event has its own object in a map keyed by event ID, and duplicates are ignored."

## 5. Threads (1.5 min)

* Server `threads` command: "One process with many threads: `main`, `Handler-ASTRO-01`…`05`, `Verification-Timer`, `UDP-Server`, `Server-Console`. Processes are the six separate programs; threads are the parallel paths inside one program, sharing memory."
* Station option 1 then option 4: "The simulator thread goes from NEW to RUNNABLE, and option 4 shows it in TIMED_WAITING while it sleeps between observations. Option 1 again stops it and it becomes TERMINATED."
* "Java's official states are NEW, RUNNABLE, BLOCKED, WAITING, TIMED_WAITING and TERMINATED. RUNNING is part of RUNNABLE. Our `ThreadLifecycleDemo` shows all six, and both ways of creating a thread: `extends Thread` (our `UDPServer`) and `implements Runnable` (our `ClientHandler` and `ObservationSimulator`). We mainly use Runnable because it separates the task from the thread, works with `ExecutorService`, and lets us restart the simulator with a new Thread object, since a Thread can only be started once."
* "Concurrency means all five stations are served at overlapping times. Parallelism means they actually run at the same instant on different CPU cores."

## 6. UDP (1 min)

On a station, choose **5 – Send UDP test message**.
"UDP has no `accept()` and no connection. We create a `DatagramSocket` and send independent `DatagramPacket`s that carry the server's IP and port. We deliberately skip one sequence number and swap two, and the server detects the gap and the wrong order. UDP itself would never tell us. That is why we use UDP only for non-critical heartbeats and TCP for alerts and verifications."

## 7. Failure handling and closing (1 min)

* Close ASTRO-05 with option 6 (or kill the window). "The server detects end-of-stream, removes the station, closes the socket in a `finally` block, and stops waiting for that station's verification answers."
* Try starting a second ASTRO-01: "Rejected with an ERROR message, so station IDs stay unique."
* "To summarise, AstroNet demonstrates TCP and UDP sockets, client–server design, a JSON protocol with Gson, multithreading with thread pools, both thread-creation methods and the thread life cycle, running on six real computers. Thank you; we're happy to take questions."

## Likely questions

* **Why TCP for alerts?** It is reliable and ordered and has flow control; losing a verification would change the decision.
* **What happens if a station never answers?** The 15 s `ScheduledExecutorService` timeout finalizes the event as INCONCLUSIVE (unless 2 confirmations already arrived).
* **Why `synchronized send()`?** Several threads (handlers, timer) may write to the same socket. Without the lock, two JSON lines could interleave and corrupt the stream.
* **Why `0.0.0.0`?** It binds to all interfaces, so the server is reachable on its Wi-Fi/LAN/VPN address, not only on 127.0.0.1.
* **Internet use?** Only through a VPN (for example Tailscale/WireGuard or a university VPN). The protocol has no encryption or authentication, so it must not be exposed directly to the public internet.
