package com.pass.ai_assistant_backend.ai;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Ensures install commands are gated behind propose_install + /api/ai/install,
 * not auto-run via run_terminal in the agent loop.
 */
class InstallSafetyTest {

    @Test
    void installPatternMatchesCommonInstallCommands() throws Exception {
        Pattern install = installCmdPattern();
        assertTrue(install.matcher("npm install express").find());
        assertTrue(install.matcher("npm i -D tailwindcss").find());
        assertTrue(install.matcher("pip install django").find());
        assertTrue(install.matcher("pnpm add lodash").find());
        assertTrue(install.matcher("yarn install").find());
    }

    @Test
    void installPatternDoesNotMatchSafeCommands() throws Exception {
        Pattern install = installCmdPattern();
        assertFalse(install.matcher("npm test").find());
        assertFalse(install.matcher("npm run build").find());
        assertFalse(install.matcher("python manage.py test catalog").find());
        assertFalse(install.matcher("git status").find());
    }

    private static Pattern installCmdPattern() throws Exception {
        Field f = AgentOrchestrator.class.getDeclaredField("INSTALL_CMD");
        f.setAccessible(true);
        return (Pattern) f.get(null);
    }
}
