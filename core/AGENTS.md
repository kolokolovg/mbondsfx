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
- `AccountKind.CASH` — доступные денежные остатки портфеля по валютам; `TAX_WITHHELD` — отдельный
  non-spendable остаток удержаний (его не включает `cashBalances()`, он доступен через
  `taxWithheldBalances()`). Ненулевые удержания проводятся отдельно `D TAX_WITHHELD / C CASH`;
  нулевые налоговые проводки отсутствуют. Для buy/sell `Commissions` хранит caller-supplied
  `brokerCommission` и `otherCommission`, `total()` складывает компоненты, а ненулевой итог
  проводится отдельно `D COMMISSION_EXPENSE / C CASH`. Существующие Money-overloads принимают
  уже известный общий итог. Никакие налоговые ставки или брокерские тарифы не вычисляются.
- Поправка удержания после исходной операции — append-only `OperationKind.TAX_CORRECTION` с
  `Posting.adjustsOperation()` на исходную операцию, в которой была налоговая проводка.
  `Ledger.taxWithheldForOperation(originalOperation)` возвращает накопленную сумму с учетом
  связанных поправок; она не может стать отрицательной. Не создавайте correction-to-correction,
  не меняйте исходную историю, позицию, купонный доход или PnL. Сохраняйте проверку портфеля,
  валюты и хронологии при применении.
- `Ledger.state()` и `Ledger.restore(LedgerState)` — парный recovery seam: восстанавливайте только
  структурно согласованное состояние из явно сохранённых postings, без изменения семантики `apply`.
  Restore не доказывает историческую достижимость; не заявляйте обратное.
- `core.money` и `core.id` — денежные примитивы и типизированные ID, в том числе `IndexId`.
  Внешние `IndexFixing`, `IndexFixingSource` и `MissingFixingException` принадлежат
  `:integrations` (`integrations.index`), не `:core`.

Разрешенные зависимости пакетов: `bond -> id/money`;
`bond.calculation` (включая `calculation.port`) `-> bond/id/money`; `ledger -> id/money`; пакеты
`id` и `money` — листья, не зависящие от других пакетов `core`. Не помещайте в `:core` внешние
DTO, HTTP/XML, адаптеры, UI или DB-модели. Не импортируйте integration API в домен.
Сохраняйте `:core` без внешних зависимостей: одобрение библиотеки для адаптера не разрешает
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

```bash
./gradlew :core:check
./gradlew --offline :core:check
./gradlew :core:test --tests '*DomainContractTests'
```

Offline-флаг использует только уже закешированные Gradle distribution и зависимости; см. также
корневые инструкции.
