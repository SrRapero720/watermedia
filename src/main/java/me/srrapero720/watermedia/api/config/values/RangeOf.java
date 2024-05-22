package me.srrapero720.watermedia.api.config.values;

import java.lang.annotation.*;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface RangeOf {
    int min() default Integer.MIN_VALUE;
    int max() default Integer.MAX_VALUE;
}
