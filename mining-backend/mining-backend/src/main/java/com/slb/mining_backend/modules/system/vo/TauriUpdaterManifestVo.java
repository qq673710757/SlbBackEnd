package com.slb.mining_backend.modules.system.vo;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Tauri plugin-updater manifest（v1 compatible）。
 *
 * <p>注意：该接口返回必须是“裸 JSON”，不能包装 ApiResponse。</p>
 */
@Data
@Schema(description = "Tauri plugin-updater manifest（v1 compatible）")
public class TauriUpdaterManifestVo {

    @Schema(description = "最新版本号", example = "0.1.3")
    private String version;

    /**
     * 更新说明（支持多行）。
     */
    @JsonProperty("notes")
    @Schema(description = "更新说明（支持多行）", example = "v0.1.3\\n- 修复若干问题\\n- 性能优化")
    private String notes;

    /**
     * 兼容字段：部分客户端/历史实现使用 note（单数）。这里输出 note=notes。
     */
    @JsonProperty("note")
    @Schema(description = "更新说明（兼容字段，等同于 notes）", example = "v0.1.3\\n- 修复若干问题\\n- 性能优化")
    public String getNote() {
        return notes;
    }

    @JsonProperty("note")
    public void setNote(String note) {
        this.notes = note;
    }

    @JsonProperty("pub_date")
    @Schema(description = "发布时间（ISO8601/RFC3339，建议带 Z）", example = "2026-01-07T21:20:48Z", nullable = true)
    private String pubDate;

    @Schema(description = "按 target 分类的平台信息（key 必须与 Tauri target 匹配，如 windows-x86_64）")
    private Map<String, PlatformInfo> platforms = new LinkedHashMap<>();

    public void putPlatform(String target, String url, String signature) {
        platforms.put(target, new PlatformInfo(url, signature));
    }

    @Data
    @Schema(description = "平台更新信息")
    public static class PlatformInfo {
        @Schema(description = "Windows updater 下载地址（*.msi 或 *.msi.zip；要求精确指向该文件）",
                example = "https://example.com/updates/windows-x86_64/stable/app_0.1.3.msi.zip")
        private String url;

        @Schema(description = "签名：生成的 *.sig 文件内容本身（minisign 文本），不能是 URL/路径，也不能只取第二行签名体",
                example = "SIG_FILE_CONTENTS")
        private String signature;

        public PlatformInfo(String url, String signature) {
            this.url = url;
            this.signature = signature;
        }
    }
}


