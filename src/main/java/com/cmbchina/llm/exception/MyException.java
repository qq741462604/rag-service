package com.cmbchina.llm.exception;

/**
 * @description: some desc
 * @author: IT006901
 * @date: 2025/12/19
 */
public class MyException extends RuntimeException{

    public MyException(String message) {
        super(message);
    }

    public MyException(String message, Throwable cause) {
        super(message, cause);
    }
}
