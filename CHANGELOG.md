# Changelog

All notable changes to `botmaker-plugin-host`.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this module uses
[semantic versioning](https://semver.org/). `release.sh` refuses to cut a version with no section here.

## [Unreleased]

### Added

- **`Palettes`**: a plugin's palette as a host reads it. `Palettes.of(plugin)` is the plugin's own
  `catalog()` when it builds one, and otherwise every `@Palette` class in the jar the plugin was loaded from,
  found without loading or linking anything else. A plugin no longer lists its palette classes.
- **`Recordings`**: the `@Records` methods of a set of plugins, found on their palette classes, each with how
  a recording fills every parameter (a plugin's `RecordedValue`, a number, a numeric component type, text, a
  key enum, a fresh value). `Recordings.problems` names a method that is not `public static` or has a
  parameter nothing fills; Studio records with `of`, `botmaker plugin validate` checks with `problems`.

### Fixed

- **A plugin whose own dependency is missing says which class is missing again, on JDK 27.** Up to JDK 25
  `ServiceLoader` let the `NoClassDefFoundError` out of `Class.forName` through raw, so the failure line read
  `a plugin — p/Helper is not on the classpath`. JDK 27 wraps it in a `ServiceConfigurationError` — which is
  an improvement, since that one names the provider line the raw error never did — and the line became
  `Provider p.BrokenPlugin not found`, which tells the user nothing they can act on. `PluginFailure.describe`
  now looks for the missing class down the cause chain (bounded, cycle-safe) instead of only at the top, so
  both JDKs print the same line. Loading behaviour is unchanged; only the sentence was lost.

### Changed

- **`Recordings` never picks an instance-method factory to write a recorded value.** A chain on part 0
  (`Precision.TIGHT.minArea(400)`) is something the host reads, not a way to write a value down.
- **Recompiled against the contract's new packages** — imports only, no behaviour change. See
  `botmaker-studio-api`'s changelog for the old → new table.

## [0.1.6] — 2026-09-21

### Fixed

- **A plugin whose own dependency is missing says which class is missing again, on JDK 27.** Up to JDK 25
  `ServiceLoader` let the `NoClassDefFoundError` out of `Class.forName` through raw, so the failure line read
  `a plugin — p/Helper is not on the classpath`. JDK 27 wraps it in a `ServiceConfigurationError` — which is
  an improvement, since that one names the provider line the raw error never did — and the line became
  `Provider p.BrokenPlugin not found`, which tells the user nothing they can act on. `PluginFailure.describe`
  now looks for the missing class down the cause chain (bounded, cycle-safe) instead of only at the top, so
  both JDKs print the same line. Loading behaviour is unchanged; only the sentence was lost.

### Changed

- **Recompiled against the contract's new packages** — imports only, no behaviour change. See
  `botmaker-studio-api`'s changelog for the old → new table.

## [0.1.5] — 2026-09-21

No source changes since v0.1.4; re-released for updated upstream pins.

No source changes since v0.1.3; re-released for updated upstream pins.

No source changes since v0.1.2; re-released for updated upstream pins.

No source changes since v0.1.1; re-released for updated upstream pins.

### Fixed

- **A plugin whose superclass is missing is reported by the class that is missing.** `Class.forName` throws
  a raw `NoClassDefFoundError` naming `p/Helper`, not the provider, and `ServiceLoader` never says which
  services line it was on — so `PluginFailure.describe()` now reads *a plugin — p/Helper is not on the
  classpath*. The test that asserted the provider's name was red on `main` since v0.1.0.

## [0.1.4] — 2026-09-19

No source changes since v0.1.3; re-released for updated upstream pins.

No source changes since v0.1.2; re-released for updated upstream pins.

No source changes since v0.1.1; re-released for updated upstream pins.

### Fixed

- **A plugin whose superclass is missing is reported by the class that is missing.** `Class.forName` throws
  a raw `NoClassDefFoundError` naming `p/Helper`, not the provider, and `ServiceLoader` never says which
  services line it was on — so `PluginFailure.describe()` now reads *a plugin — p/Helper is not on the
  classpath*. The test that asserted the provider's name was red on `main` since v0.1.0.

## [0.1.3] — 2026-09-19

No source changes since v0.1.2; re-released for updated upstream pins.

No source changes since v0.1.1; re-released for updated upstream pins.

### Fixed

- **A plugin whose superclass is missing is reported by the class that is missing.** `Class.forName` throws
  a raw `NoClassDefFoundError` naming `p/Helper`, not the provider, and `ServiceLoader` never says which
  services line it was on — so `PluginFailure.describe()` now reads *a plugin — p/Helper is not on the
  classpath*. The test that asserted the provider's name was red on `main` since v0.1.0.

## [0.1.2] — 2026-09-18

No source changes since v0.1.1; re-released for updated upstream pins.

### Fixed

- **A plugin whose superclass is missing is reported by the class that is missing.** `Class.forName` throws
  a raw `NoClassDefFoundError` naming `p/Helper`, not the provider, and `ServiceLoader` never says which
  services line it was on — so `PluginFailure.describe()` now reads *a plugin — p/Helper is not on the
  classpath*. The test that asserted the provider's name was red on `main` since v0.1.0.

## [0.1.1] — 2026-09-17

### Fixed

- **A plugin whose superclass is missing is reported by the class that is missing.** `Class.forName` throws
  a raw `NoClassDefFoundError` naming `p/Helper`, not the provider, and `ServiceLoader` never says which
  services line it was on — so `PluginFailure.describe()` now reads *a plugin — p/Helper is not on the
  classpath*. The test that asserted the provider's name was red on `main` since v0.1.0.

## [0.1.0] — 2026-09-16

### Fixed

- **One broken plugin no longer costs a project every other plugin.** The `ServiceLoader` pass was a single
  `for` inside a single `try`, so the first provider that would not load ended the iteration and every
  plugin declared after it was silently absent. With one plugin in the world that was invisible; with two it
  is the difference between *the SDK is broken* and *nothing works*. Each provider is now advanced and
  constructed inside its own `try` — two moments, because a plugin fails at two: `Class.forName` while the
  iterator advances (a missing superclass, the shape of a non-transitive toolkit) and the no-arg constructor
  in `Provider.get()` (a constructor that links an `optional` dependency, the shape SDK v1.1.5 shipped).

### Added

- **`openReporting` answers what did not load, as well as what did.** `Loaded(loader, failures)` with
  `PluginFailure(provider, cause)`; `open` is that pass with the failures dropped and keeps its contract
  exactly — `null` when nothing loaded, so a host still falls back to its bundled set. Three incidents in
  this project's record end with the same words, *an empty palette and one line on stderr*: catching a
  broken plugin is correct, and being unable to say which one is not. A host can now tell *this project pins
  no plugin* from *this project pins a plugin that is broken*.

## [0.0.5] — 2026-09-05

### Fixed

- **`0.0.4` failed on JitPack too, on a third plugin.** The compiler pin it added was correct; this pom also
  pinned `flatten-maven-plugin` at **1.6.0**, which declares a Maven 3.6.3 prerequisite, and JitPack's
  builder runs **Apache Maven 3.6.1** — so the build died with
  `The plugin org.codehaus.mojo:flatten-maven-plugin:1.6.0 requires Maven version 3.6.3` before publishing
  anything, and took `botmaker-cli` down with it. Pinned to **1.4.1**, the version `botmaker-session` and
  `botmaker-sdk` have carried since 2026-08-22. The loader itself is unchanged.

  Use `0.0.5`; `0.0.4` was never published.

## [0.0.4] — 2026-09-04

### Fixed

- **The pin `0.0.3` added was itself unbuildable on JitPack.** `maven-compiler-plugin` raised its own Maven
  prerequisite to 3.6.3 in 3.12.0, and JitPack's Maven is older — so 3.13.0 failed with
  `requires Maven version 3.6.3`. Pinned to **3.11.0**, which is what `botmaker-shared` has always used.
  Use `0.0.4`; `0.0.3` was never published.

## [0.0.3] — 2026-09-04

### Fixed

- **This module is resolvable from JitPack again.** Neither this pom nor `botmaker-studio-api`'s pinned
  `maven-compiler-plugin`, and JitPack's Maven defaults it to 3.1 — which predates
  `maven.compiler.release` and builds with `source 5` (`Source option 5 is no longer supported`). The
  contract stopped building there on 2026-09-02, so this module could not resolve it and `v0.0.2` was
  never published. Both are pinned to 3.13.0 now; the loader itself is unchanged.

## [0.0.2] — 2026-09-02

### Changed

- **Compiled for Java 25 (LTS).** A host embedding the loader needs a 25 runtime. The delegation split and
  every prefix in it are unchanged.

## [0.0.1] — 2026-09-02

First release. `0.x` because the contract whose namespace it delegates parent-first is still `0.x`.

### Added

- **The module** — the ninth BotMaker repository, and the third a plugin platform needs: the contract says
  what a plugin contributes, the toolkit is what a plugin compiles against, and this is what a **host** uses
  to load one. It exists because Studio stopped being the only host — the CLI's `validate` and `run`, and
  the plugin registry's CI, all have to load a plugin exactly as Studio does.
- **`PluginLoader`** — moved out of `botmaker-studio` unchanged. Opens one project's resolved classpath,
  runs the `ServiceLoader` pass over it and hands back the `StudioPlugin`s found there, or `null` when there
  is nothing to load or nothing loadable. `Closeable`, and closing is required rather than housekeeping: an
  open `URLClassLoader` holds every jar it read, and on Windows a held jar cannot be replaced.
- **Inverted delegation** — parent-first for `com.botmaker.plugin.api.**` and the platform namespaces,
  child-first for everything else. Parent-first for the contract because a contract class must be the *same*
  `Class` object on both sides of the boundary; child-first for `com.botmaker.sdk.**` because a bot's
  palette must come from the SDK **it** pins and not from the one the host was compiled against.
- **Fail-open covers a plugin whose own dependency is missing.** `open` catches `LinkageError` as well as
  `ServiceConfigurationError` and `RuntimeException`. A plugin jar resolved without one of its own
  dependencies — the toolkit absent from a project's classpath is the ordinary way — fails inside
  `ServiceLoader`'s `Class.forName` as a `NoClassDefFoundError`, which is an `Error` and escaped: it aborted
  whatever the host was doing, which is opening a project. Deliberately not `Error`: a broken plugin must
  not make an `OutOfMemoryError` look like a missing services file. Found on 2026-08-28 by loading the
  plugin archetype's own skeleton.
- **`PluginLoaderTest`** (10) — the split, including the case the dot-terminated prefixes exist for
  (`com.botmaker.plugin.apix.*` must **not** be parent-first); the four ways a classpath answers `null`,
  including a plugin whose superclass is missing — built by compiling one against a helper and then deleting
  the helper's `.class`, which is exactly the state a jar resolved without its dependency is in; and a real
  `ServiceLoader` round trip through the child-first fallback arm.

### Deliberately absent

- **Any dependency but the contract**, which is `provided`. No SDK, no Studio, no JavaFX, no
  `botmaker-shared` — a host that is a command-line tool must be able to load a plugin without downloading
  OpenCV and JNA.
- **A plugin cache, a reload watcher, or a sandbox.** A plugin runs arbitrary code in the host's process;
  this module loads it and says so plainly. Anything that implied otherwise would be a security claim
  nothing here can keep.
