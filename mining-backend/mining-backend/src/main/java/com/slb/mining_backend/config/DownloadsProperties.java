package com.slb.mining_backend.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;


/**
 * 客户端安装包/更新包下载目录配置。
 *
 * <p>说明：
 * <ul>
 *   <li>服务端把 {@code baseDir} 映射为一个 HTTP 路径前缀 {@code urlPrefix}（默认 /downloads/）</li>
 *   <li>管理员在版本配置里填写的 {@code downloadUrl} 建议指向该 URL（或 CDN/Nginx 上的真实地址）</li>
 * </ul>
 */
@Component
@ConfigurationProperties(prefix = "app.downloads")
@Data
public class DownloadsProperties {

    /**
     * 是否启用本地下载目录映射。
     */
    private boolean enabled = true;

    /**
     * 服务器本地目录（建议放在 jar 外部，便于更新包无需重新打包后端）。
     */
    private String baseDir = "./app-downloads";

    private String urlPrefix = "/downloads/";
}


