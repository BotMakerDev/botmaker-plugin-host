package com.botmaker.plugin.host;

import com.botmaker.plugin.api.managed.HostHolder;
import com.botmaker.plugin.api.managed.ManagedId;
import com.botmaker.plugin.api.managed.ManagedMarker;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** A plugin's mark on a value of this PC its run writes, for {@code RunHostFoldersTest}. */
@ManagedMarker
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface TestRunValue {

    Id value();

    enum Id implements ManagedId<Holder> {
        DONE
    }

    enum Holder implements HostHolder {
        RUNS
    }
}
