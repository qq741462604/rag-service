package com.example.rag.annotation;


import java.lang.annotation.*;

/**
 * IgnoreAuth 注解（显式放行）
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface IgnoreAuth {
}
