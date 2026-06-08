package ru.metaculture.config;

import ru.metaculture.ProtectionConfig;

import java.net.URI;

public final class NativeConfig {

    private final int stringPoolLocalThreshold;
    private final int remoteStringPoolThreshold;
    private final URI stringServerUri;
    private final String apiKey;
    private final int networkTimeoutMillis;
    private final int networkRetryCount;

    private NativeConfig(Builder builder) {
        this.stringPoolLocalThreshold = builder.stringPoolLocalThreshold;
        this.remoteStringPoolThreshold = builder.remoteStringPoolThreshold;
        this.stringServerUri = builder.stringServerUri;
        this.apiKey = builder.apiKey;
        this.networkTimeoutMillis = builder.networkTimeoutMillis;
        this.networkRetryCount = builder.networkRetryCount;
    }

    public static NativeConfig defaultConfig() {
        return builder().build();
    }

    public static NativeConfig fromProtectionConfig(ProtectionConfig protectionConfig) {
        if (protectionConfig != null && protectionConfig.isStringObfuscationEnabled()) {
            return builder().setStringPoolLocalThreshold(Integer.MAX_VALUE).build();
        }
        return builder().setStringPoolLocalThreshold(0).build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public boolean preferRemoteStringPool(int entryCount) {
        return stringServerUri != null
                && remoteStringPoolThreshold > 0
                && entryCount >= remoteStringPoolThreshold;
    }

    public int getStringPoolLocalThreshold() {
        return stringPoolLocalThreshold;
    }

    public URI getStringServerUri() {
        return stringServerUri;
    }

    public String getApiKey() {
        return apiKey;
    }

    public int getNetworkTimeoutMillis() {
        return networkTimeoutMillis;
    }

    public int getNetworkRetryCount() {
        return networkRetryCount;
    }

    public static final class Builder {
        private int stringPoolLocalThreshold = 0;
        private int remoteStringPoolThreshold = Integer.MAX_VALUE;
        private URI stringServerUri;
        private String apiKey = "";
        private int networkTimeoutMillis = 5_000;
        private int networkRetryCount = 1;

        public Builder setStringPoolLocalThreshold(int stringPoolLocalThreshold) {
            this.stringPoolLocalThreshold = Math.max(0, stringPoolLocalThreshold);
            return this;
        }

        public Builder setRemoteStringPoolThreshold(int remoteStringPoolThreshold) {
            this.remoteStringPoolThreshold = Math.max(0, remoteStringPoolThreshold);
            return this;
        }

        public Builder setStringServerUri(URI stringServerUri) {
            this.stringServerUri = stringServerUri;
            return this;
        }

        public Builder setApiKey(String apiKey) {
            this.apiKey = apiKey == null ? "" : apiKey;
            return this;
        }

        public Builder setNetworkTimeoutMillis(int networkTimeoutMillis) {
            this.networkTimeoutMillis = Math.max(0, networkTimeoutMillis);
            return this;
        }

        public Builder setNetworkRetryCount(int networkRetryCount) {
            this.networkRetryCount = Math.max(0, networkRetryCount);
            return this;
        }

        public NativeConfig build() {
            return new NativeConfig(this);
        }
    }
}
