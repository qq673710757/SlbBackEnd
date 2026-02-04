package com.slb.mining_backend.common.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.slb.mining_backend.common.trace.TraceIdHolder;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 统一响应封装结构 / Unified API response envelope.
 */
@Data
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "统一响应结构，所有接口（成功或异常）均返回该结构 / Unified response envelope used by all APIs.")
public class ApiResponse<T> {

    @Schema(description = "业务状态码，0 表示成功，非 0 表示业务或系统错误；通常与 HTTP 状态码含义保持一致。/ Business status code, 0 means success, non-zero indicates business or system error.", example = "0")
    private int code;

    @Schema(description = "提示信息；成功时通常为 'ok'，失败时为具体错误原因。/ Human readable message, 'ok' for success or error reason for failures.", example = "ok")
    private String message;

    @Schema(description = "展示用文案（中文等），用于前端直接展示；当 message 为机器码时可使用该字段。/ Display message for UI (e.g. Chinese).", nullable = true)
    private String displayMessage;

    @Schema(description = "业务数据载体，类型由各接口的泛型 T 决定。/ Business payload, generic type T depends on each API.", nullable = true)
    private T data;

    @Schema(description = "请求链路追踪 ID，用于排查问题。/ Trace identifier for request correlation.", example = "b3f7e6c9a1d24c31")
    private String traceId;

    @Schema(description = "错误扩展信息（可选）。/ Optional structured error details.", nullable = true)
    private ErrorBody error;

    private ApiResponse(int code, String message, T data, String traceId) {
        this.code = code;
        this.message = message;
        this.displayMessage = null;
        this.data = data;
        this.traceId = traceId;
        this.error = null;
    }

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(0, "ok", data, TraceIdHolder.require());
    }

    public static ApiResponse<Void> ok() {
        return ok(null);
    }

    public static <T> ApiResponse<T> of(int code, String message, T data) {
        return new ApiResponse<>(code, message, data, TraceIdHolder.require());
    }

    public static ApiResponse<Void> error(int code, String message) {
        return of(code, message, null);
    }

    /**
     * 鉴权/授权错误统一返回：message 使用稳定机器码，displayMessage 用于中文展示。
     */
    public static ApiResponse<Void> authError(int httpStatus, String machineCode, String displayMessage) {
        ApiResponse<Void> resp = ApiResponse.error(httpStatus, machineCode);
        resp.setDisplayMessage(displayMessage);
        resp.setError(new ErrorBody(machineCode, displayMessage, null, null));
        return resp;
    }

    /**
     * 鉴权/授权错误统一返回（带字段级错误详情）。
     */
    public static ApiResponse<Void> authError(int httpStatus, String machineCode, String displayMessage,
                                              java.util.Map<String, String> errors) {
        ApiResponse<Void> resp = ApiResponse.error(httpStatus, machineCode);
        resp.setDisplayMessage(displayMessage);
        resp.setError(new ErrorBody(machineCode, displayMessage, errors, null));
        return resp;
    }

    @Data
    @NoArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(description = "错误扩展结构 / Structured error details.")
    public static class ErrorBody {
        @Schema(description = "稳定机器错误码 / Stable machine-readable error code.", example = "AUTH_INVALID_TOKEN")
        private String code;

        @Schema(description = "展示文案（中文等）/ Display message for UI.", example = "登录已失效，请重新登录")
        private String displayMessage;

        @Schema(description = "字段级错误明细（可选）/ Field-level errors (optional).", nullable = true)
        private java.util.Map<String, String> errors;

        @Schema(description = "调试详情（可选，生产环境建议不返回）/ Debug detail (optional).", nullable = true)
        private String detail;

        public ErrorBody(String code, String displayMessage, java.util.Map<String, String> errors, String detail) {
            this.code = code;
            this.displayMessage = displayMessage;
            this.errors = errors;
            this.detail = detail;
        }
    }
}
