package common;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;

/**
 * Small helper around Gson for JSON serialization / deserialization.
 *
 * Gson's default (non-pretty) output is a single line: any newline inside a string
 * value is escaped as "\n". That is what makes newline-delimited JSON (NDJSON) work:
 * the receiver simply calls BufferedReader.readLine() to get exactly one message.
 *
 * NOTE: this file is identical in AstroNet-Server and AstroNet-Station.
 */
public final class JsonUtil {

    /** Gson instances are thread-safe, so one shared instance is used by all threads. */
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private JsonUtil() {
        // utility class - no instances
    }

    /** Object -> compact JSON text (one line). */
    public static String toJson(Object object) {
        return GSON.toJson(object);
    }

    /** JSON text -> object. Returns null if the text is not valid JSON for that class. */
    public static <T> T fromJson(String json, Class<T> type) {
        try {
            return GSON.fromJson(json, type);
        } catch (JsonParseException | IllegalStateException e) {
            return null;
        }
    }

    /** Object -> JsonObject (used for Message payloads). */
    public static JsonObject toJsonObject(Object object) {
        if (object == null) {
            return new JsonObject();
        }
        if (object instanceof JsonObject) {
            return (JsonObject) object;
        }
        JsonElement tree = GSON.toJsonTree(object);
        if (!tree.isJsonObject()) {
            throw new IllegalArgumentException("Payload must be a JSON object: " + object.getClass().getSimpleName());
        }
        return tree.getAsJsonObject();
    }

    /** JsonElement -> object. Returns null if the element does not match the class. */
    public static <T> T fromJsonElement(JsonElement element, Class<T> type) {
        try {
            return GSON.fromJson(element, type);
        } catch (JsonParseException | IllegalStateException e) {
            return null;
        }
    }

    /** Message -> one NDJSON line (without the trailing '\n'; the sender adds it). */
    public static String toLine(Message message) {
        return GSON.toJson(message);
    }

    /**
     * One NDJSON line -> Message.
     * Returns null for blank lines, malformed JSON or unknown message types,
     * so a bad message never crashes a connection handler.
     */
    public static Message parseMessage(String line) {
        if (line == null || line.isBlank()) {
            return null;
        }
        try {
            Message message = GSON.fromJson(line, Message.class);
            if (message == null || message.getMessageType() == null) {
                return null;
            }
            return message;
        } catch (JsonParseException | IllegalStateException e) {
            return null;
        }
    }

    /** Shortens long text for console display. */
    public static String abbreviate(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength - 3) + "...";
    }
}
