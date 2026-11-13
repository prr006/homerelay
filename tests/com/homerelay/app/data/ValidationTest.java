package com.homerelay.app.data;

import com.homerelay.app.Test;
import com.homerelay.app.TestRunner;

public class ValidationTest {

    @Test
    public void portRangeRules() {
        TestRunner.assertFalse(Validation.isValidProxyPort(80), "privileged rejected");
        TestRunner.assertFalse(Validation.isValidProxyPort(1023), "below min rejected");
        TestRunner.assertTrue(Validation.isValidProxyPort(1024), "min accepted");
        TestRunner.assertTrue(Validation.isValidProxyPort(8282), "default accepted");
        TestRunner.assertTrue(Validation.isValidProxyPort(65535), "max accepted");
        TestRunner.assertFalse(Validation.isValidProxyPort(65536), "above max rejected");
        TestRunner.assertFalse(Validation.isValidProxyPort(-1), "negative rejected");
        TestRunner.assertFalse(Validation.isValidProxyPort(0), "zero rejected");
    }

    @Test
    public void parseProxyPort() {
        TestRunner.assertEquals(8282, Validation.parseProxyPort("8282"), "parses");
        TestRunner.assertEquals(8282, Validation.parseProxyPort(" 8282 "), "trims");
        TestRunner.assertEquals(-1, Validation.parseProxyPort(""), "empty");
        TestRunner.assertEquals(-1, Validation.parseProxyPort("abc"), "letters");
        TestRunner.assertEquals(-1, Validation.parseProxyPort("80"), "privileged");
        TestRunner.assertEquals(-1, Validation.parseProxyPort("70000"), "too big");
        TestRunner.assertEquals(-1, Validation.parseProxyPort("8.2"), "decimal");
        TestRunner.assertEquals(-1, Validation.parseProxyPort(null), "null");
    }

    @Test
    public void ipv4Rules() {
        TestRunner.assertTrue(Validation.isValidIpv4("192.168.43.1"), "typical hotspot gateway");
        TestRunner.assertTrue(Validation.isValidIpv4("127.0.0.1"), "loopback");
        TestRunner.assertTrue(Validation.isValidIpv4("0.0.0.0"), "zero");
        TestRunner.assertTrue(Validation.isValidIpv4("255.255.255.255"), "max");
        TestRunner.assertFalse(Validation.isValidIpv4("256.1.1.1"), "octet too big");
        TestRunner.assertFalse(Validation.isValidIpv4("1.2.3"), "too few octets");
        TestRunner.assertFalse(Validation.isValidIpv4("1.2.3.4.5"), "too many octets");
        TestRunner.assertFalse(Validation.isValidIpv4("01.2.3.4"), "leading zero rejected");
        TestRunner.assertFalse(Validation.isValidIpv4("a.b.c.d"), "letters rejected");
        TestRunner.assertFalse(Validation.isValidIpv4(""), "empty rejected");
    }

    @Test
    public void safeServicePort() {
        TestRunner.assertTrue(Validation.isSafeServicePort(8282), "ok");
        TestRunner.assertFalse(Validation.isSafeServicePort(5353), "mdns blocked");
        TestRunner.assertFalse(Validation.isSafeServicePort(80), "privileged blocked");
    }
}
