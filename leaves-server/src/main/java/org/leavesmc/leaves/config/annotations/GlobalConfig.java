package org.leavesmc.leaves.config.annotations;

import org.leavesmc.leaves.config.AutoConfigValidator;
import org.leavesmc.leaves.config.ConfigValidator;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface GlobalConfig {
    String value();

    boolean lock() default false;

    Class<? extends ConfigValidator<?>> validator() default AutoConfigValidator.class;
}
