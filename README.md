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
| `PluginLoader` | one `URLClassLoader` over the project's resolved classpath and one `ServiceLoader` pass in it |
| `ContractLinks` | what a plugin's compiled classes link in the contract that the host's contract lacks, and which side is behind |
| `Palettes` | a plugin's palette: every `@Palette` class in the jar it was loaded from, unless it builds a catalog by hand |
| `Recordings` | the plugins' recorded-input methods, each parameter classified by how the host fills it |

## Using it

```java
try (PluginLoader loaded = PluginLoader.open(project.resolvedClasspath())) {
    if (loaded == null) return fallbackPlugins();   // nothing to load, or nothing loadable
    for (StudioPlugin plugin : loaded.plugins()) {
        …
    }
}
```

Two things to know before you write the second line:

- **`open` returns `null`, not an empty loader.** An unresolvable pin, a jar with no services file and a
  plugin whose constructor throws are all the same answer, because the caller's fallback is the same in all
  three: whatever plugin set it already had. Studio bundles no plugin, so its fallback is an empty set and a
  project that resolves nothing opens with no plugin menus.
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
mvn test        # the delegation split, the nulls, the link check and a real ServiceLoader round trip
mvn install     # com.github.BotMakerDev:botmaker-plugin-host at the pom's main -SNAPSHOT
```

Published through JitPack, which serves each git tag under `com.github.BotMakerDev`. Releases are cut from
the umbrella only, with `--plugin-host <version>` plus the downstreams it forces (cli, studio, dashboard);
`/release-check` names them.
