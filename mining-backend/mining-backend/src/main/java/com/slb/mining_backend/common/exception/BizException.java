package com.slb.mining_backend.common.exception;

public class BizException extends RuntimeException{

    private static final long serialVersionUID = 1L;

    // 自定义错误码k
    private final int code;

    public BizException(String message) {
        super(message);
        this.code = 400; // 默认400 - 业务错误
    }

    public BizException(int code, String message) {
        super(message);
        this.code = code;
    }

    public int getCode() {
        return code;
    }
}
