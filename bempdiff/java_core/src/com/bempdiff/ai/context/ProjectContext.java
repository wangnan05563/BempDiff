package com.bempdiff.ai.context;

import java.util.List;
import java.util.Map;

/**
 * 项目级上下文模型（AI 分析增强 · 需求 2）。
 * 由 {@link ProjectContextAnalyzer} 通过离线目录扫描构建，描述工程的
 * 构建系统、模块、依赖、入口、配置约定与架构叙述，作为 AI 两阶段分析的依据。
 *
 * <p>纯数据模型，无网络、无第三方依赖；{@link #toPromptSection()} 渲染为
 * 对 LLM 友好的紧凑文本块，可直接拼入 stageA/stageB prompt。</p>
 */
public final class ProjectContext {

    private final String rootPath;
    private final String buildSystem;      // "Maven"/"Gradle"/"npm"/"none" 等
    private final List<String> modules;     // 模块名/相对路径
    private final List<String> dependencies;// 人类可读的依赖描述
    private final List<String> entryPoints; // 主类/入口文件（相对路径）
    private final List<String> configFiles; // 配置文件（相对路径）
    private final List<String> techStack;   // 技术栈关键词
    private final List<String> conventions; // 包结构/命名约定
    private final String summary;           // 综合架构叙述（供 prompt）
    private final int analyzedFiles;        // 受扫描上限约束后实际分析的文件数；旧缓存为 0
    private final boolean truncated;        // 是否因扫描上限提前停止

    public ProjectContext(String rootPath, String buildSystem, List<String> modules, // NOSONAR(S107) - 多参重载为既有公开 API
                          List<String> dependencies, List<String> entryPoints,
                          List<String> configFiles, List<String> techStack,
                          List<String> conventions, String summary) { // NOSONAR - 9 个字段由各字段单独 getter 暴露，重建参数对象反而过度设计
        this(rootPath, buildSystem, modules, dependencies, entryPoints, configFiles,
                techStack, conventions, summary, 0, false);
    }

    public ProjectContext(String rootPath, String buildSystem, List<String> modules, // NOSONAR(S107) - 11 个字段由各字段单独 getter 暴露
                          List<String> dependencies, List<String> entryPoints,
                          List<String> configFiles, List<String> techStack,
                          List<String> conventions, String summary,
                          int analyzedFiles, boolean truncated) { // NOSONAR - 扫描可观测性与既有上下文字段并列
        this.rootPath = rootPath;
        this.buildSystem = buildSystem;
        this.modules = modules;
        this.dependencies = dependencies;
        this.entryPoints = entryPoints;
        this.configFiles = configFiles;
        this.techStack = techStack;
        this.conventions = conventions;
        this.summary = summary;
        this.analyzedFiles = analyzedFiles;
        this.truncated = truncated;
    }

    public String getRootPath() { return rootPath; }
    public String getBuildSystem() { return buildSystem; }
    public List<String> getModules() { return modules; }
    public List<String> getDependencies() { return dependencies; }
    public List<String> getEntryPoints() { return entryPoints; }
    public List<String> getConfigFiles() { return configFiles; }
    public List<String> getTechStack() { return techStack; }
    public List<String> getConventions() { return conventions; }
    public String getSummary() { return summary; }
    public int getAnalyzedFiles() { return analyzedFiles; }
    public boolean isTruncated() { return truncated; }

    /** 未提供有效工程目录时视为「未启用上下文增强」。 */
    public boolean isEmpty() {
        return rootPath == null || rootPath.isEmpty();
    }

    /** 渲染为「## 项目级上下文（分析依据）」文本块，拼入 prompt。 */
    public String toPromptSection() {
        StringBuilder sb = new StringBuilder();
        sb.append("## 项目级上下文（分析依据）\n");
        sb.append("- 构建系统：").append(nullToNone(buildSystem)).append("\n");
        sb.append("- 模块：").append(join(modules)).append("\n");
        sb.append("- 核心依赖：").append(join(dependencies)).append("\n");
        sb.append("- 入口/主类：").append(join(entryPoints)).append("\n");
        sb.append("- 配置与约定：").append(join(configFiles)).append("\n");
        if (conventions != null && !conventions.isEmpty()) {
            sb.append("- 约定：").append(join(conventions)).append("\n");
        }
        if (summary != null && !summary.isEmpty()) {
            sb.append("- 架构简述：").append(summary).append("\n");
        }
        return sb.toString();
    }

    private static String nullToNone(String s) {
        return (s == null || s.isEmpty()) ? "未识别" : s;
    }

    private static String join(List<String> l) {
        if (l == null || l.isEmpty()) return "（无）";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < l.size(); i++) {
            if (i > 0) sb.append("、");
            sb.append(l.get(i));
        }
        return sb.toString();
    }

    // ---- 缓存序列化（ProjectIndex 落盘/回读） ----

    public Map<String, Object> toJsonMap() {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("rootPath", rootPath);
        m.put("buildSystem", buildSystem);
        m.put("modules", modules);
        m.put("dependencies", dependencies);
        m.put("entryPoints", entryPoints);
        m.put("configFiles", configFiles);
        m.put("techStack", techStack);
        m.put("conventions", conventions);
        m.put("summary", summary);
        m.put("analyzedFiles", analyzedFiles);
        m.put("truncated", truncated);
        return m;
    }

    @SuppressWarnings("unchecked")
    public static ProjectContext fromJsonMap(Map<String, Object> m) {
        return new ProjectContext(
                str(m.get("rootPath")),
                str(m.get("buildSystem")),
                strList(m.get("modules")),
                strList(m.get("dependencies")),
                strList(m.get("entryPoints")),
                strList(m.get("configFiles")),
                strList(m.get("techStack")),
                strList(m.get("conventions")),
                str(m.get("summary")),
                (m.get("analyzedFiles") instanceof Number) ? ((Number) m.get("analyzedFiles")).intValue() : 0,
                Boolean.TRUE.equals(m.get("truncated")));
    }

    private static String str(Object o) {
        return (o == null) ? "" : String.valueOf(o);
    }

    @SuppressWarnings("unchecked")
    private static List<String> strList(Object o) {
        if (!(o instanceof List)) return List.of();
        java.util.List<String> out = new java.util.ArrayList<>();
        for (Object x : (List<Object>) o) out.add(String.valueOf(x));
        return out;
    }
}
