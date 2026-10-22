# Пилот верификации текущего учета

Проверки дополняют существующие example-based тесты, source/Gradle guards и root JPMS
blackbox suite. Они не являются формальным доказательством соответствия текстовой спецификации.
Production-код и финансовая семантика при подключении инструментов не изменены.

## Подключение

Только `:core` получает test-зависимости jqwik API/engine 1.9.2 и ядро ArchUnit 1.5.1.
Все проверки запускаются стандартным `test`; `check` включает их автоматически.
JUnit Jupiter 5.12.2 / Platform 1.12.2 и JaCoCo 0.8.15 сохранены. ArchUnit запускается
обычными Jupiter-тестами, без собственного JUnit engine. Его транзитивный SLF4J API в test
scope выравнивается ограничением до 2.0.20 — той же версии, что используется в root runtime.

```bash
./gradlew :core:test --tests '*LedgerProperties'
./gradlew :core:test --tests '*BytecodeArchitectureContractTests'
./gradlew clean build jacocoAggregateReport
```

Новые зависимости необходимо один раз загрузить перед использованием `--offline`.
Тестовые библиотеки не добавляются в JPMS descriptors или приложение. Лицензии и
источники перечислены в [NOTICE](../../NOTICE); их копии включаются в distribution.
Root blackbox suite проверяет сохранность этих копий и отсутствие тестовых библиотек в `lib`.

## Свойства jqwik

Источник действующего контракта — [текущий учет](../guides/core-accounting.md) и локальные правила `:core`.
`LedgerProperties` выполняет четыре свойства, каждое с бюджетом 250 попыток:

| Контракт | Свойство |
|---|---|
| Баланс отдельно по валютам; комиссии учитываются один раз | Покупки в RUB и USD, проверка каждой проводки, CASH и счета комиссий |
| Пропорциональное снятие с HALF_UP, cap по доступному остатку, точное закрытие | Частичная и последняя продажи позиции с суммами меньше копейки |
| Удержание не spendable; коррекция append-only | Полностью удержанный купон, отказ вывода, возврат части/всего удержания; неизменность позиции, дохода и исходной истории |
| Неизменяемость и recovery seam | Короткие последовательности взносов; сохранение предыдущих состояний, round-trip и одинаковое продолжение после restore |

Генераторы используют десятичные суммы, явные даты и ID. Нет `double`, внешних сервисов,
часов, скрытого расчета налогов или фильтрации почти всех сгенерированных входов.
Это ограниченный пилот: произвольные смешанные цепочки операций, JPY и конкурентный CAS
им не покрыты. Полный набор приемки v1.0.0 (статус IMPLEMENTED) проверяется example-based
тестами; сопоставление ID с тестами приведено в [реестре спецификаций](../specs/double-entry/README.md).

Для диагностики сохраняйте XML/HTML-отчет стандартного `test`: jqwik выводит seed,
число проверок и при ошибке минимизированный пример. Для воспроизведения задайте
`@Property(seed = "значение из отчета")` у соответствующего свойства и повторите
тот же тест с теми же версиями/генераторами. После исправления добавьте найденный пример
в постоянный регрессионный тест; не фиксируйте один seed навсегда для всех прогонов.
Локальная база jqwik находится в `core/build/jqwik-database`, не версионируется и
удаляется `clean`; она помогает повторять ошибки, но не заменяет сохраненный отчет.
Покрытие кода собирается тем же JaCoCo agent; процент покрытия не доказывает полноту требований.

## Правила ArchUnit

`BytecodeArchitectureContractTests` импортирует только каталоги production-классов `:core`,
переданные Gradle явно. Проверяются непустой импорт, наличие Ledger/Money и всех логических
областей, принадлежность классов известным пакетам. Тестовые фикстуры в этот импорт не входят.

Правила проверяют направления `bond → id/money`, `calculation → bond/id/money`,
`ledger → id/money`, листовые `id`/`money`, отсутствие application/integrations и HTTP/XML
зависимостей. `calculation.port` относится к calculation по наиболее длинному совпадению.
Разрешенная карта ациклична, поэтому обратные ребра и циклы между этими областями запрещены.
Отдельная отрицательная фикстура доказывает обнаружение зависимости в сигнатуре через
полное имя `java.net.http.HttpClient` без `import`.

ArchUnit не проверяет бизнес-формулы, строковые имена для рефлексии, Gradle-конфигурации
или реальную JPMS-инкапсуляцию. Существующие guards и module-path suite остаются обязательными.

## Переход к целевой спецификации

[Реестр double-entry](../specs/double-entry/README.md) отдельно фиксирует согласование и реализацию.
Версия v1.0.0 APPROVED и IMPLEMENTED: `AccountCode`, `LedgerChart`, `BondAccountingPolicy` и
`PositionMeasure` находятся в `core.ledger`, а chart хранится в `LedgerState`. Этот пилот не является
приемкой v1.0.0 и не заменяет ее матрицу тестов.

Для следующих версий добавляйте связь «версия / требование → тест → вид свидетельства»:
сначала ожидаемо падающий контрактный тест, затем минимальная реализация и зеленая общая сборка.
Не подменяйте готовность отключенными тестами, заглушками production API или ослаблением правил.
Проверки replay, position binding и chart consistency опираются на требования v1.0.0 и их матрицу
в реестре. Проверка `restore` не доказывает историческую достижимость.

## Пороги покрытия и дополнительные свойства

`check` каждого подпроекта, кроме root, запускает `jacocoTestCoverageVerification`: пороги LINE/BRANCH
задаются в `coverageThresholds` в `build.gradle` и выбраны ниже текущих значений с запасом, а для `core.ledger` — отдельным правилом пакета;
регресс покрытия падает сборкой, но сами пороги не доказывают полноту проверок. `LedgerProperties` дополнительно содержит
агрегатное свойство: случайные смешанные последовательности операций в RUB, USD и JPY сохраняют баланс каждой проводки и нулевую сумму всех остатков по валюте,
а отказанные шаги не меняют состояние; пороговые значения и сравнение сумм выполняются обычным `test`, без внешних инструментов.

## Мутационное тестирование (PIT)

PIT (`info.solidsoft.pitest` 1.19.0, PIT core 1.30.1, `pitest-junit5-plugin` 1.2.3; все Apache-2.0)
подключен только к `:core` как build/test-инструмент. Он не входит в `check`, `build`, production/runtime
classpath и distribution. Порог мутационного скора задан `mutationThreshold = 97` в `core/build.gradle`:
`./gradlew :core:pitest` падает, если скор ниже 97%; `check` и `build` PIT не запускают.

```bash
./gradlew :core:pitest
```

Отчеты: `core/build/reports/pitest/index.html` и `mutations.xml` (без таймстемпа). Нацеленные пакеты:
`core.ledger`, `core.money`, `core.bond`, `core.bond.calculation`. Тесты `ArchitectureContractTests` и
`BytecodeArchitectureContractTests` исключены из PIT: они читают Gradle-свойства и проверяют структуру,
а не поведение. Property-тесты jqwik (`LedgerProperties`) PIT выполняет через JUnit Platform
engine jqwik; их база `core/build/pitest/jqwik-database` не версионируется и удаляется `clean`.

Базовая линия до kill-тестов (первый прогон, 2026-10-22, JDK 25, без порога):

| Пакет | Убито / всего (killed + timed out) | Выжило | Без покрытия |
|---|---|---|---|
| `core.bond.calculation` | 69 / 79 (87%) | 7 | 3 |
| `core.ledger` | 310 / 355 (87%) | 41 | 4 |
| `core.money` | 73 / 77 (95%) | 3 | 1 |
| Всего | 452 / 511 (88%) | 51 | 8 |

После kill-тестов (2026-10-22, JDK 25, `mutationThreshold = 93`; два прогона дали одинаковый результат 497 / 511):

| Пакет | Убито / всего (killed + timed out) | Выжило | Без покрытия |
|---|---|---|---|
| `core.bond.calculation` | 77 / 79 (97%) | 2 | 0 |
| `core.ledger` | 344 / 355 (97%) | 9 | 2 |
| `core.money` | 76 / 77 (99%) | 1 | 0 |
| Всего | 497 / 511 (97%) | 12 | 2 |

Kill-тесты добавлены без изменения production-кода: новые классы `BondOperationValidationContractTests`
(гварды `BondOperations` с точными сообщениями) и `LedgerInvariantContractTests` (несогласованный снимок позиции,
восстановление счета вне chart, поправка налога при единственной дате истории), а также дополнения в
`ChartDistinctnessContractTests`, `LedgerChartContractTests`, `DomainContractTests` и `FoundationContractTests`.

Из убитых мутантов (452 до kill-тестов, 497 после) только 3 атрибутированы jqwik-свойствам; остальное дают example-based тесты.
Число TIMED_OUT колеблется между прогонами (2–6, включая 2 в финальном прогоне), поэтому скор меняется
на доли процента; время прогона тоже плавает (от ~1 до ~9 минут).

### Эквивалентные и мертвые мутанты (фаза 3б)

После удаления мертвой ветки `Ledger.validateTaxCorrection` и метода `operationDate` (см. ограничения ниже) `:core:pitest`
обнаруживает 594 из 607 мутантов (97,86%; `TIMED_OUT` PIT относит к обнаруженным). Не обнаружены 13 мутантов, все SURVIVED.
Каждый разобран по коду. Ни один не меняет наблюдаемый результат на достижимых входах, поэтому тесты под них не добавлялись
и не подгонялись под мутант; исключения в PIT не добавлены. Номера строк относятся к текущим исходникам `:core`.

| Класс | Метод, строка | Мутатор | Статус | Обоснование |
|---|---|---|---|---|
| `BondOperations` | `proportional`, 296 | `<=` → `<` для знаменателя | SURVIVED | Знаменатель всегда > 0. `amortization`: `reduction > 0` (`requirePositive`, строка 141) и `reduction ≤ face` (строки 148–150), значит `face > 0`. `sell`: `current.quantity() > 0` (`requireOpen`, строка 187). Ветка `== 0` недостижима. |
| `BondOperations` | `proportional`, 297 | `< 0` → `<= 0` для числителя | SURVIVED | Числитель всегда > 0: `reduction` проходит `requirePositive` (строка 141), `quantity` — `requireQuantity` (строка 188). |
| `BondOperations` | `proportional`, 297 | `>` → `>=` для числителя относительно знаменателя | SURVIVED | Числитель строго меньше знаменателя. `amortization` вызывает `proportional` только при `!fullPrincipalReduction` (строка 151); `Money` канонизирует значение (`stripTrailingZeros`), поэтому равенство `reduction == face` определяется по значению и уходит в полное погашение, а `reduction > face` выбрасывает раньше (строка 148). `sell` при `quantity == current.quantity()` идет по полной продаже (строка 199), `quantity > current.quantity()` выбрасывает раньше (строка 189). |
| `BondOperations` | `proportional`, 305 | `>` → `>=` для клампа | SURVIVED | При равенстве присваивается `amount.amount()`, то есть то же значение; `Money` канонизирует его, итоговый `Money` совпадает. |
| `BondOperations` | `min`, 312 | `<=` → `<` | SURVIVED | При `compareTo == 0` (одна валюта, одно значение) оба аргумента — равные record `Money` (канонизация), поэтому проводки и позиции совпадают. Единственный вызов: `coupon`, строка 119. |
| `BondCalculations` | `floatingRate`, 285 | `<` → `<=` для пола | SURVIVED | При `rate == floor` по `compareTo` присваивается равное значение. Результат (`segmentedInterest`, `Money.multiply`) зависит только от значения, а не от масштаба `BigDecimal`: умножение, сложение и `setScale` точны по значению. |
| `BondCalculations` | `floatingRate`, 288 | `>` → `>=` для потолка | SURVIVED | То же, что для строки 285. |
| `HolidayCalendar` | `shift`, 39 | `>` → `>=` для направления | SURVIVED | `businessDays == 0` возвращается в строке 35. При остальных значениях `>` и `>=` дают одно направление. |
| `LedgerChart` | `intersects`, 138 | возврат `false` | SURVIVED | `spendable` содержит ровно один код `s` (строка 100). Если `s ∈ scoped`, то `counterpart ∈ spendable` (строка 114), то есть `counterpart == s`, и `scoped.contains(counterpart)` истинно (строка 115): бросается то же `IllegalArgumentException("Incompatible account roles")`. Для `noNegative ⊆ spendable` (строка 117) рассуждение то же. |
| `BondDefinition` | `<init>`, 31 | удаление `requireCurrency(event.faceReductionPerUnit(), …)` (void) | SURVIVED | `Principal` (строка 27) гарантирует, что валюта каждой амортизации равна валюте начального номинала, а строка 28 в `BondDefinition` связывает номинал с валютой облигации. Проверка всегда проходит. |
| `CouponPeriods` | `copySortedAndValidate`, 18 | удаление `forEach(requireNonNull(period))` (void) | SURVIVED | Тип `NullPointerException` сохраняется: из компаратора при n ≥ 2 или из `List.copyOf` при n = 1. Меняется только текст. Копия списка локальная, внешнего эффекта нет. |
| `Principal` | `<init>`, 23 | удаление `forEach(requireNonNull(event))` (void) | SURVIVED | `NullPointerException` сохраняется: из сортировки при n ≥ 2 или из цикла строк 26–27 при n = 1. Меняется только текст. |
| `RedemptionOptions` | `sortedCopy`, 25 | удаление `forEach(requireNonNull(option))` (void) | SURVIVED | `NullPointerException` сохраняется: из компаратора при n ≥ 2 или из `List.copyOf` при n = 1. Меняется только текст. |

Ограничения трактовки:

- Для `CouponPeriods.copySortedAndValidate` (18), `Principal.<init>` (23) и `RedemptionOptions.sortedCopy` (25) меняется только
  текст `NullPointerException` (`"period"`, `"event"`, `"option"`). Контракт null-дисциплины (спецификация double-entry v1.0.0,
  R5.4 и «null-элементы → NPE») задает тип исключения, а не текст, и тесты `:core` проверяют тип. Если текст станет
  контрактом, эти мутанты убиваются проверкой `getMessage()`.
- Исключения `excludedMethods` и `avoidCallsTo` в `core/build.gradle` не добавлены: `excludedMethods` исключает все мутанты
  метода, включая обнаруживаемые (`buy`, `sell`, `proportional`, `floatingRate`, `shift`, `BondDefinition.<init>`,
  `copySortedAndValidate`, `Principal.<init>`, `sortedCopy`), а `avoidCallsTo` действует на уровне пакета. Порог достижим без них.
- `Ledger`: проверки `link.isEmpty()` в `validateTaxCorrection` и `validateTaxCorrectionPosting` заменены на
  `adjustsOperation().orElseThrow()` по решению владельца. В `validateTaxCorrection` связь гарантирует `OperationResult`
  (у каждой проводки `TAX_CORRECTION` она есть, строки 38–42). `validateTaxCorrectionPosting` вызывается только на проводках
  со связью: из `taxWithheldForOperation` (строка 213, под `filter(...).isPresent()`), из `validateTaxCorrection` (после связи)
  и из `validateTaxCorrectionHistory` при `restore` (строки 368 и 398, под `adjustsOperation().isPresent()`). Восстановленный
  недоверенный state попадает в ветку поправки только со связью, поэтому отсутствие связи недостижимо и при `restore`.
  Сообщения «missing its original operation link» и «has no adjusted operation» тестами не проверяются и в спецификации
  не описаны; в недостижимых ветках исключение теперь `NoSuchElementException`.
- `Ledger`: ветка «Tax correction precedes or lacks its original operation» и метод `operationDate` удалены по решению владельца
  как недостижимые: `apply` отвергает корректировку датой раньше последней даты истории (строки 65–68, до вызова
  `validateTaxCorrection` в строке 69), а `restore` проверяет совпадение `operationIds` с операциями из проводок (строка 317);
  `apply` поддерживает то же соответствие. Поэтому исходная операция всегда есть в истории и не позже даты корректировки.
  Правило «дата коррекции ≥ даты исходной» обеспечивает проверка обратной датировки; тест
  `correctionDatedBeforeTheOriginalIsRejectedAsBackdatedWhenAnEarlierOperationExists` фиксирует ее сообщение «Backdated operation».
- `BondOperations.buy` и `sell`: проверки `requireNonNegative(commissions.otherCommission(), …)` удалены как избыточные по решению
  владельца: `Commissions` гарантирует одну валюту компонент и их неотрицательность (спецификация R8.19), брокерская проверка
  выполняется раньше. Мутанты `buy` 88 и `sell` 197 исчезли вместе с проверкой. Тесты не сравнивают текст
  «otherCommission must not be negative»; отрицательная прочая комиссия проверяется напрямую у `Commissions`
  (`LedgerContractTests`), поскольку удаленная проверка полагалась на него.
- Расхождение со спецификацией: `docs/specs/double-entry/v1.0.0.md`, §10.2, шаг 3, описывает для этого условия сообщение
  «Tax correction precedes…». Реализация его не выдает ни до удаления (ветка была недостижима), ни после. Версия v1.0.0
  утверждена и не переписывается; выровнять текст можно только новой версией спецификации с согласованием владельца.
- `mutationThreshold = 97`. PIT сравнивает скор, округленный до целого процента (HALF_UP, `thresholdPrecision` = 0):
  594/607 = 97,86% дает 98%. Порог 97 не превышает фактический скор; порог 98 прошел бы только за счет округления и не ставится.
  Гейт падает при 585 и меньше убитых, то есть при потере 9 мутантов. В текущем прогоне 40 из 592 убитых мутантов первым
  убивающим тестом PIT записал свойство jqwik (PIT записывает только первого убийцу). Потеря 9 обнаружений за прогон
  уронит гейт; при таких срывах порог снижается до 96 с записью в журнале плана.

`BondAccountingPolicy.standard` (NullReturnVals) до kill-тестов выживал: тесты вызывали его в статических полях; теперь проверяется методом теста.
Порог действует только на задачу `:core:pitest` и еженедельный workflow; `check` и `build` PIT не запускают.
Еженедельный запуск: `.github/workflows/mutation.yml` (manual + cron), отчет выгружается артефактом.
