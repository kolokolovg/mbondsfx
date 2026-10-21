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
- Configuration is supplied as immutable `ProviderContext` strings. Supported keys are `endpoint`,
  `connect-timeout`, `request-timeout`, `max-response-bytes`, `max-xml-depth`,
  `max-xml-attributes`, `max-xml-attribute-length`, `max-xml-text-length`, and `max-series-rows`.
  Endpoint uses an HTTP(S) URL without query/fragment, timeout values use positive ISO-8601
  `Duration` strings, and all numeric limits must be positive and within their supported integer
  range. Reject unknown or invalid settings. Defaults are the official CBR SOAP endpoint, 10 seconds
  connect timeout, 30 seconds request timeout, 2 MiB per response, XML limits of depth 32, 16
  attributes per element, 1024 characters per attribute value/text field, and 20,000 fixing rows.
- `CbrProviderContractTests` is a JUnit Jupiter test class with CBR contract assertions and
  provider/configuration checks. Its fakes must not access live network. Tests run on the classpath;
  service binding is established by the root module-path blackbox tests.
- Its JPMS descriptor requires shared integrations and JDK `java.net.http`/`java.xml`, provides the
  typed index SPI, and exports no implementation package. Service binding needs module-path evidence.

Run `./gradlew --offline :integrations-cbr:check` with JDK 25 for offline validation. Use
`./gradlew :integrations-cbr:test --tests '*CbrProviderContractTests'` to filter the JUnit suite.
