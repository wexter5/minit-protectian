package ru.metaculture.source;

import ru.metaculture.Util;
import ru.metaculture.config.NativeConfig;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Класс для управления пулом строк в нативной защите.
 * Собирает все строки из Java-кода, индексирует их и подготавливает для вставки в Rust код.
 */
public class StringPool {

    private NativeConfig config;
    private final Map<String, Entry> entries;
    private byte[] lastBuiltBytes;

    public StringPool() {
        this(NativeConfig.defaultConfig());
    }

    public StringPool(boolean obfuscateStrings) {
        this(obfuscateStrings
                ? NativeConfig.builder().setStringPoolLocalThreshold(Integer.MAX_VALUE).build()
                : NativeConfig.builder().setStringPoolLocalThreshold(0).build());
    }

    public StringPool(NativeConfig config) {
        this.config = Objects.requireNonNull(config, "config");
        this.entries = new LinkedHashMap<>();
        this.lastBuiltBytes = new byte[0];
    }

    public void reset(boolean obfuscateStrings) {
        this.config = obfuscateStrings
                ? NativeConfig.builder().setStringPoolLocalThreshold(Integer.MAX_VALUE).build()
                : NativeConfig.builder().setStringPoolLocalThreshold(0).build();
        this.entries.clear();
        this.lastBuiltBytes = new byte[0];
    }

    /**
     * Получает вызов для разрешения константной строки в Rust.
     */
    public String get(String value) {
        Entry entry = entries.computeIfAbsent(value, key -> new Entry(entries.size(), key));
        return String.format(Locale.ROOT, "crate::native_jvm::string_pool::resolve(env, %d)", entry.id);
    }

    /**
     * Получает вызов для разрешения изменяемой строки.
     */
    public String getMutable(String value) {
        Entry entry = entries.computeIfAbsent(value, key -> new Entry(entries.size(), key));
        return String.format(Locale.ROOT, "crate::native_jvm::string_pool::resolve_mutable(env, %d)", entry.id);
    }

    public int size() {
        return entries.size();
    }

    public Collection<Entry> getEntries() {
        return Collections.unmodifiableCollection(entries.values());
    }

    public byte[] getEncryptedBytes() {
        return lastBuiltBytes.clone();
    }

    public String build() {
        try {
            return build(null);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to build string pool", e);
        }
    }

    /**
     * Основной метод сборки пула строк в зависимости от конфигурации (локальный или удаленный).
     */
    public String build(Path serverOutputDir) throws IOException {
        if (config.preferRemoteStringPool(entries.size())) {
            if (serverOutputDir != null) {
                Files.createDirectories(serverOutputDir);
                writeManifest(serverOutputDir.resolve("strings.json"));
            }
            lastBuiltBytes = buildManifestJson().getBytes(StandardCharsets.UTF_8);
            return buildRemoteTemplate();
        }
        return buildLocalTemplate();
    }

    /**
     * Формирует шаблон для удаленного получения строк через API.
     */
    private String buildRemoteTemplate() {
        StringBuilder idsBuilder = new StringBuilder();
        int index = 0;
        for (Entry entry : entries.values()) {
            if (index++ > 0) {
                idsBuilder.append(", ");
            }
            idsBuilder.append(entry.id);
        }
        String serverUrl = config.getStringServerUri() == null ? "" : config.getStringServerUri().toString();
        String apiKey = config.getApiKey();
        if (apiKey == null) {
            apiKey = "";
        }
        int timeout = Math.max(0, config.getNetworkTimeoutMillis());
        int retries = Math.max(0, config.getNetworkRetryCount());

        String template = Util.readResource("sources/string_pool_remote.rs");
        return Util.dynamicFormat(template, Util.createMap(
                "server_url", escapeForRustString(serverUrl),
                "api_key", escapeForRustString(apiKey),
                "has_api_key", apiKey.isEmpty() ? "false" : "true",
                "timeout", Integer.toString(timeout),
                "retries", Integer.toString(retries),
                "known_count", Integer.toString(entries.size()),
                "ids", idsBuilder.toString()
        ));
    }

    /**
     * Формирует локальный массив байт (статический пул строк) внутри Rust файла.
     */
    private String buildLocalTemplate() {
        List<Entry> serializedEntries = new ArrayList<>(entries.values());
        List<Byte> bytes = new ArrayList<>();
        List<String> tableRows = new ArrayList<>();

        boolean useOxorany = config.getStringPoolLocalThreshold() != 0
                && entries.size() <= config.getStringPoolLocalThreshold();

        long offset = 0;
        for (Entry entry : serializedEntries) {
            byte[] data = getModifiedUtf8Bytes(entry.value);
            for (byte b : data) {
                bytes.add(b);
            }
            bytes.add((byte) 0);

            tableRows.add(String.format(Locale.ROOT,
                    "    (%d, %d, %d)",
                    entry.id,
                    offset,
                    data.length + 1));
            offset += data.length + 1;
        }

        if (bytes.isEmpty()) {
            bytes.add((byte) 0);
        }
        if (tableRows.isEmpty()) {
            tableRows.add("    (0, 0, 0)");
        }

        byte[] rawBytes = new byte[bytes.size()];
        for (int i = 0; i < bytes.size(); i++) {
            rawBytes[i] = bytes.get(i);
        }
        lastBuiltBytes = rawBytes;

        StringBuilder poolBuilder = new StringBuilder();
        poolBuilder.append("[ ");
        int position = 0;
        for (byte b : bytes) {
            if (useOxorany && (position++ % 7 == 3)) {
                poolBuilder.append("oxorany!(").append(b).append(")");
            } else {
                poolBuilder.append(b);
            }
            poolBuilder.append(", ");
        }
        poolBuilder.append(" ]");

        String template = Util.readResource("sources/string_pool_local.rs");
        return Util.dynamicFormat(template, Util.createMap(
                "size", Integer.toString(Math.max(1, bytes.size())),
                "value", poolBuilder.toString(),
                "table", String.join(",\n", tableRows)
        ));
    }

    private void writeManifest(Path manifestPath) throws IOException {
        Files.writeString(manifestPath, buildManifestJson(), StandardCharsets.UTF_8);
    }

    /**
     * Генерирует JSON-манифест всех строк для отладки или внешних инструментов.
     */
    private String buildManifestJson() {
        StringBuilder builder = new StringBuilder();
        builder.append("{\n  \"strings\": [\n");
        int index = 0;
        for (Entry entry : entries.values()) {
            if (index++ > 0) {
                builder.append(",\n");
            }
            builder.append("    {\"id\": ")
                    .append(entry.id)
                    .append(", \"value\": \"")
                    .append(escapeJson(entry.value))
                    .append("\"}");
        }
        builder.append("\n  ]\n}\n");
        return builder.toString();
    }

    /**
     * Кодирует строку в Modified UTF-8.
     */
    private static byte[] getModifiedUtf8Bytes(String str) {
        int strlen = str.length();
        int utflen = 0;
        int c;

        for (int i = 0; i < strlen; i++) {
            c = str.charAt(i);
            if ((c >= 0x0001) && (c <= 0x007F)) {
                utflen++;
            } else if (c > 0x07FF) {
                utflen += 3;
            } else {
                utflen += 2;
            }
        }

        if (utflen > 65535) {
            throw new IllegalArgumentException("encoded string too long: " + utflen + " bytes");
        }

        byte[] bytearr = new byte[utflen];
        int count = 0;
        int i;
        for (i = 0; i < strlen; i++) {
            c = str.charAt(i);
            if (!((c >= 0x0001) && (c <= 0x007F))) {
                break;
            }
            bytearr[count++] = (byte) c;
        }

        for (; i < strlen; i++) {
            c = str.charAt(i);
            if ((c >= 0x0001) && (c <= 0x007F)) {
                bytearr[count++] = (byte) c;
            } else if (c > 0x07FF) {
                bytearr[count++] = (byte) (0xE0 | ((c >> 12) & 0x0F));
                bytearr[count++] = (byte) (0x80 | ((c >> 6) & 0x3F));
                bytearr[count++] = (byte) (0x80 | (c & 0x3F));
            } else {
                bytearr[count++] = (byte) (0xC0 | ((c >> 6) & 0x1F));
                bytearr[count++] = (byte) (0x80 | (c & 0x3F));
            }
        }

        return bytearr;
    }

    /**
     * Экранирование спецсимволов для JSON.
     */
    public static String escapeJson(String value) {
        StringBuilder sb = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '"':
                case '\\':
                    sb.append('\\').append(ch);
                    break;
                case '\b':
                    sb.append("\\b");
                    break;
                case '\f':
                    sb.append("\\f");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (ch < 0x20 || ch > 0x7E) {
                        sb.append(String.format(Locale.ROOT, "\\u%04X", (int) ch));
                    } else {
                        sb.append(ch);
                    }
            }
        }
        return sb.toString();
    }

    /**
     * Экранирование строки для безопасной вставки в Rust код.
     */
    private static String escapeForRustString(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        StringBuilder builder = new StringBuilder(bytes.length * 4);
        for (byte b : bytes) {
            int unsigned = b & 0xFF;
            switch (unsigned) {
                case '\\':
                    builder.append("\\\\");
                    break;
                case '"':
                    builder.append("\\\"");
                    break;
                case '\n':
                    builder.append("\\n");
                    break;
                case '\r':
                    builder.append("\\r");
                    break;
                case '\t':
                    builder.append("\\t");
                    break;
                default:
                    if (unsigned >= 0x20 && unsigned <= 0x7E) {
                        builder.append((char) unsigned);
                    } else {
                        builder.append(String.format(Locale.ROOT, "\\x%02X", unsigned));
                    }
            }
        }
        return builder.toString();
    }

    public static final class Entry {
        private final int id;
        private final String value;

        private Entry(int id, String value) {
            this.id = id;
            this.value = value;
        }

        public int getId() {
            return id;
        }

        public String getValue() {
            return value;
        }
    }
}
