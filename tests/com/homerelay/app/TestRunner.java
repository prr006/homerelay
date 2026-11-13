package com.homerelay.app;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Discovers @Test methods in the given classes and runs them.
 * Exit code 0 iff every test passes. No external dependencies.
 */
public final class TestRunner {
    private TestRunner() {}

    public static void main(String[] args) {
        if (args.length == 0) {
            System.err.println("usage: TestRunner <fqcn> [<fqcn> ...]");
            System.exit(2);
        }
        int passed = 0, failed = 0;
        List<String> failures = new ArrayList<>();
        for (String className : args) {
            Class<?> cls;
            try {
                cls = Class.forName(className);
            } catch (ClassNotFoundException e) {
                System.err.println("class not found: " + className);
                failed++;
                continue;
            }
            for (Method method : cls.getDeclaredMethods()) {
                if (method.getAnnotation(Test.class) == null) continue;
                if (!Modifier.isPublic(method.getModifiers())) continue;
                String testName = className + "#" + method.getName();
                try {
                    Object instance = cls.getDeclaredConstructor().newInstance();
                    method.invoke(instance);
                    passed++;
                    System.out.println("PASS " + testName);
                } catch (InvocationTargetException e) {
                    failed++;
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    failures.add(testName + " -> " + cause);
                    System.out.println("FAIL " + testName + " -> " + cause);
                    cause.printStackTrace(System.out);
                } catch (Throwable t) {
                    failed++;
                    failures.add(testName + " -> " + t);
                    System.out.println("FAIL " + testName + " -> " + t);
                }
            }
        }
        System.out.println();
        System.out.println((passed + failed) + " tests, " + passed + " passed, " + failed + " failed");
        if (failed > 0) {
            System.out.println("Failures:");
            for (String f : failures) System.out.println("  " + f);
            System.exit(1);
        }
    }

    /* --- assertion helpers --- */

    public static void assertTrue(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void assertFalse(boolean condition, String message) {
        assertTrue(!condition, message);
    }

    public static void assertEquals(long expected, long actual, String message) {
        if (expected != actual) {
            throw new AssertionError(message + " (expected=" + expected + " actual=" + actual + ")");
        }
    }

    public static void assertEquals(Object expected, Object actual, String message) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(message + " (expected=" + expected + " actual=" + actual + ")");
        }
    }

    public static void assertNotNull(Object value, String message) {
        if (value == null) throw new AssertionError(message);
    }

    public static void assertNull(Object value, String message) {
        if (value != null) throw new AssertionError(message + " (was " + value + ")");
    }

    public static <T> void assertArrayEquals(T[] expected, T[] actual, String message) {
        if (!Arrays.equals(expected, actual)) throw new AssertionError(message);
    }

    public static void assertArrayEquals(byte[] expected, byte[] actual, String message) {
        if (!Arrays.equals(expected, actual)) throw new AssertionError(message);
    }
}
