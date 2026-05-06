package Service.cache;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

public interface JsonCacheStore {

    CachedJsonValue getValue(String key);

    void putValue(String key, ObjectNode payload, long ttl, TimeUnit unit) throws IOException;

    JsonNode range(String key, int limit);

    void pushLeft(String key, ObjectNode payload, int maxSize) throws IOException;
}
