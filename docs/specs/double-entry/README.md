# Спецификации подсистемы double-entry учета (core.ledger)

Каталог содержит нормативные спецификации подсистемы двойной записи:
`core.ledger`, её взаимодействия с `application`/`persistence-memory` и плана
счетов (chart of accounts).

Спецификация — единственный источник требований, по которому строится или
дорабатывается реализация. Спецификация обязана содержать всю информацию,
необходимую для реализации, без обращения к побочным источникам («как раньше»,
«очевидно» и т.п. недопустимы).

## Отношение к остальной документации репозитория

Общая структура `docs/` делит документы на living-описания **реализованных**
контрактов и датированные research-заметки (см. `docs/README.md`). Этот
каталог — отдельная практика: **нормативные целевые (target) спецификации**,
введенная по явному запросу пользователя для durable-версионирования.

Различие, которое обязательно учитывать:

- **`docs/core-accounting.md`** (и аналогичные living-документы) — описание
  **текущего реализованного** учета. Они обновляются вместе с кодом и всегда
  соответствуют ему.
- **`docs/specs/double-entry/v*.md`** — нормативные **целевые** спецификации:
  того, что должно быть построено. До достижения статуса реализации
  `IMPLEMENTED` спецификация может расходиться с кодом — это нормальное,
  явное состояние (`target ≠ implemented` зафиксировано в шапке каждой
  версии).

Правило сосуществования: целевая спецификация **не перезаписывает**
living-документы; living-документы обновляются на реализацию (вместе с кодом,
по правилам root `AGENTS.md`). Пока ни одна версия здесь не реализована,
действующим описанием учета остается `docs/core-accounting.md` и текущий код.
`docs/architecture-plan.md` использует собственную in-place практику ревизий
(rev.3) — она не распространяется на этот каталог, а semver-практика этого
каталога не распространяется на него.

## Две независимые оси статуса

Статус спецификации — **две отдельные характеристики**, а не цепочка
переходов:

1. **Согласование (approval):** `DRAFT` → `APPROVED`. Утверждает только
   пользователь явным действием; документ не считается одобренным, пока
   статус не изменен в индексе.
2. **Реализация (implementation):** `NOT_STARTED` → `IN_PROGRESS` →
   `IMPLEMENTED`. Отслеживается отдельно от согласования: approved ≠
   implemented. Разработка реализации начинается только после APPROVED;
   согласование само по себе не означает начала или завершения реализации.

**Текущее состояние индекса: current = v1.0.0; latest-approved = v1.0.0;
latest-implemented = v1.0.0.** Версия v1.0.0 явно утверждена пользователем
2026-10-21. Реализация выполнена 2026-10-22 по явному указанию пользователя
выполнить [план реализации](../../double-entry-v1.0.0-implementation-plan.md);
доказательства приемки приведены ниже.

## Индекс версий

| Версия | Согласование | Реализация | Дата | Commit утверждения | Содержание |
|---|---|---|---|---|---|
| [v1.0.0](v1.0.0.md) | APPROVED | IMPLEMENTED | 2026-10-21 | `9c1dffe4b0276624fd9edc27412d3624ccdf1648` | Устранение хардкода плана счетов из double-entry движка: `AccountCode`, `LedgerChart`, chart в `LedgerState`, instance `BondOperations` |

Статусы и evidence (commit-хэши, ссылки на проверки) фиксируются **только в
индексе**. Шапка файла версии фиксирует статус на момент подготовки проекта;
актуальные статусы после согласования читаются из индекса, без правки файла версии.

## Приемка v1.0.0 — 2026-10-22

- Проверено рабочее дерево поверх `6b6bc30e240eb0284792f8f4bfaf19f0afa9ad51`.
  Commit реализации пока отсутствует: автоматический commit не выполнялся.
  После фиксации изменений пользователем хэш следует добавить сюда.
- JDK: OpenJDK 25.0.4.1, Gradle wrapper 9.8.2. Выполнены focused
  `:core:test`, `:core:check`, затем `:application:check :persistence-memory:check`.
- Финальный единый прогон: `./gradlew clean build jacocoAggregateReport` —
  **BUILD SUCCESSFUL**, 47 задач выполнены, 1 мин 48 с. XML итогового прогона:
  **170 tests, 0 failures, 0 errors, 0 skipped** по семи проектам.
  Из них root `ModulePathContractTests` — **16** проверок произведенного
  distribution по module path, включая ServiceLoader и запрещенный доступ.
- Разбивка tests: root 16, core 62, application 31, integrations 16,
  integrations-moex 15, integrations-cbr 11, persistence-memory 19.
  В core число включает четыре jqwik property-метода, а не число generated cases.
- Отчеты: `<project>/build/test-results/test/TEST-*.xml` и
  `<project>/build/reports/tests/test/`; агрегированный JaCoCo HTML/XML —
  `build/reports/jacoco/jacocoAggregateReport/`. Локальный лог прогона —
  `.opencode/tmp/double-entry-v1/final-build.log` (игнорируемый рабочий артефакт).
  Эти отчеты относятся к проверенному рабочему дереву и перезаписываются сборками.
- `git diff --check` прошел. Утвержденный `v1.0.0.md` побайтово совпадает с
  commit утверждения; SHA-256:
  `302d7aacb1415d9aab31d2347a33ed0b7868dcaa2a4a129a4abd1c8ad780bb76`.
  JPMS descriptors, Gradle-зависимости, `Main`, providers и неизменяемые
  production-контракты раздела 14 не изменены.

### Привязка приемочной матрицы к тестам

Имена ниже относятся к тестовым исходникам соответствующего модуля.
`LedgerChartContractTests` находится в core test package `core.ledger`,
остальные core suites — в `ru.onlychill.mbondsfx.core`.

| ID | Проверка |
|---|---|
| DE-P-01/02 | Core `LedgerChartContractTests.deP01StandardAndCustomChartsAreImmutableValueObjectsWithDeterministicOrder`; `LedgerContractTests.emptyAndRichLedgerStateRoundTripAndContinuation` |
| DE-P-03…05 | Core `LedgerContractTests.openingDepositWithdrawalAndCashConstraints` |
| DE-P-06…09 | Core `LedgerContractTests.dirtyPurchaseCouponAmortizationAndRedemption`, exact-cent/sub-minor sale/amortization tests, `fullAmortizationCanBeRetiredWithoutDuplicateCashFlow`, withholding/commission tests |
| DE-P-10/11 | Core `LedgerContractTests.taxCorrectionsAreLinkedCumulativeAndScopedToTheirOriginalOperation`, `taxCorrectionRespectsStrictCashAvailability` |
| DE-P-12 | Core `LedgerChartContractTests.deP12RestoresAfterEveryOperationIncludingCorrection` и существующие restore/property tests |
| DE-P-13 | Memory `PersistenceMemoryContractTests`: duplicate/stale/exact-before/concurrent CAS tests и custom-policy цикл |
| DE-P-14 | Memory `PersistenceMemoryContractTests.customPolicyEndToEndUsesRepositoryCouponReceiptCorrectionAndRestore`: девять нестандартных кодов, реальные операции/сервисы/CAS, точные postings, views и restore |
| DE-P-15 | Core `LedgerChartContractTests.deP15PassiveCodesStoreBalancesWithoutSpecialEngineBehavior` |
| DE-P-16 | Core `deN04AndDeP16NoNegativeRoleAndTwoPositionMeasuresAreChartDriven`; memory `repositoryPersistsEmptyNoNegativeChartAndAllowsNegativeCash`, `repositoryAllowsNegativeCashWhenPolicyFlagDisablesNoNegativeRole`, `repositoryRestoresBothPositionBindingsAndSpendableViews` |
| DE-P-17; DE-N-03/11/13 | Core `LedgerChartContractTests.deN03DeN11AndDeN13ApplyRestoreAndBalanceEnforceChartScoping` |
| DE-N-01/02/14/15 | Core `LedgerChartContractTests` соответствующие `deN...` методы, включая null-аргументы всех операций/overload-ов |
| DE-N-04 | Core `LedgerChartContractTests.deN04AndDeP16NoNegativeRoleAndTwoPositionMeasuresAreChartDriven`, существующие cash-policy tests |
| DE-N-05 | Core `deN05AdjustmentUsesPolicyRolesAndRejectsMalformedTransfers` и tax-correction contract tests; application `InvestmentAccountContractTests.deN05_taxCorrectionPreservesQueryErrorsAndRejectsInvalidCorrectionsAtomically` |
| DE-N-06 | Core `LedgerContractTests.restoreRejectsStructurallyInconsistentState`, malformed-tax restore tests и chart/scoping tests |
| DE-N-07 | Memory `PersistenceMemoryContractTests.chartDriftIsRejectedByBothServicesAndRepositoryBeforeStateCas` |
| DE-N-08 | Memory `PersistenceMemoryContractTests.ledgerCommitRejectsAChartChangeBetweenBeforeAndAfter` |
| DE-N-09 | Memory `PersistenceMemoryContractTests.concurrentFirstCommitsWithDifferentChartsSelectExactlyOnePolicy` |
| DE-N-10 | Core `ArchitectureContractTests.doubleEntryDictionaryIsOutsideTheEngine`; прежние package/dependency guards сохранены |
| DE-N-12 | Core `LedgerContractTests.ledgerOperationAndPositionValidation`: переполнение `Long.MAX_VALUE + 1` |
| DE-N-16 | Core `LedgerChartContractTests.deN16RestoreUsesPostingOrderInsideSameDateBucket` |

Приемка подтверждает проверенные контракты, не аутентификацию первого chart,
не происхождение произвольного `OperationResult` и не историческую достижимость
восстановленного состояния. Module-path subprocesses не входят в данные JaCoCo.

## Учесть в будущих версиях (carry-over из аудита 2026-10-22)

Аудит v1.0.0 нашел одно расхождение (Low), которое не правится в утвержденной
версии и обязано быть разрешено явно в следующей спецификации. Это
обязательный вход для спецификации персистентного (не in-memory) слоя.

- **`Ledger.restore` строже R9.2** (`Ledger.java`, проверка истории по
  securityScoped-счетам): каждая историческая entry по securityScoped-счету
  требует существующую позицию той же валюты; спецификация проверяет только
  ненулевые остатки (R7.9). Через `apply` состояние недостижимо, но `restore`
  и re-валидация `LedgerCommit` отклоняют вручную собранные/загруженные состояния,
  которые спецификация считает допустимыми.
- **Требование к будущей версии:** выбрать и нормативно зафиксировать одно из:
  (а) ослабить код до R7.9; (б) внести правило в перечень R9.2 как намеренное
  ужесточение. Не оставлять поведение неявным.
- **Для персистентного слоя:** загруженные из БД состояния проходят `restore`
  как недоверенный вход, а не как результат `apply`. Спецификация обязана
  определить: (1) точное множество принимаемых состояний (это расхождение
  затрагивает его границу); (2) тип отказа при повреждении данных (IAE из
  `restore` против отдельной ошибки хранилища); (3) тесты на состояния,
  собранные в обход `apply` (round-trip через хранилище и ручные fixture-ы);
  (4) что A2 (нет внешнего сериализованного формата `LedgerState`) перестает
  действовать — нужны формат, версия схемы и миграции; (5) TR2 (подлинность
  первого chart) и TR3 (происхождение операций) остаются без защиты — решить
  явно, не наследовать молча.
- Информативно, не нарушения: сервисы держат `BondOperations(policy)` в поле;
  null-проверки Money-overload-ов `buy`/`sell` делегируются Commissions-overload-у.

## Правила изменения версий

1. **DRAFT** — файл можно менять; каждое содержательное и каждое
   редакционное изменение отражается в разделе «Журнал изменений» той же
   версии (для draft журнал ведется свободно, одной записью на правку или
   группу связанных правок).
2. **APPROVED** — файл **не редактируется вообще**: ни требования, ни
   опечатки, ни статус, ни журнал. Любое изменение — даже исправление
   опечатки — оформляется **новой версией** со ссылкой на изменяемое:
   PATCH для редакционных исправлений, MINOR/MAJOR для изменений требований по п.5.
   Обновление статусов и evidence происходит только в индексе.
3. **Фиксация утверждения (content-addressed).** Commit-хэш, в котором версия
   получила статус `APPROVED`, записывается в индексе **последующим
   индексным commit-ом** — commit не может содержать собственный хэш,
   поэтому фиксация всегда отложена на один шаг. Неизменность
   утвержденной версии проверяется сравнением содержимого файла с этим
   commit.
4. **Реализация (IMPLEMENTED)** проставляется в индексе, когда реализация
   прошла все проверки, предписанные спецификацией; evidence (commit
   реализации, ссылка на прогон) — в индексе.
5. Версионирование — **semver, независимый от версии приложения**.
   - **MAJOR** — удаление/сужение публичного контракта, изменение финансовой
     семантики, несовместимое изменение persisted-shape. Новая роль или новая
     операция могут быть breaking (отклонение старых валидных входов, смена
     инвариантов) — тогда это MAJOR, а не MINOR.
   - **MINOR** — **обратно совместимые** расширения: новые опциональные
     требования, которые не отвергают ничего, допустимого предыдущей
     версией.
   - **PATCH** — уточнения, не меняющие нормативных требований (примеры,
     разъяснения, опечатки, переформатирование).
6. Каждая версия — **полное состояние требований**, а не дельта к предыдущей:
   будущие версии включают все требования заново, устаревшие явно
   исключаются. Чтение одной версии достаточно для построения реализации.
7. Одна активная разработка ведет к одной версии; параллельные несовместимые
   предложения невозможны — сначала разрешается конфликт версий в индексе.
8. Имя файла: `v{MAJOR}.{MINOR}.{PATCH}.md` в этом каталоге.
