package com.bempdiff.test;

import com.bempdiff.decompile.Decompiler;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * NF-02 安全 V1：反编译子进程超时护栏。
 * 旧实现 `p.waitFor()` 无上限，CFR/javap 在畸形 class 上挂住会永久占住请求线程；
 * 本测试锁定超时配置的边界与“超时可配置但不可关掉”的契约。
 */
public final class DecompileGuardTest {

    public void testTimeoutClamp() throws Exception {
        String old = System.getProperty("bempdiff.decompileTimeoutSeconds");
        try {
            Method m = Decompiler.class.getDeclaredMethod("resolveTimeoutSeconds");
            m.setAccessible(true);
            System.setProperty("bempdiff.decompileTimeoutSeconds", "1");
            Asserts.assertEquals("低于 5s 应钳到 5s", 5, ((Integer) m.invoke(null)).intValue());
            System.setProperty("bempdiff.decompileTimeoutSeconds", "999");
            Asserts.assertEquals("高于 120s 应钳到 120s", 120, ((Integer) m.invoke(null)).intValue());
            System.setProperty("bempdiff.decompileTimeoutSeconds", "not-a-number");
            Asserts.assertEquals("非法值回退 20s", 20, ((Integer) m.invoke(null)).intValue());
            System.setProperty("bempdiff.decompileTimeoutSeconds", "30");
            Asserts.assertEquals("合法值直通", 30, ((Integer) m.invoke(null)).intValue());
        } finally {
            if (old == null) System.clearProperty("bempdiff.decompileTimeoutSeconds");
            else System.setProperty("bempdiff.decompileTimeoutSeconds", old);
        }
    }

    public void testProcessHelperReturnsOutput() throws Exception {
        Method m = Decompiler.class.getDeclaredMethod("runProcessWithTimeout", ProcessBuilder.class, String.class);
        m.setAccessible(true);
        ProcessBuilder pb = new ProcessBuilder("cmd", "/c", "echo guard-ok");
        pb.redirectErrorStream(true);
        String out = (String) m.invoke(null, pb, "guard");
        Asserts.assertContains("正常子进程应返回输出", out, "guard-ok");
    }

    public void testInProcessCfrFailsFastOnBadClass() throws Exception {
        Path bad = Files.createTempFile("bdf-bad-class", ".class");
        try {
            Files.write(bad, "not a class".getBytes(StandardCharsets.UTF_8));
            Method m = Decompiler.class.getDeclaredMethod("cfrInProcess", File.class);
            m.setAccessible(true);
            Decompiler d = new Decompiler(null, "javap");
            try {
                m.invoke(d, bad.toFile());
                throw new AssertionError("非法 class 不应返回成功");
            } catch (InvocationTargetException e) {
                Asserts.assertTrue("应包装为 IOException", e.getCause() instanceof IOException);
            }
        } finally {
            Files.deleteIfExists(bad);
        }
    }
}
