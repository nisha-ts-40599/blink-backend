package com.talentserv.blink.config;

final class BlinkRuntime {

    private BlinkRuntime() {
    }

    static boolean testsRunning() {
        try {
            Class.forName("org.junit.jupiter.api.Test");
            return true;
        } catch (ClassNotFoundException ex) {
            return false;
        }
    }
}
