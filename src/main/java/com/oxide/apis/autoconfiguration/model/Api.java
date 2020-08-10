package com.oxide.apis.autoconfiguration.model;

import lombok.Data;
import lombok.experimental.Accessors;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.RequestMethod;

import java.io.Serializable;
import java.lang.reflect.Type;

@Data
@Accessors(chain = true)
public class Api implements Serializable {

    private RequestMethod method;
    private String url;
    private final MultiValueMap<String, String> header = new LinkedMultiValueMap<>();
    private final MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
    private Object body;
    private Type returner;
    private int retry = 1;
}
