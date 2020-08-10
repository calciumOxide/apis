package com.oxide.apis;

import org.springframework.core.annotation.AliasFor;

import java.lang.annotation.*;

@Target({ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Apis {

    @AliasFor("url")
    String value() default "";
    @AliasFor("value")
    String url() default "";

    int retry() default 1;
}
