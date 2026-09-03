package com.talentserv.blink.service;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.context.annotation.Profile;

@Component
@Profile("!nodb")
public class DemoDataInitializer implements ApplicationRunner {

    private final DemoUserService demoUserService;

    public DemoDataInitializer(DemoUserService demoUserService) {
        this.demoUserService = demoUserService;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        demoUserService.ensureDemoUser();
    }
}
