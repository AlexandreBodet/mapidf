package com.mapidf.configurations;

import com.mapidf.controllers.support.RateLimitInterceptor;
import lombok.AllArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Sans motif de chemin : l'interceptor couvre les quatre endpoints. Que le contexte enfant de
 * l'Actuator hérite ou non de ce {@code WebMvcConfigurer} importe peu en pratique : le port 9100
 * n'est publié que sur la loopback de la machine, donc inatteignable depuis le réseau — et si un
 * appel venu de l'hôte y était malgré tout compté (passerelle du bridge Docker, cf. README),
 * 600/min ne gênerait aucun collecteur réaliste.
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
