# How AstroNet demonstrates each lecture concept

All paths are relative to the repository root. `S/` = `AstroNet-Server/src/`, `C/` = `AstroNet-Station/src/`.

## A. Fundamentals of network programming

| Concept | Where it appears |
|---|---|
| Network programming between separate computers | The server and the stations are separate programs (separate JVM **processes**) that only talk through TCP/UDP sockets. They can run on six different computers. |
| Client–server architecture | `S/server/AstroServer.java` is the passive server (listens, provides the "verification coordination" service). `C/station/StationConnection.java` is the active client (starts the connection, requests services). |
| Communication protocol | An application-layer protocol on top of TCP: newline-delimited JSON messages with nine types (`S/common/MessageType.java`), a fixed envelope (`Message.java`), and fixed rules (REGISTER first, one response per station, and so on). |
| IP address | The server prints its LAN IPs (`NetworkConfig.getLanIpAddresses()`, using `NetworkInterface` and `InetAddress`). The station asks the user for the server IP and validates it with `InetAddress.getByName()`. |
| Port number | TCP 5000 and UDP 5001 by default (`S/config/NetworkConfig.java`). Both can be overridden with command-line arguments. |
| Socket endpoint (IP + port) | The server logs `remote endpoint 192.168.1.21:53122 -> local endpoint 192.168.1.10:5000` for every accepted connection. Station menu option 4 prints the local and remote endpoints of its socket. |
| Binding to `0.0.0.0` | `AstroServer.start()`: `listener.bind(new InetSocketAddress("0.0.0.0", port))` accepts connections on every network interface. |

## B. TCP socket programming

**Server** (`S/server/AstroServer.java`, `S/server/ClientHandler.java`)
1. `new ServerSocket()` followed by `bind(0.0.0.0:5000)`.
2. `listener.accept()` inside a `while` loop. **accept() blocks** the main thread: the thread does nothing until a client completes the TCP three-way handshake. accept() then returns a **new `Socket`** dedicated to that client, and the `ServerSocket` keeps listening on port 5000. The console shows `accept() is BLOCKING - main thread waits ...` before each call.
3. Multiple clients: each returned `Socket` is wrapped in a `ClientHandler` and given to an `ExecutorService`. The main thread goes straight back to `accept()`.
4. Streams: `socket.getInputStream()` and `socket.getOutputStream()` (byte streams) are wrapped in `InputStreamReader`/`OutputStreamWriter` (UTF-8), `BufferedReader` (for `readLine()`) and `PrintWriter`.
5. Closing: `ClientHandler.cleanup()` runs in a `finally` block and calls `socket.close()`, which also closes both streams. `ServerSocket` uses try-with-resources.

**Station** (`C/station/StationConnection.java`)
1. Asks for the server IP (`AstroStation.askServerIp`). Localhost is never assumed.
2. `new Socket()` followed by `connect(new InetSocketAddress(ip, port), 5000 ms)` creates the TCP connection with a timeout.
3. Sends and receives NDJSON messages.
4. Errors are reported in plain language: `ConnectException` (server not running or wrong port), `SocketTimeoutException` (wrong IP or firewall), `NoRouteToHostException`, `UnknownHostException`, and connection loss while running.

**Framing:** one JSON object per line. The sender writes `json + '\n'` and calls `flush()`. The receiver calls `readLine()`. UTF-8 is set explicitly on both sides, and `'\n'` is written explicitly instead of using `println()`, so Windows and Linux machines can be mixed.

## C. UDP socket programming (optional module)

* `S/udp/UDPServer.java`: `DatagramSocket(5001, 0.0.0.0)`, then `receive(DatagramPacket)` in a loop, then replies to `packet.getAddress()`/`packet.getPort()`.
* `C/udp/UDPClient.java`: `new DatagramSocket()` (random local port), then builds `DatagramPacket(data, len, InetAddress, 5001)` and calls `send()`.
* **Connectionless:** there is no `accept()`, no handshake and no stream. Every packet carries its own destination address.
* **No delivery or ordering guarantee:** each datagram has a sequence number. The demo deliberately skips one number and swaps two, and the server prints `UDP GAP` and `UDP ORDERING` warnings. The station waits only 1.5 s for replies; a missing reply is normal for UDP.
* UDP carries **only** heartbeat/status data. Alerts and verification responses use TCP, because losing them would corrupt the scientific result.

## D. Client–server architecture

* The server waits and listens; stations initiate.
* The server provides the services: registration, alert distribution, result aggregation and the decision. Stations request them (REGISTER, OBSERVATION_ALERT).
* Information flows both ways: the server also *pushes* FOLLOW_UP_REQUEST and FINAL_RESULT over the same TCP connection.
* Connections are closed properly with DISCONNECT messages, `finally` blocks, try-with-resources, and a shutdown hook on Ctrl+C.
* Five simultaneous stations: `NetworkConfig.MAX_STATIONS = 5`, enforced in `StationManager.register()`. A sixth station, or a duplicate ID, receives an `ERROR` message.

## E. Processes and threads

* **Processes:** six independent JVM processes (1 server and 5 stations), each with its own memory, usually on different computers. They share nothing and can only communicate over the network.
* **Threads:** inside one process, threads share memory, which is why shared data uses `ConcurrentHashMap`, `CopyOnWriteArrayList`, `AtomicInteger`, `AtomicBoolean` and `synchronized`.

| Process | Threads |
|---|---|
| Server | `main` (accept loop), `ClientHandler-N` / `Handler-ASTRO-0X` (one per station, from an `ExecutorService`), `Verification-Timer` (`ScheduledExecutorService`), `UDP-Server`, `Server-Console` |
| Station | `main` (menu), `Receiver-ASTRO-0X` (reads server messages), `Simulator-ASTRO-0X` (sky survey), `Verifier-N` (follow-up observations), `Heartbeat-ASTRO-0X` |

**Why multithreading is necessary:** `accept()` and `readLine()` block. With one thread, the server could not accept station 2 while waiting for a line from station 1. A station could not receive a FOLLOW_UP_REQUEST while its menu waits for keyboard input or its simulator sleeps. Each blocking activity therefore gets its own thread.

**Concurrency vs parallelism here:** *concurrency* means the five handler threads are all in progress at the same time and their work interleaves. That happens even on a single-core CPU, because the OS switches between threads. *Parallelism* means two handlers execute instructions at the same instant on different CPU cores, which happens on multi-core machines (the `threads` command prints the core count). AstroNet is designed for concurrency; parallelism is a bonus the hardware may provide. The six separate computers themselves run truly in parallel.

## F. Thread life cycle

Java's official `Thread.State` values are NEW, RUNNABLE, BLOCKED, WAITING, TIMED_WAITING and TERMINATED. The lecture's **RUNNING** state is not a separate Java value: a thread actually running on a CPU is still reported as `RUNNABLE` (RUNNABLE = ready *or* running).

| State | In AstroNet |
|---|---|
| NEW | `Simulation thread created, state = NEW`, `Receiver thread created, state = NEW`, `UDP-Server thread created, state = NEW` |
| RUNNABLE / "RUNNING" | Logged right after `start()`. Threads blocked in socket `accept()`/`readLine()` also show RUNNABLE, because Java counts native I/O as runnable. |
| TIMED_WAITING | The simulator in `Thread.sleep(3–6 s)` between observations (station menu option 4), the verifier while "exposing", and the heartbeat scheduler. |
| WAITING | Idle `ExecutorService` threads; the console thread in `CountDownLatch.await()`. |
| BLOCKED | Shown in the demo program: a thread waiting to enter a `synchronized` block. In AstroNet it happens briefly when two threads call the synchronized `send()` on the same connection. |
| TERMINATED | `Simulation thread stopped, state = TERMINATED` (menu option 1 twice), `Receiver thread state after close: TERMINATED` (option 6). |

`S/demo/ThreadLifecycleDemo.java` (`run-thread-demo.bat` / `.sh`) shows all six states deterministically.

## G. Creating threads

* **Extending `Thread`:** `S/udp/UDPServer.java extends Thread` (also `TelescopeCalibrationThread` in the demo).
* **Implementing `Runnable`:** `S/server/ClientHandler.java` and `C/station/ObservationSimulator.java implement Runnable`. Lambdas and method references are also Runnables (`new Thread(this::receiveLoop, ...)`).

**Why Runnable is the main choice:** a Runnable is just *a task*, separate from the thread that runs it. That allows:
1. Submitting it to an `ExecutorService` (thread pool), as the server does for every station.
2. Extending another class if needed (Java has single inheritance).
3. Restarting work: a `Thread` object can be started only once, so the simulator creates a new `Thread` around the same `Runnable` each time the user restarts the survey.

`extends Thread` is used for the UDP server because it is a single, long-lived background job started exactly once.

## JSON (Gson)

* `S/common/Message.java` is the common envelope (`messageType`, `messageId`, `senderId`, `timestamp`, `payload`).
* `S/common/JsonUtil.java` wraps a shared, thread-safe `Gson` instance. Invalid JSON returns `null` instead of crashing a handler, and the sender gets an `ERROR` message.
* Payload classes: `StationInfo`, `AstronomicalEvent`, `VerificationResult`, `VerificationResult.FinalReport`, and `UDPMessage` for UDP.
* The `common` classes are byte-for-byte identical in both projects.
