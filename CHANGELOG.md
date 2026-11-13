# Changelog

All notable changes to `botmaker-plugin-host`.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this module uses
[semantic versioning](https://semver.org/). `release.sh` refuses to cut a version with no section here.

## [0.12.1] — 2026-11-13

### Changed

- Built against botmaker-studio-api 0.17.1 (was 0.17.0).
- Built against botmaker-java 0.2.1 (was 0.2.0).

## [0.12.0] — 2026-11-10

No source changes since v0.11.0; re-released for updated upstream pins.

### Changed (botmaker-java)

- The value grammar and `SourceParser` left for `botmaker-java` (2026-11-04): `com.botmaker.plugin.host.grammar` is now
  `com.botmaker.java.grammar`. `ClasspathGrammar` stays here and needs `botmaker-java`, an `optional` dependency in place
  of JDT: a host that calls it declares that module itself.

### Changed (one parser)

- `SourceParser` is the one entry from text to a tree (2026-11-04): a unit (`parse`), an expression, a type, a block of
  statements, and a bound parser (`bound`) for a host's own batch. A fragment comes back only when it is well-formed
  and whole, so `"not java"`, trailing text or a damaged statement is `null`, never a made-up tree. `JavaExpressions`
  is removed; `SourceNode.parse` reads through it.
- `BotAnnotation` moved here from Studio: `ParamValues` and `ParamsFitter` know `@Param`, `@Param.Shown`,
  `@Param.Kept` and `@Migrate` by what the imports resolve to, so another library's `@Shown(changes = DURING_RUN)` or
  `@Param` group is no parameter. `ParamValues.changesOf` and `ParamsFitter.isParameter` are the shared readings.
- `ParamsFitter` compares a parameter's type by what its file's imports resolve it to: `a.Foo` against `b.Foo`
  resets the value, an imported `Duration` and a written-whole `java.time.Duration` keep it.

### Added

- A typed chain (2026-11-04): an instance-method factory whose receiver is another declared class, such as
  `When::atLeast` writing a `Test`, is written as its receiver's own call followed by the link,
  `Params.when(Progress::gold).atLeast(5)`, and read back the same way (`TypedChainTest`). A receiver nothing
  declares leaves the call unwritten and unread.
- A renamed parameter keeps its value (2026-11-04): `ParamsFitter` reads a rename rule in the group's starting copy,
  `@Migrate(Group.class) static T old(@Migrate.Self Group self) { return self.renamed(); }`, and moves the old name's
  body and time to the new name at the same type; `ParamsFitter.renameTarget` reads one.
- `ParamValues.keptAt` (2026-11-04) reads the time a run wrote a value off its method, so a host can say a value's age.
- A value's time (2026-11-04): `ParamValues.withReturned(…, Instant)` writes `@Param.Kept`, `ParamsFitter.fit` and
  `withValue` carry it with the body, a time alone never makes a fit, and `ParamsFitter.withStartingValue` takes the
  body without it, for a starting copy.

### Changed

- **`ParamsFitter` works on JDT trees** (2026-11-04): it compares units with `ASTMatcher` and writes through `ASTRewrite`
  instead of splicing text. A copied value is laid out by the rewrite, so a comment inside it does not follow, and a
  comment outside a doc comment no longer makes a file differ from its starting copy.
- Built against botmaker-studio-api 0.17.0 (was 0.15.0).
- Built against botmaker-java 0.2.0 (was 0.1.0).

### Fixed

- **`ValueGrammar.spellData` never reads text as Java** (2026-11-04): a value a run read off a game, under a type declared
  with no component, is refused at any depth instead of written as the source it spells.
- **Text under `Object` or `CharSequence` is written as a literal** (2026-11-04): `ValueWriter` read such a `String` as
  Java source, so text a run held could reach the bot's next compile as code.

### Added

- **`ParamValues` and `ClasspathGrammar`** (2026-11-04): one parameter's value written into its group's source, a
  during-a-run value taken from disk into a host's copy, and the grammar of a process on its plugins' own classpath,
  for a running bot.
- **The value grammar** (`com.botmaker.plugin.host.grammar`, 2026-11-04): `ValueGrammar`, its reader and writer,
  `SourceParser` and `SourceFormatter`, moved unchanged from Studio's `plugin/grammar` and `parser/helpers` so every
  host reads and writes a bot's values the same way. This module now depends on JDT (`org.eclipse.jdt.core`
  3.47.0, Studio's pin). Once the SDK depends on it too (private-values plan, phase 4), a host release forces sdk.
- **`ParamsFitter`** (2026-11-04): fits a bot's private `params/` files to their copies in `starting-params/` — each
  file becomes its starting copy with this computer's method bodies: a parameter the starting copy lacks is dropped,
  one retyped is reset to its starting value, one added is added, a file whose starting copy is gone is deleted and
  one only the starting copy has is copied. Nothing is decided while a source does not parse or with no
  `starting-params/` folder; each file changed is backed up at its relative path. `fit(starting, params)` on two
  texts, `over` on two maps, `onDisk(paramsRoot, startingRoot, backups)` on disk. A private file is deleted only
  when it is a `@Param` group (`isGroup`), so an old tracked field class in `params/` is left alone.
  `withValue(starting, params, name)` copies one parameter's value into the other text, with the imports it needs
  (*Make this the starting value*, Forget).

### Fixed

- **A string with four or more quotes in a row is written as a text block that still parses** (2026-11-04): the
  third quote of every run is escaped, not only a whole `"""`, which left a fourth quote to close the block.

## [0.11.0] — 2026-11-10

### Changed (botmaker-java)

- The value grammar and `SourceParser` left for `botmaker-java` (2026-11-04): `com.botmaker.plugin.host.grammar` is now
  `com.botmaker.java.grammar`. `ClasspathGrammar` stays here and needs `botmaker-java`, an `optional` dependency in place
  of JDT: a host that calls it declares that module itself.

### Changed (one parser)

- `SourceParser` is the one entry from text to a tree (2026-11-04): a unit (`parse`), an expression, a type, a block of
  statements, and a bound parser (`bound`) for a host's own batch. A fragment comes back only when it is well-formed
  and whole, so `"not java"`, trailing text or a damaged statement is `null`, never a made-up tree. `JavaExpressions`
  is removed; `SourceNode.parse` reads through it.
- `BotAnnotation` moved here from Studio: `ParamValues` and `ParamsFitter` know `@Param`, `@Param.Shown`,
  `@Param.Kept` and `@Migrate` by what the imports resolve to, so another library's `@Shown(changes = DURING_RUN)` or
  `@Param` group is no parameter. `ParamValues.changesOf` and `ParamsFitter.isParameter` are the shared readings.
- `ParamsFitter` compares a parameter's type by what its file's imports resolve it to: `a.Foo` against `b.Foo`
  resets the value, an imported `Duration` and a written-whole `java.time.Duration` keep it.

### Added

- A typed chain (2026-11-04): an instance-method factory whose receiver is another declared class, such as
  `When::atLeast` writing a `Test`, is written as its receiver's own call followed by the link,
  `Params.when(Progress::gold).atLeast(5)`, and read back the same way (`TypedChainTest`). A receiver nothing
  declares leaves the call unwritten and unread.
- A renamed parameter keeps its value (2026-11-04): `ParamsFitter` reads a rename rule in the group's starting copy,
  `@Migrate(Group.class) static T old(@Migrate.Self Group self) { return self.renamed(); }`, and moves the old name's
  body and time to the new name at the same type; `ParamsFitter.renameTarget` reads one.
- `ParamValues.keptAt` (2026-11-04) reads the time a run wrote a value off its method, so a host can say a value's age.
- A value's time (2026-11-04): `ParamValues.withReturned(…, Instant)` writes `@Param.Kept`, `ParamsFitter.fit` and
  `withValue` carry it with the body, a time alone never makes a fit, and `ParamsFitter.withStartingValue` takes the
  body without it, for a starting copy.

### Changed

- **`ParamsFitter` works on JDT trees** (2026-11-04): it compares units with `ASTMatcher` and writes through `ASTRewrite`
  instead of splicing text. A copied value is laid out by the rewrite, so a comment inside it does not follow, and a
  comment outside a doc comment no longer makes a file differ from its starting copy.

### Fixed

- **`ValueGrammar.spellData` never reads text as Java** (2026-11-04): a value a run read off a game, under a type declared
  with no component, is refused at any depth instead of written as the source it spells.
- **Text under `Object` or `CharSequence` is written as a literal** (2026-11-04): `ValueWriter` read such a `String` as
  Java source, so text a run held could reach the bot's next compile as code.

### Added

- **`ParamValues` and `ClasspathGrammar`** (2026-11-04): one parameter's value written into its group's source, a
  during-a-run value taken from disk into a host's copy, and the grammar of a process on its plugins' own classpath,
  for a running bot.
- **The value grammar** (`com.botmaker.plugin.host.grammar`, 2026-11-04): `ValueGrammar`, its reader and writer,
  `SourceParser` and `SourceFormatter`, moved unchanged from Studio's `plugin/grammar` and `parser/helpers` so every
  host reads and writes a bot's values the same way. This module now depends on JDT (`org.eclipse.jdt.core`
  3.47.0, Studio's pin). Once the SDK depends on it too (private-values plan, phase 4), a host release forces sdk.
- **`ParamsFitter`** (2026-11-04): fits a bot's private `params/` files to their copies in `starting-params/` — each
  file becomes its starting copy with this computer's method bodies: a parameter the starting copy lacks is dropped,
  one retyped is reset to its starting value, one added is added, a file whose starting copy is gone is deleted and
  one only the starting copy has is copied. Nothing is decided while a source does not parse or with no
  `starting-params/` folder; each file changed is backed up at its relative path. `fit(starting, params)` on two
  texts, `over` on two maps, `onDisk(paramsRoot, startingRoot, backups)` on disk. A private file is deleted only
  when it is a `@Param` group (`isGroup`), so an old tracked field class in `params/` is left alone.
  `withValue(starting, params, name)` copies one parameter's value into the other text, with the imports it needs
  (*Make this the starting value*, Forget).

### Fixed

- **A string with four or more quotes in a row is written as a text block that still parses** (2026-11-04): the
  third quote of every run is escaped, not only a whole `"""`, which left a fourth quote to close the block.

## [0.10.1] — 2026-11-01

No source changes since v0.10.0; re-released for updated upstream pins.

No source changes since v0.9.0; re-released for updated upstream pins.

### Changed (breaking)

- **A plugin is named by its jar's pom** (2026-10-27). `PluginLoader` no longer uses `ServiceLoader`: it reads
  each entry's poms (`PluginPoms`, new) and constructs the class the one pom setting `botmaker.plugin` names. An
  entry whose poms name two plugins, a second entry with the same coordinate, and a class that is not the entry's
  own `public final` direct `DeclaredPlugin` subclass are refused, each a `PluginFailure`.
- **`PluginLoader.plugins()` answers `LoadedPlugin`s** (coordinate, name, jar, instance); `PluginCoordinate` is a
  plugin's id. Nothing is printed to stderr: every failure is in `Loaded.failures()`.
- `ContractLinks.declaresPlugin` is gone for `PluginPoms.declaresPlugin`.
- `maven-model` 3.10.0 is a dependency, the pom parser Studio and the CLI use.

## [0.10.0] — 2026-11-01

No source changes since v0.9.0; re-released for updated upstream pins.

### Changed (breaking)

- **A plugin is named by its jar's pom** (2026-10-27). `PluginLoader` no longer uses `ServiceLoader`: it reads
  each entry's poms (`PluginPoms`, new) and constructs the class the one pom setting `botmaker.plugin` names. An
  entry whose poms name two plugins, a second entry with the same coordinate, and a class that is not the entry's
  own `public final` direct `DeclaredPlugin` subclass are refused, each a `PluginFailure`.
- **`PluginLoader.plugins()` answers `LoadedPlugin`s** (coordinate, name, jar, instance); `PluginCoordinate` is a
  plugin's id. Nothing is printed to stderr: every failure is in `Loaded.failures()`.
- `ContractLinks.declaresPlugin` is gone for `PluginPoms.declaresPlugin`.
- `maven-model` 3.10.0 is a dependency, the pom parser Studio and the CLI use.

## [0.9.0] — 2026-10-28

### Changed (breaking)

- **A plugin is named by its jar's pom** (2026-10-27). `PluginLoader` no longer uses `ServiceLoader`: it reads
  each entry's poms (`PluginPoms`, new) and constructs the class the one pom setting `botmaker.plugin` names. An
  entry whose poms name two plugins, a second entry with the same coordinate, and a class that is not the entry's
  own `public final` direct `DeclaredPlugin` subclass are refused, each a `PluginFailure`.
- **`PluginLoader.plugins()` answers `LoadedPlugin`s** (coordinate, name, jar, instance); `PluginCoordinate` is a
  plugin's id. Nothing is printed to stderr: every failure is in `Loaded.failures()`.
- `ContractLinks.declaresPlugin` is gone for `PluginPoms.declaresPlugin`.
- `maven-model` 3.10.0 is a dependency, the pom parser Studio and the CLI use.

## [0.8.0] — 2026-10-25

No source changes since v0.7.0; re-released for updated upstream pins.

### Removed

- **`Recordings`**, with the contract's `@Records` it read: the macro recorder it served is gone from Studio.

### Added

- **`FilledTypes`**: every `@Fills` method in a plugin's own jar, found like the `@Palette` classes (the jar scan
  is now shared, `JarClasses`) and kept per plugin class; a method of the wrong shape, or a second for one class,
  is a problem and declares nothing. `Recordings` counts such a type as having a fresh value.

### Changed

- **A plugin whose constructor throws is described by what it threw**, not by `ServiceLoader`'s "Provider … could
  not be instantiated" (`PluginFailure.describe`).

## [0.7.0] — 2026-10-23

### Removed

- **`Recordings`**, with the contract's `@Records` it read: the macro recorder it served is gone from Studio.

### Added

- **`FilledTypes`**: every `@Fills` method in a plugin's own jar, found like the `@Palette` classes (the jar scan
  is now shared, `JarClasses`) and kept per plugin class; a method of the wrong shape, or a second for one class,
  is a problem and declares nothing. `Recordings` counts such a type as having a fresh value.

### Changed

- **A plugin whose constructor throws is described by what it threw**, not by `ServiceLoader`'s "Provider … could
  not be instantiated" (`PluginFailure.describe`).

## [0.6.0] — 2026-10-18

No source changes since v0.5.3; re-released for updated upstream pins.

### Build

- **JitPack builds with `.jitpack/build.sh`**, the umbrella's `tools/jitpack/build.sh`: Maven 3.9.9 and every
  dependency from Google's mirror of Maven Central first, with backoff and Maven's 429 retry, so Central's
  `429 Too Many Requests` no longer loses a tag. The statx shim comes with it.

## [0.5.3] — 2026-10-12

### Build

- **JitPack builds with `.jitpack/build.sh`**, the umbrella's `tools/jitpack/build.sh`: Maven 3.9.9 and every
  dependency from Google's mirror of Maven Central first, with backoff and Maven's 429 retry, so Central's
  `429 Too Many Requests` no longer loses a tag. The statx shim comes with it.

## [0.5.2] — 2026-10-12

No source changes since v0.5.1; re-released for updated upstream pins.

No source changes since v0.5.0; re-released for updated upstream pins.

### Fixed

- v0.4.0 never published: Maven Central refused JitPack's build machine (`429 Too Many Requests`) while it
  fetched a Maven plugin. No source changes since v0.4.0; its notes follow.

## [0.5.1] — 2026-10-10

No source changes since v0.5.0; re-released for updated upstream pins.

### Fixed

- v0.4.0 never published: Maven Central refused JitPack's build machine (`429 Too Many Requests`) while it
  fetched a Maven plugin. No source changes since v0.4.0; its notes follow.

## [0.5.0] — 2026-10-10

### Fixed

- v0.4.0 never published: Maven Central refused JitPack's build machine (`429 Too Many Requests`) while it
  fetched a Maven plugin. No source changes since v0.4.0; its notes follow.

## [0.4.0] — 2026-10-06

### Changed

- The pom carries a real version and names the contract's version, so a tag's pom says what it was built
  against; `.deps.env` is gone and JitPack runs a plain `mvn install` (umbrella
  `docs/refactor/43-real-versions.md`).
- v0.3.2 never published: JitPack's build machine failed to start Maven. This release carries its changes.

## [0.3.2] — 2026-10-06

### Changed

- Published as `com.github.BotMakerDev:botmaker-plugin-host` (was `com.github.LiQiyeDev`), and reads the
  contract pin from its own pom under that groupId. Tags already built under the old groupId still resolve
  under it.

## [0.3.1] — 2026-10-05

No source changes since v0.3.0; re-released for updated upstream pins.

No source changes since v0.2.2; re-released for updated upstream pins.

### Changed

- Built against `botmaker-studio-api` 0.4.0, which deleted `@Palette.order`. No change of its own.

## [0.3.0] — 2026-10-05

No source changes since v0.2.2; re-released for updated upstream pins.

### Changed

- Built against `botmaker-studio-api` 0.4.0, which deleted `@Palette.order`. No change of its own.

## [0.2.2] — 2026-10-01

### Changed

- Built against `botmaker-studio-api` 0.4.0, which deleted `@Palette.order`. No change of its own.

## [0.2.1] — 2026-09-29

### Fixed

- **A refused plugin now says which side is behind.** Every contract mismatch used to read *built for a newer
  Studio*, including a plugin built on an older contract that uses members the host has since deleted. The
  plugin's flattened pom names the contract tag it pinned, and the host compares it with its own. The message
  now reads *built for an older Studio (contract v0.2.1); update the plugin*, *built for a newer Studio; update
  Studio*, or *built for a different Studio* when either tag is unknown. `ContractLinks.NewerContract` is now
  `ContractMismatch`, which carries a `direction()`.
- **A plugin that extends a deleted contract type is named.** Its provider could not be resolved, so the
  failure read `a plugin — com/…/X is not on the classpath`. Mismatched entries are now reported from their
  services file, by provider name, before anything is loaded.

## [0.2.0] — 2026-09-29

### Added

- **`ContractLinks`: a plugin built for a newer contract is refused at load.** Each classpath entry declaring a
  plugin has its constant pools read; every reference reaching `com.botmaker.plugin.api` (directly, or through
  the plugin's own supertypes) is resolved against the host's contract class files — never reflected, so a
  headless host without JavaFX reads them too — and a public or protected member must be there.
  `PluginLoader.openReporting` skips a provider from an entry with a missing link and reports it as a failure,
  *built for a newer Studio: needs …*, instead of a `NoSuchMethodError` at first use. The entry stays on the
  classpath for plugins that depend on it.

## [0.1.9] — 2026-09-27

### Added

- **`Recordings.Slot.KeyParts`**: a `@Records` parameter whose type is written as a varargs call over one enum
  (`Combo.of(Key...)`) is filled from the gesture's key names, one constant each, and built. Checked after
  `Parts`, so every existing fill resolves as before. A host's `switch` on `Slot` must add the case.

## [0.1.8] — 2026-09-26

No source changes since v0.1.7; re-released for updated upstream pins.

### Changed

- **`PluginLoader.classLoader()`** — the loader the plugins came from, which a host resolves a bot's call
  on so the `Executable` a slot editor is handed (contract 0.3.0) names that plugin's own classes.
- **`Recordings.Slot.Fresh` carries the parameter's `Class`, not its name.** A host that seeds a fresh value
  looks the type up by the class it was handed rather than by a string it would have to resolve again.

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

## [0.1.7] — 2026-09-23

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
