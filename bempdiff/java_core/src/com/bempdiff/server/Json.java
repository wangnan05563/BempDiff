package com.bempdiff.server;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 零依赖的极简 JSON 读写器（用于 Web UI 的 REST 接口，避免引入第三方 JSON 库）。
 * 仅覆盖本服务所需子集：对象/数组/字符串/数字/布尔/null 的互转。
 */
public final class Json {

    private Json() {
    }

    /** JSON 解析/写入异常：替代通用 RuntimeException，便于上游精确捕获与日志定位。 */
    private static final class JsonParseException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        JsonParseException(String msg) {
            super(msg);
        }
    }

    // ---------- 写 ----------

    public static String write(Object o) {
        StringBuilder sb = new StringBuilder();
        writeValue(sb, o);
        return sb.toString();
    }

    private static void writeValue(StringBuilder sb, Object o) {
        if (o == null) {
            sb.append("null");
        } else if (o instanceof String) {
            writeString(sb, (String) o);
        } else if (o instanceof Number) {
            sb.append(o.toString());
        } else if (o instanceof Boolean) {
            sb.append(o.toString());
        } else if (o instanceof Map) {
            writeObject(sb, (Map<?, ?>) o);
        } else if (o instanceof Iterable) {
            writeArray(sb, (Iterable<?>) o);
        } else {
            writeString(sb, String.valueOf(o));
        }
    }

    private static void writeObject(StringBuilder sb, Map<?, ?> map) {
        sb.append('{');
        boolean first = true;
        for (Map.Entry<?, ?> e : map.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            writeString(sb, String.valueOf(e.getKey()));
            sb.append(':');
            writeValue(sb, e.getValue());
        }
        sb.append('}');
    }

    private static void writeArray(StringBuilder sb, Iterable<?> it) {
        sb.append('[');
        boolean first = true;
        for (Object v : it) {
            if (!first) sb.append(',');
            first = false;
            writeValue(sb, v);
        }
        sb.append(']');
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        sb.append('"');
    }

    // ---------- 读 ----------

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String s) {
        Object o = parse(s);
        return (o instanceof Map) ? (Map<String, Object>) o : new LinkedHashMap<>();
    }

    /**
     * 严格对象解析：根节点必须是 JSON 对象，且解析后不能有尾随字符。
     * 用于审阅 PUT 等“非对象 body 不能被当成空文档”的入口；普通兼容入口仍走 parseObject。
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObjectStrict(String s) {
        Object o;
        try {
            o = parseStrict(s);
        } catch (JsonParseException e) {
            // 审阅等 HTTP 入口把格式错误归为客户端 400，而不是外层兜底 500。
            throw new IllegalArgumentException(e.getMessage(), e);
        }
        if (!(o instanceof Map)) throw new IllegalArgumentException("JSON 根节点必须是对象");
        return (Map<String, Object>) o;
    }

    public static Object parse(String s) {
        Parser p = new Parser(s);
        p.skipWs();
        Object v = p.parseValue();
        p.skipWs();
        return v;
    }

    public static Object parseStrict(String s) {
        Parser p = new Parser(s);
        p.skipWs();
        if (p.i >= p.s.length()) throw new JsonParseException("JSON 为空");
        Object v = p.parseValue();
        p.skipWs();
        if (p.i < p.s.length()) throw new JsonParseException("JSON 尾部存在多余字符");
        return v;
    }

    private static final class Parser {
        final String s;
        int i;

        Parser(String s) {
            this.s = s;
            this.i = 0;
        }

        void skipWs() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        }

        Object parseValue() {
            skipWs();
            if (i >= s.length()) throw new JsonParseException("JSON 意外结束");
            char c = s.charAt(i);
            if (c == '{') return parseObject();
            if (c == '[') return parseArray();
            if (c == '"') return parseString();
            if (c == 't' || c == 'f') return parseBool();
            if (c == 'n') {
                i += 4;
                return null;
            }
            return parseNumber();
        }

        Map<String, Object> parseObject() {
            Map<String, Object> m = new LinkedHashMap<>();
            i++;
            skipWs();
            if (i < s.length() && s.charAt(i) == '}') {
                i++;
                return m;
            }
            while (i < s.length() && s.charAt(i) != '}') {
                skipWs();
                String key = parseString();
                skipWs();
                if (i >= s.length() || s.charAt(i) != ':') throw new JsonParseException("JSON 期望 ':'");
                i++;
                m.put(key, parseValue());
                skipWs();
                if (i < s.length() && s.charAt(i) == ',') i++;
            }
            if (i < s.length() && s.charAt(i) == '}') i++;
            return m;
        }

        List<Object> parseArray() {
            List<Object> a = new ArrayList<>();
            i++;
            skipWs();
            if (i < s.length() && s.charAt(i) == ']') {
                i++;
                return a;
            }
            while (i < s.length() && s.charAt(i) != ']') {
                a.add(parseValue());
                skipWs();
                if (i < s.length() && s.charAt(i) == ',') i++;
            }
            if (i < s.length() && s.charAt(i) == ']') i++;
            return a;
        }

        String parseString() {
            if (s.charAt(i) != '"') throw new JsonParseException("JSON 期望 '\"'");
            i++;
            StringBuilder sb = new StringBuilder();
            while (i < s.length()) {
                char c = s.charAt(i++);
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    char e = s.charAt(i++);
                    switch (e) {
                        case '"': sb.append('"'); break;
                        case '\\': sb.append('\\'); break;
                        case '/': sb.append('/'); break;
                        case 'n': sb.append('\n'); break;
                        case 't': sb.append('\t'); break;
                        case 'r': sb.append('\r'); break;
                        case 'b': sb.append('\b'); break;
                        case 'f': sb.append('\f'); break;
                        case 'u':
                            String hex = s.substring(i, i + 4);
                            i += 4;
                            sb.append((char) Integer.parseInt(hex, 16));
                            break;
                        default:
                            sb.append(e);
                    }
                } else {
                    sb.append(c);
                }
            }
            throw new JsonParseException("JSON 字符串未闭合");
        }

        Object parseNumber() {
            int start = i;
            while (i < s.length()) {
                char c = s.charAt(i);
                if ("0123456789+-.eE".indexOf(c) >= 0) i++;
                else break;
            }
            String num = s.substring(start, i);
            if (num.contains(".") || num.contains("e") || num.contains("E")) return Double.parseDouble(num);
            try {
                return Long.parseLong(num);
            } catch (NumberFormatException ex) {
                return Double.parseDouble(num);
            }
        }

        boolean parseBool() {
            if (s.startsWith("true", i)) {
                i += 4;
                return true;
            }
            if (s.startsWith("false", i)) {
                i += 5;
                return false;
            }
            throw new JsonParseException("JSON 期望 true/false");
        }
    }

    // ---------- 便捷取值 ----------

    public static String str(Map<String, Object> m, String k, String def) {
        Object v = m.get(k);
        return (v == null) ? def : String.valueOf(v);
    }

    public static boolean bool(Map<String, Object> m, String k, boolean def) {
        Object v = m.get(k);
        if (v == null) return def;
        if (v instanceof Boolean) return (Boolean) v;
        return Boolean.parseBoolean(String.valueOf(v));
    }

    public static int intv(Map<String, Object> m, String k, int def) {
        Object v = m.get(k);
        if (v == null) return def;
        if (v instanceof Number) return ((Number) v).intValue();
        try {
            return Integer.parseInt(String.valueOf(v));
        } catch (NumberFormatException ex) {
            return def;
        }
    }

    public static long longv(Map<String, Object> m, String k, long def) {
        Object v = m.get(k);
        if (v == null) return def;
        if (v instanceof Number) return ((Number) v).longValue();
        try {
            return Long.parseLong(String.valueOf(v));
        } catch (NumberFormatException ex) {
            return def;
        }
    }
}
