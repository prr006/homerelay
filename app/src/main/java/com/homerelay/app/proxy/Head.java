package com.homerelay.app.proxy;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal, dependency-free representation of an HTTP head (start line + headers).
 *
 * <p>Supports the bits of RFC 7230 a forwarding proxy needs:
 * obs-fold continuation lines, case-insensitive lookup, multi-valued headers.
 * Anything else (header value validation beyond token legality) is left to the
 * peer — the proxy never reflects header values into HTML/JS contexts.
 *
 * <p>This class is deliberately free of android.* imports so it can be unit
 * tested on a plain JVM.
 */
public final class Head {
    private String startLine;
    /** Insertion-ordered, original-case header names, each with its values. */
    private final LinkedHashMap<String, List<String>> headers = new LinkedHashMap<>();

    private Head(String startLine) {
        this.startLine = startLine;
    }

    public static Head of(String startLine) {
        return new Head(startLine);
    }

    public String startLine() { return startLine; }
    public void setStartLine(String line) { this.startLine = line; }

    /** Parse a header block that begins with the start line. */
    public static Head parse(String block) {
        if (block == null) throw new IllegalArgumentException("null head block");
        String normalized = block.replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = normalized.split("\n", -1);
        if (lines.length == 0 || lines[0].trim().isEmpty()) {
            throw new IllegalArgumentException("missing request/status line");
        }
        Head head = new Head(lines[0].trim());
        String currentName = null;
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if (line.isEmpty()) break; // end of head
            if (line.charAt(0) == ' ' || line.charAt(0) == '\t') {
                // obs-fold: continuation of previous header value
                if (currentName != null) {
                    List<String> vals = head.headers.get(currentName);
                    int last = vals.size() - 1;
                    vals.set(last, vals.get(last) + " " + line.trim());
                }
                continue;
            }
            int sep = line.indexOf(':');
            if (sep <= 0) {
                // Unparseable header line: skip it instead of failing the whole request.
                currentName = null;
                continue;
            }
            String name = line.substring(0, sep).trim();
            if (!isToken(name)) { currentName = null; continue; }
            String value = line.substring(sep + 1).trim();
            List<String> existing = null;
            for (Map.Entry<String, List<String>> e : head.headers.entrySet()) {
                if (e.getKey().equalsIgnoreCase(name)) { existing = e.getValue(); break; }
            }
            if (existing == null) {
                head.headers.put(name, new ArrayList<>(Collections.singletonList(value)));
            } else {
                existing.add(value);
            }
            currentName = findKey(head.headers, name);
        }
        return head;
    }

    private static String findKey(Map<String, List<String>> map, String name) {
        for (String k : map.keySet()) if (k.equalsIgnoreCase(name)) return k;
        return name;
    }

    private static boolean isToken(String s) {
        if (s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c <= 32 || c == 127) return false;
            switch (c) {
                case '(': case ')': case '<': case '>': case '@': case ',': case ';':
                case ':': case '\\': case '"': case '/': case '[': case ']': case '?':
                case '=': case '{': case '}':
                    return false;
                default:
            }
        }
        return true;
    }

    /** First value of the named header, or null. */
    public String get(String name) {
        List<String> v = getAll(name);
        return v.isEmpty() ? null : v.get(0);
    }

    public List<String> getAll(String name) {
        for (Map.Entry<String, List<String>> e : headers.entrySet()) {
            if (e.getKey().equalsIgnoreCase(name)) return e.getValue();
        }
        return Collections.emptyList();
    }

    public boolean has(String name) { return get(name) != null; }

    /** Remove every value of the named header. */
    public void remove(String name) {
        String key = findKey(headers, name);
        headers.remove(key);
    }

    /** Replace any existing values with a single value. */
    public void set(String name, String value) {
        remove(name);
        add(name, value);
    }

    /** Add a value without removing existing ones. */
    public void add(String name, String value) {
        String key = findKey(headers, name);
        List<String> list = headers.get(key);
        if (list == null) list = new ArrayList<>();
        list.add(value);
        headers.put(key, list);
    }

    /** Rename-and-set helper keeping header order stable-ish. */
    public void setAll(String name, List<String> values) {
        remove(name);
        for (String v : values) add(name, v);
    }

    public Map<String, List<String>> asMap() {
        return Collections.unmodifiableMap(headers);
    }

    /** Headers that must not be forwarded by an intermediary (RFC 7230 §6.1). */
    public void stripHopByHop() {
        // Connection header may nominate additional headers to strip.
        List<String> connectionValues = getAll("Connection");
        List<String> nominated = new ArrayList<>();
        for (String v : connectionValues) {
            for (String token : v.split(",")) {
                String t = token.trim();
                if (!t.isEmpty()) nominated.add(t);
            }
        }
        for (String n : nominated) remove(n);
        remove("Connection");
        remove("Keep-Alive");
        remove("Proxy-Connection");
        remove("Proxy-Authenticate");
        remove("Proxy-Authorization");
        remove("TE");
        remove("Trailer");
        remove("Transfer-Encoding");
        remove("Upgrade");
        remove("Expect");
    }

    /** Serialize start line + headers + terminating CRLFCRLF. */
    public byte[] toBytes() {
        StringBuilder sb = new StringBuilder(startLine.length() + 256);
        sb.append(startLine).append("\r\n");
        for (Map.Entry<String, List<String>> e : headers.entrySet()) {
            for (String v : e.getValue()) {
                sb.append(e.getKey()).append(": ").append(v).append("\r\n");
            }
        }
        sb.append("\r\n");
        return sb.toString().getBytes(StandardCharsets.ISO_8859_1);
    }
}
