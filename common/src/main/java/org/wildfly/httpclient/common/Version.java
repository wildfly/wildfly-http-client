package org.wildfly.httpclient.common;

/*
 * Versioning enum for HttpHandler implementations.
 */
public enum Version {
    VERSION_1(1),
    VERSION_2(2)
    ;
    private final int version;

    Version(int version) {
        this.version = version;
    }

    public String getVersion() {
        return "v" + version;
    }

    public boolean since(Version version) {
        return this.version >= version.version;
    }
}

