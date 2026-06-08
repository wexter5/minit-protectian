package ru.metaculture;

import java.util.HashMap;
import java.util.Map;

public class NodeCache<T> {

    private final String pointerPattern;
    private final Map<T, Integer> cache;

    public NodeCache(String pointerPattern) {
        this.pointerPattern = pointerPattern;
        cache = new HashMap<>();
    }

    public String getPointer(T key) {
        return String.format(pointerPattern, getId(key));
    }

    public String getInitializationCheck(T key) {
        return normalizeLvalue(getPointer(key));
    }

    public String getStoreExpression(T key, String valueExpression) {
        return getStoreExpression(getId(key), valueExpression);
    }

    public String getStoreExpression(int id, String valueExpression) {
        return normalizeLvalue(String.format(pointerPattern, id));
    }

    public int getId(T key) {
        if(!cache.containsKey(key)) {
            cache.put(key, cache.size());
        }
        return cache.get(key);
    }

    public int size() {
        return cache.size();
    }

    public boolean isEmpty() {
        return cache.isEmpty();
    }

    public Map<T, Integer> getCache() {
        return cache;
    }

    public void clear() {
        cache.clear();
    }

    private static String normalizeLvalue(String value) {
        if (value != null && value.length() > 1 && value.startsWith("(") && value.endsWith(")")) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }
}

