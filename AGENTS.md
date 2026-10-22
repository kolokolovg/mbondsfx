# Инструкции для агентов

## Проект и границы модулей

Это Java 25 / Gradle проект с явной моделью облигаций и денежных потоков, доменными расчетами,
учетом портфеля и интеграциями рыночных данных. Перед изменениями прочитайте эти правила и
инструкции затрагиваемого модуля (`core/AGENTS.md`, `application/AGENTS.md`, `integrations/AGENTS.md`,
`integrations-moex/AGENTS.md`, `integrations-cbr/AGENTS.md` или
`persistence-memory/AGENTS.md`); локальные инструкции уточняют, но не отменяют эти правила.

- `:core` — чистый домен: модель облигации, расчеты, учет, деньги и идентификаторы.
- `:application` — use cases и исходящие порты; Gradle `api` зависит только от `:core`.
- `:integrations` — общие fixing DTO/API, transport/XML, typed provider SPI и generic fixing bridge;
  Gradle `api` зависит от `:application` и транзитивно предоставляет `:core`.
- `:integrations-moex` — MOEX-адаптер и его raw-контракт (`MarketDataPort`, `BondMarketData`);
  Gradle `implementation` зависит от `:integrations`.
- `:integrations-cbr` — CBR-адаптер; Gradle `implementation` зависит от `:integrations`.
- `:persistence-memory` — in-memory реализации application `LedgerRepository` и
  `InvestmentAccountRepository`; Gradle
  `implementation` зависит от `:application`.
- Root compile-зависимости — `:core`, `:application`, `:integrations`; `runtimeOnly` зависимости —
  `:integrations-moex`, `:integrations-cbr`, `:persistence-memory`. Только root использует SLF4J
  (`slf4j-api` на compile/runtime, `slf4j-simple` только на runtime); остальные production-модули от него не зависят.

Направление зависимостей: `:integrations` → `:application` → `:core`; `:integrations-moex` и
`:integrations-cbr` → `:integrations` (транзитивно `:application`/`:core`);
`:persistence-memory` → `:application`/`:core`. `:core` не зависит от вышележащих модулей, а root
собирает единственную composition root. Реальные JPMS descriptors настроены: root требует SLF4J API
для логирования. Core экспортирует шесть доменных пакетов; application транзитивно требует core;
shared integrations транзитивно требует application, экспортирует provider SPI всем и транспорт/XML/index/generic adapter только
MOEX/CBR; providers и memory не экспортируют пакеты. Root module `ru.onlychill.mbondsfx` использует
четыре typed SPIs и загружает runtime providers через `ServiceLoader`. Root module-path blackbox suite
доказывает границы произведенного distribution и остается обязательной ongoing-проверкой; classpath
tests ее не заменяют. `IndexFixing`, `IndexFixingSource` и `MissingFixingException` в
`integrations.index` описывают внешний fixing API и DTO. Доменный порт — `IndexValueSource` в
`core.bond.calculation.port`; он принимает `IndexId` и дату и возвращает `BigDecimal`, не раскрывая
integration-типы. `IndexValueSourceAdapter` в `integrations.adapter` — явный bridge; доменная
`MissingIndexValueException` сохраняет запрошенные `index()` и `date()`.

## Размещение изменений

Сохраняйте разницу между тремя видами данных:

- `BondDefinition` в `ru.onlychill.mbondsfx.core.bond` — агрегат выпуска и его связанные value
  objects: заявленные условия и даты.
- `BondCalculations` в `core.bond.calculation` — доменный сервис; `AccruedInterest` — вычисленный
  результат, а не агрегат или исходные условия выпуска.
- `BondMarketData` в `ru.onlychill.mbondsfx.integrations.marketdata.model` — MOEX raw DTO,
  размещенная в `:integrations-moex`, а не доменная модель. Не переименовывайте и не нормализуйте
  ответы MOEX молча в термины домена.

Решение о размещении:

- Условия выпуска, описания купонных формул (`FloatingRateFormula`, `IndexationRule`) и их
  инварианты — `core.bond`; выполнение расчетов, `MissingIndexValueException` и производные
  денежные результаты — `core.bond.calculation`; порт `IndexValueSource` — `core.bond.calculation.port`.
- Денежные значения и календарные/day-count примитивы — `core.money`; `SecurityId`, `IndexId` и
  остальные типизированные ID — `core.id`.
- Проводки, позиции и операции учета — `core.ledger`. Не переносите `OperationResult` только
  из-за названия: у него package-private конструктор, связанный с `BondOperations`. Денежные
  остатки `CASH` и удержания `TAX_WITHHELD` стандартной policy ведутся отдельно по портфелю и валюте; `TAX_WITHHELD`
  не входит в доступный для трат `cashBalances()`; его per-currency read view — `taxWithheldBalances()`.
  Покупки и продажи учитывают явно переданные
  брокерскую и прочую комиссию через `Commissions`/`total()` и отдельную проводку
  `D COMMISSION_EXPENSE / C CASH`; ненулевое удержание также отдельная проводка
  `D TAX_WITHHELD / C CASH`. Не рассчитывайте налог или тариф автоматически.
  `TAX_CORRECTION` — append-only операция с `Posting.adjustsOperation()` на исходную tax-операцию;
  `Ledger.taxWithheldForOperation()` дает ее накопленное удержание, неотрицательное после поправок.
  Идентичность счета задает `AccountCode`, роли движка — неизменяемый `LedgerChart`.
  Chart задается явно в `Ledger.open(portfolio, chart, allowNegativeCash)`, хранится в
  `LedgerState` и заморожен на весь срок жизни ledger; restore не требует внешней конфигурации.
  `BondAccountingPolicy` — единственный источник словаря bond-счетов; `BondOperations` —
  instance-генератор с policy. Движок не знает стандартных имен счетов и bond-policy,
  но сохраняет enum-диспетчеризацию `OperationKind`, включая `TAX_CORRECTION`.
  Composition root — единственная точка создания policy для сборки приложения: передавайте
  одну policy генератору и всем accounting-сервисам через конструкторы. Сервисы отвергают
  snapshot с другим chart по value-equality. Сейчас `Main` accounting-сервисы не создает.
  Первый успешный CAS фиксирует chart, но не подтверждает его подлинность; движок проверяет
  структуру операций, не их происхождение. `balance()` отклоняет unknown/scoping как IAE.
- Use cases и исходящие порты — `application`: `BondReferenceDataSource` и его sparse read-модель,
  порты repository/journal/factory и их контракты; сервис применения операций учета также здесь.
  `InvestmentAccount`, `InvestmentAccountRepository`/factory, подтверждение фактического купона и
  `TaxCorrectionService` также принадлежат application и не меняют словарь `core.ledger.AccountCode`.
  `CouponReceiptService` принимает только фактические `gross`, `tax` и `net`; `TaxCorrectionService`
  принимает caller-supplied corrected total и фиксирует delta отдельным CAS-коммитом, не переписывая
  исходный журнал/receipt и не вычисляя налог.
  Этот модуль зависит от core, но не от интеграций или конкретных адаптеров.
- `integrations` владеет fixing DTO/API/ошибкой отсутствующего внешнего fixing (`integrations.index`),
  generic transport (`integrations.transport`), typed provider SPI (`integrations.provider`),
  защищенным XML helper (`integrations.xml`) и generic `IndexValueSourceAdapter`/`StaticFixings`
  (`integrations.adapter`). Здесь не размещайте vendor-адаптеры.
- `integrations-moex` владеет `integrations.marketdata(.model)` raw-контрактом и
  `integrations.moex.internal.MoexBondAdapter`; `integrations-cbr` владеет
  `integrations.cbr.internal.CbrIndexAdapter`. Эти DTO/адаптеры не являются core domain models.
- `persistence-memory` реализует application ledger и investment-account repository ports; `:core` и
  `:application` не зависят от этой реализации.

Внутри `:core` соблюдайте эту карту зависимостей: `bond -> id/money`,
`bond.calculation` (включая `calculation.port`) `-> bond/id/money`, `ledger -> id/money`; пакеты
`id` и `money` — листья, без зависимостей на другие пакеты `core`. Не вводите в домен внешние
DTO, UI- или DB-модели.

## Сохранение контрактов

- Используйте `BigDecimal` для ставок и денежных расчетов; не заменяйте его `double`/`float` и
  не меняйте округление или финансовую семантику без явного требования.
- Даты, ID, ставки и внешние данные передаются явно. Не вводите часы, случайные ID, поиск
  «последнего» fixing, интерполяцию или перенос дат по умолчанию. Расчетный `IndexValueSource`
  получает точные `IndexId` и дату; внешний `IndexFixingSource` адаптируется к нему явно.
- Доменные модели и коллекции должны оставаться неизменяемыми; сохраняйте проверку дат и другие
  проверяемые инварианты. Не подменяйте неизвестные значения нулем или предполагаемыми правилами
  выпуска.
- Не добавляйте внешние production-зависимости и не меняйте финансовое поведение без явного
  требования. Production SLF4J остается только в root composition module; `slf4j-simple` — его runtime backend.
  Для test/coverage используются JUnit Jupiter 5.12.2, JUnit Platform 1.12.2 и JaCoCo
  0.8.15; не переносите тестовые библиотеки или JaCoCo agent в production/runtime classpath.
- Для тестового пилота `:core` разрешены jqwik API/engine 1.9.2 (EPL-2.0, явно одобрена
  пользователем) и ядро ArchUnit 1.5.1 (Apache-2.0/BSD). ArchUnit использует SLF4J API только
  в test scope. JUnit Platform остается 1.12.2; отдельный ArchUnit engine не используется.
  Лицензия кода проекта не меняется; сторонние лицензии и notices перечислены в `NOTICE`.
- Если архитектурную границу намеренно меняют, обновите соответствующие инструкции и документы
  вместе с кодом; не оставляйте их противоречащими новой границе.

## Внешние зависимости и лицензии

- Вне UI не выбирайте тяжёлые фреймворки по умолчанию: не добавляйте DI/ORM/boot/reactive stacks
  или framework для миграций без явного обоснования и одобрения. Исключение для UI не означает
  автоматического разрешения на зависимости.
- Сначала оценивайте API JDK 25 (`HttpClient`, защищённый XML API, concurrency, JDBC,
  `ServiceLoader`) или небольшой проектный компонент, если это соразмерно задаче. Поддерживаемая,
  широко используемая лёгкая библиотека с permissive-лицензией допустима, если необходимость
  доказана; Apache Commons — пример, не blanket-разрешение на все артефакты или их лицензии.
  Не пишите собственную замену зрелой реализации безопасности, SQL-драйвера или протокола без
  причины.
- До любого одобренного добавления зависимости проверьте точные координаты и версию артефакта, лицензию,
  транзитивные зависимости, встроенные native-компоненты/notices и JPMS module name. Copyleft и
  proprietary-лицензии требуют отдельного явного одобрения; молча добавлять их нельзя.
- `java.sql` — только JDBC API, не драйвер. Если позднее будет явно одобрено persistence через
  SQLite, потребуется проверенный лёгкий SQLite JDBC driver в persistence adapter, никогда в
  `:core`. Проектные миграции можно рассмотреть отдельно; SQLite и migration stack не реализованы.
  JPMS descriptors настроены в фазе 4, но classpath JUnit tests не доказывают module-path
  encapsulation; для этого root JUnit suite запускает отдельные java/javac subprocesses против
  произведенного distribution по module path.

## Сборка и проверки

Требуется JDK 25. Установите `JAVA_HOME` на JDK 25; если переменная не задана, убедитесь, что
Gradle обнаруживает установленный JDK 25. Не фиксируйте локальный путь к JDK в документации.

```bash
./gradlew clean build
./gradlew :application:check :core:check :integrations:check :integrations-moex:check :integrations-cbr:check :persistence-memory:check
./gradlew --offline :application:check :core:check :integrations:check :integrations-moex:check :integrations-cbr:check :persistence-memory:check
./gradlew :core:test --tests '*DomainContractTests'
./gradlew :test --tests 'ru.onlychill.mbondsfx.ModulePathContractTests'
./gradlew jacocoAggregateReport
./gradlew cyclonedxBom
```

`cyclonedxBom` (build-плагин `org.cyclonedx.bom` 3.5.1, Apache-2.0; в production/runtime classpath не попадает)
создает CycloneDX 1.6 SBOM только по runtime-зависимостям в `build/reports/cyclonedx/bom.{json,xml}`
для CI/SCA-сканеров.

Mutation testing (PIT, Apache-2.0) одобрен только как build/test-инструмент для `:core`: плагин
`info.solidsoft.pitest` 1.19.0, PIT 1.30.1 и JUnit 5 plugin 1.2.3 подключены в `core/build.gradle`.
Запуск вручную: `./gradlew :core:pitest` (отчеты в `core/build/reports/pitest/`). Задача НЕ входит
в `check`, `build` и основную CI-сборку; порог мутационного скора (`mutationThreshold` в `core/build.gradle`) проверяет сама задача `:core:pitest`; еженедельный запуск
идет отдельным workflow `.github/workflows/mutation.yml`. Не переносите PIT в production/runtime
classpath и не добавляйте в другие модули без нового одобрения. Подробности — в
[docs/guides/verification.md](docs/guides/verification.md).

Проверки используют стандартный Gradle `Test` task с JUnit Jupiter и `useJUnitPlatform()`;
`check` автоматически включает `test`. HTML/XML reports доступны в
`<project>/build/reports/tests/test/` и `<project>/build/test-results/test/`. `--tests` позволяет
запускать один тестовый класс или метод. `--offline` запрещает Gradle обращаться к сети и сработает
только если Gradle distribution и нужные зависимости, включая JUnit и JaCoCo, уже доступны локально; при
отсутствующем кеше это не заменяет обычную сборку.
Root `test` зависит от `installDist` и содержит реальный JUnit module-path suite. Тестовый JVM и
`compileTestJava` остаются на classpath (`inferModulePath = false`); JPMS-доказательство дают его
java/javac subprocesses, запускающие произведенный distribution/фикстуры на module path, без
`opens` или `--add-opens`. Обычные classpath tests не являются доказательством encapsulation.
`test`/`check` создают per-project JaCoCo HTML/XML reports в
`<project>/build/reports/jacoco/test/`; `jacocoAggregateReport` запускает все семь `test` tasks и
создает root HTML/XML отчет в `build/reports/jacoco/jacocoAggregateReport/`. JaCoCo использует
tool version 0.8.15; root java/javac module-path subprocesses не instrumented и не входят в
coverage данных отчета.
`check` каждого подпроекта (кроме root) также запускает `jacocoTestCoverageVerification` с консервативными
порогами LINE/BRANCH (см. `coverageThresholds` в `build.gradle`; для `:core` дополнительно `core.ledger`);
root не проверяется, так как его production-код покрывается только out-of-process module-path subprocesses.

## Версионируемые спецификации учета

Целевая архитектура double-entry фиксируется в `docs/specs/double-entry/`;
реестр версий и правила их изменения находятся в `README.md` этого каталога.
Перед реализацией или дальнейшей доработкой этой архитектуры сначала актуализируйте
полную спецификацию и получите согласование пользователя. Утвержденные версии не
перезаписывайте: изменения оформляйте новой версией. Не считайте статус проекта
спецификации доказательством согласования или реализации. Описание текущего учета
в `docs/guides/core-accounting.md` обновляйте вместе с кодом, а не заранее по целевому проекту.

## Работа в репозитории

Сохраняйте все не относящиеся к задаче изменения и staged work. Не меняйте индекс Git и не
выполняйте автоматический commit. Не перезаписывайте чужие изменения в пределах изменяемых
файлов.

При создании нового Gradle-модуля добавьте в него локальный `AGENTS.md` с наследованием этих
правил и только модульными уточнениями.
