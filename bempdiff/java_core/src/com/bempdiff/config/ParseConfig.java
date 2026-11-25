package com.bempdiff.config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** 解析配置（§5.1 / FR9.5）。对应 prototype: INTERNAL_PREFIXES 等默认值。 */
public final class ParseConfig {
    /** 单条目字节上限的唯一定义来源：解析层(PackageParser)、解包层(NestedUnpacker)共用，避免三层各设上限导致
     *  "顶层 >64MB 的 war/zip 被解析丢弃、而嵌套层级却允许 256MB" 的口径分裂。默认 256MB，
     *  足以容纳多层嵌套中的 webapp WAR。 */
    public static final long DEFAULT_ENTRY_CAP_BYTES = 256L * 1024 * 1024;
    /** 忽略的扩展名集合（小写、含点如 ".log"）。命中的条目在解析收集阶段直接跳过，不参与差异比对。 */
    private List<String> ignoreExtensions = new ArrayList<>();
    /** 命中这些前缀的 lib jar 视为内部业务码，展开其 class 为 L1（§5.2.1） */
    private List<String> internalPrefixes = Arrays.asList("com/", "cn/");
    /** L1：是否展开内部 lib（默认 true） */
    private boolean expandInternalLib = true;
    /** L2：是否展开第三方 lib（默认 false，仅 jar 级） */
    private boolean expandThirdPartyLib = false;
    /** 单条目大小上限，超过则跳过内容读取（防 OOM）。默认值与 DEFAULT_ENTRY_CAP_BYTES 对齐。 */
    private long maxEntryBytes = DEFAULT_ENTRY_CAP_BYTES;

    public List<String> getInternalPrefixes() {
        return internalPrefixes;
    }

    public void setInternalPrefixes(List<String> internalPrefixes) {
        this.internalPrefixes = internalPrefixes;
    }

    public boolean isExpandInternalLib() {
        return expandInternalLib;
    }

    public void setExpandInternalLib(boolean v) {
        this.expandInternalLib = v;
    }

    public boolean isExpandThirdPartyLib() {
        return expandThirdPartyLib;
    }

    public void setExpandThirdPartyLib(boolean v) {
        this.expandThirdPartyLib = v;
    }

    public long getMaxEntryBytes() {
        return maxEntryBytes;
    }

    public void setMaxEntryBytes(long v) {
        this.maxEntryBytes = v;
    }

    public List<String> getIgnoreExtensions() {
        return ignoreExtensions;
    }

    public void setIgnoreExtensions(List<String> ignoreExtensions) {
        this.ignoreExtensions = ignoreExtensions == null ? new ArrayList<>() : ignoreExtensions;
    }

    /**
     * 判定 entry key 是否命中某忽略扩展名（比对级过滤的唯一权威入口，供 PackageParser /
     * FolderParser / ArchiveTree / NestedUnpacker 复用）。大小写不敏感；用「小写并以忽略项结尾」
     * 匹配，天然兼容多段扩展名（如 .min.js 命中 foo.min.js），也兼容 .js 命中任意 .js。
     * @param ignores 忽略扩展名字符串集合（可带/不带前导点，如 ".log" / "properties"）
     */
    public static boolean ignoredExt(String key, java.util.List<String> ignores) {
        if (ignores == null || ignores.isEmpty() || key == null) return false;
        String k = key.toLowerCase();
        for (String e : ignores) {
            if (e != null && !e.isEmpty()) {
                String norm = e.startsWith(".") ? e.toLowerCase() : "." + e.toLowerCase();
                // 忽略纯点项（norm 仅可能为 "." 或非空串），命中忽略扩展名才返回
                if (!norm.isEmpty() && !".".equals(norm) && k.endsWith(norm)) {
                    return true;
                }
            }
        }
        return false;
    }
}
