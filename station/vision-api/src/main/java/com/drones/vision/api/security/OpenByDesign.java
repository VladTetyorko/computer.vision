package com.drones.vision.api.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a request handler that deliberately performs no authority check.
 *
 * <p>Every handler in this module must either consult {@code CurrentUser} (directly or through an
 * {@code *Access} collaborator) or carry this annotation — {@code EndpointAuthorizationTest} in
 * vision-app fails the build otherwise (docs/plans/done/LIVE-SCOPE-PLAN.md §2). The point is that
 * an unauthorized endpoint must be an explicit, reviewable claim rather than an omission nobody
 * noticed: the audit that motivated this rule found 29 such omissions, none of them decisions.
 *
 * <p>This is not a security mechanism; it changes no behavior at runtime. It only forces the
 * question to be answered in code review, next to the handler, in words.
 *
 * @param reason why this endpoint is safe to expose without an authority check — write it for a
 *               reviewer who does not trust you
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface OpenByDesign {

    /** Why this endpoint needs no authority check. */
    String reason();
}
