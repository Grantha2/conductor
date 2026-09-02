package conductor.agents;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.function.Consumer;

/**
 * Gson conveniences shared by the provider clients: a one-line object
 * builder for wire shapes, null-safe reads for responses that omit fields
 * freely (no usage on errors, no content on refusals), and the two schema
 * rewrites needed because providers disagree about {@code additionalProperties}.
 */
final class Json {

    static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private Json() {}

    /** {@code of("type","text","text",s)} -> {@code {"type":"text","text":s}}. Values: String, Number, Boolean, JsonElement, null. */
    static JsonObject of(Object... keyValues) {
        var o = new JsonObject();
        for (int i = 0; i < keyValues.length; i += 2) {
            String key = (String) keyValues[i];
            switch (keyValues[i + 1]) {
                case null            -> o.add(key, JsonNull.INSTANCE);
                case JsonElement e   -> o.add(key, e);
                case Number n        -> o.addProperty(key, n);
                case Boolean b       -> o.addProperty(key, b);
                case Object v        -> o.addProperty(key, v.toString());
            }
        }
        return o;
    }

    static JsonArray arrayOf(JsonElement... elements) {
        var a = new JsonArray();
        for (var e : elements) a.add(e);
        return a;
    }

    static String str(JsonObject o, String key) {
        var v = get(o, key);
        return v != null && v.isJsonPrimitive() ? v.getAsString() : "";
    }

    static int num(JsonObject o, String key) {
        var v = get(o, key);
        return v != null && v.isJsonPrimitive() && v.getAsJsonPrimitive().isNumber() ? v.getAsInt() : 0;
    }

    static JsonObject obj(JsonObject o, String key) {
        var v = get(o, key);
        return v != null && v.isJsonObject() ? v.getAsJsonObject() : new JsonObject();
    }

    static JsonArray arr(JsonObject o, String key) {
        var v = get(o, key);
        return v != null && v.isJsonArray() ? v.getAsJsonArray() : new JsonArray();
    }

    /** Parses a JSON object string; anything else (bad JSON, array, null) yields {}. */
    static JsonObject parseObject(String text) {
        try {
            var el = JsonParser.parseString(text == null ? "" : text);
            return el.isJsonObject() ? el.getAsJsonObject() : new JsonObject();
        } catch (RuntimeException e) {
            return new JsonObject();
        }
    }

    /** Copy of {@code schema} with {@code additionalProperties:false} on every object node that lacks it; Anthropic and OpenAI strict modes reject schemas without it. */
    static JsonObject closedObjects(JsonObject schema) {
        var copy = schema.deepCopy();
        walkObjects(copy, o -> {
            if ("object".equals(str(o, "type")) && !o.has("additionalProperties")) o.addProperty("additionalProperties", false);
        });
        return copy;
    }

    /** Copy of {@code schema} with every {@code additionalProperties} removed; Gemini's schema dialect rejects the keyword. */
    static JsonObject withoutAdditionalProperties(JsonObject schema) {
        var copy = schema.deepCopy();
        walkObjects(copy, o -> o.remove("additionalProperties"));
        return copy;
    }

    private static void walkObjects(JsonElement el, Consumer<JsonObject> visit) {
        if (el.isJsonObject()) {
            visit.accept(el.getAsJsonObject());
            for (var entry : el.getAsJsonObject().entrySet()) walkObjects(entry.getValue(), visit);
        } else if (el.isJsonArray()) {
            for (var item : el.getAsJsonArray()) walkObjects(item, visit);
        }
    }

    private static JsonElement get(JsonObject o, String key) {
        var v = o == null ? null : o.get(key);
        return v == null || v.isJsonNull() ? null : v;
    }
}
