package com.bempdiff.server;

import com.bempdiff.config.ParseConfig;
import com.bempdiff.diff.DiffRules;
import com.bempdiff.unpack.UnpackOptions;

/** 一次比对的选项（由前端请求携带，或取默认值）。 */
public final class CompareOptions {
    private boolean expandAll = false;
    private int topK = 12;
    private String internalPrefixes = "com.hundsun";
    private String cfrJar = ""; // 为空则依赖进程内 CFR（cfr 在 classpath 上时自动启用）

    // P0-②：忽略不重要差异
    private boolean ignoreWhitespace = false; // 折叠空白（避免纯空白 / 行尾空白噪声）
    private boolean ignoreComments = false;    // 剥离整行注释
    private String ignoreRegex = "";           // 自定义正则（命中子串从行中移除；非法则忽略）

    // 比对级过滤：忽略的扩展名（多选，如 .log/.tmp）。解析收集阶段直接跳过，不参与差异比对。
    private java.util.List<String> ignoreExtensions = new java.util.ArrayList<>();

    // 物理平铺解包（WAR/ZIP 嵌套归档多线程逐层展开）。默认关闭，保护既有差异树语义；
    // 开启后由 runCompareTask 对 ARCHIVE/JAR 条目物理平铺递归解包，差异统计与 AI 覆盖嵌套子文件。
    private boolean unpackNested = false;
    private int unpackThreads = 4;
    private long unpackPerItemTimeoutMs = 60_000;
    private int unpackMaxDepth = 6;
    private long unpackTotalBytesCap = 4L * 1024 * 1024 * 1024;

    public boolean isExpandAll() { return expandAll; }
    public void setExpandAll(boolean expandAll) { this.expandAll = expandAll; }

    public int getTopK() { return topK; }
    public void setTopK(int topK) { this.topK = topK; }

    public String getInternalPrefixes() { return internalPrefixes; }
    public void setInternalPrefixes(String internalPrefixes) { this.internalPrefixes = internalPrefixes; }

    public String getCfrJar() { return cfrJar; }
    public void setCfrJar(String cfrJar) { this.cfrJar = cfrJar; }

    public boolean isIgnoreWhitespace() { return ignoreWhitespace; }
    public void setIgnoreWhitespace(boolean v) { this.ignoreWhitespace = v; }

    public boolean isIgnoreComments() { return ignoreComments; }
    public void setIgnoreComments(boolean v) { this.ignoreComments = v; }

    public String getIgnoreRegex() { return ignoreRegex; }
    public void setIgnoreRegex(String v) { this.ignoreRegex = v; }

    public java.util.List<String> getIgnoreExtensions() { return ignoreExtensions; }
    public void setIgnoreExtensions(java.util.List<String> v) {
        this.ignoreExtensions = v == null ? new java.util.ArrayList<>() : v;
    }

    public boolean isUnpackNested() { return unpackNested; }
    public void setUnpackNested(boolean v) { this.unpackNested = v; }

    /** 由 request options 派生解包配置（未提供则用默认值，线程池 4 / 单任务 5s / 深度 6）。 */
    public UnpackOptions toUnpackOptions() {
        UnpackOptions o = new UnpackOptions();
        o.threadPoolSize = unpackThreads;
        o.perItemTimeoutMs = unpackPerItemTimeoutMs;
        o.maxDepth = unpackMaxDepth;
        o.totalBytesCap = unpackTotalBytesCap;
        o.ignoreExtensions = new java.util.ArrayList<>(ignoreExtensions);
        return o;
    }

    public static CompareOptions fromRequest(java.util.Map<String, Object> req) {
        CompareOptions o = new CompareOptions();
        Object optsObj = req.get("options");
        if (optsObj instanceof java.util.Map) {
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> opts = (java.util.Map<String, Object>) optsObj;
            o.setExpandAll(Json.bool(opts, "expandAll", false));
            o.setTopK(Json.intv(opts, "topK", 12));
            o.setInternalPrefixes(Json.str(opts, "internalPrefixes", "com.hundsun"));
            o.setCfrJar(Json.str(opts, "cfrJar", ""));
            o.setIgnoreWhitespace(Json.bool(opts, "ignoreWhitespace", false));
            o.setIgnoreComments(Json.bool(opts, "ignoreComments", false));
            o.setIgnoreRegex(Json.str(opts, "ignoreRegex", ""));
            Object ig = opts.get("ignoreExtensions");
            if (ig instanceof java.util.List) {
                java.util.List<String> exts = new java.util.ArrayList<>();
                for (Object x : (java.util.List<?>) ig) {
                    if (x != null && !String.valueOf(x).trim().isEmpty()) exts.add(String.valueOf(x).trim());
                }
                o.setIgnoreExtensions(exts);
            }
            o.setUnpackNested(Json.bool(opts, "unpackNested", false));
            o.unpackThreads = Json.intv(opts, "unpackThreads", 4);
            o.unpackPerItemTimeoutMs = Json.longv(opts, "unpackPerItemTimeoutMs", 60_000L);
            o.unpackMaxDepth = Json.intv(opts, "unpackMaxDepth", 6);
            o.unpackTotalBytesCap = Json.longv(opts, "unpackTotalBytesCap", 4L * 1024 * 1024 * 1024);
        }
        return o;
    }

    /** 由比对选项派生行级 diff 的忽略规则（正则非法时 DiffRules 内部自动忽略）。 */
    public DiffRules toDiffRules() {
        return DiffRules.of(ignoreWhitespace, ignoreComments, ignoreRegex);
    }

    public ParseConfig toParseConfig() {
        ParseConfig c = new ParseConfig();
        c.setInternalPrefixes(java.util.Arrays.asList(internalPrefixes.split("[,;\\s]+")));
        c.setExpandInternalLib(expandAll);
        c.setMaxEntryBytes(8L * 1024 * 1024);
        c.setIgnoreExtensions(ignoreExtensions);
        return c;
    }
}