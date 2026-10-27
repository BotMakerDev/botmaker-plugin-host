# CLAUDE.md

Guidance for working in **botmaker-plugin-host**, the loader a BotMaker Studio plugin host uses to get a
plugin off one project's own classpath.

Read the umbrella `../CLAUDE.md` first, `../botmaker-studio-api/CLAUDE.md` for the contract this loads
against, and `../docs/refactor/24-plugin-platform.md` for why any of it exists.

## What this module is, and what it is not

**A plugin is its jar, named by its pom** (2026-10-27): `PluginPoms` reads every
`META-INF/maven/<g>/<a>/pom.xml` in an entry (a `target/classes` directory reads `../../pom.xml`); exactly one
setting `botmaker.plugin` makes the entry a plugin, with that pom's coordinate (`PluginCoordinate`) as its id and
`<name>` as its name. `PluginLoader` refuses an entry whose poms name two, a second entry with one coordinate, a
contract mismatch, and a named class that is not the entry's own `public final` direct `DeclaredPlugin`
subclass; each plugin comes back as a `LoadedPlugin` (coordinate, name, jar, instance). No services file, no
`ServiceLoader`, nothing printed.

Beside those: `PluginLoader`; `Palettes` (2026-09-23), the host's reading of a plugin's
palette — its hand-built `catalog()`, or every `@Palette` class found in the plugin's own jar when
`catalog()` is the empty default; and `ContractLinks` (2026-09-28), which reads a plugin entry's
constant pools and resolves every reference reaching the contract against the host's own contract class
files, so `PluginLoader` refuses a plugin built for a newer Studio at load, naming what it needs, instead of it
failing with `NoSuchMethodError` at first use (`../docs/refactor/25-compatibility.md` §1). It follows
references through the plugin's own supertypes, because javac writes an inherited call against the calling
class; it reads the contract as bytes and never reflects on it, because a contract signature names JavaFX and a
headless host has none; and it judges nothing it cannot prove missing (a supertype from the toolkit or a
library is unreadable here). **Which side is behind** (2026-09-29, `Direction`) is the one thing read from a
version: the contract tag in the plugin jar's flattened `META-INF/maven/**/pom.xml` against the one in this
module's own. An untagged host is `main`, so a tagged plugin is the older side. It words the refusal and
never decides it. A mismatched entry is reported by the class its pom names, before loading, because a class
that extends a deleted contract type cannot be resolved at all. Discovery lives here because every host (Studio, `botmaker plugin
validate`, the registry's CI) must read the same answer, and the contract holds no implementation. The
three plugin-facing modules are three different relationships and the fastest way to keep them straight is
by who depends on which:

| module | who declares it | scope | on whose classloader |
|---|---|---|---|
| `botmaker-studio-api` | the plugin **and** the host | `provided` in both | the host's — it must be one `Class` object |
| `botmaker-plugin-toolkit` | the plugin | `compile` | the **plugin's**, never the host's |
| `botmaker-plugin-host` | the **host** | `compile` | the host's |

So: `botmaker-studio` depends on this and must never depend on the toolkit; a plugin depends on the toolkit
and never on this.

It was extracted from `botmaker-studio` on 2026-08-28 (plugin-ecosystem plan, phase 5) as a **pure move** —
not a line of behaviour changed. The reason for the move is that Studio stopped being the only host: the
CLI's `validate` and `run` load a plugin to check it, and the registry's CI loads a plugin to admit it. All
three must load it *the same way*, and the delegation split is the last code here that should exist in two
copies.

## The one rule

**Nothing in this module may name a type outside `com.botmaker.plugin.api` and the JDK** — with one exception,
`maven-model` inside `PluginPoms` (2026-10-27, the user's call: the parser Studio and the CLI already read poms
with; no natives, and none of its types leaves `PluginPoms`). Not the SDK, not Studio, not JavaFX, not
`botmaker-shared`. Two reasons, and the second is the operational one:

1. A host that is not Studio must be able to use it. Reaching for `botmaker-shared` here would drag OpenCV
   and JNA into a command-line tool that wants to load one jar and read an id.
2. Every type crossing the plugin boundary is a contract type or a JDK type — that is what makes a plugin,
   constructed once, usable as a `StudioPlugin` without reflecting on its own classes again. A
   third namespace here would be a third namespace to get the parent-first/child-first answer right for.

## The delegation split, and the thing that will bite

Parent-first for `com.botmaker.plugin.api.`, `java.`, `javax.`, `javafx.`, `jdk.`, `sun.`; child-first for
everything else with a parent fallback.

**Every prefix ends in a dot, and that is not cosmetic.** Without it `com.botmaker.plugin.apix.*` — or a
third-party `javafxsupport.*` — is silently taken from the host. `PluginLoaderTest` holds that case
specifically, because it is the kind of bug that produces a working build and a wrong answer.

**Two SDK class-spaces are live at once** while a host also declares a compile dependency on the SDK: the
host's own and the loader's. They must never exchange an SDK type. Studio does not today — every consumer of
a catalog entry reaches it through `simpleName()` / `qualifiedName()` / `offered()` and nothing compares a
`Class<?>` across the two. That is a condition of this class working, not a preference.

## Why `botmaker-studio-api` is `provided`

A host has the contract already. More to the point, the parent-first arm exists precisely so a contract
class is the **same** `Class` object on both sides — and a transitive second copy at a second version is the
one thing that can make that false. So this module refuses to supply one, and a consumer declares it.

## Building

```bash
mvn test        # PluginLoaderTest, PluginPomsTest, PalettesTest, ContractLinksTest; a fixture that fails to compile fails the test
mvn install     # com.github.BotMakerDev:botmaker-plugin-host at the pom's main -SNAPSHOT
```

**Fail-open catches `LinkageError` too, and that is the arm most likely to fire.** A plugin resolved without
one of its own dependencies fails in `Class.forName` as a `NoClassDefFoundError` — an `Error`, which walked
past the original catch and aborted the host's project-open until 2026-08-28. It is deliberately not `Error`: a
broken plugin must not make an `OutOfMemoryError` look like a missing class. Note the shape a test for it needs — a missing class named only inside a method body resolves lazily
and does **not** reproduce; the absent class has to be a supertype.

Do not add a test that asserts `open` returned non-null on a real jar you built in `target/`. What is worth
holding is what has **no visible symptom when it is wrong**: the parent-first rule, which fails much later as a
`ClassCastException` between two identically named classes, and one jar being one plugin, which fails as two
plugins answering for the same classes.

Published through JitPack. **The maintainer owns the publish** — releases are cut from the umbrella with
`../release.sh --plugin-host <version>`.
