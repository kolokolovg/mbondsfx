# MOEX Bonds FX

Java 25 / Gradle 9.8.2 проект для доменной модели облигаций, явных расчетов, учета портфеля и
источников рыночных данных.

## Модули

- `:core` — чистый домен: агрегат облигации, вычисляемые результаты, денежные типы и учет.
- `:application` — use cases и исходящие порты; зависит от `:core` через Gradle `api`.
- `:integrations` — общие fixing DTO/API, транспорт/XML и provider SPI; `api :application` (и
  транзитивно `:core`).
- `:integrations-moex` — MOEX adapter и его raw-контракт `MarketDataPort`/`BondMarketData`;
  `implementation :integrations`.
- `:integrations-cbr` — CBR adapter; `implementation :integrations`.
- `:persistence-memory` — in-memory `LedgerRepository` и реестр инвестиционных счетов;
  `implementation :application`.
- Root compile-зависимости: `:core`, `:application`, `:integrations`; runtime modules:
  `:integrations-moex`, `:integrations-cbr`, `:persistence-memory`.
- SLF4J logging is root-only: `slf4j-api` is the root compile API, and `slf4j-simple` is its
  runtime backend. No other module depends on these libraries.
- JPMS descriptors, qualified provider contracts and root `ServiceLoader` composition are configured.
  Phase 4 passed module-path/distribution blackbox validation (18 checks); SQLite persistence is not implemented.
  The original JavaExec main-runner harness has since been migrated to JUnit without dropping those checks.

The CLI defaults to both capabilities and the memory repository; `--profile=all|moex|cbr` selects
capabilities without fetching market data. Provider IDs can be selected with `--bond-provider=`,
`--index-provider=`, and `--repository-provider=`. Startup also resolves and creates the
`InvestmentAccountRepository` from the factory with the same provider ID as the ledger repository;
this is composition/bootstrap, not an account-management command.

Accounting accepts caller-supplied broker/other commissions (`Commissions.total()` sums the explicit
components) and records nonzero fees separately from base buy/sell postings. Explicit withholding is
kept in non-spendable `TAX_WITHHELD`, separate from available `CASH`; after commit, the application can
append linked tax corrections without rewriting the original receipt or history. No tax rate or tax
amount is calculated automatically. See [accounting](docs/core-accounting.md) and
[investment-account contracts](docs/investment-accounts.md).

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

Требуется JDK 25: задайте `JAVA_HOME` или убедитесь, что Gradle обнаруживает установленный JDK.

```bash
./gradlew clean build
./gradlew :application:check :core:check :integrations:check :integrations-moex:check :integrations-cbr:check :persistence-memory:check
./gradlew run
./gradlew :core:test --tests '*DomainContractTests'
./gradlew :test --tests 'ru.onlychill.mbondsfx.ModulePathContractTests'
./gradlew jacocoAggregateReport
```

Проверки используют обычный Gradle `Test` task и JUnit Jupiter; `check` запускает их автоматически.
HTML/XML отчеты находятся в `<project>/build/reports/tests/test/` и
`<project>/build/test-results/test/`. Для выборочного запуска используйте Gradle `--tests`, как в
примерах выше. Офлайн-проверка: `./gradlew --offline :application:check :core:check :integrations:check :integrations-moex:check :integrations-cbr:check :persistence-memory:check`
— только если Gradle distribution и нужные зависимости, включая JUnit и JaCoCo, уже закешированы.
Root `test` зависит от `installDist`; реальные `java`/`javac` subprocesses JUnit-suite запускают
произведенный distribution и fixtures на module path. Сам тестовый JVM и `compileTestJava` остаются
на classpath (`inferModulePath = false`), поэтому обычные classpath tests не доказывают JPMS
encapsulation; `opens` и `--add-opens` не используются.
Стандартные `test`/`check` создают per-module JaCoCo HTML/XML reports в
`<project>/build/reports/jacoco/test/`. `./gradlew jacocoAggregateReport` запускает все семь test
tasks; общий HTML/XML отчет находится в `build/reports/jacoco/jacocoAggregateReport/`. JaCoCo
использует tool version 0.8.15. Root java/javac module-path subprocesses не instrumented и не
учитываются в coverage.

## Документация

- [Методики расчетов и реестр первоисточников](docs/README.md)
- [Архитектурный план и фактическая топология модулей](docs/architecture-plan.md)
- [Брокерский счет, ИИС-3 и подтверждение купонов](docs/investment-accounts.md)
- [Налоги и оценка досрочного закрытия ИИС-3](docs/bond-tax-and-iis3.md)
- [Доменная модель облигаций](docs/core-domain.md)
- [Учет портфеля](docs/core-accounting.md)
- [Интеграции рыночных данных](docs/core-market-data.md)

Расчеты требуют явных входных данных. Поддержка формул не универсальна: RUONIA averaging не
реализован, автоматический CPI-источник и календарь не выбраны, а индексированные выпуски могут
требовать объявленные `KnownCoupon` суммы или формулы конкретного выпуска.
