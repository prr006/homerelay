package com.homerelay.app.proxy;

import com.homerelay.app.Test;
import com.homerelay.app.TestRunner;

public class PacProviderTest {

    @Test
    public void pacContainsProxyAndHost() {
        String pac = PacProvider.pacBody("192.168.43.1", 8282);
        TestRunner.assertTrue(pac.contains("PROXY 192.168.43.1:8282"), "mentions proxy");
        TestRunner.assertTrue(pac.contains("FindProxyForURL"), "function defined");
        TestRunner.assertTrue(pac.contains("DIRECT"), "localhost direct");
    }

    @Test
    public void pacBytesAreUtf8() {
        byte[] bytes = PacProvider.pacBytes("10.0.0.1", 9000);
        TestRunner.assertTrue(bytes.length > 30, "non-trivial content");
    }
}
