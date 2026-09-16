package quest.server.flags;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * §4's rule, on the server side: a feature's endpoints answer 404 while its flag is off for the caller's school.
 * Put it on the controller class when every route of it belongs to one feature, or on the single handler otherwise;
 * {@link FeatureFlagInterceptor} enforces it and `FeatureFlagCoverageTest` fails the build when a controller added
 * after P2.1 carries neither.
 *
 * <p>The value must be one of {@link FlagKeys#ALL}.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface FeatureFlag {
    String value();
}
