# botmaker-plugin-host

Loading a **BotMaker Studio plugin** out of one project's own resolved jars.

`botmaker-studio-api` says what a plugin contributes. `botmaker-plugin-toolkit` is what a plugin compiles
against. This is the other side: what a **host** uses to get a plugin onto a classloader and read what it
declares — Studio, the `botmaker` CLI's `validate` and `run`, and the plugin registry's CI.

```xml
<dependency>
    <groupId>com.github.BotMakerDev</groupId>
    <artifactId>botmaker-plugin-host</artifactId>
    <version><!-- latest tag: https://github.com/BotMakerDev/botmaker-plugin-host/tags --></version>
</dependency>
```

`botmaker-studio-api` is `provided` here — declare it yourself. That is not tidiness: a contract class must
be the **same `Class` object** on both sides of the plugin boundary, and a transitive second copy is the one
thing that can make that false.

## What is in it

| | |
|---|---|
| `PluginPoms` | which plugin an entry declares: the one `META-INF/maven/<g>/<a>/pom.xml` setting `botmaker.plugin`, its coordinate and `<name>`, read as bytes |
| `PluginLoader` | one `URLClassLoader` over the project's resolved classpath, constructing the class each declaring pom names |
| `ContractLinks` | what a plugin's compiled classes link in the contract that the host's contract lacks, and which side is behind |
| `Palettes` | a plugin's palette: every `@Palette` class in the jar it was loaded from, unless it builds a catalog by hand |

## Using it

```java
try (PluginLoader loaded = PluginLoader.open(project.resolvedClasspath())) {
    if (loaded == null) return fallbackPlugins();   // nothing to load, or nothing loadable
    for (LoadedPlugin plugin : loaded.plugins()) {   // coordinate, name, jar, instance
        …
    }
}
```

A plugin is declared by its jar's pom: exactly one `META-INF/maven/<g>/<a>/pom.xml` in the jar sets
`<botmaker.plugin>the.plugin.Class</botmaker.plugin>`, and that pom's `groupId:artifactId` is the plugin's
identity, its `<name>` the name. The class is the jar's own `public final` direct `DeclaredPlugin`. A jar with two
declaring poms, a second jar with one coordinate and a class of the wrong shape are refused, each a
`PluginFailure` from `openReporting`; a jar with none is a library.

Two things to know before you write the second line:

- **`open` returns `null`, not an empty loader.** An unresolvable pin, a classpath that declares no plugin and
  one whose every plugin is refused are all the same answer: a host holds no classloader for a project with no
  plugin. Studio bundles no plugin, so a project that loads nothing opens with no plugin menus.
- **`close()` is required.** An open `URLClassLoader` holds every jar it read, and on Windows a held jar
  cannot be replaced — a project left unclosed makes the next dependency resolve fail on a file lock.

## Why it is a module and not eighty lines you copy

Delegation is inverted: **parent-first** for `com.botmaker.plugin.api.**` and the platform namespaces,
**child-first** for everything else. That split is the whole point — a plugin must be read from the version
the project pins, not from whatever the host happens to have on its own classpath.

It is also code that must not exist in two copies. A wrong answer there does not throw where it happens; it
throws much later as a `ClassCastException` between two classes with identical names.

## Building

```bash
mvn test        # the delegation split, the nulls, the link check, the declaring poms and their refusals
mvn install     # com.github.BotMakerDev:botmaker-plugin-host at the pom's main -SNAPSHOT
```

Published through JitPack, which serves each git tag under `com.github.BotMakerDev`. Releases are cut from
the umbrella only, with `--plugin-host <version>` plus the downstreams it forces (cli, studio, dashboard);
`/release-check` names them.
