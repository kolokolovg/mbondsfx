# ru.onlychill.mbondsfx

Java 21 / Gradle проект для доменной модели облигаций, явных расчетов, учета портфеля и источников
рыночных данных.

## Модули

- `:core` — чистый домен: агрегат облигации, вычисляемые результаты, денежные типы и учет.
- `:application` — use cases и исходящие порты; зависит от `:core` через Gradle `api`.
- `:integrations` — общие fixing DTO/API, транспорт/XML и provider SPI; `api :application` (и
  транзитивно `:core`).
- `:integrations-moex` — MOEX adapter и его raw-контракт `MarketDataPort`/`BondMarketData`;
  `implementation :integrations`.
- `:integrations-cbr` — CBR adapter; `implementation :integrations`.
- `:persistence-memory` — in-memory `LedgerRepository`; `implementation :application`.
- Root compile-зависимости: `:core`, `:application`, `:integrations`; runtime modules:
  `:integrations-moex`, `:integrations-cbr`, `:persistence-memory`.
- JPMS descriptors, qualified provider contracts and root `ServiceLoader` composition are configured.
  Phase 4 passed module-path/distribution blackbox validation (18 checks); SQLite persistence is not implemented.

The CLI defaults to both capabilities and the memory repository; `--profile=all|moex|cbr` selects
capabilities without fetching market data. Provider IDs can be selected with `--bond-provider=`,
`--index-provider=`, and `--repository-provider=`.

## Инструкции для AI-агентов

- [Общие правила и карта размещения](AGENTS.md)
- [Правила `:core`](core/AGENTS.md)
- [Правила `:application`](application/AGENTS.md)
- [Правила `:integrations`](integrations/AGENTS.md)
- [Правила `:integrations-moex`](integrations-moex/AGENTS.md)
- [Правила `:integrations-cbr`](integrations-cbr/AGENTS.md)
- [Правила `:persistence-memory`](persistence-memory/AGENTS.md)

Перед изменениями прочитайте корневые правила и инструкции затрагиваемого модуля.

## Сборка и проверки

Требуется JDK 21: задайте `JAVA_HOME` или убедитесь, что Gradle обнаруживает установленный JDK.

```bash
./gradlew clean build
./gradlew :application:check :core:check :integrations:check :integrations-moex:check :integrations-cbr:check :persistence-memory:check
./gradlew run
```

Проверки модулей запускают dependency-free Java runners через `JavaExec`, а не через JUnit.
Для офлайн-проверки используйте `./gradlew --offline :application:check :core:check :integrations:check :integrations-moex:check :integrations-cbr:check :persistence-memory:check`
только если Gradle distribution и нужные зависимости уже закешированы.
Root `check` additionally runs `modulePathContractTest` against the produced installation; classpath
contract runners do not prove JPMS encapsulation.

## Документация

- [Доменная модель облигаций](docs/core-domain.md)
- [Учет портфеля](docs/core-accounting.md)
- [Интеграции рыночных данных](docs/core-market-data.md)

Расчеты требуют явных входных данных. Поддержка формул не универсальна: RUONIA averaging не
реализован, автоматический CPI-источник и календарь не выбраны, а индексированные выпуски могут
требовать объявленные `KnownCoupon` суммы или формулы конкретного выпуска.
