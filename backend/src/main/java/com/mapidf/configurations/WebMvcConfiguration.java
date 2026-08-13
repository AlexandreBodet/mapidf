package com.mapidf.configurations;

import com.mapidf.controllers.support.RateLimitInterceptor;
import lombok.AllArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Sans motif de chemin : l'interceptor couvre les quatre endpoints. Que le contexte enfant de
 * l'Actuator hérite ou non de ce {@code WebMvcConfigurer} n'a pas d'importance : le port 9100
 * n'est publié que sur la loopback, donc exempté dans tous les cas par {@code isLoopback}.
 */
@Configuration
@AllArgsConstructor
public class WebMvcConfiguration implements WebMvcConfigurer {

    private final RateLimitInterceptor rateLimitInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(rateLimitInterceptor);
    }
}
