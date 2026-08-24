package com.talentserv.blink;

import com.talentserv.blink.config.BlinkProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(BlinkProperties.class)
public class BlinkApplication {

    public static void main(String[] args) {
        SpringApplication.run(BlinkApplication.class, args);
    }
}
