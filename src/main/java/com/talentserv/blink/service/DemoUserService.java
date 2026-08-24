package com.talentserv.blink.service;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.talentserv.blink.config.BlinkProperties;
import com.talentserv.blink.domain.AppUser;
import com.talentserv.blink.error.ApiException;
import com.talentserv.blink.repo.AppUserRepository;

@Service
public class DemoUserService {

    public static final String STATUS_ACTIVE = "ACTIVE";

    private final AppUserRepository appUserRepository;
    private final BlinkProperties properties;

    public DemoUserService(AppUserRepository appUserRepository, BlinkProperties properties) {
        this.appUserRepository = appUserRepository;
        this.properties = properties;
    }

    @Transactional
    public AppUser ensureDemoUser() {
        return appUserRepository.findByEmailIgnoreCase(properties.getDemoUserEmail())
                .orElseGet(this::createDemoUser);
    }

    @Transactional(readOnly = true)
    public AppUser requireDemoUser() {
        return appUserRepository.findByEmailIgnoreCase(properties.getDemoUserEmail())
                .orElseThrow(() -> new ApiException(
                        HttpStatus.INTERNAL_SERVER_ERROR,
                        "Demo user is not seeded. Restart the API so created_by can be set."));
    }

    private AppUser createDemoUser() {
        AppUser user = new AppUser();
        user.setFirstName("Blink");
        user.setLastName("System");
        user.setEmail(properties.getDemoUserEmail());
        user.setStatus(STATUS_ACTIVE);
        user.setDescription("Seeded operator for the Blink wizard until authentication ships.");
        return appUserRepository.save(user);
    }
}
