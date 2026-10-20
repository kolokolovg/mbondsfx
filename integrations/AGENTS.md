# Инструкции для `:integrations`

Общие правила находятся в [корневом `AGENTS.md`](../AGENTS.md); они наследуются здесь. Эти
инструкции относятся только к `:integrations` и уточняют размещение кода, работающего с внешними
источниками.

## Размещение и зависимости

- `ru.onlychill.mbondsfx.integrations.provider` — shared typed capability SPIs:
  `IndexValueSourceProvider` возвращает доменный `IndexValueSource`, а
  `BondReferenceDataSourceProvider` возвращает `application.port.BondReferenceDataSource`;
  `ProviderContext` несет настройки провайдера.
- `ru.onlychill.mbondsfx.integrations.index` — внешние `IndexFixing` DTO,
  `IndexFixingSource` и `MissingFixingException`.
- `ru.onlychill.mbondsfx.integrations.transport` — `Transport`, `TransportException` и
  `HttpTransport`, а также независимый контракт `HttpStatusException` для HTTP status failures.
- `ru.onlychill.mbondsfx.integrations.xml` — публичный `SecureXml` и защищенные XML helpers.
- `ru.onlychill.mbondsfx.integrations.adapter` — generic `StaticFixings` и
  `IndexValueSourceAdapter`; vendor-адаптеры здесь не размещаются.

Gradle `api` зависимость модуля — `:application` (и через него `:core`); поэтому оба типа в
provider SPI доступны потребителям через контракты. После фазы 3 vendor-код разделен: MOEX adapter
и raw `integrations.marketdata(.model)` контракты принадлежат `:integrations-moex`; CBR adapter —
`:integrations-cbr`. Shared `:integrations` оставляет generic SPI, fixing API, transport/XML и fixing
bridge. Root composition compile dependencies — `:core`, `:application`, `:integrations`, а
runtime providers/memory выбираются root через три typed `ServiceLoader` catalogs. JPMS descriptors
теперь настроены: shared module transitively requires application, unconditionally
exports only `integrations.provider`, and qualified-exports transport/XML/index/adapter only to MOEX
and CBR provider modules. Provider bootstrap lives in the root composition module. Module-path
blackbox proof remains the phase4 acceptance; classpath tests are not JPMS evidence.
Core/application-модели не должны импортировать адаптеры, транспорт или сырые DTO. Не превращайте
MOEX-ответ в `BondDefinition` и не переводите незнакомые поля, формулы или значения в
предположительные доменные значения без отдельного явного mapping требования.

`ProviderContext` defensively copies settings into an immutable map. Null map/key/value and blank
key/value are rejected; nonblank key/value text is retained verbatim (no trimming, case folding,
or implicit defaults). Shared SPI defines no reserved setting keys or schema: keys are opaque and
owned/validated by each provider; an empty map means that no explicit settings were supplied.
Providers own construction/configuration of their transport. Discovery/selection and network
bootstrap are not provided by this module in phase 1.

CBR adapter в `:integrations-cbr` и `StaticFixings` из этого модуля реализуют integration
`IndexFixingSource`. Когда core расчету нужен index value, оборачивайте источник через
`new IndexValueSourceAdapter(source)` и передавайте результат как доменный
`core.bond.calculation.port.IndexValueSource`. Не передавайте `IndexFixing` или `IndexFixingSource`
в core. Мост проверяет запрошенные `IndexId`/дату и DTO metadata,
возвращает исходный `BigDecimal` без изменения; отсутствие внешнего значения переводит в
`core.bond.calculation.MissingIndexValueException` с запрошенными ID/датой. Null или несовпадение
метаданных — ошибка; forward-fill запрещен.

Библиотеку для конкретного протокола размещайте только в модуле/минимальном build scope
соответствующего адаптера; не превращайте общий integration слой в корзину необязательных
зависимостей. `java.sql` сам по себе не содержит JDBC driver. Если SQLite будет явно одобрен
позднее и для него будет создан отдельный persistence-adapter subproject, проверенный лёгкий
драйвер относится только туда — не в общий `:integrations` и не в `:core`. Такого subproject в
текущей структуре нет.

## Транспорт, безопасность и проверки

Тесты адаптеров используют fake `Transport` и fixture responses; тесты и создание адаптера/
транспорта не должны инициировать live network. Сохраняйте настраиваемые endpoints для фикстур,
таймауты, обработку HTTP-ошибок и текущую защиту XML в `xml.SecureXml` (в частности, защиту от
внешних сущностей/DTD). `HttpStatusException` остается cause внутри `TransportException`, но
является общим контрактом, а не типом реализации `HttpTransport`. Не ослабляйте ограничения на
обработку источника ради «починки» данных.

Контрактные проверки запускаются стандартным Gradle `test` task с JUnit Jupiter. Shared `AdapterContractTests` покрывает только
generic `StaticFixings` и transport; shared `IntegrationModelContractTests` покрывает fixing API,
bridge, calculation и transport; SPI/settings assertions принадлежат `ProviderContractTests`.
MOEX adapter/raw DTO assertions размещаются в тестах `:integrations-moex`; CBR adapter/XML assertions
— в тесте `:integrations-cbr`. XML hardening остается покрыт проверками обоих провайдеров.
Все module tests остаются classpath-only и не являются JPMS proof.

```bash
./gradlew :integrations:check
./gradlew --offline :integrations:check
./gradlew :integrations:test --tests '*ProviderContractTests'
```

Offline-флаг использует только уже закешированные Gradle distribution и зависимости; см. также
корневые инструкции.
