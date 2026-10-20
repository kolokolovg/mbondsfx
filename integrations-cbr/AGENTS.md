# Instructions for `:integrations-cbr`

These rules inherit the root [`AGENTS.md`](../AGENTS.md). This vendor provider depends only on
the shared `:integrations` contracts and has no third-party dependencies.

- `ru.onlychill.mbondsfx.integrations.cbr.internal` contains the CBR adapter and provider factory.
  Keep implementation details internal to this provider module; the shared SPI is
  `integrations.provider.IndexValueSourceProvider` and the returned calculation port is the core
  `IndexValueSource` through shared `IndexValueSourceAdapter`.
- `CbrProviderFactory` uses provider ID `cbr`. It creates the JDK HTTP client/transport but does not
  make network requests during construction or `create`. Keep an injectable fake `Transport` seam
  for offline tests. CBR values are exact-index/exact-date only; do not forward-fill or alter the
  adapter's reported value conversion.
- Configuration is supplied as immutable `ProviderContext` strings. Only `endpoint`,
  `connect-timeout`, and `request-timeout` are supported; endpoint uses an HTTP(S) URL without
  query/fragment, timeout values use positive ISO-8601 `Duration` strings. Reject unknown or invalid
  settings. Defaults are the official CBR SOAP endpoint, 10 seconds connect timeout, and 30 seconds
  request timeout.
- `CbrProviderContractTests` is a dependency-free JavaExec runner with copied CBR contract
  assertions and provider/configuration checks. Its fakes must not access live network.
- Its JPMS descriptor requires shared integrations and JDK `java.net.http`/`java.xml`, provides the
  typed index SPI, and exports no implementation package. Service binding needs module-path evidence.

Run `./gradlew --offline :integrations-cbr:check` with JDK 21 for offline validation.
