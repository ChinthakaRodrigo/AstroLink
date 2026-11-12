package common;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The common envelope used for EVERY TCP message in AstroNet.
 *
 * On the network each Message is serialized by Gson into ONE line of JSON
 * (newline-delimited JSON, "NDJSON"), for example:
 *
 * {"messageType":"OBSERVATION_ALERT","messageId":"MSG-ASTRO-01-0001","senderId":"ASTRO-01",
 *  "timestamp":"2026-11-13T10:30:00Z","payload":{...}}
 *
 * The payload is kept as a generic JsonObject so that one envelope class can carry
 * different payload classes (StationInfo, AstronomicalEvent, VerificationResult, ...).
 *
 * NOTE: this file is identical in AstroNet-Server and AstroNet-Station.
 */
public class Message {

    /** Thread-safe counter used to build unique message IDs inside one process. */
    private static final AtomicLong SEQUENCE = new AtomicLong();

    private MessageType messageType;
    private String messageId;
    private String senderId;
    private String timestamp;
    private JsonObject payload;

    /** No-argument constructor required by Gson for deserialization. */
    public Message() {
    }

    public Message(MessageType messageType, String senderId, JsonObject payload) {
        this.messageType = messageType;
        this.messageId = String.format("MSG-%s-%04d", senderId, SEQUENCE.incrementAndGet());
        this.senderId = senderId;
        this.timestamp = utcNow();
        this.payload = (payload == null) ? new JsonObject() : payload;
    }

    /** Creates a message whose payload is any Java object (converted to JSON by Gson). */
    public static Message create(MessageType type, String senderId, Object payloadObject) {
        return new Message(type, senderId, JsonUtil.toJsonObject(payloadObject));
    }

    /** Creates a message whose payload contains a single text field, e.g. {"reason": "..."}. */
    public static Message createText(MessageType type, String senderId, String key, String value) {
        JsonObject payload = new JsonObject();
        payload.addProperty(key, value);
        return new Message(type, senderId, payload);
    }

    /** Current UTC time in ISO-8601 format, e.g. 2026-11-13T10:30:00Z. */
    public static String utcNow() {
        return Instant.now().truncatedTo(ChronoUnit.SECONDS).toString();
    }

    /** Converts the JSON payload back into a Java object. Returns null if the payload does not match. */
    public <T> T getPayloadAs(Class<T> type) {
        if (payload == null) {
            return null;
        }
        return JsonUtil.fromJsonElement(payload, type);
    }

    /** Reads one field of the payload as text, or returns null if it does not exist. */
    public String getPayloadString(String key) {
        if (payload == null || !payload.has(key)) {
            return null;
        }
        JsonElement value = payload.get(key);
        if (value.isJsonNull()) {
            return null;
        }
        return value.isJsonPrimitive() ? value.getAsString() : value.toString();
    }

    public MessageType getMessageType() {
        return messageType;
    }

    public String getMessageId() {
        return messageId;
    }

    public String getSenderId() {
        return senderId;
    }

    public String getTimestamp() {
        return timestamp;
    }

    public JsonObject getPayload() {
        return payload;
    }

    @Override
    public String toString() {
        return messageType + " (" + messageId + ") from " + senderId;
    }
}
