package com.homerelay.app.qr;

import com.homerelay.app.Test;
import com.homerelay.app.TestRunner;

public class QrCodeGeneratorTest {

    @Test
    public void sizeMatchesVersion() {
        QrCodeGenerator qr = QrCodeGenerator.encodeText("HELLO WORLD", QrCodeGenerator.Ecc.MEDIUM);
        TestRunner.assertEquals(21, qr.getSize(), "v1 size");
        TestRunner.assertEquals(1, qr.getVersion(), "v1 selected");
    }

    @Test
    public void finderPatternsPresent() {
        QrCodeGenerator qr = QrCodeGenerator.encodeText("FINDER TEST PAYLOAD 123", QrCodeGenerator.Ecc.MEDIUM);
        int s = qr.getSize();
        // Centers of the three finders are dark.
        TestRunner.assertTrue(qr.getModule(3, 3), "top-left finder center dark");
        TestRunner.assertTrue(qr.getModule(s - 4, 3), "top-right finder center dark");
        TestRunner.assertTrue(qr.getModule(3, s - 4), "bottom-left finder center dark");
        // Corners of finder ring are dark, inner light ring light.
        TestRunner.assertTrue(qr.getModule(0, 0) && qr.getModule(6, 0) && qr.getModule(0, 6) && qr.getModule(6, 6),
                "finder outer corners dark");
        TestRunner.assertFalse(qr.getModule(1, 1) && qr.getModule(5, 1) && qr.getModule(1, 5) && qr.getModule(5, 5),
                "finder inner ring light");
    }

    @Test
    public void darkModulePresent() {
        QrCodeGenerator qr = QrCodeGenerator.encodeText("dark module check", QrCodeGenerator.Ecc.MEDIUM);
        TestRunner.assertTrue(qr.getModule(8, qr.getSize() - 8), "dark module at (8, size-8)");
    }

    @Test
    public void timingPatternAlternates() {
        QrCodeGenerator qr = QrCodeGenerator.encodeText("timing pattern", QrCodeGenerator.Ecc.MEDIUM);
        int s = qr.getSize();
        // Timing bits between finders: position 8..size-9 along row/col 6 alternate.
        for (int i = 8; i < s - 8; i++) {
            boolean expected = i % 2 == 0;
            TestRunner.assertEquals(expected ? 1 : 0, qr.getModule(i, 6) ? 1 : 0, "timing col6 at " + i);
            TestRunner.assertEquals(expected ? 1 : 0, qr.getModule(6, i) ? 1 : 0, "timing row6 at " + i);
        }
    }

    @Test
    public void versionIncreasesWithPayload() {
        QrCodeGenerator small = QrCodeGenerator.encodeText("tiny", QrCodeGenerator.Ecc.MEDIUM);
        StringBuilder bigText = new StringBuilder();
        for (int i = 0; i < 120; i++) bigText.append((char) ('a' + (i % 26)));
        QrCodeGenerator big = QrCodeGenerator.encodeText(bigText.toString(), QrCodeGenerator.Ecc.MEDIUM);
        TestRunner.assertTrue(big.getVersion() > small.getVersion(), "bigger payload -> bigger version");
    }

    @Test
    public void maskIsInRange() {
        QrCodeGenerator qr = QrCodeGenerator.encodeText("mask range", QrCodeGenerator.Ecc.MEDIUM);
        TestRunner.assertTrue(qr.getMask() >= 0 && qr.getMask() <= 7, "mask 0..7");
    }

    @Test
    public void deterministicOutput() {
        QrCodeGenerator a = QrCodeGenerator.encodeText("same input", QrCodeGenerator.Ecc.MEDIUM);
        QrCodeGenerator b = QrCodeGenerator.encodeText("same input", QrCodeGenerator.Ecc.MEDIUM);
        TestRunner.assertEquals(a.getVersion(), b.getVersion(), "same version");
        TestRunner.assertEquals(a.getMask(), b.getMask(), "same mask");
        for (int y = 0; y < a.getSize(); y++) {
            for (int x = 0; x < a.getSize(); x++) {
                TestRunner.assertEquals(a.getModule(x, y) ? 1 : 0, b.getModule(x, y) ? 1 : 0,
                        "same module at " + x + "," + y);
            }
        }
    }

    @Test
    public void reedSolomonRoundTrip() {
        // Divisor/remainder relationship: remainder(data . ec) == 0 for a single block.
        for (int degree = 7; degree <= 30; degree++) {
            byte[] divisor = QrCodeGenerator.reedSolomonComputeDivisor(degree);
            byte[] message = new byte[37];
            for (int i = 0; i < message.length; i++) message[i] = (byte) (i * 31 + 7);
            byte[] ec = QrCodeGenerator.reedSolomonComputeRemainder(message, divisor);
            byte[] joined = new byte[message.length + ec.length];
            System.arraycopy(message, 0, joined, 0, message.length);
            System.arraycopy(ec, 0, joined, message.length, ec.length);
            byte[] rem = QrCodeGenerator.reedSolomonComputeRemainder(joined, divisor);
            boolean allZero = true;
            for (byte b : rem) if (b != 0) { allZero = false; break; }
            TestRunner.assertTrue(allZero, "remainder must vanish for degree " + degree);
        }
    }

    @Test
    public void everyBlockEccIsDivisibleByGenerator() {
        // For every version 1..10 (ECC Medium): each block's (data||ecc) polynomial
        // must be divisible by its RS generator polynomial.
        for (int version = 1; version <= 10; version++) {
            QrCodeGenerator.Ecc ecl = QrCodeGenerator.Ecc.MEDIUM;
            int dataCodewords = QrCodeGenerator.getNumDataCodewords(version, ecl);
            int ecLen = QrCodeGenerator.ECC_CODEWORDS_PER_BLOCK[ecl.ordinal()][version] & 0xFF;
            int blocks = QrCodeGenerator.NUM_ERROR_CORRECTION_BLOCKS[ecl.ordinal()][version] & 0xFF;

            int size = version * 4 + 17;
            int raw = size * size - 8 * 8 * 3 - (15 * 2 + 1) - (size - 16) * 2;
            if (version >= 2) {
                int numAlign = version / 7 + 2;
                raw -= (numAlign - 1) * (numAlign - 1) * 25;
                raw -= (numAlign - 2) * 2 * 20;
                if (version >= 7) raw -= 6 * 3 * 2;
            }
            raw = raw / 8;
            int numShortBlocks = blocks - raw % blocks;
            int shortBlockLen = raw / blocks;

            byte[] data = new byte[dataCodewords];
            for (int i = 0; i < data.length; i++) data[i] = (byte) (i * 37 + 11);
            int k = 0;
            byte[] divisor = QrCodeGenerator.reedSolomonComputeDivisor(ecLen);
            for (int b = 0; b < blocks; b++) {
                int datLen = shortBlockLen - ecLen + (b < numShortBlocks ? 0 : 1);
                byte[] dat = new byte[datLen];
                System.arraycopy(data, k, dat, 0, datLen);
                k += datLen;
                byte[] ec = QrCodeGenerator.reedSolomonComputeRemainder(dat, divisor);
                byte[] joined = new byte[dat.length + ec.length];
                System.arraycopy(dat, 0, joined, 0, dat.length);
                System.arraycopy(ec, 0, joined, dat.length, ec.length);
                byte[] rem = QrCodeGenerator.reedSolomonComputeRemainder(joined, divisor);
                for (byte v : rem) {
                    TestRunner.assertEquals(0, v & 0xFF, "block " + b + " of version " + version);
                }
            }
        }
    }

    @Test
    public void dataTooLongThrows() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 5000; i++) sb.append('x');
        try {
            QrCodeGenerator.encodeText(sb.toString(), QrCodeGenerator.Ecc.MEDIUM);
            TestRunner.assertTrue(false, "expected exception");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }
}
