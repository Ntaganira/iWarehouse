package com.ntaganira.heritier.iWarehouse.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.i18n.LocaleChangeInterceptor;
import org.springframework.web.servlet.i18n.SessionLocaleResolver;

import java.util.List;
import java.util.Locale;

/**
 * Login view and language switching (?lang=en|fr|rw), as in iVura. The mobile POS API keeps no session: its language is the
 * request's (?lang, else Accept-Language), for that request only.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private static final List<String> LANGUAGES = List.of("en", "fr", "rw");

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController("/login").setViewName("login");
        // The mobile POS (PWA): its page is the static app shell
        registry.addRedirectViewController("/m", "/m/");
        registry.addViewController("/m/").setViewName("forward:/m/index.html");
    }

    @Bean
    public LocaleResolver localeResolver() {
        SessionLocaleResolver resolver = new SessionLocaleResolver();
        resolver.setDefaultLocale(Locale.ENGLISH);
        return resolver;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        LocaleChangeInterceptor interceptor = new LocaleChangeInterceptor();
        interceptor.setParamName("lang");
        registry.addInterceptor(interceptor).excludePathPatterns("/api/**");
        // The sign-in page's CSRF token is made (with the session holding it) before the page is written. Made lazily, it would
        // come when Thymeleaf reaches the form, about 8 KB in: once the page is that long before it (the "signed out" notice
        // after logging out) Tomcat has already sent the start, no session can be created any more and the page breaks off.
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                if (request.getAttribute(CsrfToken.class.getName()) instanceof CsrfToken token) {
                    token.getToken();
                }
                return true;
            }
        }).addPathPatterns("/login");
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                String lang = request.getParameter("lang");
                String code = (lang != null ? Locale.forLanguageTag(lang) : request.getLocale()).getLanguage();
                LocaleContextHolder.setLocale(Locale.forLanguageTag(LANGUAGES.contains(code) ? code : "en"));
                return true;
            }
        }).addPathPatterns("/api/**");
    }
}
