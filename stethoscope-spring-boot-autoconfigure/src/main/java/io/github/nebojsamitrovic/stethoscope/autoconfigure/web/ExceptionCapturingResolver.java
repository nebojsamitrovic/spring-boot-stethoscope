package io.github.nebojsamitrovic.stethoscope.autoconfigure.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;

/**
 * Runs before every other {@link HandlerExceptionResolver}, remembers the exception on the request
 * and steps aside (returns {@code null}). Without this, exceptions turned into responses by
 * {@code @ExceptionHandler} / {@code @ControllerAdvice} would never reach the request filter.
 */
public class ExceptionCapturingResolver implements HandlerExceptionResolver, Ordered {

    public static final String EXCEPTION_ATTRIBUTE = ExceptionCapturingResolver.class.getName() + ".exception";

    @Override
    public ModelAndView resolveException(
            HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        if (request.getAttribute(EXCEPTION_ATTRIBUTE) == null) {
            request.setAttribute(EXCEPTION_ATTRIBUTE, ex);
        }
        return null;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
