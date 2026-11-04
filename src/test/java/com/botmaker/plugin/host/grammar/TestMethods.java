package com.botmaker.plugin.host.grammar;

import java.lang.reflect.Method;

/** A fixture's factory found by reflection. */
final class TestMethods {

    /** {@code owner.name(parameters)} for a fixture's factory; a missing one fails the test that asked. */
    static Method method(Class<?> owner, String name, Class<?>... parameters) {
        try {
            return owner.getMethod(name, parameters);
        } catch (NoSuchMethodException e) {
            throw new AssertionError(e);
        }
    }

    private TestMethods() {}
}
