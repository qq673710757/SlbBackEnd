package com.slb.mining_backend.modules.system.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "Android app version info")
public class AndroidAppVersionVo {

    @Schema(description = "Version name, e.g. 1.2.0")
    private String version;

    @Schema(description = "Build number (versionCode)")
    private Integer buildNumber;

    @Schema(description = "Force update flag")
    private Boolean forceUpdate;

    @Schema(description = "APK download URL (full HTTPS URL)")
    private String downloadUrl;

    @Schema(description = "Release notes (supports \\n)")
    private String description;
}
