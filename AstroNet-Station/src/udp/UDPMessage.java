package udp;

import common.JsonUtil;
import common.Message;

import java.nio.charset.StandardCharsets;

/**
 * A small JSON message carried inside ONE UDP datagram (optional UDP demo).
 *
 * UDP is only used for non-critical status/heartbeat information. Alerts and
 * verification responses always use the reliable TCP connection.
 *
 * The sequenceNumber lets the receiver notice that datagrams were lost or arrived
 * out of order - something UDP itself never reports.
 *
 * NOTE: this file is identical in AstroNet-Server and AstroNet-Station.
 */
public class UDPMessage {

    private String type;          // HEARTBEAT, STATUS or ACK
    private String stationId;
    private long sequenceNumber;
    private String timestamp;
    private String status;
    private String text;

    /** No-argument constructor required by Gson. */
    public UDPMessage() {
    }

    public UDPMessage(String type, String stationId, long sequenceNumber, String status, String text) {
        this.type = type;
        this.stationId = stationId;
        this.sequenceNumber = sequenceNumber;
        this.timestamp = Message.utcNow();
        this.status = status;
        this.text = text;
    }

    /** Serializes this message to UTF-8 JSON bytes for a DatagramPacket. */
    public byte[] toBytes() {
        return JsonUtil.toJson(this).getBytes(StandardCharsets.UTF_8);
    }

    /** Parses the received bytes of a DatagramPacket. Returns null if the data is not valid JSON. */
    public static UDPMessage fromBytes(byte[] data, int offset, int length) {
        String json = new String(data, offset, length, StandardCharsets.UTF_8);
        return JsonUtil.fromJson(json, UDPMessage.class);
    }

    public String getType() {
        return type;
    }

    public String getStationId() {
        return stationId;
    }

    public long getSequenceNumber() {
        return sequenceNumber;
    }

    public String getTimestamp() {
        return timestamp;
    }

    public String getStatus() {
        return status;
    }

    public String getText() {
        return text;
    }

    @Override
    public String toString() {
        return String.format("%s #%d from %s [%s] %s", type, sequenceNumber, stationId, status, text);
    }
}
