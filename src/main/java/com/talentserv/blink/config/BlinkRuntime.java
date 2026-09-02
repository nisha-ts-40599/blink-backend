package com.talentserv.blink.config;

final class BlinkRuntime {

    private BlinkRuntime() {
    }

    /**
     * True only for Surefire/JUnit launches. Do not treat JUnit on the compile
     * classpath as a test run — {@code spring-boot:run} still needs {@code .env}.
     */
    static boolean testsRunning() {
        if (System.getProperty("surefire.test.class.path") != null) {
            return true;
        }
        String command = System.getProperty("sun.java.command", "");
        return command.contains("surefirebooter")
                || command.contains("JUnitStarter")
                || command.contains("org.junit.platform");
    }
}
