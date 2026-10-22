# Инструкции для `:core`

Общие правила находятся в [корневом `AGENTS.md`](../AGENTS.md); они наследуются здесь. Эти
инструкции действуют только для `:core` и уточняют размещение доменного кода и проверок.

## Пакеты и зависимости

- `ru.onlychill.mbondsfx.core.bond` — агрегат `BondDefinition` и связанные с ним value objects.
- `ru.onlychill.mbondsfx.core.bond.calculation` — `BondCalculations`, вычисляемый `AccruedInterest`
  и ошибки расчета, включая `MissingIndexValueException`; `calculation.port` содержит доменный
  `IndexValueSource` (`BigDecimal value(IndexId, LocalDate)`). `MissingIndexValueException.index()`
  и `.date()` возвращают запрошенные метаданные. Результаты вычислений не являются агрегатами или
  исходными terms.
- `ru.onlychill.mbondsfx.core.ledger` — учет и операции; сохраняйте `OperationResult` в этом
  пакете: его package-private конструктор намеренно связан с `BondOperations`.
- `AccountCode` задает identity счета, `LedgerChart` — неизменяемые роли движка. Chart передается
  в `Ledger.open(portfolio, chart, allowNegativeCash)`, сохраняется в `LedgerState`, заморожен до
  конца жизни ledger и используется при `restore` без внешней конфигурации. Движок не знает
  bond-словарь: `BondAccountingPolicy` — единственный источник девяти bond-кодов, а
  `BondOperations` — instance-генератор с policy. Composition root должна создавать одну policy
  и передавать ее генератору и accounting-сервисам; per-call chart не передается.
- В standard chart роль `spendable` у `CASH`, `adjustable` — у `TAX_WITHHELD`; последнее не входит
  в `cashBalances()` и доступно через `taxWithheldBalances()`. Ненулевые удержания проводятся
  отдельно `D TAX_WITHHELD / C CASH`; нулевые налоговые проводки отсутствуют. Для buy/sell
  `Commissions` хранит caller-supplied `brokerCommission` и `otherCommission`, `total()` складывает
  компоненты, а ненулевой итог проводится отдельно `D COMMISSION_EXPENSE / C CASH`. Существующие
  Money-overloads принимают уже известный общий итог. Никакие налоговые ставки или тарифы не
  вычисляются.
- Поправка удержания после исходной операции — append-only `OperationKind.TAX_CORRECTION` с
  `Posting.adjustsOperation()` на исходную операцию, в которой была налоговая проводка.
  `Ledger.taxWithheldForOperation(originalOperation)` возвращает накопленную сумму с учетом
  связанных поправок; она не может стать отрицательной. Не создавайте correction-to-correction,
  не меняйте исходную историю, позицию, купонный доход или PnL. Сохраняйте проверку портфеля,
  валюты и хронологии при применении.
- `Ledger.state()` и `Ledger.restore(LedgerState)` — парный recovery seam: состояние включает
  неизменяемый chart; восстанавливайте только структурно согласованное состояние из явно
  сохранённых postings, без изменения семантики `apply`. Restore не доказывает историческую
  достижимость; не заявляйте обратное. `Account` не валидирует security-scoping без chart;
  `apply`, `restore` и `balance` проверяют known-код и соответствие `security` chart-ролям.
- `Ledger.balance()` возвращает zero для известного и корректно скопированного отсутствующего
  счета; неизвестный code или неверный scoping отклоняются `IllegalArgumentException`.
- `OperationKind` остается enum и `Ledger.apply` сохраняет enum-based TAX_CORRECTION dispatch.
- `core.money` и `core.id` — денежные примитивы и типизированные ID, в том числе `IndexId`.
  Внешние `IndexFixing`, `IndexFixingSource` и `MissingFixingException` принадлежат
  `:integrations` (`integrations.index`), не `:core`.

Разрешенные зависимости пакетов: `bond -> id/money`;
`bond.calculation` (включая `calculation.port`) `-> bond/id/money`; `ledger -> id/money`; пакеты
`id` и `money` — листья, не зависящие от других пакетов `core`. Не помещайте в `:core` внешние
DTO, HTTP/XML, адаптеры, UI или DB-модели. Не импортируйте integration API в домен.
Сохраняйте production `:core` без внешних зависимостей: одобрение библиотеки для адаптера не разрешает
добавлять её в доменный модуль.

`module-info.java` сохраняет `:core` leaf-модулем без `requires` и экспортирует только шесть
публичных доменных packages: `bond`, `bond.calculation`, `bond.calculation.port`, `id`, `ledger`,
`money`. Не добавляйте `opens` или export других packages.

При добавлении модели сохраняйте явные даты и входы, неизменяемость и проверки доменных
инвариантов. Финансовые формулы, округление и трактовка неизвестных данных меняются только по
явному требованию.

## Тесты

Контрактные проверки запускаются стандартным Gradle `test` на JUnit Jupiter. Добавляйте
domain/accounting/architecture assertions в соответствующие тесты и сохраняйте модульный guard
`ArchitectureContractTests`. Тесты компилируются/исполняются на classpath (`compileTestJava` с
`inferModulePath = false`); они не заменяют root module-path blackbox acceptance.

Тесты проверяют матрицу DE-IDs для double-entry v1.0.0; новый chart не переводит
`OperationKind` в данные и не меняет финансовые формулы.

Тестовый пилот использует также jqwik 1.9.2 для свойств текущего учета и ядро ArchUnit 1.5.1
из Jupiter-тестов для проверки production-байткода. Эти зависимости, включая транзитивный
SLF4J API ArchUnit, разрешены только в test scope и не добавляются в `module-info.java`.
DE-ID assertions guard both the chart migration and preserved accounting results; tests do not
replace the root distribution/module-path acceptance required for the full change set.

Mutation testing: PIT (`info.solidsoft.pitest` 1.19.0, PIT 1.30.1, JUnit 5 plugin 1.2.3) одобрен
пользователем только как build/test-инструмент `:core`. Он запускается вручную через
`./gradlew :core:pitest` и не входит в `check`; порог мутационного скора задан `mutationThreshold` в `core/build.gradle` и проверяется самой задачей `:core:pitest`. Целевые пакеты:
`core.ledger`, `core.money`, `core.bond.calculation`. Архитектурные guards исключены из PIT-прогона.
PIT не добавляется в production/runtime classpath, JPMS descriptors и distribution.

```bash
./gradlew :core:check
./gradlew --offline :core:check
./gradlew :core:test --tests '*DomainContractTests'
./gradlew :core:pitest
```

Offline-флаг использует только уже закешированные Gradle distribution и зависимости; см. также
корневые инструкции.
