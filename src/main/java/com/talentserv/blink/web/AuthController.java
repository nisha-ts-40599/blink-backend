package com.talentserv.blink.web;

import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.talentserv.blink.dto.AuthLoginConfig;
import com.talentserv.blink.dto.AuthSessionResponse;
import com.talentserv.blink.dto.OtpRequest;
import com.talentserv.blink.dto.OtpRequestResponse;
import com.talentserv.blink.dto.OtpVerifyRequest;
import com.talentserv.blink.service.OtpLoginService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final OtpLoginService otpLoginService;

    public AuthController(OtpLoginService otpLoginService) {
        this.otpLoginService = otpLoginService;
    }

    @GetMapping("/config")
    public AuthLoginConfig config() {
        return otpLoginService.loginConfig();
    }

    @PostMapping("/otp/request")
    public OtpRequestResponse requestOtp(@Valid @RequestBody OtpRequest request) {
        return otpLoginService.requestOtp(request.email(), request.accessCode());
    }

    @PostMapping("/otp/verify")
    public AuthSessionResponse verifyOtp(@Valid @RequestBody OtpVerifyRequest request) {
        return otpLoginService.verifyOtp(request.email(), request.otp(), request.accessCode());
    }

    @GetMapping("/me")
    public AuthSessionResponse me(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        return otpLoginService.requireSession(authorization);
    }

    @PostMapping("/logout")
    public void logout(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        otpLoginService.logout(authorization);
    }
}
