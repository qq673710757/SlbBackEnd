package com.slb.mining_backend.modules.system.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
@Schema(description = "用户反馈提交请求体 / User feedback submission request body")
public class FeedbackSubmitDto {

    @NotBlank(message = "反馈类型不能为空")
    @Schema(description = "反馈类型：bug(问题反馈)、suggestion(功能建议)、other(其他)。/ Feedback type: bug, suggestion or other.", example = "bug")
    private String type; // "bug", "suggestion", "other"

    @NotBlank(message = "反馈内容不能为空")
    @Schema(description = "反馈内容描述，建议包含环境、复现步骤等。/ Feedback content, including environment and steps if possible.", example = "Windows 11 客户端在登录后白屏，控制台报错 ...")
    private String content;

    @Schema(description = "联系方式（选填），如邮箱或联系方式账号。/ Optional contact information such as email or IM handle.", example = "user@example.com")
    private String contact;
}