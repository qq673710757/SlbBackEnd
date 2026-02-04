package com.slb.mining_backend.common.util;

import org.springframework.lang.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 版本号比较工具（近似 semver，兼容常见的 "v1.2.3" / "1.2.3-beta" / "1.2" 等形式）。
 *
 * 约定：
 * - build metadata（+xxxx）忽略；
 * - pre-release（-alpha.1）低于同 core 的正式版本；
 * - core 段按 "." 拆分，数值段按整数比较，缺失段视为 0；
 */
public final class VersionUtil {

    private VersionUtil() {
    }

    /**
     * 比较两个版本号。
     *
     * @return -1 表示 v1 < v2；0 表示相等；1 表示 v1 > v2
     */
    public static int compare(@Nullable String v1, @Nullable String v2) {
        String a = normalize(v1);
        String b = normalize(v2);
        if (a.isEmpty() && b.isEmpty()) return 0;
        if (a.isEmpty()) return -1;
        if (b.isEmpty()) return 1;

        ParsedVersion pa = ParsedVersion.parse(a);
        ParsedVersion pb = ParsedVersion.parse(b);

        int coreCmp = compareCore(pa.coreParts, pb.coreParts);
        if (coreCmp != 0) return coreCmp;

        // core 相同：无 pre-release 的更大（正式版 > 预发布）
        if (pa.preReleaseParts.isEmpty() && pb.preReleaseParts.isEmpty()) return 0;
        if (pa.preReleaseParts.isEmpty()) return 1;
        if (pb.preReleaseParts.isEmpty()) return -1;

        return comparePreRelease(pa.preReleaseParts, pb.preReleaseParts);
    }

    public static boolean lt(@Nullable String v1, @Nullable String v2) {
        return compare(v1, v2) < 0;
    }

    public static boolean lte(@Nullable String v1, @Nullable String v2) {
        return compare(v1, v2) <= 0;
    }

    public static boolean gt(@Nullable String v1, @Nullable String v2) {
        return compare(v1, v2) > 0;
    }

    public static boolean gte(@Nullable String v1, @Nullable String v2) {
        return compare(v1, v2) >= 0;
    }

    private static String normalize(@Nullable String v) {
        if (v == null) return "";
        String s = v.trim();
        if (s.isEmpty()) return "";
        if (s.startsWith("v") || s.startsWith("V")) {
            s = s.substring(1).trim();
        }
        return s;
    }

    private static int compareCore(List<Part> a, List<Part> b) {
        int max = Math.max(a.size(), b.size());
        for (int i = 0; i < max; i++) {
            Part pa = i < a.size() ? a.get(i) : Part.numeric(0);
            Part pb = i < b.size() ? b.get(i) : Part.numeric(0);
            int c = pa.compareCore(pb);
            if (c != 0) return c;
        }
        return 0;
    }

    /**
     * 预发布比较（近似 semver 规则）：
     * - 逐段比较；
     * - 数字段按整数比较；字符串段按字典序；
     * - 数字段 < 字符段；
     * - 若前面都相同，段数少的更小（alpha < alpha.1）。
     */
    private static int comparePreRelease(List<Part> a, List<Part> b) {
        int max = Math.max(a.size(), b.size());
        for (int i = 0; i < max; i++) {
            if (i >= a.size()) return -1;
            if (i >= b.size()) return 1;
            int c = a.get(i).comparePreRelease(b.get(i));
            if (c != 0) return c;
        }
        return 0;
    }

    private record ParsedVersion(List<Part> coreParts, List<Part> preReleaseParts) {
        static ParsedVersion parse(String raw) {
            // ignore build metadata
            String noBuild = raw.split("\\+", 2)[0];
            String[] coreAndPre = noBuild.split("-", 2);
            String core = coreAndPre[0];
            String pre = coreAndPre.length > 1 ? coreAndPre[1] : null;

            List<Part> coreParts = splitParts(core, "\\.");
            List<Part> preParts = pre == null ? List.of() : splitParts(pre, "\\.");
            return new ParsedVersion(coreParts, preParts);
        }
    }

    private static List<Part> splitParts(String raw, String delimiterRegex) {
        List<Part> parts = new ArrayList<>();
        if (raw == null || raw.isEmpty()) return parts;
        String[] tokens = raw.split(delimiterRegex);
        for (String t : tokens) {
            if (t == null || t.isEmpty()) continue;
            parts.add(Part.parse(t));
        }
        return parts;
    }

    private record Part(@Nullable Integer number, @Nullable String text) {
        static Part parse(String token) {
            try {
                // 只要是纯数字就按数值比较，否则按字符串
                if (token.matches("\\d+")) {
                    // 避免超大数溢出：超过 int 直接按字符串
                    if (token.length() > 9) {
                        return new Part(null, token);
                    }
                    return numeric(Integer.parseInt(token));
                }
            } catch (Exception ignored) {
            }
            return text(token);
        }

        static Part numeric(int n) {
            return new Part(n, null);
        }

        static Part text(String s) {
            return new Part(null, s);
        }

        int compareCore(Part other) {
            // core 段：优先数值比较；否则字典序
            if (this.number != null && other.number != null) {
                return Integer.compare(this.number, other.number);
            }
            if (this.number != null) return 1;     // 数值段 > 文本段（兼容非常规 core）
            if (other.number != null) return -1;
            String a = this.text == null ? "" : this.text;
            String b = other.text == null ? "" : other.text;
            return a.compareToIgnoreCase(b);
        }

        int comparePreRelease(Part other) {
            if (this.number != null && other.number != null) {
                return Integer.compare(this.number, other.number);
            }
            // semver: numeric < non-numeric
            if (this.number != null) return -1;
            if (other.number != null) return 1;
            String a = this.text == null ? "" : this.text;
            String b = other.text == null ? "" : other.text;
            return a.compareToIgnoreCase(b);
        }
    }
}


