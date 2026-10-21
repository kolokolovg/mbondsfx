# Инструкции для `:integrations-moex`

Общие правила наследуются из [корневого `AGENTS.md`](../AGENTS.md). Этот модуль — vendor-specific
адаптер MOEX и зависит только от shared `:integrations` через Gradle `implementation`; application
и core доступны транзитивно через shared API.

- `ru.onlychill.mbondsfx.integrations.marketdata` и `.marketdata.model` принадлежат этому модулю:
  `MarketDataPort` и `BondMarketData` — MOEX raw-контракт. Сохраняйте исходные поля, значения и
  raw-семантику; эти packages не существуют в shared `:integrations`.
- `MoexBondAdapter` находится в `ru.onlychill.mbondsfx.integrations.moex.internal`. Он использует
  shared `Transport` и защищенный `SecureXml`; fake `Transport` и endpoint пригодны для офлайн
  проверок. Создание adapter/provider не отправляет сетевые запросы.
- `MoexProviderFactory` реализует shared `BondReferenceDataSourceProvider`, имеет ID `moex` и
  публичный конструктор без аргументов. Module descriptor requires только shared `:integrations` и
  необходимые JDK `java.net.http`/`java.xml`; он provides SPI и не экспортирует implementation packages.
  Runtime service binding подтверждается отдельным module-path runner, не classpath test suite.
- Provider принимает `moex.endpoint` (HTTP(S) URI без query/fragment), `moex.connectTimeout` и
  `moex.requestTimeout` (положительные ISO-8601 `Duration`), а также положительные
  `moex.maxResponseBytes` (512 KiB), `moex.maxXmlDepth` (8), `moex.maxXmlAttributes` (32),
  `moex.maxXmlAttributeLength` (8192), `moex.maxXmlTextLength` (1024),
  `moex.maxDescriptionRows` (256) и `moex.maxPageRows` (128). Пустые настройки допустимы;
  значения по умолчанию только настраивают транспорт/endpoint и защитные бюджеты. Неизвестные
  ключи `moex.*` отклоняются, настройки других capability остаются непрозрачными для этого провайдера.
- Sparse mapping публикует только raw-факты: `FACEVALUE` вместе с `FACEUNIT` как единую пару;
  MOEX response не предоставляет observation timestamp в текущем raw DTO. Сырые coupon rows —
  недифференцированное расписание без явного маркера «следующий» или даты `as-of`, поэтому
  `nextCoupon` остается пустым даже при наличии coupon schedule. Не выбирайте первую строку и не
  создавайте `BondDefinition`; не подставляйте единицы, даты или нули.
- Проверки — JUnit Jupiter tests в package
  `ru.onlychill.mbondsfx.integrations.moex`, включая MOEX pagination/mapping, XXE/DTD и raw DTO
  assertions. Generic fixing/transport assertions живут в `:integrations`, CBR SOAP/XML/fault
  assertions — в `:integrations-cbr`. `compileTestJava` и обычные module tests остаются на classpath;
  runtime service binding подтверждается root JPMS blackbox suite:

```bash
./gradlew :integrations-moex:check
./gradlew --offline :integrations-moex:check
./gradlew :integrations-moex:test --tests '*MoexProviderContractTests'
```
