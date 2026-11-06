package com.bempdiff.ai.context;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

/**
 * 项目级上下文扫描器（AI 分析增强 · 需求 1/2）。
 * 离线目录扫描：识别构建系统、模块、依赖、入口、配置与约定，合成架构叙述，
 * 产出 {@link ProjectContext}。纯启发式（正则/文件名），无网络、无执行、可单测。
 *
 * <p>识别分两层：
 * <ol>
 *   <li>标准源码工程：根级 pom.xml / build.gradle / package.json / go.mod 等构建文件
 *       + src/main/java 包结构（Maven/Gradle 多模块、依赖、入口、配置）；</li>
 *   <li>部署产物/非标准目录降级：WAR 解包（WEB-INF/classes + WEB-INF/lib）、JAR 解包
 *       （META-INF/MANIFEST）、Java 字节码/源码非标准布局 —— 面向「对比部署包产物」
 *       这一核心场景，避免对产物目录给出全空上下文。</li>
 * </ol></p>
 *
 * <p>安全：{@link #MAX_FILES}/{@link #MAX_DEPTH} 限制遍历规模，防止超大目录拖垮 JVM
 * （与 PackageParser 的 zip-bomb 防护、HttpAiAnalyzer 的响应体上限同一哲学）。</p>
 */
public final class ProjectContextAnalyzer {

    public static final int MAX_FILES = 4000;
    public static final int MAX_DEPTH = 12;
    private static final long MAX_READ_BYTES = 2L * 1024 * 1024; // 单文件读取上限 2MB
    private static final String MVN_FILE = "pom.xml";
    private static final String MVN = "Maven";
    private static final String GRADLE = "Gradle";
    private static final String GRADLE_FILE = "build.gradle";
    private static final String GRADLE_KTS_FILE = "build.gradle.kts";
    private static final String PKG_JSON = "package.json";

    // 部署产物降级识别特征
    private static final String WAR_DESC = "Java Web 部署包（WAR 解包）";
    private static final String JAR_DESC = "Java 应用产物（JAR 解包）";
    private static final String BYTECODE_DESC = "Java 字节码产物（非标准布局）";
    private static final String SOURCE_DESC = "Java 源码工程（非标准布局）";

    /** 遍历剪枝目录名：构建产物/版本控制/临时噪音，避免其占满文件上限并污染约定统计。 */
    private static final Set<String> SKIP_DIRS = Set.of(
            "node_modules", ".git", ".idea", ".vscode", ".trae", ".venv", ".workbuddy",
            "target", "output", "dist", "build", "logs", "tmp", "temp", "screenshots",
            "reports", "test-reports", "testreports", "archives", "defects", "delivery");

    private ProjectContextAnalyzer() {
        throw new UnsupportedOperationException("工具类不允许实例化");
    }

    public static ProjectContext analyze(Path root) {
        if (root == null || !Files.isDirectory(root)) {
            return new ProjectContext("", "none", List.of(), List.of(), List.of(),
                    List.of(), List.of(), List.of(), "未提供有效工程目录");
        }
        List<Path> files = listFiles(root);
        String buildSystem = detectBuildSystem(root, files);
        List<String> modules = findModules(root, buildSystem, files);
        List<String> dependencies = extractDependencies(root, buildSystem, files);
        List<String> entryPoints = findEntryPoints(root, files);
        List<String> configFiles = findConfigFiles(root, files, buildSystem);
        List<String> techStack = inferTechStack(buildSystem, dependencies);
        List<String> conventions = inferConventions(files, buildSystem);
        String summary = synthesizeSummary(buildSystem, modules, entryPoints, conventions);
        if (files.size() >= MAX_FILES) {
            summary += "（上下文扫描已达 " + MAX_FILES + " 文件上限，部分文件未纳入分析）";
        }
        return new ProjectContext(root.toString(), buildSystem, modules, dependencies,
                entryPoints, configFiles, techStack, conventions, summary,
                files.size(), files.size() >= MAX_FILES);
    }

    // ---- 目录遍历（受限） ----

    private static List<Path> listFiles(Path root) {
        List<Path> files = new ArrayList<>();
        try {
            Files.walkFileTree(root, EnumSet.noneOf(FileVisitOption.class), MAX_DEPTH,
                    new SimpleFileVisitor<Path>() {
                        @Override
                        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                            if (files.size() >= MAX_FILES) return FileVisitResult.TERMINATE;
                            if (!dir.equals(root)) {
                                String n = dir.getFileName().toString();
                                if (SKIP_DIRS.contains(n) || n.contains("@tmp") || n.startsWith(".")) {
                                    return FileVisitResult.SKIP_SUBTREE;
                                }
                            }
                            return FileVisitResult.CONTINUE;
                        }
                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                            if (files.size() >= MAX_FILES) return FileVisitResult.TERMINATE;
                            files.add(file);
                            return FileVisitResult.CONTINUE;
                        }
                    });
        } catch (IOException ignored) {
            // 部分子目录无权限：忽略，继续
        }
        return files;
    }

    // ---- 构建系统识别 ----

    private static String detectBuildSystem(Path root, List<Path> files) {
        if (exists(root, MVN_FILE)) return MVN;
        if (exists(root, GRADLE_FILE) || exists(root, GRADLE_KTS_FILE)) return GRADLE;
        if (exists(root, PKG_JSON)) return "npm";
        if (exists(root, "go.mod")) return "Go";
        if (exists(root, "requirements.txt") || exists(root, "pyproject.toml")) return "Python";
        if (exists(root, "Cargo.toml")) return "Rust";
        // 标准构建文件缺失：按部署产物/非标准布局降级识别（对比部署包场景）
        return detectDeploymentArtifact(files);
    }

    /**
     * 非标准目录降级识别：WAR 解包（WEB-INF/classes）→ JAR 解包（META-INF/MANIFEST）
     * → 字节码/源码非标准布局 → none。注意：标准源码工程会先被根级构建文件命中，
     * 不会走到这里，故 META-INF 等特征在无构建文件目录中足以代表产物结构。
     */
    private static String detectDeploymentArtifact(List<Path> files) {
        boolean war = hasSegment(files, "/WEB-INF/");
        if (war) return WAR_DESC;
        boolean manifest = hasSegment(files, "/META-INF/MANIFEST.MF")
                || hasSegment(files, "/META-INF/MANIFEST.xml");
        if (manifest) return JAR_DESC;
        if (countSuffix(files, ".class") > 0) return BYTECODE_DESC;
        if (countSuffix(files, ".java") > 0) return SOURCE_DESC;
        return "none";
    }

    private static boolean exists(Path root, String name) {
        Path p = root.resolve(name);
        return Files.isRegularFile(p);
    }

    // ---- 模块识别 ----

    private static List<String> findModules(Path root, String bs, List<Path> files) {
        Set<String> mods = new LinkedHashSet<>();
        if (MVN.equals(bs)) {
            collectMavenModules(root, mods);
        } else if (GRADLE.equals(bs)) {
            collectGradleModules(root, mods);
        }
        collectFallbackModules(root, files, mods);
        return capped(new ArrayList<>(mods), 40);
    }

    private static void collectMavenModules(Path root, Set<String> mods) {
        String txt = readIfSmall(root.resolve(MVN_FILE));
        if (txt == null) return;
        Matcher m = Pattern.compile("<module>([^<]+)</module>").matcher(txt);
        while (m.find()) mods.add(m.group(1).trim());
    }

    private static void collectGradleModules(Path root, Set<String> mods) {
        String txt = readIfSmall(root.resolve("settings.gradle"));
        if (txt == null) txt = readIfSmall(root.resolve(GRADLE_KTS_FILE));
        if (txt == null) return;
        Matcher m = Pattern.compile("include\\s*\\(?\\s*['\"]([^'\"]+)['\"]") // NOSONAR java:S5852 — 复核通过：输入有界（单行/文件名/限大小小文件 readIfSmall），模式锚定且实际输入无回溯放大
                .matcher(txt);
        while (m.find()) mods.add(m.group(1).trim().replace(":", "/"));
    }

    /**
     * 兜底：根级无构建文件（bs=none）时，含独立构建文件的子目录视为模块。
     * 仅扫描遍历范围内文件，跳过 node_modules/.git 等噪音目录，结果 capped。
     */
    private static void collectFallbackModules(Path root, List<Path> files, Set<String> mods) {
        if (!mods.isEmpty()) return;
        for (Path f : files) collectFallbackModule(root, f, mods);
    }

    /** 判定单个文件是否可作为回退模块根并加入 mods（抽离循环体以降认知复杂度）。 */
    private static void collectFallbackModule(Path root, Path f, Set<String> mods) {
        String s = f.toString().replace('\\', '/');
        if (s.contains("/node_modules/") || s.contains("/.git/") || s.contains("/target/") || s.contains("/dist/")) return;
        String name = f.getFileName().toString();
        if (!(name.equals(MVN_FILE) || name.equals(GRADLE_FILE) || name.equals(GRADLE_KTS_FILE) || name.equals(PKG_JSON))) return;
        Path parent = f.getParent();
        if (parent == null || parent.equals(root)) return;
        String mod = root.relativize(parent).toString().replace('\\', '/');
        if (!mod.isEmpty()) mods.add(mod);
    }

    // ---- 依赖抽取 ----

    private static List<String> extractDependencies(Path root, String bs, List<Path> files) {
        Set<String> deps = new LinkedHashSet<>();
        if (MVN.equals(bs)) {
            collectMavenDeps(root, deps);
        } else if (GRADLE.equals(bs)) {
            collectGradleDeps(root, deps);
        } else if ("npm".equals(bs)) {
            collectNpmDeps(root, deps);
        } else if (isNonStandardLayout(bs)) {
            // 部署包：从 WEB-INF/lib/*.jar 提取依赖（jar 文件名即依赖标识）
            collectWarLibDeps(files, deps);
        }
        return capped(new ArrayList<>(deps), 60);
    }

    private static void collectMavenDeps(Path root, Set<String> deps) {
        String txt = readIfSmall(root.resolve(MVN_FILE));
        if (txt == null) return;
        Matcher m = Pattern.compile("<artifactId>([^<]+)</artifactId>").matcher(txt);
        while (m.find()) deps.add(m.group(1).trim());
    }

    private static void collectGradleDeps(Path root, Set<String> deps) {
        for (String fn : List.of(GRADLE_FILE, GRADLE_KTS_FILE)) {
            String txt = readIfSmall(root.resolve(fn));
            if (txt == null) continue;
            Matcher m = Pattern.compile( // NOSONAR java:S5852 — 复核通过：输入有界（单行/文件名/限大小小文件 readIfSmall），模式锚定且实际输入无回溯放大
                    "(?:implementation|api|compileOnly|testImplementation|runtimeOnly)\\s*\\(?\\s*['\"]([^'\"]+)['\"]")
                    .matcher(txt);
            while (m.find()) deps.add(m.group(1).trim());
        }
    }

    private static void collectNpmDeps(Path root, Set<String> deps) {
        String txt = readIfSmall(root.resolve(PKG_JSON));
        if (txt == null) return;
        Matcher m = Pattern.compile("\"([a-zA-Z0-9@/_\\-.]+)\"\\s*:\\s*\"[^}]\"")
                .matcher(txt);
        while (m.find()) deps.add(m.group(1).trim());
    }

    /** WAR 解包依赖：WEB-INF/lib/*.jar 文件名（去扩展名）作为依赖标识。 */
    private static void collectWarLibDeps(List<Path> files, Set<String> deps) {
        for (Path f : files) {
            String s = f.toString().replace('\\', '/');
            if (s.contains("/WEB-INF/lib/") && s.endsWith(".jar")) {
                String n = f.getFileName().toString();
                deps.add(n.substring(0, n.length() - ".jar".length()));
            }
        }
    }

    /** 非标准布局降级识别结果（部署产物/非标准源码布局），区别于标准构建系统。 */
    private static boolean isNonStandardLayout(String bs) {
        return bs != null && (bs.contains("部署包") || bs.contains("产物") || bs.contains("非标准布局"));
    }

    // ---- 入口/主类 ----

    private static List<String> findEntryPoints(Path root, List<Path> files) {
        Set<String> eps = new LinkedHashSet<>();
        for (Path f : files) {
            String name = f.getFileName().toString();
            if (name.endsWith(".java")) {
                // 入口类名约定（后缀），避免 "mapper/wrapper" 等含 "app" 子串误命中
                String low = name.toLowerCase();
                boolean nameHint = low.endsWith("application.java") || low.endsWith("app.java")
                        || low.endsWith("main.java") || low.endsWith("bootstrap.java")
                        || low.endsWith("starter.java") || low.endsWith("launcher.java");
                if (nameHint) {
                    String txt = readIfSmall(f);
                    if (txt != null && txt.contains("public static void main")) {
                        eps.add(rel(root, f));
                    }
                }
            } else if (name.equals("index.html") || name.equals("main.js")
                    || name.equals("main.ts") || name.endsWith("App.jsx")
                    || name.endsWith("App.vue")) {
                eps.add(rel(root, f));
            }
        }
        return capped(new ArrayList<>(eps), 20);
    }

    // ---- 配置文件 ----

    private static List<String> findConfigFiles(Path root, List<Path> files, String bs) {
        Set<String> cf = new LinkedHashSet<>();
        for (Path f : files) {
            String name = f.getFileName().toString().toLowerCase();
            boolean match = name.equals("application.yml")
                    || name.equals("application.properties")
                    || name.equals("application.yaml")
                    || name.equals("config.json")
                    || name.equals(".env.example")
                    || name.endsWith(".conf")
                    || (name.startsWith("application-")
                        && (name.endsWith(".yml") || name.endsWith(".yaml") || name.endsWith(".properties")));
            if (match) cf.add(rel(root, f));
        }
        // 部署包产物：补充产物元信息文件（MANIFEST / web.xml / deploy.xml），并放宽
        // WEB-INF/classes 下的 .properties（如 version.properties）纳入配置清单。
        if (isNonStandardLayout(bs)) {
            for (Path f : files) {
                String s = f.toString().replace('\\', '/');
                String low = s.toLowerCase();
                if (low.endsWith("/meta-inf/manifest.mf") || low.endsWith("/meta-inf/manifest.xml")
                        || low.endsWith("/web-inf/web.xml") || low.endsWith("/deploy.xml")
                        // WEB-INF/classes 下的 .properties（如 version.properties）同样纳入
                        || (low.contains("/web-inf/classes/") && low.endsWith(".properties"))) {
                    cf.add(rel(root, f));
                }
            }
        }
        return capped(new ArrayList<>(cf), 20);
    }

    // ---- 技术栈推断 ----

    private static List<String> inferTechStack(String bs, List<String> deps) {
        Set<String> ts = new LinkedHashSet<>();
        ts.add(bs);
        for (String d : deps) {
            String low = d.toLowerCase();
            if (low.contains("spring")) ts.add("Spring");
            else if (low.contains("mybatis")) ts.add("MyBatis");
            else if (low.contains("rocketmq") || low.contains("kafka")) ts.add("消息队列");
            else if (low.contains("redis")) ts.add("Redis");
            else if (low.contains("react") || low.contains("vue")) ts.add("前端框架");
            else if (low.contains("hibernate")) ts.add("Hibernate");
        }
        return new ArrayList<>(ts);
    }

    // ---- 约定推断（包根 + 命名 + 部署包结构） ----

    private static List<String> inferConventions(List<Path> files, String bs) {
        Set<String> conv = new LinkedHashSet<>();
        if (isNonStandardLayout(bs)) {
            collectDeploymentConventions(files, conv);
        }
        List<String[]> pkgDirs = collectPkgDirs(files);
        if (!pkgDirs.isEmpty()) {
            conv.add("Java 包根：" + computePackageRoot(pkgDirs));
        }
        addNamingConventions(files, conv);
        return capped(new ArrayList<>(conv), 10);
    }

    /** 部署产物约定：结构叙述 + MANIFEST 标识（Implementation-Title/Version）。 */
    private static void collectDeploymentConventions(List<Path> files, Set<String> conv) {
        int libJars = 0;
        Path manifest = null;
        for (Path f : files) {
            String s = f.toString().replace('\\', '/');
            if (s.contains("/WEB-INF/lib/") && s.endsWith(".jar")) libJars++;
            else if (manifest == null && isManifestPath(s)) {
                manifest = f;
            }
        }
        if (libJars > 0) conv.add("WEB-INF/lib 依赖库 " + libJars + " 个");
        if (manifest != null) {
            String desc = manifestDescription(manifest);
            if (desc != null) conv.add(desc);
        }
    }

    /** MANIFEST 描述符地址判定（META-INF/MANIFEST.MF 或 .xml）。 */
    private static boolean isManifestPath(String s) {
        return s.endsWith("/META-INF/MANIFEST.MF") || s.endsWith("/META-INF/MANIFEST.xml");
    }

    /** 读取 manifest 并生成「部署包标识」叙述；无标识信息返回 null。 */
    private static String manifestDescription(Path manifest) {
        String txt = readIfSmall(manifest);
        if (txt == null) return null;
        String title = pickManifest(txt, "Implementation-Title");
        String version = pickManifest(txt, "Implementation-Version");
        if (title == null && version == null) return null;
        return "部署包标识：" + (title == null ? "" : title)
                + (version == null ? "" : " v" + version);
    }

    private static String pickManifest(String txt, String key) {
        for (String line : txt.split("\n")) {
            String t = line.trim();
            if (t.startsWith(key + ":")) {
                String v = t.substring(key.length() + 1).trim();
                return v.isEmpty() ? null : v;
            }
        }
        return null;
    }

    /** 收集所有 src/main/java 下 Java 源文件所在包的目录层级（文件名之前的目录部分）。 */
    private static List<String[]> collectPkgDirs(List<Path> files) {
        List<String[]> pkgDirs = new ArrayList<>();
        for (Path f : files) {
            String s = f.toString().replace('\\', '/');
            int idx = s.indexOf("/src/main/java/");
            if (idx < 0) continue;
            String after = s.substring(idx + "/src/main/java/".length());
            int lastSlash = after.lastIndexOf('/');
            // 去掉文件名，取其所在包的目录路径（如 com/example/foo）
            String dir = (lastSlash < 0) ? "" : after.substring(0, lastSlash);
            if (!dir.isEmpty()) {
                pkgDirs.add(dir.split("/"));
            }
        }
        return pkgDirs;
    }

    /** 计算所有包目录的公共前缀层级，返回「包根」叙述字符串。 */
    private static String computePackageRoot(List<String[]> pkgDirs) {
        String[] first = pkgDirs.get(0);
        int common = first.length;
        for (String[] p : pkgDirs) {
            int lim = Math.min(common, p.length);
            int i = 0;
            while (i < lim && p[i].equals(first[i])) i++;
            common = i;
        }
        // 最深包层级（用于判断项目包结构深度）
        int maxDepth = 0;
        for (String[] p : pkgDirs) {
            maxDepth = Math.max(maxDepth, p.length);
        }
        StringBuilder rootSb = new StringBuilder();
        for (int i = 0; i < common; i++) {
            if (i > 0) rootSb.append('.');
            rootSb.append(first[i]);
        }
        String rootStr = rootSb.length() == 0 ? "(默认包/无层级)" : rootSb.toString();
        return rootStr + "（公共 " + common + " 级 / 最深 " + maxDepth + " 级）";
    }

    /** 统计命名约定频次并追加约定叙述到 conv。 */
    private static void addNamingConventions(List<Path> files, Set<String> conv) {
        long mapper = 0;
        long service = 0;
        long controller = 0;
        for (Path f : files) {
            String n = f.getFileName().toString();
            if (n.endsWith("Mapper.java") || n.endsWith("Dao.java")) mapper++;
            else if (n.endsWith("Service.java")) service++;
            else if (n.endsWith("Controller.java") || n.endsWith("Resource.java")) controller++;
        }
        if (mapper > 0) conv.add("持久层命名 *Mapper/*Dao（" + mapper + " 个）");
        if (service > 0) conv.add("服务层命名 *Service（" + service + " 个）");
        if (controller > 0) conv.add("接口/控制层命名 *Controller/*Resource（" + controller + " 个）");
    }

    // ---- 架构叙述合成 ----

    private static String synthesizeSummary(String bs, List<String> modules,
                                            List<String> eps, List<String> conv) {
        if ("none".equals(bs) && (modules == null || modules.isEmpty())) {
            return "未识别到标准构建系统，仅基于目录结构推断；架构分层信息有限，请谨慎参考。";
        }
        if (isNonStandardLayout(bs)) {
            // 部署产物/非标准布局：直接给出结构叙述，避免套用「单模块工程」误导
            StringBuilder dsb = new StringBuilder();
            dsb.append("未识别到标准构建系统，按目录特征推断为 ").append(bs);
            if (conv != null && !conv.isEmpty()) {
                dsb.append("（").append(String.join("；", conv)).append("）");
            }
            dsb.append("；无标准源码工程结构，架构分层信息有限，请谨慎参考。");
            return dsb.toString();
        }
        StringBuilder sb = new StringBuilder();
        if (modules != null && !modules.isEmpty()) {
            sb.append("多模块工程（").append(bs).append("），模块含 ")
              .append(String.join("、", modules)).append("；");
        } else {
            sb.append("单模块工程（").append(bs).append("）；");
        }
        if (eps != null && !eps.isEmpty()) {
            sb.append("入口/主类：").append(String.join("、", eps)).append("；");
        }
        if (conv != null && !conv.isEmpty()) {
            sb.append("约定：").append(String.join("；", conv)).append("。");
        }
        return sb.toString();
    }

    // ---- 工具 ----

    private static String rel(Path root, Path f) {
        return root.relativize(f).toString().replace('\\', '/');
    }

    private static List<String> capped(List<String> in, int max) {
        return in.size() > max ? in.subList(0, max) : in;
    }

    /** 任一文件路径含指定路径段（如 "/WEB-INF/"）。 */
    private static boolean hasSegment(List<Path> files, String segment) {
        for (Path f : files) {
            if (f.toString().replace('\\', '/').contains(segment)) return true;
        }
        return false;
    }

    /** 统计以指定后缀结尾的文件数量（.class / .java 等）。 */
    private static long countSuffix(List<Path> files, String suffix) {
        long n = 0;
        for (Path f : files) {
            if (f.getFileName().toString().endsWith(suffix)) n++;
        }
        return n;
    }

    private static String readIfSmall(Path p) {
        if (p == null || !Files.isRegularFile(p)) return null;
        try {
            if (Files.size(p) > MAX_READ_BYTES) return null;
            return new String(Files.readAllBytes(p), java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }
}
