# borba-core-component

[![CI](https://github.com/AF2B/borba-core-component/actions/workflows/ci.yml/badge.svg)](https://github.com/AF2B/borba-core-component/actions/workflows/ci.yml)

The lifecycle and the entry point of a Borba service: an [Integrant](https://github.com/weavejester/integrant) system described
by an [Aero](https://github.com/juxt/aero) configuration with a section per profile, started from the command line, and stopped
cleanly when the process is told to terminate.

A service that is told to stop does not just exit. It turns its readiness off first and waits, so the load balancer stops
sending it requests, then halts the rest of the system in the reverse order it started in, and only then ends.

## Install

```clojure
io.github.af2b/borba-core-component
{:git/url "https://github.com/AF2B/borba-core-component"
 :git/tag "v2.0.0"
 :git/sha "<the commit of the tag, printed in the release notes>"}
```

It depends on Clojure, Integrant, Aero and `tools.logging`. Add the logging backend you want (Logback, `slf4j-simple`, ...);
without one, `tools.logging` writes through `java.util.logging`.

## A service in two files

`deps.edn` adds the entry point as an alias:

```clojure
{:paths   ["resources"]
 :deps    {io.github.af2b/borba-core-component {:git/url "https://github.com/AF2B/borba-core-component"
                                                :git/tag "v2.0.0"
                                                :git/sha "..."}
           org.slf4j/slf4j-simple              {:mvn/version "2.0.13"}}
 :aliases {:run {:main-opts ["-m" "borba.core.main"]}}}
```

`resources/config.edn` describes the system, with a section per profile where they differ:

```clojure
{:ig/system
 {:borba/lifecycle {}

  :borba/core
  {:service-name   "demo-service"
   :version        #or [#env APP_VERSION "dev"]
   :profile        #profile {:dev :dev :prod :prod}
   :lifecycle      #ig/ref :borba/lifecycle
   :drain-delay-ms #profile {:dev 0 :prod 1500}}}}
```

Run it with a profile:

```bash
clojure -M:run prod
```

```
[main] INFO borba.core - demo-service started (version dev, profile prod)
[main] INFO borba.core.system - system started, components: 2
```

On `SIGTERM` or Ctrl-C the service drains and stops:

```
[borba-shutdown] INFO borba.core - demo-service is draining for 1500 ms
[borba-shutdown] INFO borba.core - demo-service stopped after 2 s
[borba-shutdown] INFO borba.core.system - system stopped
```

## The command line

```bash
clojure -M:run <profile>
```

- The profile is the first argument, or else the `PROFILE` environment variable. There is no default: a default profile is how
  a development configuration ends up in production.
- A service that cannot start logs why, halts the components that had started, and exits with status **1**. Without a profile
  it exits with status **2**.
- A running service stays up until the process is terminated; the JVM then runs a hook that stops the system.

## Configuration

`config.edn` is read from the classpath with Aero, so every profile lives in one file and secrets come from the environment.
The tags are Aero's (`#profile`, `#env`, `#or`, `#include`, ...) and the readers of Integrant (`#ig/ref`, `#ig/refset`), plus
`#deep-merge`, which merges maps recursively and lets the later one win elsewhere:

```clojure
(require '[borba.core.config :as config])

(config/deep-merge {:server {:host "0.0.0.0" :port 8080}}
                   {:server {:port 9090}})
;; => {:server {:host "0.0.0.0", :port 9090}}
```

With `#include` it takes a base file and overrides a few keys for a profile:

```clojure
{:ig/system
 #profile {:dev  #include "base.edn"
           :prod #deep-merge [#include "base.edn"
                              {:borba/core {:drain-delay-ms 1500}}]}}
```

Integrant components register themselves with `defmethod`, in namespaces that nothing else requires. List them under
`:service/namespaces` and they are loaded before the system starts. A name that cannot be loaded fails the start naming it,
instead of surfacing later as a missing method:

```clojure
{:service/namespaces [com.example.payments.routes
                      com.example.payments.consumer]

 :ig/system {...}}
```

A component of the system that nothing registered, because its namespace is not listed, fails the start the same way: it names the
key and, when the key is one of the Borba libraries, the namespace that registers it.

```
no component is registered for :server/http: the namespace that registers it is not loaded; list borba.server.component under :service/namespaces
```

## Components

### `:borba/lifecycle`

Holds the state of the service, which readiness reports and shutdown moves forward. It takes no options. Refer to it from
`:borba/core`, and from whatever answers a readiness probe.

### `:borba/core`

Marks the service ready once it is up, logs what it is, and on the way down turns readiness off and waits.

| Option | What it is | Default |
|---|---|---|
| `:service-name` | The name logged at start and stop | `"borba-service"` |
| `:version` | The version logged at start | `"dev"` |
| `:profile` | The profile logged at start; it does not select the configuration | none |
| `:lifecycle` | `#ig/ref :borba/lifecycle`, the state to mark ready and to drain | none: no readiness is tracked |
| `:drain-delay-ms` | How long to wait between turning readiness off and halting the rest | `0` |
| `:after` | Refs to the components that must be running while the service is ready and when it drains | none |

`:after` is the way to say what the service waits for. Integrant starts a component after the ones it refers to and halts it
before them, so `:borba/core` is marked ready only once they are up, and begins draining while they are still running:

```clojure
:borba/core {:lifecycle #ig/ref :borba/lifecycle
             :after     [#ig/ref :server/http]}
```

## Graceful shutdown

On `SIGTERM`, the JVM runs the `borba-shutdown` hook, which stops the system. Integrant halts components in the reverse order
of their dependencies, which gives this sequence for the service above:

1. `:borba/core` halts first, because the server it comes `:after` is still up. Readiness turns false (`:draining`).
2. It waits `:drain-delay-ms`. The load balancer sees the failing readiness probe and stops routing; what was already routed is
   still served, because the server is running.
3. The server stops, then the components it needed, each after what needs it. `:borba/lifecycle` ends as `:stopped`.

Why wait: when an orchestrator stops an instance, taking it out of the load balancer and sending it `SIGTERM` happen at about
the same time, and the load balancer takes a few seconds to notice. A service that closes its listener on the signal resets the
requests that arrive in that window. Failing readiness first, and serving until the balancer has noticed, turns those resets
into ordinary requests. Set `:drain-delay-ms` to at least the time the balancer needs (the probe period times its failure
threshold, or the deregistration delay), and keep the grace period of the orchestrator longer than the drain plus the time the
rest of the system takes to stop.

### Reporting readiness

A readiness endpoint reads the state, and nothing else:

```clojure
(require '[borba.core.lifecycle :as lifecycle])

(defn readiness
  [state]
  (if (lifecycle/ready? state)
    {:status 200 :body {:status :ready}}
    {:status 503 :body {:status (lifecycle/state state)}}))

(readiness (lifecycle/mark-ready! (lifecycle/create)))
;; => {:status 200, :body {:status :ready}}

(readiness (lifecycle/create))
;; => {:status 503, :body {:status :starting}}
```

### The states

```
:starting -> :ready -> :draining -> :stopped
```

A service is *ready* when it can take requests, *draining* when it was told to stop and is finishing what it accepted, and
*stopped* when it is done. A service that fails to start goes from `:starting` straight to `:stopped`. A move against the
direction throws, and asking for the state the service is already in does nothing, so shutdown can be requested twice:

```clojure
(def state (lifecycle/create))
(lifecycle/mark-ready! state)
(lifecycle/begin-drain! state)
(lifecycle/ready? state)     ;; => false
(lifecycle/draining? state)  ;; => true

(lifecycle/mark-stopped! state)
(lifecycle/mark-ready! state)
;; throws ExceptionInfo "a service cannot move to that state from this one"
;;   {:error :borba.core.lifecycle/invalid-transition, :from :stopped, :to :ready}
```

## From the REPL

`borba.core.system` starts the system of a configuration without the command line, and keeps it as the running one. A process
runs one system, so you stop it before you start another:

```clojure
(require '[borba.core.system :as system]
         '[integrant.core :as ig])

(defmethod ig/init-key :demo/greeter [_ {:keys [name]}] (println "hello," name) {:name name})
(defmethod ig/halt-key! :demo/greeter [_ {:keys [name]}] (println "bye," name))

(def config {:ig/system {:demo/greeter {:name "Ana"}}})

(keys (system/start! config))
;; hello, Ana
;; INFO: system started, components: 1
;; => (:demo/greeter)

(system/start! config)
;; throws ExceptionInfo "a system is already running"
;;   {:error :borba.core.system/already-running}

(system/stop!)
;; bye, Ana
;; INFO: system stopped

(system/current)
;; => nil
```

When a component fails to start, the ones that had started are halted before the failure is thrown. Integrant does not do this
by itself: it leaves them running.

## Errors

Mistakes of configuration and of use throw `ex-info`, with the code under `:error` and what explains it beside it:

| Code | When |
|---|---|
| `:borba.core.config/invalid-profile` | The profile is not a keyword (`:profile` in the data) |
| `:borba.core.config/config-not-found` | There is no `config.edn` on the classpath, or no source to read |
| `:borba.core.system/no-system` | The configuration has no `:ig/system` map |
| `:borba.core.system/namespace-not-found` | A namespace of `:service/namespaces` cannot be loaded (`:namespace` in the data) |
| `:borba.core.system/unregistered-component` | A key of `:ig/system` has no component registered, because its namespace is not loaded (`:key` in the data, and `:namespace` when it is one of the Borba libraries) |
| `:borba.core.system/already-running` | `start!` was called while a system is running |
| `:borba.core.lifecycle/invalid-transition` | A move against the direction of the lifecycle (`:from` and `:to` in the data) |

## API

| Namespace | Function | What it does |
|---|---|---|
| `borba.core.main` | `-main`, `start` | Start the service from the command line; `start` returns the system or `{:exit status}` instead of exiting |
| | `run` | Starts the service and registers the shutdown hook |
| | `resolve-profile`, `shutdown-hook` | The profile of the command line, and the hook thread |
| `borba.core.system` | `start!`, `stop!`, `current` | Run one system and stop it |
| | `init`, `halt`, `require-namespaces` | Start or halt a system without keeping it |
| | `known-components` | The namespace of each component of the Borba libraries, for the message of a component that is not registered |
| `borba.core.config` | `load-config`, `read-config` | Read `config.edn`, or any source Aero reads, for a profile |
| | `deep-merge` | Merge maps recursively |
| `borba.core.lifecycle` | `create`, `state` | Make a lifecycle, and read its state |
| | `ready?`, `draining?`, `stopped?` | Ask about it |
| | `mark-ready!`, `begin-drain!`, `mark-stopped!`, `transition!` | Move it forward |

## Design notes

- **Failure to start is not silent.** The process exits with a status the supervisor can act on, and nothing is left running
  behind it.
- **The profile is explicit.** There is no default, so the environment a service runs in is always something someone chose.
- **Readiness is state, not an endpoint.** The library keeps the state and moves it; serving it is the job of whatever HTTP
  component the service has, so this library has no dependency on one.
- **The shutdown hook is a thread you can run.** `shutdown-hook` returns it, so a test runs it without ending the JVM.

## Development

```bash
make check      # lint, format, conventions, reflection, tests, coverage
make ci         # everything the pipelines enforce
```

See [CONTRIBUTING.md](CONTRIBUTING.md). The repository follows the [Borba standard](https://github.com/AF2B/borba-tooling/blob/main/docs/standard.md).

## License

[MIT](LICENSE)
