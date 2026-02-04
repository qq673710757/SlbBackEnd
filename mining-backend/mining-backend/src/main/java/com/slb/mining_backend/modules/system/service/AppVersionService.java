package com.slb.mining_backend.modules.system.service;

import com.slb.mining_backend.common.exception.BizException;
import com.slb.mining_backend.modules.admin.dto.AppVersionUpdateDto;
import com.slb.mining_backend.modules.system.entity.AppVersionConfig;
import com.slb.mining_backend.modules.system.mapper.AppVersionConfigMapper;
import com.slb.mining_backend.modules.system.vo.AndroidAppVersionVo;
import com.slb.mining_backend.modules.system.vo.AppVersionCheckVo;
import com.slb.mining_backend.modules.system.vo.TauriUpdaterManifestVo;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.Locale;
import java.util.Optional;

@Service
public class AppVersionService {

    private final AppVersionConfigMapper appVersionConfigMapper;

    public AppVersionService(AppVersionConfigMapper appVersionConfigMapper) {
        this.appVersionConfigMapper = appVersionConfigMapper;
    }

    public void upsert(AppVersionUpdateDto dto) {
        String platform = normalizeKey(dto.getPlatform());
        String channel = normalizeKey(StringUtils.hasText(dto.getChannel()) ? dto.getChannel() : "stable");
        if (!StringUtils.hasText(platform)) {
            throw new BizException(400, "platform 不能为空");
        }
        if (!StringUtils.hasText(dto.getLatestVersion())) {
            throw new BizException(400, "latestVersion 不能为空");
        }

        AppVersionConfig config = new AppVersionConfig();
        config.setPlatform(platform);
        config.setChannel(channel);
        config.setLatestVersion(dto.getLatestVersion().trim());
        config.setMinSupportedVersion(StringUtils.hasText(dto.getMinSupportedVersion()) ? dto.getMinSupportedVersion().trim() : null);
        config.setForceUpdate(Boolean.TRUE.equals(dto.getForceUpdate()));
        config.setDownloadUrl(StringUtils.hasText(dto.getDownloadUrl()) ? dto.getDownloadUrl().trim() : null);
        config.setUpdaterUrl(StringUtils.hasText(dto.getUpdaterUrl()) ? dto.getUpdaterUrl().trim() : null);
        config.setUpdaterSignature(StringUtils.hasText(dto.getUpdaterSignature())
                ? normalizeAndValidateUpdaterSignatureForStorage(dto.getUpdaterSignature())
                : null);
        config.setReleaseNotes(StringUtils.hasText(dto.getReleaseNotes()) ? dto.getReleaseNotes().trim() : null);
        config.setStatus(dto.getStatus() != null ? dto.getStatus() : 1);

        appVersionConfigMapper.upsert(config);
    }

    public AppVersionCheckVo check(String platform, String channel, String currentVersion) {
        String p = normalizeKey(platform);
        String c = normalizeKey(StringUtils.hasText(channel) ? channel : "stable");
        if (!StringUtils.hasText(p)) {
            throw new BizException(400, "platform 不能为空");
        }

        AppVersionCheckVo vo = new AppVersionCheckVo();
        vo.setPlatform(p);
        vo.setChannel(c);
        vo.setCurrentVersion(StringUtils.hasText(currentVersion) ? currentVersion.trim() : null);

        AppVersionConfig cfg = appVersionConfigMapper.findActiveByPlatformChannel(p, c).orElse(null);
        if (cfg == null) {
            vo.setNeedUpdate(false);
            vo.setForceUpdate(false);
            return vo;
        }

        vo.setLatestVersion(cfg.getLatestVersion());
        vo.setMinSupportedVersion(cfg.getMinSupportedVersion());
        vo.setDownloadUrl(cfg.getDownloadUrl());
        vo.setReleaseNotes(cfg.getReleaseNotes());
        vo.setConfigUpdateTime(cfg.getUpdateTime());

        // 未提供版本：只返回配置，不做判定
        if (!StringUtils.hasText(currentVersion) || !StringUtils.hasText(cfg.getLatestVersion())) {
            vo.setNeedUpdate(false);
            vo.setForceUpdate(false);
            return vo;
        }

        boolean needUpdate = com.slb.mining_backend.common.util.VersionUtil.lt(currentVersion, cfg.getLatestVersion());
        boolean belowMin = StringUtils.hasText(cfg.getMinSupportedVersion())
                && com.slb.mining_backend.common.util.VersionUtil.lt(currentVersion, cfg.getMinSupportedVersion());
        boolean forceUpdate = needUpdate && (Boolean.TRUE.equals(cfg.getForceUpdate()) || belowMin);

        vo.setNeedUpdate(needUpdate);
        vo.setForceUpdate(forceUpdate);
        return vo;
    }

    /**
     * Android app version info for update checks.
     * The build number is parsed from latestVersion using the "1.2.0+11" format.
     */
    public Optional<AndroidAppVersionVo> getAndroidAppVersion() {
        AppVersionConfig cfg = appVersionConfigMapper.findActiveByPlatformChannel("android", "stable").orElse(null);
        if (cfg == null || !StringUtils.hasText(cfg.getLatestVersion())) {
            return Optional.empty();
        }

        VersionParts parts = parseVersionAndBuild(cfg.getLatestVersion());
        AndroidAppVersionVo vo = new AndroidAppVersionVo();
        vo.setVersion(parts.version());
        vo.setBuildNumber(parts.buildNumber());
        vo.setForceUpdate(Boolean.TRUE.equals(cfg.getForceUpdate()));
        vo.setDownloadUrl(StringUtils.hasText(cfg.getDownloadUrl()) ? cfg.getDownloadUrl().trim() : "");
        vo.setDescription(StringUtils.hasText(cfg.getReleaseNotes()) ? cfg.getReleaseNotes().trim() : "");
        return Optional.of(vo);
    }

    /**
     * 生成 Tauri plugin-updater 所需的 manifest。
     *
     * <p>返回规则：
     * <ul>
     *   <li>无配置或无需更新：Optional.empty()（Controller 可返回 204）</li>
     *   <li>需要更新但缺少 updaterUrl/updaterSignature：抛 BizException(409) 触发客户端兜底</li>
     * </ul>
     */
    public Optional<TauriUpdaterManifestVo> buildTauriUpdaterManifest(String target,
                                                                     String channel,
                                                                     String currentVersion) {
        String t = normalizeKey(target);
        String c = normalizeKey(StringUtils.hasText(channel) ? channel : "stable");
        if (!StringUtils.hasText(t)) {
            throw new BizException(400, "target 不能为空");
        }

        AppVersionConfig cfg = findUpdaterConfigByTargetOrPlatform(t, c).orElse(null);
        if (cfg == null || !StringUtils.hasText(cfg.getLatestVersion())) {
            return Optional.empty();
        }

        // 注意：plugin-updater 的 static JSON / dynamic server 逻辑是“服务端始终返回 latest manifest”，
        // 客户端自己比较 version 决定是否更新。
        // 因此这里不再返回 204（无 JSON body），避免客户端报：Could not fetch a valid release JSON.

        // 需要更新：必须具备 updaterUrl & updaterSignature
        if (!StringUtils.hasText(cfg.getUpdaterUrl()) || !StringUtils.hasText(cfg.getUpdaterSignature())) {
            throw new BizException(409, "updater 产物未配置（updaterUrl/updaterSignature）");
        }

        // Windows：要求 URL 精确指向真实的 updater 文件（路径+文件名完全一致）
        validateUpdaterUrl(t, cfg.getUpdaterUrl());

        // Tauri 示例通常使用 RFC3339 + Z；这里统一输出 UTC 的 ISO_INSTANT（带 Z），避免 +08:00 等偏移导致的兼容性差异
        String pubDate = null;
        if (cfg.getUpdateTime() != null) {
            Instant instant = cfg.getUpdateTime().atZone(ZoneId.of("Asia/Shanghai")).toInstant();
            pubDate = DateTimeFormatter.ISO_INSTANT.format(instant.atOffset(ZoneOffset.UTC));
        }

        TauriUpdaterManifestVo vo = new TauriUpdaterManifestVo();
        vo.setVersion(cfg.getLatestVersion());
        String notes = StringUtils.hasText(cfg.getReleaseNotes()) ? cfg.getReleaseNotes() : "";
        vo.setNotes(notes);
        vo.setPubDate(pubDate);

        // Tauri plugin-updater 要求：manifest.signature 是 ".sig 文件内容的 Base64"（再由客户端解码）。
        SigPayload sig = normalizeAndValidateUpdaterSignature(cfg.getUpdaterSignature());
        validateSigFileHintIfPresent(t, cfg.getUpdaterUrl(), sig.sigText());
        String signatureBase64 = sig.sigBase64();
        if (!StringUtils.hasText(signatureBase64)) {
            signatureBase64 = Base64.getEncoder().encodeToString(sig.sigText().getBytes(StandardCharsets.UTF_8));
        }
        vo.putPlatform(t, cfg.getUpdaterUrl().trim(), signatureBase64);
        return Optional.of(vo);
    }

    private void validateUpdaterUrl(String normalizedTarget, String updaterUrl) {
        if (!StringUtils.hasText(updaterUrl)) return;
        String u = updaterUrl.trim();
        // 生产环境强制 https（避免 plugin-updater 拒绝）
        if (u.startsWith("http://")) {
            throw new BizException(409, "updaterUrl 必须为 HTTPS");
        }
        // 对 windows* target：允许 *.msi 或 *.msi.zip（取决于你的发布方式/构建产物）
        if (StringUtils.hasText(normalizedTarget) && normalizedTarget.toLowerCase().startsWith("windows")) {
            String fileName = extractFileNameFromUrl(u);
            String fileNameLower = StringUtils.hasText(fileName) ? fileName.toLowerCase(Locale.ROOT) : "";
            boolean ok = fileNameLower.endsWith(".msi") || fileNameLower.endsWith(".msi.zip");
            if (!ok) {
                throw new BizException(409, "updaterUrl 必须精确指向 *.msi 或 *.msi.zip（不能是 .exe / 页面链接）");
            }
        }
    }

    private boolean isStandardBase64NoWhitespace(String base64) {
        if (!StringUtils.hasText(base64)) return false;
        // 只允许标准 base64 字符集；不允许空白、'-' '_' 等（保证“单行 Base64”且可被客户端严格解码）
        return base64.matches("^[A-Za-z0-9+/=]+$");
    }

    /**
     * 将输入规范化为 “.sig 文件内容本身（minisign 文本）”，并做校验：
     * - 必须包含 minisign 结构（至少包含 untrusted/trusted comment）
     *
     * <p>允许两种输入：
     * <ul>
     *   <li>直接传 .sig 文件内容（推荐，minisign 原文，含换行）</li>
     *   <li>传 “.sig 文件内容的 Base64”（兼容旧数据/旧接口；服务端会先解码成 minisign 文本再入库/下发）</li>
     * </ul>
     */
    private String normalizeAndValidateUpdaterSignatureForStorage(String input) {
        return normalizeAndValidateUpdaterSignature(input).sigText();
    }

    private SigPayload normalizeAndValidateUpdaterSignature(String input) {
        if (!StringUtils.hasText(input)) {
            throw new BizException(409, "signature must be the .sig file contents (or its base64)");
        }
        String sig = input.trim();
        // 去掉 UTF-8 BOM/零宽字符（Windows/某些编辑器复制文本时可能带）
        sig = sig.replace("\uFEFF", "").replace("\u200B", "");

        // 1) 优先识别 minisign 原文（包含 comment 标识）
        //    重要：不能提取第二行“签名体”，必须保留完整 minisign 文本（含 untrusted/trusted comment）。
        if (containsMinisignMarkers(sig)) {
            String sigText = normalizeMinisignText(sig);
            assertLooksLikeMinisign(sigText);
            return new SigPayload("", sigText);
        }

        // 2) 否则：兼容旧数据/旧接口（存的是 “.sig 文件内容的 Base64”）
        //    兼容：Base64 被换行/空格分割 -> 先去除所有空白
        String compacted = removeAllWhitespace(sig);
        if (!StringUtils.hasText(compacted)) {
            throw new BizException(409, "signature must be the .sig file contents (or its base64)");
        }
        String decodedText = decodeSigBase64ToUtf8TextOrThrow(compacted);
        assertLooksLikeMinisign(decodedText);
        return new SigPayload(compacted, normalizeMinisignText(decodedText));
    }

    private boolean containsMinisignMarkers(String s) {
        if (!StringUtils.hasText(s)) return false;
        String lower = s.toLowerCase(Locale.ROOT);
        return lower.contains("untrusted comment:") || lower.contains("trusted comment:");
    }

    private String normalizeMinisignText(String s) {
        if (!StringUtils.hasText(s)) return s;
        String t = s;
        // 兼容“字面量 \\n / \\r\\n”被保存或传入的情况（例如某些管理端把换行转义成两个字符）
        t = t.replace("\\r\\n", "\n").replace("\\n", "\n").replace("\\r", "\n");
        // 统一 Windows 换行
        t = t.replace("\r\n", "\n").replace("\r", "\n");
        return t;
    }

    private String removeAllWhitespace(String s) {
        if (!StringUtils.hasText(s)) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (Character.isWhitespace(ch)) continue;
            sb.append(ch);
        }
        return sb.toString();
    }

    private String decodeSigBase64ToUtf8TextOrThrow(String base64) {
        final byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
            throw new BizException(409, "signature must be the .sig file contents (or its base64)");
        }

        CharsetDecoder decoder = StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            return decoder.decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw new BizException(409, "signature must be the .sig file contents (or its base64)");
        }
    }

    private void assertLooksLikeMinisign(String decodedText) {
        if (!StringUtils.hasText(decodedText)) {
            throw new BizException(409, "signature must be the .sig file contents (or its base64)");
        }

        // 关键验收点（Tauri 2）：Base64 解码后必须是“完整 minisign 文本”，包含 untrusted/trusted comment 行。
        // 否则客户端会报：Invalid encoding in minisign data / verify 失败。
        String normalized = decodedText
                .replace("\uFEFF", "").replace("\u200B", "")
                .replace("\r\n", "\n").replace("\r", "\n");
        String lower = normalized.toLowerCase(Locale.ROOT);
        if (!lower.contains("untrusted comment:") || !lower.contains("trusted comment:")) {
            throw new BizException(409, "signature must be the .sig file contents (or its base64)");
        }

        // 进一步校验结构：
        // untrusted comment 行 -> (下一非空行) 签名体 -> trusted comment 行 -> (下一非空行) 全局签名
        String[] lines = normalized.split("\n");
        int untrustedIdx = -1;
        int trustedIdx = -1;
        for (int i = 0; i < lines.length; i++) {
            String l = lines[i] == null ? "" : lines[i].replace("\uFEFF", "").replace("\u200B", "").trim();
            String ll = l.toLowerCase(Locale.ROOT);
            if (untrustedIdx < 0 && ll.startsWith("untrusted comment:")) {
                untrustedIdx = i;
                continue;
            }
            if (untrustedIdx >= 0 && ll.startsWith("trusted comment:")) {
                trustedIdx = i;
                break;
            }
        }
        if (untrustedIdx < 0 || trustedIdx < 0 || trustedIdx <= untrustedIdx) {
            throw new BizException(409, "signature must be the .sig file contents (or its base64)");
        }

        String sigBodyLine = nextNonEmptyLine(lines, untrustedIdx + 1);
        String globalSigLine = nextNonEmptyLine(lines, trustedIdx + 1);
        if (!looksLikeMinisignBase64Line(sigBodyLine) || !looksLikeMinisignBase64Line(globalSigLine)) {
            throw new BizException(409, "signature must be the .sig file contents (or its base64)");
        }
    }

    private record SigPayload(String sigBase64, String sigText) {}

    private String nextNonEmptyLine(String[] lines, int startIdx) {
        if (lines == null) return "";
        for (int i = Math.max(0, startIdx); i < lines.length; i++) {
            String l = lines[i] == null ? "" : lines[i].replace("\uFEFF", "").replace("\u200B", "").trim();
            if (!l.isEmpty()) return l;
        }
        return "";
    }

    private boolean looksLikeMinisignBase64Line(String s) {
        if (!StringUtils.hasText(s)) return false;
        String t = s.trim();
        // minisign 的签名体/全局签名通常是较长的 Base64 token；这里不校验具体长度/算法，只做格式与最小长度保护。
        return t.length() >= 16 && isStandardBase64NoWhitespace(t);
    }

    /**
     * 若 sig 文本中带 trusted comment 的 file: 提示，则做一次“误签文件”保护：
     * - 若能从 trusted comment 解析到 file:xxx，则要求 xxx 与 updaterUrl 的文件名完全一致
     *   （对 URL 文件名做一次 percent decode 以兼容中文/特殊字符）。
     */
    private void validateSigFileHintIfPresent(String normalizedTarget, String updaterUrl, String normalizedSigText) {
        if (!StringUtils.hasText(normalizedTarget) || !normalizedTarget.toLowerCase().startsWith("windows")) return;
        if (!StringUtils.hasText(updaterUrl)) return;
        if (!StringUtils.hasText(normalizedSigText)) return;

        boolean foundFileHint = false;
        String[] lines = normalizedSigText.replace("\r\n", "\n").replace("\r", "\n").split("\n");
        for (String line : lines) {
            if (line == null) continue;
            String l = line.replace("\uFEFF", "").replace("\u200B", "").trim();
            if (!l.toLowerCase(Locale.ROOT).startsWith("trusted comment:")) continue;
            int idx = l.indexOf("file:");
            if (idx < 0) continue;
            String fileHint = l.substring(idx + "file:".length()).trim();
            if (!StringUtils.hasText(fileHint)) continue;
            foundFileHint = true;

            String urlFileNameRaw = extractFileNameFromUrl(updaterUrl.trim());
            if (!StringUtils.hasText(urlFileNameRaw)) continue;

            String hintFileNameRaw = extractFileNameFromPathLike(fileHint);
            String hintFileNameDecoded = decodePercentEncodedUtf8(hintFileNameRaw);
            String urlFileNameDecoded = decodePercentEncodedUtf8(urlFileNameRaw);

            // 要求 “签名提示的 file” 与 “URL 文件名” 完全一致（decode 后比对）
            if (!hintFileNameDecoded.equals(urlFileNameDecoded)) {
                throw new BizException(409, "updaterSignature 的 trusted comment file 与 updaterUrl 文件名不一致："
                        + " file: " + hintFileNameRaw + " vs url: " + urlFileNameRaw
                        + "（请确认 latest.json / manifest 里的 url 精确指向被签名的同一个文件）");
            }
        }

        // 为了“必须保证 signature 对应这个文件”，Windows 场景强制要求 minisign 的 trusted comment 中带 file:xxx。
        // 没有 file: 时无法做“签名对应文件名”的硬校验。
        if (!foundFileHint) {
            throw new BizException(409, "updaterSignature 的 trusted comment 必须包含 file:<filename>（用于校验签名对应文件名；避免误用其它产物的 .sig）");
        }
    }

    private String extractFileNameFromUrl(String url) {
        if (!StringUtils.hasText(url)) return "";
        String u = url.trim();
        int q = u.indexOf('?');
        int h = u.indexOf('#');
        int cut = -1;
        if (q >= 0 && h >= 0) cut = Math.min(q, h);
        else if (q >= 0) cut = q;
        else if (h >= 0) cut = h;
        String noQuery = cut >= 0 ? u.substring(0, cut) : u;
        int slash = Math.max(noQuery.lastIndexOf('/'), noQuery.lastIndexOf('\\'));
        return slash >= 0 ? noQuery.substring(slash + 1) : noQuery;
    }

    private String extractFileNameFromPathLike(String s) {
        if (!StringUtils.hasText(s)) return "";
        String t = s.trim();
        int slash = Math.max(t.lastIndexOf('/'), t.lastIndexOf('\\'));
        return slash >= 0 ? t.substring(slash + 1) : t;
    }

    /**
     * 对 URL path segment 的 percent-encoding 做 UTF-8 decode（不会把 '+' 当空格）。
     */
    private String decodePercentEncodedUtf8(String s) {
        if (!StringUtils.hasText(s) || s.indexOf('%') < 0) return s;
        ByteArrayOutputStream out = new ByteArrayOutputStream(s.length());
        for (int i = 0; i < s.length(); ) {
            char ch = s.charAt(i);
            if (ch == '%' && i + 2 < s.length()) {
                int hi = hexVal(s.charAt(i + 1));
                int lo = hexVal(s.charAt(i + 2));
                if (hi >= 0 && lo >= 0) {
                    out.write((hi << 4) + lo);
                    i += 3;
                    continue;
                }
            }
            byte[] bytes = String.valueOf(ch).getBytes(StandardCharsets.UTF_8);
            out.writeBytes(bytes);
            i++;
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private int hexVal(char ch) {
        if (ch >= '0' && ch <= '9') return ch - '0';
        if (ch >= 'a' && ch <= 'f') return 10 + (ch - 'a');
        if (ch >= 'A' && ch <= 'F') return 10 + (ch - 'A');
        return -1;
    }

    private Optional<AppVersionConfig> findUpdaterConfigByTargetOrPlatform(String normalizedTarget, String normalizedChannel) {
        // 1) 优先用 target 作为 platform 直查（允许为不同 arch/target 配不同记录，例如 windows-x86_64）
        Optional<AppVersionConfig> byTarget = appVersionConfigMapper.findActiveByPlatformChannel(normalizedTarget, normalizedChannel);
        if (byTarget.isPresent()) return byTarget;

        // 2) 兜底：target -> platform 映射（windows-x86_64 -> windows; darwin-aarch64 -> mac; linux-x86_64 -> linux）
        String platform = mapTauriTargetToPlatform(normalizedTarget);
        if (!platform.equals(normalizedTarget)) {
            return appVersionConfigMapper.findActiveByPlatformChannel(platform, normalizedChannel);
        }
        return Optional.empty();
    }

    private String mapTauriTargetToPlatform(String target) {
        if (!StringUtils.hasText(target)) return "";
        String head = target.split("-", 2)[0];
        if ("darwin".equalsIgnoreCase(head)) return "mac";
        return head.toLowerCase();
    }

    private String normalizeKey(String s) {
        if (!StringUtils.hasText(s)) return "";
        return s.trim().toLowerCase();
    }

    private record VersionParts(String version, int buildNumber) {}

    private VersionParts parseVersionAndBuild(String latestVersion) {
        String raw = latestVersion == null ? "" : latestVersion.trim();
        if (raw.startsWith("v") || raw.startsWith("V")) {
            raw = raw.substring(1).trim();
        }

        String version = raw;
        Integer buildNumber = null;

        int plusIdx = raw.indexOf('+');
        if (plusIdx >= 0) {
            version = raw.substring(0, plusIdx).trim();
            buildNumber = parseLeadingInt(raw.substring(plusIdx + 1).trim());
        } else {
            int leftParen = raw.lastIndexOf('(');
            int rightParen = raw.endsWith(")") ? raw.length() - 1 : -1;
            if (leftParen >= 0 && rightParen > leftParen) {
                Integer parsed = parseLeadingInt(raw.substring(leftParen + 1, rightParen).trim());
                if (parsed != null) {
                    buildNumber = parsed;
                    version = raw.substring(0, leftParen).trim();
                }
            }
        }

        int build = (buildNumber != null && buildNumber > 0) ? buildNumber : 1;
        String normalizedVersion = StringUtils.hasText(version) ? version : "0.0.0";
        return new VersionParts(normalizedVersion, build);
    }

    private Integer parseLeadingInt(String s) {
        if (!StringUtils.hasText(s)) return null;
        int i = 0;
        while (i < s.length() && Character.isDigit(s.charAt(i))) {
            i++;
        }
        if (i == 0) return null;
        try {
            return Integer.parseInt(s.substring(0, i));
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}


