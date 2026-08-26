package com.talentserv.blink.config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class CorsConfig {

    @Bean
    WebMvcConfigurer blinkCorsConfigurer(BlinkProperties properties) {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                List<String> patterns = new ArrayList<>();
                patterns.add("*");
                Arrays.stream(properties.getCorsOrigins().split(","))
                        .map(String::trim)
                        .filter(origin -> !origin.isBlank())
                        .forEach(patterns::add);
                registry.addMapping("/api/**")
                        .allowedOriginPatterns(patterns.toArray(String[]::new))
                        .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                        .allowedHeaders("*")
                        .exposedHeaders(
                                "Content-Disposition",
                                "X-Blink-Stakeholder-Source",
                                "X-Blink-Workspace-Structure",
                                "X-Blink-File-Count",
                                "X-Blink-Next-Command"
                        )
                        .maxAge(3600);
            }
        };
    }
}
