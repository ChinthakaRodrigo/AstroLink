# AstroNet: Distributed Astronomical Observation Network Simulation

AstroLink/AstroNet is a Java 17+ network programming project. Five simulated observation stations (Sri Lanka, India, Australia, Japan and South Africa) run on five computers and talk to one central server on a sixth computer over **real TCP/IP**.
When a station detects a sudden brightening in the sky, the server asks the other four stations to observe the same coordinates. It then collects their independent results and broadcasts whether the event is **LIKELY_CONFIRMED**, **NOT_CONFIRMED** or **INCONCLUSIVE**.

* Plain Java projects: no Maven or Gradle. The only dependency is `lib/gson-2.13.2.jar`, which is already included.
* Console UI. Runs on Windows and Linux.
* TCP + newline-delimited JSON (Gson) for all critical messages. A separate optional UDP heartbeat module.
* Multithreaded server (`ExecutorService`, one handler per station), multithreaded stations (receiver, simulator, verifier and heartbeat threads).

Further documents:
* [docs/CONCEPTS.md](docs/CONCEPTS.md): where each lecture concept is implemented
* [docs/PRESENTATION_SCRIPT.md](docs/PRESENTATION_SCRIPT.md): presentation and demo script

---

## 1. Project structure

```
AstroLink/
├── AstroNet-Server/                 Project A: runs on Computer 1
│   ├── src/server/   AstroServer, ClientHandler, StationManager, AlertManager, VerificationManager
│   ├── src/common/   Message, MessageType, StationInfo, Observation, AstronomicalEvent, VerificationResult, JsonUtil
│   ├── src/config/   NetworkConfig
│   ├── src/udp/      UDPServer, UDPMessage
│   ├── src/demo/     ThreadLifecycleDemo   (stand-alone thread demo, extra)
│   ├── lib/gson-2.13.2.jar
│   └── compile.bat / compile.sh / run-server.bat / run-server.sh / run-thread-demo.bat / .sh
├── AstroNet-Station/                Project B: runs on Computers 2-6
│   ├── src/station/  AstroStation, StationConnection, ObservationSimulator, EventDetector, StationConsole
│   ├── src/common/   (identical copies of the server's common classes)
│   ├── src/config/   NetworkConfig (identical)
│   ├── src/udp/      UDPClient, UDPMessage (identical)
│   ├── lib/gson-2.13.2.jar
│   └── compile.bat / compile.sh / run-station.bat / run-station.sh
└── docs/
```

## 2. Requirements

* JDK 17 or later on every computer (`java -version` and `javac -version`).
* All six computers on the same Wi-Fi/LAN, or connected through a VPN (see section 6).

## 3. Compiling (no Maven or Gradle)

Gson is added to the classpath with `-cp lib/gson-2.13.2.jar` when compiling, and with `-cp "out;lib/gson-2.13.2.jar"` (Windows) or `-cp "out:lib/gson-2.13.2.jar"` (Linux) when running.
**Classpath separator: `;` on Windows, `:` on Linux.**
`--release 17` produces Java 17 bytecode, so classes compiled with a newer JDK still run on JDK 17 machines.

### Windows (PowerShell)

Easiest:
```powershell
cd AstroNet-Server
.\compile.bat
cd ..\AstroNet-Station
.\compile.bat
```

Manual equivalent:
```powershell
cd AstroNet-Server
javac --release 17 -encoding UTF-8 -d out -cp lib/gson-2.13.2.jar (Get-ChildItem -Recurse -Filter *.java src).FullName

cd ..\AstroNet-Station
javac --release 17 -encoding UTF-8 -d out -cp lib/gson-2.13.2.jar (Get-ChildItem -Recurse -Filter *.java src).FullName
```

### Linux (bash)

```bash
cd AstroNet-Server && chmod +x *.sh && ./compile.sh
cd ../AstroNet-Station && chmod +x *.sh && ./compile.sh
```

Manual equivalent:
```bash
cd AstroNet-Server
javac --release 17 -encoding UTF-8 -d out -cp lib/gson-2.13.2.jar $(find src -name "*.java")
cd ../AstroNet-Station
javac --release 17 -encoding UTF-8 -d out -cp lib/gson-2.13.2.jar $(find src -name "*.java")
```

## 4. Running

### Server (Computer 1)

| | Command (run inside `AstroNet-Server`) |
|---|---|
| Windows | `.\run-server.bat`  or  `java -cp "out;lib/gson-2.13.2.jar" server.AstroServer` |
| Linux | `./run-server.sh`  or  `java -cp "out:lib/gson-2.13.2.jar" server.AstroServer` |

Optional arguments: `[tcpPort] [udpPort]`, for example `server.AstroServer 6000 6001`. The defaults are 5000 and 5001.
The server prints the LAN IP addresses that stations must use.
Server console commands: `list`, `events`, `threads`, `help`, `quit`.

Running the server in the background on Linux (the console commands are then disabled, but the server keeps running):
```bash
nohup ./run-server.sh > server.log 2>&1 &
tail -f server.log          # watch output;  stop with: pkill -f server.AstroServer
```

### Stations (Computers 2–6)

| | Command (run inside `AstroNet-Station`) |
|---|---|
| Windows | `.\run-station.bat`  or  `java -cp "out;lib/gson-2.13.2.jar" station.AstroStation` |
| Linux | `./run-station.sh`  or  `java -cp "out:lib/gson-2.13.2.jar" station.AstroStation` |

The program asks for the station ID (1–5), the **server IP address** and the TCP port (press Enter for 5000).
To skip the questions, pass arguments: `[stationId] [serverIp] [tcpPort] [udpPort]`, for example:
```
.\run-station.bat ASTRO-02 192.168.1.10
./run-station.sh  ASTRO-03 192.168.1.10 5000 5001
```

Station menu:
```
 1. Start observation simulation      (press again to stop)
 2. Send a test astronomical alert
 3. Display station information
 4. Display connection status        (also shows thread states)
 5. Send UDP test message
 6. Disconnect and exit
```

### Thread demo (any computer, no network needed)
`.\run-thread-demo.bat` or `./run-thread-demo.sh` (inside `AstroNet-Server`), or `java -cp out demo.ThreadLifecycleDemo`.

### IDEs
* **IntelliJ IDEA:** open the `AstroNet-Server` folder (and the station folder separately, in a new window). Right-click `src` → *Mark Directory as → Sources Root*. Then *File → Project Structure → Libraries → + → Java* → choose `lib/gson-2.13.2.jar`. Run `server.AstroServer` / `station.AstroStation`. To pass a station ID or IP, use *Run → Edit Configurations → Program arguments*.
* **VS Code** (Extension Pack for Java): open each project folder on its own (`File → Open Folder → AstroNet-Server`). The included `.vscode/settings.json` already adds `src` and `lib/*.jar`.
* **Eclipse:** *File → New → Java Project*, untick "default location" and point it at the project folder. Then *Build Path → Add External Archives →* `lib/gson-2.13.2.jar`.

## 5. Setting up six computers on the same Wi-Fi/LAN

1. Copy (or `git clone`) the project to every computer. The server computer needs `AstroNet-Server`; station computers need `AstroNet-Station`.
2. **Find the server's IP address** (it is also printed by the server at start-up):
   * Windows: `ipconfig`, then look at "IPv4 Address" under the active Wi-Fi or Ethernet adapter, for example `192.168.1.10`.
   * Linux: `hostname -I` or `ip -4 addr show`.
3. **Allow the ports through the server's firewall** (stations make only outgoing connections and normally need no change):
   * Windows (PowerShell **as Administrator**):
     ```powershell
     New-NetFirewallRule -DisplayName "AstroNet TCP 5000" -Direction Inbound -Protocol TCP -LocalPort 5000 -Action Allow
     New-NetFirewallRule -DisplayName "AstroNet UDP 5001" -Direction Inbound -Protocol UDP -LocalPort 5001 -Action Allow
     ```
     Also make sure the Wi-Fi network is set to **Private**, not Public (Settings → Network & Internet → Wi-Fi → network properties). If Windows shows a "Windows Defender Firewall has blocked..." pop-up the first time the server starts, tick *Private networks* and click *Allow*.
     When the demo is over: `Remove-NetFirewallRule -DisplayName "AstroNet TCP 5000"; Remove-NetFirewallRule -DisplayName "AstroNet UDP 5001"`
   * Linux with ufw: `sudo ufw allow 5000/tcp && sudo ufw allow 5001/udp`
   * Linux with firewalld: `sudo firewall-cmd --add-port=5000/tcp --add-port=5001/udp` (temporary until reboot)
4. **Test the network path** from a station computer:
   * `ping 192.168.1.10`. Some Windows PCs block ping by default, so a failed ping is not conclusive.
   * Windows: `Test-NetConnection 192.168.1.10 -Port 5000` should show `TcpTestSucceeded : True` while the server runs.
   * Linux: `nc -vz 192.168.1.10 5000`
5. Start the server, then the stations, each with a **different** station ID.

> **Campus or public Wi-Fi often has "client isolation"**, which blocks laptops from reaching each other even on the same network. If the connection test fails while the firewall is open, use a phone hotspot or a home router, or use a VPN as described below.

## 6. Stations on different networks (VPN)

Do **not** port-forward port 5000 to the public internet. The protocol has no encryption or authentication.
Put all six computers in one private virtual network instead:

* **Tailscale** (easiest, free for personal use) or **ZeroTier**: install it on all six computers and log in to the same account or network. Each computer gets a private address such as `100.x.y.z`. Run `tailscale ip -4` on the server and give that address to the stations. AstroNet prints this address too, because the server binds to `0.0.0.0`.
* A **WireGuard** VPN or your **university VPN**, if your lecturer provides one. Use the server's VPN IP address.

The firewall rules in section 5 still apply on the server; allow the ports for the VPN interface or network profile.

## 7. Demonstration procedure

| # | Action | What to point out |
|---|---|---|
| 1 | Start server: `run-server.bat` | `TCP ServerSocket bound to 0.0.0.0:5000`, LAN IP list, `accept() is BLOCKING ...` |
| 2–6 | Start stations ASTRO-01 … ASTRO-05 on computers 2–6 | Server: `TCP connection accepted: remote endpoint ...`, `REGISTERED ASTRO-0X`. Station: `REGISTER_ACK received`. |
| 7 | Server: type `list` | All five stations with IP addresses: `Connected stations (5/5)` |
| 8 | ASTRO-01: option **2** | Station: `OBSERVATION_ALERT sent`. Server: raw NDJSON line, assigned event ID. |
| 9 | Watch the server | `FOLLOW_UP_REQUEST EVT-... -> ASTRO-02` … `-> ASTRO-05`. ASTRO-01 is not asked. |
| 10 | Watch stations 2–5 | `>>> FOLLOW_UP_REQUEST received`, `Pointing telescope ...`, `<<< VERIFICATION_RESPONSE sent ...: CONFIRMED / NOT_DETECTED / UNAVAILABLE` |
| 11 | Watch the server and all stations | `Progress ... 3/4 answered`, then a `FINAL RESULT` block and `FINAL_RESULT broadcast to 5 connected station(s)` |
| 12 | ASTRO-05: option **6** (or close its window) | Server: `ASTRO-05 sent DISCONNECT`, `DISCONNECTED ASTRO-05 ... (4/5)`. Then trigger another alert: only 3 verifiers are asked. |
| 13 | Any station: option **5** | Server: `UDP datagram from ...`, `UDP GAP`, `UDP ORDERING`. Station: `UDP reply ...`. |
| 14 | ASTRO-02 and ASTRO-03 press **2** at the same moment, and/or several stations run option **1** | Two events with different IDs are verified at the same time without mixing. Log lines from `Handler-ASTRO-0X` threads interleave. Server `threads` command; station option **4** shows `Simulator: TIMED_WAITING`. |
| extra | Start a second station with an ID already in use | Server: `Rejected ... already connected`. Station: `ERROR from server`. |
| extra | Stop the 4th station while an event is being verified, or let a station ignore a request | `can no longer answer ... (disconnected)`, or `TIMEOUT: 15 s elapsed` → `INCONCLUSIVE` |
| extra | `run-thread-demo.bat` | NEW, RUNNABLE, TIMED_WAITING, WAITING, BLOCKED, TERMINATED |

Results are random: most strong test alerts are LIKELY_CONFIRMED, some events are NOT_CONFIRMED (a false alarm), and stations may answer UNAVAILABLE because of cloud cover or because the target is below their horizon.

Single-computer rehearsal: start the server and five stations in six terminals on one PC, and enter `127.0.0.1` as the server IP. This is only for practice; on the real demo use the LAN IP.

## 8. Sample console output

These excerpts come from a real test run on one computer. Loopback addresses, test ports and dates were replaced with LAN-style values to show what a six-computer run looks like. Your random values will differ.

**Server**
```
[01:17:41] [main                ] TCP ServerSocket bound to 0.0.0.0:5000  (socket endpoint = IP address + port)
[01:17:41] [main                ] Stations on other computers must connect to one of these LAN/VPN IP addresses:
[01:17:41] [main                ]    -> 192.168.8.110   (Realtek RTL8822CE 802.11ac PCIe Adapter)   TCP port 5000
[01:17:41] [main                ] accept() is BLOCKING - main thread waits for the next station to connect...
[01:17:43] [main                ] TCP connection accepted: remote endpoint 192.168.8.121:52413 -> local endpoint 192.168.8.110:5000
[01:17:43] [ClientHandler-1     ] REGISTERED ASTRO-01 (Sri Lanka) from 192.168.8.121:52413 -> REGISTER_ACK sent. Connected stations (5/5): [ASTRO-01, ASTRO-02, ASTRO-03, ASTRO-04, ASTRO-05]
[01:18:08] [Handler-ASTRO-03    ] <- raw NDJSON from ASTRO-03: {"messageType":"OBSERVATION_ALERT","messageId":"MSG-ASTRO-03-0004","senderId":"ASTRO-03","timestamp":"2026-11-13T19:48:08Z","payload":{"eventId":"ASTRO-03-LOCAL-0001",...
[01:18:08] [Handler-ASTRO-03    ] OBSERVATION_ALERT received from ASTRO-03 (station event ASTRO-03-LOCAL-0001)
[01:18:08] [Handler-ASTRO-03    ]   Assigned event ID : EVT-20261113-0003
[01:18:08] [Handler-ASTRO-03    ]   Event             : EVT-20261113-0003 NOVA_CANDIDATE at RA=117.5532 deg, Dec=+1.7992 deg | brightness 9.528 (baseline 1.268, x7.51) | confidence 0.87
[01:18:08] [Handler-ASTRO-03    ]   Independent verifiers: [ASTRO-01, ASTRO-02, ASTRO-04, ASTRO-05]
[01:18:08] [Handler-ASTRO-03    ] Verification of EVT-20261113-0003 started: waiting for 4 station(s), timeout 15 s.
[01:18:08] [Handler-ASTRO-03    ] FOLLOW_UP_REQUEST EVT-20261113-0003 -> ASTRO-01 (observe RA=117.5532, Dec=+1.7992)
   ... (ASTRO-02, ASTRO-04, ASTRO-05)
[01:18:10] [Handler-ASTRO-02    ] VERIFICATION_RESPONSE EVT-20261113-0003: ASTRO-02 -> CONFIRMED (measured 7.011 vs baseline 1.388, confidence 0.72) x5.05 of baseline seen from India
[01:18:10] [Handler-ASTRO-01    ] VERIFICATION_RESPONSE EVT-20261113-0003: ASTRO-01 -> UNAVAILABLE (Cloud cover over Sri Lanka)
[01:18:10] [Handler-ASTRO-01    ]   Progress EVT-20261113-0003: 2/4 answered | CONFIRMED=1 NOT_DETECTED=0 UNAVAILABLE=1 | waiting for [ASTRO-04, ASTRO-05]
   ...
[01:18:11] [Handler-ASTRO-05    ] FINAL RESULT for EVT-20261113-0003 (NOVA_CANDIDATE, reported by ASTRO-03)
[01:18:11] [Handler-ASTRO-05    ]   Decision : LIKELY_CONFIRMED
[01:18:11] [Handler-ASTRO-05    ]   Reason   : 3 independent stations confirmed the brightening. [All asked stations have answered]
[01:18:11] [Handler-ASTRO-05    ]   Counts   : CONFIRMED=3  NOT_DETECTED=0  UNAVAILABLE=1  NO_RESPONSE=0  (asked 4 stations)
[01:18:11] [Handler-ASTRO-05    ] FINAL_RESULT EVT-20261113-0003 broadcast to 5 connected station(s).
[01:18:11] [UDP-Server          ] UDP datagram from 192.168.8.121:57566 (152 bytes): HEARTBEAT #4 from ASTRO-01 [OPERATIONAL] UDP status datagram #4
[01:18:11] [UDP-Server          ]   UDP GAP: expected #3 from ASTRO-01 but got #4 -> 1 datagram(s) missing so far (lost or delayed).
[01:18:11] [UDP-Server          ]   UDP ORDERING: #3 from ASTRO-01 arrived AFTER #4 (out of order or duplicate) - UDP does not guarantee ordering.
[01:18:33] [Handler-ASTRO-05    ] ASTRO-05 sent DISCONNECT (Station operator exit).
[01:18:33] [Handler-ASTRO-05    ] DISCONNECTED ASTRO-05 (South Africa). Connected stations (4/5): [ASTRO-01, ASTRO-02, ASTRO-03, ASTRO-04]
```

**Station (ASTRO-01, asked to verify)**
```
[01:17:43] [main                ] TCP connection established. Local endpoint 192.168.8.121:52413  ->  server endpoint 192.168.8.110:5000
[01:17:43] [main                ] Receiver thread created, state = NEW
[01:17:43] [main                ] Receiver thread started, state = RUNNABLE
[01:17:43] [Receiver-ASTRO-01   ] REGISTER_ACK received: Welcome ASTRO-01 (Sri Lanka). You are connected to the AstroNet central server.
[01:18:08] [Receiver-ASTRO-01   ] >>> FOLLOW_UP_REQUEST received for EVT-20261113-0003 (reported by ASTRO-03)
[01:18:08] [Receiver-ASTRO-01   ]     Target: RA=117.5532 deg, Dec=+1.7992 deg, type NOVA_CANDIDATE
[01:18:08] [Verifier-2          ] Pointing telescope at RA=117.5532, Dec=+1.7992 for EVT-20261113-0003 ...
[01:18:10] [Verifier-2          ] <<< VERIFICATION_RESPONSE sent for EVT-20261113-0003: UNAVAILABLE - Cloud cover over Sri Lanka
[01:18:11] [Receiver-ASTRO-01   ] FINAL_RESULT EVT-20261113-0003 (NOVA_CANDIDATE) from ASTRO-03  [this station was asked to VERIFY]
[01:18:11] [Receiver-ASTRO-01   ]   DECISION: LIKELY_CONFIRMED
[01:18:15] [main                ] Simulation thread created, state = NEW
[01:18:15] [main                ] Simulation thread started, state = RUNNABLE
[01:18:20] [Simulator-ASTRO-01  ] obs #1 RA=107.7207 Dec=-1.2141 brightness=2.400 baseline=1.934 (x1.24) -> normal
[01:18:30] [main                ]   Simulator-ASTRO-01: TIMED_WAITING   (sleeping between observations)
[01:18:30] [main                ] Simulation thread stopped, state = TERMINATED
```

## 9. Protocol summary

One JSON object per line, UTF-8, `\n`-terminated and flushed immediately:
```json
{"messageType":"OBSERVATION_ALERT","messageId":"MSG-ASTRO-01-0002","senderId":"ASTRO-01","timestamp":"2026-11-13T10:30:00Z","payload":{...}}
```

| Type | Direction | Payload |
|---|---|---|
| REGISTER | station → server | `StationInfo` (must be the first message, within 30 s) |
| REGISTER_ACK | server → station | `accepted`, `message`, `connectedStations` |
| OBSERVATION_ALERT | station → server | `AstronomicalEvent` (RA/Dec in decimal degrees) |
| FOLLOW_UP_REQUEST | server → other stations | `AstronomicalEvent` with the server's event ID |
| VERIFICATION_RESPONSE | station → server | `VerificationResult` (CONFIRMED / NOT_DETECTED / UNAVAILABLE) |
| FINAL_RESULT | server → all stations | `VerificationResult.FinalReport` |
| HEARTBEAT | station → server | `status` (every 30 s) |
| ERROR | server → station | `reason` |
| DISCONNECT | both | `reason` |

Decision rules: ≥ 2 CONFIRMED → **LIKELY_CONFIRMED**. All asked stations answered, 0 CONFIRMED and ≥ 2 NOT_DETECTED → **NOT_CONFIRMED**. Anything else (including the 15 s timeout) → **INCONCLUSIVE**. UNAVAILABLE never counts as a negative, and the reporting station never counts as a verifier.

## 10. Troubleshooting

| Symptom | Cause / fix |
|---|---|
| `Connection timed out` | Wrong IP, different network, Wi-Fi client isolation, or a firewall is blocking TCP 5000 on the server |
| `Connection refused` | The server is not running, or it uses another port |
| `Station ID ASTRO-0X is already connected` | Each computer must choose a different ID |
| `cannot bind TCP port 5000` | Another program (or a second server) uses the port. Start with `run-server.bat 6000 6001` and use port 6000 on the stations |
| UDP test: `0 replies` | UDP 5001 is not allowed in the server firewall (TCP still works) |
| `Error: Could not find or load main class` | Run `compile` first, and run the command from inside the project folder |
| `NoClassDefFoundError: com/google/gson/...` | Gson is missing from the classpath. Check the `;` (Windows) or `:` (Linux) separator and the quotes |
| `UnsupportedClassVersionError` | That computer has a JDK older than 17 |
