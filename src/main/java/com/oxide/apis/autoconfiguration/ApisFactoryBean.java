package com.oxide.apis.autoconfiguration;

import com.alibaba.fastjson.JSON;
import com.oxide.apis.Apis;
import com.oxide.apis.autoconfiguration.model.Api;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.cglib.proxy.Enhancer;
import org.springframework.cglib.proxy.MethodInterceptor;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.HttpMessageConverterExtractor;
import org.springframework.web.client.RequestCallback;
import org.springframework.web.client.RestTemplate;

import java.lang.reflect.*;
import java.util.*;

import static org.springframework.web.bind.annotation.RequestMethod.*;

@Setter @Slf4j
public class ApisFactoryBean<T> implements FactoryBean<T>, ApplicationContextInitializer {

    private static ApplicationContext applicationContext;

    private Class<T> apisInterface;

    @Override
    public T getObject() throws Exception {
        Enhancer enhancer = new Enhancer();
        enhancer.setSuperclass(apisInterface);
        enhancer.setCallback((MethodInterceptor) (o, method, args, methodProxy) -> {
            if (Object.class.equals(method.getDeclaringClass())) {
                try {
                    return method.invoke(this, args);
                } catch (Throwable t) {
                    Throwable unwrapped = t;
                    while (true) {
                        if (unwrapped instanceof InvocationTargetException) {
                            unwrapped = ((InvocationTargetException) unwrapped).getTargetException();
                        } else if (unwrapped instanceof UndeclaredThrowableException) {
                            unwrapped = ((UndeclaredThrowableException) unwrapped).getUndeclaredThrowable();
                        } else {
                            break;
                        }
                    }
                    throw unwrapped;
                }
            }
            Api api = analysis(method, args);
            return send(api);
        });
        return (T) enhancer.create();
    }

    @Override
    public Class<?> getObjectType() {
        return apisInterface;
    }

    private Api analysis(Method method, Object[] args) {
        Class<?> apiInterface = method.getDeclaringClass();
        if ( ! apiInterface.isAnnotationPresent(Apis.class)) {
            throw new RuntimeException("interface not is apis type.");
        }
        Api api = new Api().setReturner(method.getGenericReturnType());
        Apis apis = AnnotationUtils.findAnnotation(apiInterface, Apis.class);
        api.setUrl(apis.url()).setRetry(apis.retry());
        String[] paths = {};
        if (method.isAnnotationPresent(PostMapping.class)) {
            api.setMethod(POST);
            paths = AnnotationUtils.findAnnotation(method, PostMapping.class).path();
        } else if (method.isAnnotationPresent(PutMapping.class)) {
            api.setMethod(PUT);
            paths = AnnotationUtils.findAnnotation(method, PutMapping.class).path();
        } else if (method.isAnnotationPresent(DeleteMapping.class)) {
            api.setMethod(DELETE);
            paths =  AnnotationUtils.findAnnotation(method, DeleteMapping.class).path();
        } else if (method.isAnnotationPresent(GetMapping.class)) {
            api.setMethod(GET);
            paths =  AnnotationUtils.findAnnotation(method, GetMapping.class).path();
        } else if (method.isAnnotationPresent(RequestMapping.class)) {
            RequestMapping requestMapping = AnnotationUtils.findAnnotation(method, RequestMapping.class);
            RequestMethod[] methods = requestMapping.method();
            if (methods.length > 0) {
                api.setMethod(methods[0]);
            }
            paths =  AnnotationUtils.findAnnotation(method, RequestMapping.class).path();
        } else {
            throw new RuntimeException("request method not allow empty.");
        }
        if (paths != null && paths.length > 0) {
            if ( ! StringUtils.isEmpty(api.getUrl()) && api.getUrl().endsWith("/") && paths[0].startsWith("/")) {
                api.setUrl(api.getUrl().substring(0, api.getUrl().length() - 1));
            }
            api.setUrl((api.getUrl() + paths[0]));
        }
        Parameter[] parameters = method.getParameters();
        if (parameters != null) {
            for (int i = 0; i < parameters.length; i++) {
                Object arg = args[i];
                Parameter parameter = parameters[i];
                if (parameter.isAnnotationPresent(PathVariable.class)) {
                    PathVariable pathVariable = AnnotationUtils.findAnnotation(parameter, PathVariable.class);
                    String name = pathVariable.name();
                    if (StringUtils.isEmpty(name)) {
                        name = parameter.getName();
                    }
                    api.setUrl(api.getUrl().replace(String.format("{%s}", name), Optional.ofNullable(arg).orElse("").toString()));
                } else if (parameter.isAnnotationPresent(RequestHeader.class)) {
                    RequestHeader requestHeader = AnnotationUtils.findAnnotation(parameter, RequestHeader.class);
                    String name = requestHeader.name();
                    if (StringUtils.isEmpty(name)) {
                        name = parameter.getName();
                    }
                    parse(api.getHeader(), name, arg);
                } else if (parameter.isAnnotationPresent(RequestParam.class)) {
                    RequestParam requestParam = AnnotationUtils.findAnnotation(parameter, RequestParam.class);
                    String name = Optional.ofNullable(requestParam.name()).orElse("");
                    parse(api.getParams(), name, arg);
                } else if (parameter.isAnnotationPresent(RequestBody.class)) {
                    if (api.getMethod().equals(GET)) {
                        throw new RuntimeException("get method not supper body.");
                    }
                    if (api.getBody() != null) {
                        throw new RuntimeException("request body not allow multiple.");
                    }
                    api.setBody(arg);
                    if (api.getMethod() == null) {
                        api.setMethod(POST);
                    }
                } else {
                    /*api.setBody(arg);*/
                    parse(api.getParams(), "", arg);
                }
            }
        }
        return api;
    }

    private Object send(Api api) {

        RestTemplate restTemplate = applicationContext.getBean(RestTemplate.class);
        String result = null;
        int retry = api.getRetry();
        HttpHeaders headers = new HttpHeaders(api.getHeader());
        headers.setAccept(Arrays.asList(MediaType.ALL));
        if (api.getMethod().equals(GET)) {
            StringBuffer queryer = new StringBuffer(1024);
            api.getParams().forEach((k, v) -> {
                queryer.append(String.format("&%s=%s", k, v));
            });
            if (api.getUrl().indexOf("?") >= 0) {
                api.setUrl(api.getUrl() + queryer.toString());
            } else {
                if (queryer.length() > 0) {
                    api.setUrl(api.getUrl() + "?" + (queryer.toString().substring(1)));
                }
            }
            do {
                try {
                    result = restTemplate.getForObject(api.getUrl(), String.class);
                } catch (Exception e) {
                    if (retry <= 0) {
                        throw e;
                    }
                    try {
                        Thread.sleep(200);
                    } catch (InterruptedException ex) {}
                }
            } while (retry-- > 0);
        } else {
            HttpEntity request = null;
            if (api.getBody() == null) {
                headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
                request = new HttpEntity(api.getParams(), headers);
            } else {
                headers.setContentType(MediaType.APPLICATION_JSON);
                String body = api.getBody() instanceof CharSequence ? api.getBody().toString() : JSON.toJSONString(api.getBody());
                request = new HttpEntity(body, headers);
            }
            RequestCallback requestCallback = restTemplate.httpEntityCallback(request, String.class);
            HttpMessageConverterExtractor<String> responseExtractor = new HttpMessageConverterExtractor(String.class, restTemplate.getMessageConverters());
            do {
                try {
                    result = restTemplate.execute(api.getUrl(), HttpMethod.resolve(api.getMethod().name()), requestCallback, responseExtractor);
                } catch (Exception e) {
                    if (retry <= 0) {
                        throw e;
                    }
                    try {
                        Thread.sleep(200);
                    } catch (InterruptedException ex) {}
                }
            } while (retry-- > 0);
        }
        return JSON.parseObject(result, api.getReturner());
    }

    private void parse(final MultiValueMap<String, String> map, final String name, final Object arg) {
        if (arg == null || arg.getClass().equals(Object.class)) {
            return;
        }
        if (arg instanceof Number || arg instanceof CharSequence || arg instanceof Character) {
            map.add(StringUtils.trimLeadingCharacter(name, '.'), Optional.ofNullable(arg).orElse("").toString());
        } else if (arg.getClass().isEnum()) {
            map.add(StringUtils.trimLeadingCharacter(name, '.'), Optional.ofNullable(arg).orElse("").toString());
        } else if (arg.getClass().isArray()) {
            Object[] objects = (Object[]) arg;
            for (int i = 0; i < objects.length; i++) {
                parse(map, name + "[" + i + "]", objects[i]);
            }
        } else if (arg instanceof Map) {
            ((Map) arg).forEach((k, v) -> {
                parse(map, name + "." + k, v);
            });
        } else if (arg instanceof Collection) {
            Object[] objects = ((Collection<?>) arg).toArray();
            for (int i = 0; i < objects.length; i++) {
                parse(map, name + "[" + i + "]", objects[i]);
            }
        } else {
            List<Field> fields = new ArrayList<>(32);
            for (Class c = arg.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                fields.addAll(Arrays.asList(c.getDeclaredFields()));
            }
            for (Field field : fields) {
                boolean accessible = field.isAccessible();
                field.setAccessible(true);
                try {
                    Object o = field.get(arg);
                    parse(map, name + "." + field.getName(), o);
                } catch (IllegalAccessException e) {
                    e.printStackTrace();
                }
                field.setAccessible(accessible);
            }
        }
    }

    @Override
    public void initialize(ConfigurableApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }
}
