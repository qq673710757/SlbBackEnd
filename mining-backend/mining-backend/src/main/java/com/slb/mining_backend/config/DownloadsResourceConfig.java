package com.slb.mining_backend.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;


/**
 * 将服务器本地目录映射为静态资源下载路径（默认 /downloads/**）。
 *
 *
 *
 * <p>用于承载客户端安装包/更新包等大文件。推荐把文件放在 jar 外部目录，
 * <p>
 * 仅通过配置切换目录，避免每次更新都重新打包后端。</p>
 */
@Configuration
public class DownloadsResourceConfig implements WebMvcConfigurer {

    private final DownloadsProperties downloadsProperties;

    public DownloadsResourceConfig(DownloadsProperties downloadsProperties) {
        this.downloadsProperties = downloadsProperties;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {

        if (!downloadsProperties.isEnabled()) {
            return;
        }

        String baseDir = downloadsProperties.getBaseDir();
        if (!StringUtils.hasText(baseDir)) {
            return;
        }

        String prefix = normalizePrefix(downloadsProperties.getUrlPrefix());
        String resourceHandler = prefix + "**";

        Path dir = Paths.get(baseDir).toAbsolutePath().normalize();
        String location = dir.toUri().toString();
        if (!location.endsWith("/")) {
            location = location + "/";
        }

        registry.addResourceHandler(resourceHandler)
                .addResourceLocations(location)
                // 安装包通常以版本号命名,允许适度缓存,减少宽带压力
                .setCacheControl(CacheControl.maxAge(1, TimeUnit.HOURS).cachePublic());
    }

    private String normalizePrefix(String raw) {
        String p = StringUtils.hasText(raw) ? raw.trim() : "/downloads/";
        if (!p.startsWith("/")) {
            p = "/" + p;
        }
        if (!p.endsWith("/")) {
            p = p + "/";
        }
        return p;
    }


}
