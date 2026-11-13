package com.homerelay.app.util;

import com.homerelay.app.Test;
import com.homerelay.app.TestRunner;

public class FormatUtilsTest {

    @Test
    public void bytesFormatting() {
        TestRunner.assertEquals("0 B", FormatUtils.bytes(0), "zero");
        TestRunner.assertEquals("512 B", FormatUtils.bytes(512), "bytes");
        TestRunner.assertEquals("1.0 KB", FormatUtils.bytes(1024), "kb");
        TestRunner.assertEquals("1.5 MB", FormatUtils.bytes(1572864), "mb");
        TestRunner.assertEquals("2.0 GB", FormatUtils.bytes(2147483648L), "gb");
        TestRunner.assertEquals("0 B", FormatUtils.bytes(-5), "negative clamps");
    }

    @Test
    public void uptimeFormatting() {
        TestRunner.assertEquals("38s", FormatUtils.uptime(38000), "seconds");
        TestRunner.assertEquals("2m 5s", FormatUtils.uptime(125000), "minutes");
        TestRunner.assertEquals("1h 1m", FormatUtils.uptime(3660000), "hours");
        TestRunner.assertEquals("1d 1h", FormatUtils.uptime(90000000), "days");
        TestRunner.assertEquals("0s", FormatUtils.uptime(-1), "negative clamps");
    }
}
