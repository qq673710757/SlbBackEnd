package com.slb.mining_backend.modules.invite.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 获取邀请码接口的视图对象
 */
@Data
@Schema(description = "邀请码视图对象 / Invitation code view object")
public class InviteCodeVo {

    @Schema(description = "用户专属邀请码。/ User's unique invitation code.", example = "INVITE123")
    private String inviteCode;

    @Schema(description = "可直接分享的邀请链接。/ Shareable invitation URL.", example = "https://slb.xyz/register?code=INVITE123")
    private String inviteLink;
}
