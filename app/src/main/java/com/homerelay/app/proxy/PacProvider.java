package com.homerelay.app.proxy;

/** Generates a proxy auto-config (PAC) file for HomeRelay. Pure Java, no Android deps. */
public final class PacProvider {
    private PacProvider() {}

    public static String pacBody(String proxyHost, int proxyPort) {
        String host = proxyHost == null ? "PROXY" : proxyHost;
        return "// HomeRelay proxy auto-config\r\n"
                + "// Generated on-device; no network calls leave the phone.\r\n"
                + "function FindProxyForURL(url, host) {\r\n"
                + "  if (isPlainHostName(host) || host === \"localhost\"\n"
                + "      || host === \"127.0.0.1\" || host === \"::1\") {\r\n"
                + "    return \"DIRECT\";\r\n"
                + "  }\r\n"
                + "  return \"PROXY " + host + ":" + proxyPort + "\";\r\n"
                + "}\r\n";
    }

    public static byte[] pacBytes(String proxyHost, int proxyPort) {
        return pacBody(proxyHost, proxyPort).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
}
