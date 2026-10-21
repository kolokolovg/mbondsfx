# Инструкции для `:application`

Общие правила находятся в [корневом `AGENTS.md`](../AGENTS.md); они наследуются здесь. Этот модуль
зависит только от `:core` и содержит use cases и исходящие порты. Не импортируйте сюда `:integrations`
или конкретные реализации repository/provider.

`module-info.java` requires transitive `ru.onlychill.mbondsfx.core` (matching `api :core`) and exports
only `application.port` and `application.service`. Keep implementation providers out of this module;
JUnit tests compile and run on the classpath (`compileTestJava` has `inferModulePath = false`).
`ApplicationArchitectureContractTests` continues to receive its explicit source/build paths from the
Gradle `test` task.

- `application.port.BondReferenceDataSource` — минимальный sparse-контракт; сохраняйте только
  явно сообщённые факты и их source ID/единицы. Не создавайте из них `BondDefinition`.
- `LedgerRepository.commit` — атомарный CAS snapshot+journal contract; сервис строит переход только
  через `Ledger.apply`, передаёт явные portfolio/policy/operation и никогда не повторяет конфликт
  автоматически.
- Инвестиционные счета и их `InvestmentAccountRepository`/`InvestmentAccountRepositoryFactory` —
  отдельные от `core.ledger.AccountKind` application-контракты. Отсутствие записи означает unknown;
  не выводите BROKERAGE как значение по умолчанию. `CouponReceiptService` принимает только три
  известные суммы фактического получения и записывает операцию через `BondOperations.coupon` и
  `LedgerApplicationService`; купонное расписание не является проводкой.
- `TaxCorrectionService.correct` принимает явно заданный скорректированный total удержания для
  исходной операции, уже содержащей налоговую проводку. Сервис читает один snapshot, вычисляет только
  delta от накопленного удержания и сохраняет append-only correction через `Ledger.apply` и CAS
  `LedgerRepository.commit`, без повторов. Не вычисляйте налог, не меняйте исходный journal/receipt,
  позицию или доход; сохраняйте проверки валюты, cash policy, no-op и дубликата.
- `InvestmentAccount.newIis3WithMinimumTermForOpeningDate` — отдельная factory для нового счета с
  календарным минимумом по подтвержденной шкале НК 219.2п4; custom `Iis3Terms` остаются только
  структурными данными и не проходят правовую валидацию. Не переносите этот минимум на существующие
  записи и не выводите налоговую льготу из срока.
- `EarlyClosureAssessment` — только partial structural assessment: даты и явно известные компоненты.
  Не выводите начало пеней из открытия счета или дат вычетов; неизвестные компоненты остаются unknown,
  а total появляется только при наличии всех трех. Налоговые правила требуют подтвержденных источников.
- `PenaltyEstimateService` вычисляет только сценарий по caller-supplied RUB недоимке после adjustments,
  полузакрытым интервалам и десятичной key rate: 1/300 за календарный день, без compounding и с одной
  финальным HALF_UP-округлением. Не ищите ставку, дату начала или юридическую недоимку; список интервалов
  охватывает только указанные даты, gaps не заполняются, отсутствие input остается unknown.
- Новые storage/provider adapters принадлежат отдельным реализациям, не этому модулю. Для нового
  Gradle-модуля при его создании нужны локальные правила, наследующие корневые.

Проверки — стандартный Gradle `test` task на JUnit Jupiter; `check` запускает его автоматически.
`--tests` выбирает тесты по классу/методу, а отчеты доступны в
`application/build/reports/tests/test/` и `application/build/test-results/test/`:

```bash
./gradlew :application:check
./gradlew --offline :application:check
./gradlew :application:test --tests '*ApplicationContractTests'
```
