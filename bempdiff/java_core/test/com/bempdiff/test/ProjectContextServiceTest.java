package com.bempdiff.test;

import com.bempdiff.ai.context.ProjectContextCache;
import com.bempdiff.ai.context.ProjectIndex;
import com.bempdiff.ai.context.ProjectContextService;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.concurrent.TimeUnit;

/** 项目级上下文缓存测试：首次构建、缓存命中、指纹失效重算、手动刷新。 */
public final class ProjectContextServiceTest {

    private static Path write(Path dir, String rel, String content) throws IOException {
        Path p = dir.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.write(p, content.getBytes(StandardCharsets.UTF_8));
        return p;
    }

    public void testResolve_cacheHitAndFingerprintInvalidation() throws IOException, InterruptedException {
        Path root = Files.createTempDirectory("bdcsvc");
        Files.createDirectories(root);
        write(root, "pom.xml", "<project><artifactId>demo</artifactId></project>");
        write(root, "src/main/java/com/demo/DemoApp.java",
                "package com.demo; public class DemoApp { public static void main(String[] a){} }");
        String projDir = root.toString();
        try {
            // 1) 首次构建
            ProjectIndex first = ProjectContextService.resolve(projDir);
            Asserts.assertNotNull("首次应构建成功", first);
            Asserts.assertEquals("应识别 1 个项目", 1, first.size());
            long t1 = first.getScannedAt();
            Asserts.assertTrue("缓存文件应落盘", Files.isRegularFile(ProjectContextCache.cacheFile(projDir)));
            Asserts.assertTrue("应记录实际扫描文件数",
                    first.getProjects().get(0).getCtx().getAnalyzedFiles() > 0);
            Asserts.assertFalse("小目录不应标记截断", first.getProjects().get(0).getCtx().isTruncated());

            // 2) 未改动 → 缓存命中（scannedAt 不变）
            ProjectIndex hit = ProjectContextService.resolve(projDir);
            Asserts.assertNotNull("缓存命中", hit);
            Asserts.assertEquals("命中后 scannedAt 应不变", t1, hit.getScannedAt());

            // 3) 修改构建文件 → 指纹变化 → 增量重算
            Thread.sleep(20); // 确保 mtime/时间戳可区分
            write(root, "pom.xml", "<project><artifactId>demo</artifactId><version>2.0</version></project>");
            ProjectIndex fresh = ProjectContextService.resolve(projDir);
            Asserts.assertNotNull("指纹失效后应重算", fresh);
            Asserts.assertTrue("重算后 scannedAt 应更新", fresh.getScannedAt() >= t1);
            Asserts.assertTrue("重算后缓存应回写", Files.isRegularFile(ProjectContextCache.cacheFile(projDir)));

            // 4) 手动刷新
            ProjectIndex refreshed = ProjectContextService.refresh(projDir);
            Asserts.assertNotNull("手动刷新应成功", refreshed);
            Asserts.assertEquals("刷新后仍识别 1 个项目", 1, refreshed.size());
        } finally {
            ProjectContextCache.clear(projDir);
        }
    }

    public void testResolve_invalidDir_returnsNull() {
        Asserts.assertNull("无效目录应返回 null",
                ProjectContextService.resolve("C:/__not_exist__/xyz"));
        Asserts.assertNull("空目录参数应返回 null", ProjectContextService.resolve("  "));
        Asserts.assertNull("null 应返回 null", ProjectContextService.resolve(null));
    }

    public void testPurgeStale_removesExpiredCache() throws IOException {
        Path root = null;
        String projDir = null;
        try {
            root = Files.createTempDirectory("bdcpurge");
            Files.createDirectories(root);
            Files.writeString(root.resolve("pom.xml"), "<project><artifactId>purge</artifactId></project>");
            projDir = root.toString();
            ProjectIndex first = ProjectContextService.resolve(projDir);
            Asserts.assertNotNull("清理测试应先生成缓存", first);
            Path cacheFile = ProjectContextCache.cacheFile(projDir);
            Asserts.assertTrue("清理测试缓存应落盘", Files.isRegularFile(cacheFile));
            Files.setLastModifiedTime(cacheFile,
                    FileTime.fromMillis(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(31)));
            ProjectContextCache.purgeStale(TimeUnit.DAYS.toMillis(30));
            Asserts.assertFalse("超过保留期缓存应被删除", Files.exists(cacheFile));
            Asserts.assertNull("删除后不应再读出缓存", ProjectContextCache.load(projDir));
        } finally {
            if (projDir != null) ProjectContextCache.clear(projDir);
        }
    }

    public void testRenderContextView_multiProject() throws IOException {
        Path root = Files.createTempDirectory("bdcsvc");
        Files.createDirectories(root);
        write(root, "a/pom.xml", "<project><packaging>pom</packaging><modules><module>m1</module></modules></project>");
        write(root, "a/m1/pom.xml", "<project><artifactId>m1</artifactId></project>");
        write(root, "b/pom.xml", "<project><artifactId>b</artifactId></project>");
        String projDir = root.toString();
        try {
            ProjectIndex idx = ProjectContextService.resolve(projDir);
            Asserts.assertNotNull("应解析成功", idx);
            Asserts.assertEquals("应识别 2 个项目", 2, idx.size());
            var view = ProjectContextService.renderContextView(idx);
            Asserts.assertNotNull("上下文视图应可渲染", view);
            Asserts.assertEquals("视图构建系统应标注多项目", "多项目仓库（2 个项目）", view.getBuildSystem());
            Asserts.assertContains("视图简述应含项目路径", view.getSummary(), "a");
        } finally {
            ProjectContextCache.clear(projDir);
        }
    }
}
