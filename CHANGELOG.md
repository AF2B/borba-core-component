# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- A system with a component that nothing registered, because its namespace is not in `:service/namespaces`, fails the start with
  `:borba.core.system/unregistered-component`. The message names the key, and the namespace that registers it when it is one of the
  Borba libraries (`known-components`); Integrant reported it as `No such namespace`, which says nothing about the key.

## [2.0.0] - 2026-10-06

### Added

- `:borba/lifecycle`, a component that holds the state of the service (`:starting`, `:ready`, `:draining`, `:stopped`), and
  `borba.core.lifecycle` to ask about it and move it forward, so a readiness endpoint reports the truth.
- Graceful shutdown: on termination `:borba/core` turns readiness off, waits `:drain-delay-ms` for the load balancer to notice,
  and only then lets the rest of the system halt, in the reverse order it started in. `:after` names the components that are
  still running while the service drains.
- `borba.core.system`: `start!`, `stop!` and `current` run one system and stop it, `init` and `halt` do it without keeping it,
  and `require-namespaces` names the namespace of the configuration that cannot be loaded.
- `borba.core.config`: `load-config` and `read-config`, and the `#deep-merge` tag, which merges maps recursively. The
  `#ig/ref` and `#ig/refset` readers are registered by the library, in `data_readers.clj`.
- `borba.core.main/start`, which returns the system or an exit status instead of exiting, and `resolve-profile`.
- A test suite with the ordering of shutdown, the failure paths and a fresh JVM that reads a configuration with nothing loaded
  beforehand.

### Changed

- **Breaking:** the profile is required, as the first argument or in `PROFILE`. It used to default to `stag`, which is how a
  configuration of one environment ends up running in another. Without one the service exits with status 2.
- **Breaking:** a service that cannot start logs why and exits with status 1.
- **Breaking:** the startup banner is gone. `:borba/core` logs one line when the service starts and two when it stops, through
  `tools.logging`, and `:components` is no longer an option: `:after` says what the service comes after.
- **Breaking:** moves to Integrant 1.0, where a reference must be a qualified keyword.
- `:borba/core` and `:borba/lifecycle` are registered by `borba.core.system`, so `borba.core` no longer has to be listed under
  `:service/namespaces`.
- The published library is named `io.github.af2b/borba-core-component`.

### Fixed

- A system that fails to start no longer leaves running the components that had started, which Integrant does not halt by
  itself.
- The shutdown hook can run twice: the second run finds nothing to stop.

## [1.1.0] - 2026-03-30

### Added

- `borba.core.main`, the entry point: reads `config.edn` with Aero for a profile, loads `:service/namespaces`, starts the
  Integrant system and registers a shutdown hook.

## [1.0.0] - 2026-03-28

First release: the `:borba/core` Integrant component, which prints a startup banner and a stop message.

[Unreleased]: https://github.com/AF2B/borba-core-component/compare/v2.0.0...HEAD
[2.0.0]: https://github.com/AF2B/borba-core-component/compare/v1.1.0...v2.0.0
[1.1.0]: https://github.com/AF2B/borba-core-component/compare/v1.0.0...v1.1.0
[1.0.0]: https://github.com/AF2B/borba-core-component/releases/tag/v1.0.0
