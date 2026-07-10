/**
 * Cross-cutting concerns shared by all bounded contexts: framework configuration,
 * exception-to-HTTP mapping, security, and observability.
 *
 * <p>Per the Dependency Rule, {@code common} may depend on framework config only and must
 * never import any context's {@code domain} or {@code application} packages.
 */
package com.cobre.notifications.common;
