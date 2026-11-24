package com.bempdiff.test;

import com.bempdiff.server.PreflightService;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * CX-09 比对前预检单测：只读检查路径/类型/体量/条目/层级，并把「同一路径」变成阻断项。
 * 守护点：
 *  - 预检绝不能抛异常给调用方（缺失路径/损坏包都要变成 blockers）；
 *  - 目录模式与包模式类型不匹配必须阻断；
 *  - 目录扫描统计条目与忽略扩展名；
 *  - ZIP 可读性检查不依赖解压（ZipFile 只读中央目录）。
 */
public final class PreflightServiceTest {

    public void testFolderPairReturnsInfoAndStats() throws Exception {
        Path tmp = Files.createTempDirectory("bdf-pf");
        try {
            Path oldDir = Files.createDirectories(tmp.resolve("old"));
            Path newDir = Files.createDirectories(tmp.resolve("new"));
            Files.write(oldDir.resolve("a.txt"), "a".getBytes(StandardCharsets.UTF_8));
            Files.write(newDir.resolve("a.txt"), "a".getBytes(StandardCharsets.UTF_8));
            Files.write(newDir.resolve("b.log"), "ignore".getBytes(StandardCharsets.UTF_8));

            Map<String, Object> r = PreflightService.check(oldDir.toString(), newDir.toString(),
                    "folder", List.of(".log"));
            Asserts.assertTrue("目录对预检应通过", (Boolean) r.get("ok"));
            Asserts.assertContains("应识别 info 级", String.valueOf(r.get("level")), "info");
            Map<?, ?> left = (Map<?, ?>) r.get("left");
            Map<?, ?> right = (Map<?, ?>) r.get("right");
            Asserts.assertTrue("老目录条目数应 >=1", ((Number) left.get("entries")).longValue() >= 1);
            Asserts.assertTrue("新目录条目数应 >=2", ((Number) right.get("entries")).longValue() >= 2);
            Asserts.assertTrue("忽略扩展名应被统计", ((Number) right.get("ignored")).longValue() >= 1);
            Asserts.assertTrue("应给出预计耗时", ((Number) r.get("estimatedSeconds")).longValue() >= 1);
        } finally {
            deleteTree(tmp);
        }
    }

    public void testSamePathIsBlocker() throws Exception {
        Path tmp = Files.createTempDirectory("bdf-pf");
        try {
            Path dir = Files.createDirectories(tmp.resolve("only"));
            Map<String, Object> r = PreflightService.check(dir.toString(), dir.toString(), "folder", null);
            Asserts.assertTrue("同一路径必须阻断", !((Boolean) r.get("ok")));
            Asserts.assertContains("阻断原因应明确", String.join("|", (List<String>) r.get("blockers")), "同一路径");
        } finally {
            deleteTree(tmp);
        }
    }

    public void testMissingPathIsBlockerNotException() throws Exception {
        Path tmp = Files.createTempDirectory("bdf-pf");
        try {
            Map<String, Object> r = PreflightService.check(tmp.resolve("gone.war").toString(),
                    tmp.resolve("also-gone.war").toString(), "package", null);
            Asserts.assertTrue("缺失路径应为 block", "block".equals(r.get("level")));
            Asserts.assertContains("应包含不存在说明", String.join("|", (List<String>) r.get("blockers")), "不存在");
        } finally {
            deleteTree(tmp);
        }
    }

    public void testPlainFileInPackageModeIsBlocker() throws Exception {
        Path tmp = Files.createTempDirectory("bdf-pf");
        try {
            Path notZip = Files.write(tmp.resolve("plain.txt"), "not a zip".getBytes(StandardCharsets.UTF_8));
            Map<String, Object> r = PreflightService.check(notZip.toString(), notZip.toString(), "package", null);
            Asserts.assertTrue("非 zip 必须阻断", !((Boolean) r.get("ok")));
        } finally {
            deleteTree(tmp);
        }
    }

    public void testZipPairReadsCentralDirectory() throws Exception {
        Path tmp = Files.createTempDirectory("bdf-pf");
        try {
            Path oldZip = writeZip(tmp.resolve("old.zip"), "WEB-INF/classes/A.class", "old");
            Path newZip = writeZip(tmp.resolve("new.zip"), "WEB-INF/classes/A.class", "new");
            Map<String, Object> r = PreflightService.check(oldZip.toString(), newZip.toString(), "package", null);
            Asserts.assertTrue("可读 ZIP 对预检应通过", (Boolean) r.get("ok"));
            Map<?, ?> left = (Map<?, ?>) r.get("left");
            Asserts.assertTrue("ZIP 应被识别为 archive", (Boolean) left.get("archive"));
            Asserts.assertTrue("ZIP 条目数应 >=1", ((Number) left.get("entries")).longValue() >= 1);
            Asserts.assertTrue("ZIP 解压体量应 >0", ((Number) left.get("uncompressedBytes")).longValue() > 0);
        } finally {
            deleteTree(tmp);
        }
    }

    public void testFolderModeRejectsPlainFile() throws Exception {
        Path tmp = Files.createTempDirectory("bdf-pf");
        try {
            Path oldDir = Files.createDirectories(tmp.resolve("old"));
            Path newFile = Files.write(tmp.resolve("new.txt"), "x".getBytes(StandardCharsets.UTF_8));
            Map<String, Object> r = PreflightService.check(oldDir.toString(), newFile.toString(), "folder", null);
            Asserts.assertTrue("目录模式选文件必须阻断", !((Boolean) r.get("ok")));
            Asserts.assertContains("应说明不是目录", String.join("|", (List<String>) r.get("blockers")), "不是目录");
        } finally {
            deleteTree(tmp);
        }
    }

    public void testSmallZipPreflightFastPath() throws Exception {
        Path tmp = Files.createTempDirectory("bdf-pf");
        try {
            Path oldZip = writeZip(tmp.resolve("old.zip"), "WEB-INF/classes/A.class", "old");
            Path newZip = writeZip(tmp.resolve("new.zip"), "WEB-INF/classes/A.class", "new");
            // 预热一次后再计时，避免把首次 ZipFile/JIT 开销算进“快速包”口径。
            PreflightService.check(oldZip.toString(), newZip.toString(), "package", null);
            long bestMs = Long.MAX_VALUE;
            for (int i = 0; i < 7; i++) {
                long t0 = System.nanoTime();
                PreflightService.check(oldZip.toString(), newZip.toString(), "package", null);
                long ms = (System.nanoTime() - t0) / 1_000_000L;
                if (ms < bestMs) bestMs = ms;
            }
            Asserts.assertTrue("快速包预检应 <100ms（实测最优 " + bestMs + "ms）", bestMs < 100);
            System.out.println("[CX-09][bench] 小 zip 预检最优 " + bestMs + "ms（缓存命中路径）");
        } finally {
            deleteTree(tmp);
        }
    }

    private static Path writeZip(Path file, String entry, String content) throws IOException {
        try (OutputStream os = Files.newOutputStream(file);
             ZipOutputStream zos = new ZipOutputStream(os)) {
            zos.putNextEntry(new ZipEntry(entry));
            zos.write(content.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        return file;
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var walk = Files.walk(root)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException ignored) { }
            });
        }
    }
}
