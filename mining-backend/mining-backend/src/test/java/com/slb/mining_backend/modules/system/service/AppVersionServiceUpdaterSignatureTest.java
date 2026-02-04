package com.slb.mining_backend.modules.system.service;

import com.slb.mining_backend.common.exception.BizException;
import com.slb.mining_backend.modules.admin.dto.AppVersionUpdateDto;
import com.slb.mining_backend.modules.system.entity.AppVersionConfig;
import com.slb.mining_backend.modules.system.mapper.AppVersionConfigMapper;
import com.slb.mining_backend.modules.system.vo.TauriUpdaterManifestVo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AppVersionServiceUpdaterSignatureTest {

    @Mock
    AppVersionConfigMapper appVersionConfigMapper;

    @Captor
    ArgumentCaptor<AppVersionConfig> configCaptor;

    private AppVersionService newService() {
        return new AppVersionService(appVersionConfigMapper);
    }

    private static String minisignTextSample() {
        // 用于后端校验：要求内容是完整 minisign 文本（同时包含 untrusted/trusted comment）。
        // 结构参考 minisign（Tauri 2）：
        //   1) untrusted comment: ...
        //   2) <base64 signature body>
        //   3) trusted comment: timestamp:... file:...
        //   4) <base64 global signature>
        return "untrusted comment: signature from tauri secret key\n"
                + "QUJDREVGR0hJSktMTU5PUA==\n"
                + "trusted comment: timestamp:1767854724\tfile:app_0.1.3.msi\n"
                + "cXdlcnR5dWlvcGFzZGZoamtsbXhjdg==\n";
    }

    private static String toSigFileBase64(String rawSigText) {
        // 兼容旧数据：DB/接口里可能保存的是 “.sig 文件内容的 Base64”
        String normalized = rawSigText.trim();
        return Base64.getEncoder().encodeToString(normalized.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void upsert_shouldStoreSigText_whenInputIsSigFileBase64() {
        String minisignText = minisignTextSample();
        String sigFileBase64 = toSigFileBase64(minisignText);
        String expectedSigText = minisignText.trim();

        AppVersionUpdateDto dto = new AppVersionUpdateDto();
        dto.setPlatform("windows-x86_64");
        dto.setChannel("stable");
        dto.setLatestVersion("0.1.3");
        dto.setUpdaterUrl("https://example.com/updates/windows-x86_64/stable/app_0.1.3.msi");
        dto.setUpdaterSignature("  " + sigFileBase64 + "  ");

        when(appVersionConfigMapper.upsert(any())).thenReturn(1);

        newService().upsert(dto);

        verify(appVersionConfigMapper).upsert(configCaptor.capture());
        assertEquals(expectedSigText, configCaptor.getValue().getUpdaterSignature());
    }

    @Test
    void upsert_shouldStoreSigText_whenInputIsMinisignMultiLineText() {
        String minisignText = minisignTextSample();
        String expectedSigText = minisignText.trim();

        AppVersionUpdateDto dto = new AppVersionUpdateDto();
        dto.setPlatform("windows-x86_64");
        dto.setChannel("stable");
        dto.setLatestVersion("0.1.3");
        dto.setUpdaterUrl("https://example.com/updates/windows-x86_64/stable/app_0.1.3.msi");
        dto.setUpdaterSignature("\n" + minisignText + "\n");

        when(appVersionConfigMapper.upsert(any())).thenReturn(1);

        newService().upsert(dto);

        verify(appVersionConfigMapper).upsert(configCaptor.capture());
        assertEquals(expectedSigText, configCaptor.getValue().getUpdaterSignature());
    }

    @Test
    void upsert_shouldNormalizeAndStoreSigText_whenInputIsWrappedBase64WithNewlinesOrSpaces() {
        String minisignText = minisignTextSample();
        String sigFileBase64 = toSigFileBase64(minisignText);
        String expectedSigText = minisignText.trim();

        // 模拟：复制出来的 Base64 被换行/空格分割
        String wrapped = "  \n" + sigFileBase64.substring(0, 10) + " \n"
                + sigFileBase64.substring(10, 35) + "\n"
                + sigFileBase64.substring(35) + "  ";

        AppVersionUpdateDto dto = new AppVersionUpdateDto();
        dto.setPlatform("windows-x86_64");
        dto.setChannel("stable");
        dto.setLatestVersion("0.1.3");
        dto.setUpdaterUrl("https://example.com/updates/windows-x86_64/stable/app_0.1.3.msi");
        dto.setUpdaterSignature(wrapped);

        when(appVersionConfigMapper.upsert(any())).thenReturn(1);

        newService().upsert(dto);

        verify(appVersionConfigMapper).upsert(configCaptor.capture());
        assertEquals(expectedSigText, configCaptor.getValue().getUpdaterSignature());
    }

    @Test
    void upsert_shouldRejectSecondLineSignatureBodyBase64() {
        // 这类输入是“看起来像 Base64”，但 decode 后不包含 minisign 结构
        String notSigFileBase64 = "aGVsbG8="; // "hello"

        AppVersionUpdateDto dto = new AppVersionUpdateDto();
        dto.setPlatform("windows-x86_64");
        dto.setChannel("stable");
        dto.setLatestVersion("0.1.3");
        dto.setUpdaterUrl("https://example.com/updates/windows-x86_64/stable/app_0.1.3.msi");
        dto.setUpdaterSignature(notSigFileBase64);

        BizException ex = assertThrows(BizException.class, () -> newService().upsert(dto));
        assertEquals(409, ex.getCode());
        assertEquals("signature must be the .sig file contents (or its base64)", ex.getMessage());
        verify(appVersionConfigMapper, never()).upsert(any());
    }

    @Test
    void manifest_shouldReturnSignatureAsSigText_whenStoredIsSigFileBase64() {
        String minisignText = minisignTextSample();
        String sigFileBase64 = toSigFileBase64(minisignText);
        String expectedSigText = minisignText.trim();

        AppVersionConfig cfg = new AppVersionConfig();
        cfg.setPlatform("windows-x86_64");
        cfg.setChannel("stable");
        cfg.setLatestVersion("0.1.3");
        cfg.setUpdaterUrl("https://example.com/updates/windows-x86_64/stable/app_0.1.3.msi");
        cfg.setUpdaterSignature(sigFileBase64);
        cfg.setUpdateTime(LocalDateTime.now());

        when(appVersionConfigMapper.findActiveByPlatformChannel("windows-x86_64", "stable"))
                .thenReturn(Optional.of(cfg));

        Optional<TauriUpdaterManifestVo> voOpt = newService()
                .buildTauriUpdaterManifest("windows-x86_64", "stable", "0.1.2");
        assertTrue(voOpt.isPresent());

        TauriUpdaterManifestVo vo = voOpt.get();
        assertEquals("0.1.3", vo.getVersion());
        assertNotNull(vo.getPlatforms().get("windows-x86_64"));
        assertEquals(expectedSigText, vo.getPlatforms().get("windows-x86_64").getSignature());
    }

    @Test
    void manifest_shouldAcceptLegacyStoredMinisignText_andReturnSigText() {
        String minisignText = minisignTextSample();
        String expectedSigText = minisignText.trim();

        AppVersionConfig cfg = new AppVersionConfig();
        cfg.setPlatform("windows-x86_64");
        cfg.setChannel("stable");
        cfg.setLatestVersion("0.1.3");
        cfg.setUpdaterUrl("https://example.com/updates/windows-x86_64/stable/app_0.1.3.msi");
        // 兼容：DB 里仍是 minisign 多行原文
        cfg.setUpdaterSignature(minisignText);
        cfg.setUpdateTime(LocalDateTime.now());

        when(appVersionConfigMapper.findActiveByPlatformChannel("windows-x86_64", "stable"))
                .thenReturn(Optional.of(cfg));

        Optional<TauriUpdaterManifestVo> voOpt = newService()
                .buildTauriUpdaterManifest("windows-x86_64", "stable", "0.1.2");
        assertTrue(voOpt.isPresent());

        TauriUpdaterManifestVo vo = voOpt.get();
        assertEquals(expectedSigText, vo.getPlatforms().get("windows-x86_64").getSignature());
    }

    @Test
    void manifest_shouldNormalizeStoredSigBase64WithWhitespace_andReturnSigText() {
        String minisignText = minisignTextSample();
        String sigFileBase64 = toSigFileBase64(minisignText);
        String expectedSigText = minisignText.trim();

        // 模拟：历史数据/手工写库时带了换行
        String storedWithWhitespace = sigFileBase64.substring(0, 20) + "\n"
                + sigFileBase64.substring(20, 60) + "\r\n"
                + sigFileBase64.substring(60);

        AppVersionConfig cfg = new AppVersionConfig();
        cfg.setPlatform("windows-x86_64");
        cfg.setChannel("stable");
        cfg.setLatestVersion("0.1.3");
        cfg.setUpdaterUrl("https://example.com/updates/windows-x86_64/stable/app_0.1.3.msi");
        cfg.setUpdaterSignature(storedWithWhitespace);
        cfg.setUpdateTime(LocalDateTime.now());

        when(appVersionConfigMapper.findActiveByPlatformChannel("windows-x86_64", "stable"))
                .thenReturn(Optional.of(cfg));

        Optional<TauriUpdaterManifestVo> voOpt = newService()
                .buildTauriUpdaterManifest("windows-x86_64", "stable", "0.1.2");
        assertTrue(voOpt.isPresent());

        TauriUpdaterManifestVo vo = voOpt.get();
        assertEquals(expectedSigText, vo.getPlatforms().get("windows-x86_64").getSignature());
    }

    @Test
    void manifest_shouldReject_whenTrustedCommentFileDoesNotMatchUrlFileName() {
        String minisignText = minisignTextSample()
                .replace("file:app_0.1.3.msi", "file:other.msi");
        String sigFileBase64 = toSigFileBase64(minisignText);

        AppVersionConfig cfg = new AppVersionConfig();
        cfg.setPlatform("windows-x86_64");
        cfg.setChannel("stable");
        cfg.setLatestVersion("0.1.3");
        cfg.setUpdaterUrl("https://example.com/updates/windows-x86_64/stable/app_0.1.3.msi");
        cfg.setUpdaterSignature(sigFileBase64);
        cfg.setUpdateTime(LocalDateTime.now());

        when(appVersionConfigMapper.findActiveByPlatformChannel("windows-x86_64", "stable"))
                .thenReturn(Optional.of(cfg));

        BizException ex = assertThrows(BizException.class, () ->
                newService().buildTauriUpdaterManifest("windows-x86_64", "stable", "0.1.3")
        );
        assertEquals(409, ex.getCode());
        assertTrue(ex.getMessage().contains("文件名不一致"));
    }

    @Test
    void manifest_shouldAcceptPercentEncodedUrlFileName_whenTrustedCommentFileIsUtf8Name() {
        // fileHint 使用 UTF-8 中文名；URL 侧使用 percent-encoding（常见于 Nginx / 浏览器）。
        String minisignText = minisignTextSample()
                .replace("file:app_0.1.3.msi", "file:SuanLiBao_0.1.4_x64_zh-CN_中文.msi");
        String sigFileBase64 = toSigFileBase64(minisignText);
        String expectedSigText = minisignText.trim();

        AppVersionConfig cfg = new AppVersionConfig();
        cfg.setPlatform("windows-x86_64");
        cfg.setChannel("stable");
        cfg.setLatestVersion("0.1.4");
        cfg.setUpdaterUrl("https://example.com/updates/windows-x86_64/stable/SuanLiBao_0.1.4_x64_zh-CN_%E4%B8%AD%E6%96%87.msi");
        cfg.setUpdaterSignature(sigFileBase64);
        cfg.setUpdateTime(LocalDateTime.now());

        when(appVersionConfigMapper.findActiveByPlatformChannel("windows-x86_64", "stable"))
                .thenReturn(Optional.of(cfg));

        Optional<TauriUpdaterManifestVo> voOpt = newService()
                .buildTauriUpdaterManifest("windows-x86_64", "stable", "0.1.3");
        assertTrue(voOpt.isPresent());
        assertEquals("0.1.4", voOpt.get().getVersion());
        assertEquals(expectedSigText, voOpt.get().getPlatforms().get("windows-x86_64").getSignature());
    }
}


