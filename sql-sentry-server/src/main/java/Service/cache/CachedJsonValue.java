package Service.cache;

import com.fasterxml.jackson.databind.node.ObjectNode;

public class CachedJsonValue {

    private final ObjectNode payload;
    private final String source;

    public CachedJsonValue(ObjectNode payload, String source) {
        this.payload = payload;
        this.source = source;
    }

    public ObjectNode getPayload() {
        return payload;
    }

    public String getSource() {
        return source;
    }
}
