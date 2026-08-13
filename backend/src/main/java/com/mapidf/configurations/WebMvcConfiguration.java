package com.mapidf.configurations;

import com.mapidf.controllers.support.RateLimitInterceptor;
import lombok.AllArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Sans motif de chemin : l'interceptor couvre les quatre endpoints. Il n'atteint pas l'Actuator,
 * qui vit sur le port 9100 dans un contexte enfant distinct — et ce port n'est publié que sur la
 * loopback.
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
