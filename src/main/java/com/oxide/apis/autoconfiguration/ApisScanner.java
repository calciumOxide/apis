package com.oxide.apis.autoconfiguration;

import com.oxide.apis.Apis;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinitionHolder;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.GenericBeanDefinition;
import org.springframework.context.annotation.ClassPathBeanDefinitionScanner;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.client.RestTemplate;

import java.util.Set;

public class ApisScanner extends ClassPathBeanDefinitionScanner {

    public ApisScanner(BeanDefinitionRegistry registry) {
        super(registry);
    }

    @Override
    public Set<BeanDefinitionHolder> doScan(String... basePackages) {
        addIncludeFilter(new AnnotationTypeFilter(Apis.class));
        Set<BeanDefinitionHolder> beanDefinitionHolders = super.doScan(basePackages);
        beanDefinitionHolders.forEach(e -> {
            GenericBeanDefinition beanDefinition = (GenericBeanDefinition) (e.getBeanDefinition());
            Class<?> clazz = null;
            try {
                clazz = beanDefinition.resolveBeanClass(this.getClass().getClassLoader());
            } catch (ClassNotFoundException e1) {
                e1.printStackTrace();
            }
            beanDefinition.getPropertyValues().add("apisInterface", beanDefinition.getBeanClassName());
            beanDefinition.setBeanClass(ApisFactoryBean.class);

            beanDefinition.setAutowireMode(AbstractBeanDefinition.AUTOWIRE_BY_TYPE);
        });
        return beanDefinitionHolders;
    }

    @Override
    protected boolean isCandidateComponent(AnnotatedBeanDefinition beanDefinition) {
        return (beanDefinition.getMetadata().isInterface() && beanDefinition.getMetadata().isIndependent());
    }
}
