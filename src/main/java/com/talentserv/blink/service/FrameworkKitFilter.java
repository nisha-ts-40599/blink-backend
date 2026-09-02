package com.talentserv.blink.service;

import java.util.Set;

/**
 * Customer workspaces get the AI-SDLC kit from setup-new-project / setup-new-workspace:
 * Makefile, prompts, tools, schemas, policies, templates, and related framework paths.
 * Product context is written separately under {@code .cursor/ai-sdlc/}.
 * Do not ship the Worker, framework docs/CI, or Python tests.
 */
final class FrameworkKitFilter {

    static final Set<String> SKIP_DIR_NAMES = Set.of(
            ".git",
            ".cursor",
            ".idea",
            ".vscode",
            ".tools",
            ".github",
            "python312",
            "site-packages",
            "node_modules",
            "target",
            "dist",
            "__pycache__",
            ".venv",
            ".venv-ai-sdlc",
            ".pytest_cache",
            ".mypy_cache",
            "runtime-data",
            "htmlcov",
            "services",
            "docs",
            "scripts",
            "tests",
            "examples",
            "fixtures",
            "blink_demo",
            "blink_backend",
            "blink-backend"
    );

    private FrameworkKitFilter() {
    }

    static boolean skipDirectory(String name) {
        return name != null && (SKIP_DIR_NAMES.contains(name) || name.startsWith("test_"));
    }

    static boolean skipFile(String name) {
        if (name == null || name.isBlank()) {
            return true;
        }
        if (name.equals(".DS_Store") || name.equals(".env") || name.equals("conftest.py")) {
            return true;
        }
        if (name.startsWith(".env.") && !name.contains("example")) {
            return true;
        }
        if (name.endsWith(".zip") || name.endsWith(".pyc") || name.endsWith(".log")) {
            return true;
        }
        if (name.startsWith("test_") && name.endsWith(".py")) {
            return true;
        }
        return name.endsWith("_test.py")
                || name.endsWith("test_helpers.py")
                || name.endsWith("_e2e_harness.py");
    }
}
