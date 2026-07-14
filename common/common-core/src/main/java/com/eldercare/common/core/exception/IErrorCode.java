package com.eldercare.common.core.exception;

import org.springframework.http.HttpStatus;

public interface IErrorCode {
    int getCode();
    String getMsg();
    HttpStatus getHttpStatus();
}