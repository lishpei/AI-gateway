package com.corp.agent.admin.common;

import lombok.Getter;

@Getter
public class BizException extends RuntimeException {

    private final int code;

    public BizException(int code, String message) {
        super(message);
        this.code = code;
    }

    public static BizException notFound(String what) {
        return new BizException(40401, what + " not found");
    }

    public static BizException conflict(String message) {
        return new BizException(40901, message);
    }

    public static BizException badRequest(String message) {
        return new BizException(40001, message);
    }
}
