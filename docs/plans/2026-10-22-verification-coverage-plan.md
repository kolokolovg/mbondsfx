# План: усиление верификации по отчету 2026-10-22

Источник: [reports/2026-10-22-verification-coverage.md](../reports/2026-10-22-verification-coverage.md).

Легенда: `[ ]` не начато, `[~]` в работе, `[x]` выполнено, `[!]` заблокировано / нужно решение.

Цель: все свойства доменной логики спецификаций покрыты тестами и трассируются к требованиям; PIT убивает
100% неэквивалентных мутантов, каждый оставшийся эквивалентный мутант документирован или исключен с
обоснованием. Тесты не являются формальным доказательством; формальный инструмент решается в фазе 6.

Общие правила: только тесты и документация; production-код и финансовая семантика не меняются;
новые зависимости не добавляются (jqwik и ArchUnit уже одобрены для `:core`). Если свойство найдет
расхождение с поведением, остановиться и согласовать исправление отдельно.

## Фаза 1. Свойства `core.money` и `core.bond.calculation` (приоритет 1)

- [x] 1.1 `Money`: коммутативность и ассоциативность сложения, сохранение валюты, идемпотентность округления.
- [x] 1.2 Day-count и `HolidayCalendar.shift`: свойства (границы, монотонность, отсутствие сдвига назад).
- [x] 1.3 `BondCalculations`/`AccruedInterest`: неотрицательность, монотонность НКД, суммирование купонов, вырожденные периоды.
- [x] 1.4 Floor/cap плавающей ставки и индексация (`FloatingRateFormula`, `IndexationRule`).
- [x] 1.5 Проверка: `./gradlew :core:check`, `:core:pitest` не ухудшил скор.

## Фаза 2. `core.bond` и PIT (приоритет 2)

- [x] 2.1 Свойства инвариантов `BondDefinition` и купонных расписаний (даты, упорядоченность, отсутствие пересечений).
- [x] 2.2 Включить `core.bond` в целевые пакеты PIT (`core/build.gradle`).
- [x] 2.3 Пересмотреть `mutationThreshold` и пороги JaCoCo (`coverageThresholds`).
- [x] 2.4 Проверка: `:core:check`, `:core:pitest`.

## Фаза 3. Расширение `LedgerProperties` (приоритет 3)

- [x] 3.1 Охват всех `OperationKind`.
- [x] 3.2 Длинные цепочки с `TAX_CORRECTION`.
- [x] 3.3 Воспроизводимость состояния через replay.
- [x] 3.4 Проверка: `:core:check`, `:core:pitest`.

## Фаза 3б. Выжившие мутанты (приоритет 3)

Источник: `core/build/reports/pitest/mutations.xml` (14 из 548: 12 SURVIVED, 2 NO_COVERAGE). Итог 3б: 13 из 607 не обнаружены (все SURVIVED), все эквивалентны; см. журнал и `docs/plans/verification.md`.

- [x] 3б.1 `BondOperations.proportional` (298, 299, 307), `min` (314): тесты границ равенства. Итог: эквивалентны (знаменатель и числитель гарантированы вызывающими, `Money` канонизирован), тестов не добавлено.
- [x] 3б.2 `BondOperations.buy` (88), `sell` (197): проверка побочного эффекта удаленного void-вызова. Итог: избыточные `requireNonNegative(otherCommission)` удалены (`Commissions` гарантирует знак и валюту); брокерские проверки оставлены; добавлен прямой тест `Commissions` на отрицательную прочую комиссию. Мутанты 88 и 197 исчезли.
- [x] 3б.3 `BondCalculations.floatingRate` (285, 288), `HolidayCalendar.shift` (39): границы floor/cap и сдвига. Итог: эквивалентны (присваивается равное значение; при `businessDays == 0` метод уже вернул управление), тестов не добавлено.
- [x] 3б.4 `LedgerChart.intersects` (138), `Ledger.operationDate` (368): тесты на результат. Итог: `intersects` эквивалентен (counterpart и scoped дают то же исключение); `operationDate` удален как недостижимый по решению владельца (см. журнал).
- [x] 3б.5 `Ledger.validateTaxCorrection` (341), `validateTaxCorrectionPosting` (459): покрыть лямбды. Итог: лямбды переписаны на явные проверки `Optional`, затем `link.isEmpty()` заменены на `adjustsOperation().orElseThrow()`; мертвая ветка «precedes or lacks» удалена (решения владельца, см. журнал); тестов для мутантов не добавлено.
- [x] 3б.6 Классифицировать оставшиеся эквивалентные мутанты, задокументировать или исключить с обоснованием. Сюда входят 4 выживших в `core.bond` (фаза 2, см. журнал). Итог: 13 записей в `docs/plans/verification.md`; исключений в `core/build.gradle` нет (методные исключения скрыли бы обнаруживаемые мутанты).
- [x] 3б.7 Поднять `mutationThreshold` до 100% за вычетом документированных эквивалентных; `:core:pitest`. Итог: порог 95 → 97, не выше фактического скора 97,08% на момент 3б (гейт PIT округляет до целого процента, см. журнал, поправка); `:core:check` и `:core:pitest` успешны.

## Фаза 4. Конкурентность и репозитории (приоритет 4)

- [ ] 4.1 Многопоточный стресс-тест CAS в `:persistence-memory` (без новых зависимостей; jcstress только с одобрением).
- [ ] 4.2 Property-тесты идемпотентности и атомарности репозиториев.
- [ ] 4.3 `CouponReceiptService` и `TaxCorrectionService`: свойства (net = gross − tax, delta поправок).
- [ ] 4.4 Проверка: `:application:check :persistence-memory:check`.

## Фаза 5. Документация и трассируемость (приоритет 6)

- [ ] 5.1 Обновить `docs/plans/verification.md` (таблица свойств, пакеты PIT, пороги).
- [ ] 5.2 Расширить реестр «требование → тест → вид свидетельства» в `docs/specs/double-entry/README.md`.
- [ ] 5.3 Обновить отчет или добавить новый с актуальными числами после реального запуска.

## Фаза 6. Решение по формальной верификации (приоритет 5)

- [ ] 6.1 Решить, нужны ли OpenJML/KeY или TLA+ для `Ledger.apply`, CAS и restore.
- [ ] 6.2 Если да: проверить лицензии и получить явное одобрение по AGENTS.md.

## Финальная проверка

- [ ] `./gradlew clean build` и `./gradlew :test --tests 'ru.onlychill.mbondsfx.ModulePathContractTests'`.

## Журнал

| Дата | Шаг | Результат |
|---|---|---|
| 2026-10-22 | Фаза 1 (1.1–1.5) | 65 свойств (`MoneyProperties`, `DayCountProperties`, `HolidayCalendarProperties`, `AccruedInterestProperties`, `FloatingRateIndexationProperties`), по 250 попыток; `:core:check` и `:core:pitest` успешны: убито 497 из 511 мутантов (97%), как и до фазы. |

### Открытые наблюдения из фазы 1 (нужно решение владельца, код не менялся)

- [x] Решено, см. журнал. 30E/360: `days(30 янв, 31 янв) = 0`, поэтому `BondCalculations.accrued` на 31-е число для периода, начинающегося 30-го, бросает `IllegalArgumentException`. Ожидается 0. Свойства 30E/360 не покрывают этот случай.
- [x] Решено, см. журнал. Index-linked купон при отрицательном индексе возвращает отрицательную сумму без ошибки, а `facePerUnit` такой номинал отвергает. Нужно подтвердить контракт.
- «НКД ≥ 0» верно только для неотрицательной эффективной ставки; монотонность и «НКД ≤ купон» проверены при постоянном индексе.
- `BusinessDayConvention` свойствами не покрыт.
| 2026-10-22 | Решение по расхождениям фазы 1 | Одобрено пользователем: (1) нулевая доля года допустима только для `DayCounts.thirtyE360()` в сегментах купона/НКД, для ACT/365F, ACT/360, ACT/ACT.ISDA и прочих `DayCount` проверка остается строгой; (2) отрицательный коэффициент индексации отвергается в купоне и НКД (`IllegalArgumentException("Indexation must not produce negative index coefficient")`). Правка в `BondCalculations`, тесты добавлены; `:core:check` и `:core:pitest` успешны (502 из 516 мутантов, 97%). |
| 2026-10-22 | Спецификация day-count v1.0.0 и v1.0.1 | Утверждены и реализованы (`docs/specs/day-count/`): добавлены `30/360` и `30E+/360`; нулевая доля года допустима только для `30E/360` и `30/360` (по identity); на дату начала периода доля НКД равна 0 без вызова `DayCount`. Это заменяет запись выше про «только `thirtyE360`». `:core:check`, `:core:pitest` (534 из 548, 97%), `:integrations:test` успешны. |
| 2026-10-22 | Фаза 2 (2.1–2.4) | 19 свойств (`CouponScheduleProperties` 9, `BondDefinitionProperties` 10), по 250 попыток; домены с малым числом значений jqwik перебирает полностью (7, 5 и 233). PIT: `core.bond` добавлен в целевые пакеты; скор до 534 из 548 (97%), после 599 из 617 (97%); `core.bond` 65 из 69 (94%). Порог `mutationThreshold` 93 → 95. JaCoCo `:core`: LINE 96,8% → 96,9%, BRANCH 87,9% → 88,0%; пороги LINE 0,93 → 0,95, BRANCH 0,82 → 0,85; `core.bond` 100% и 100%; правило `core.ledger` (0,90 / 0,77) не менялось. `:core:check` и `:core:pitest` успешны. Выжившие в `core.bond`, все эквивалентны по поведению: `BondDefinition.<init>` строка 31 (`requireCurrency` для амортизации, мертвая проверка, так как `Principal` гарантирует валюту каждой амортизации); `CouponPeriods.copySortedAndValidate` строка 18, `Principal.<init>` строка 23, `RedemptionOptions.sortedCopy` строка 25 (удаление `forEach(requireNonNull)`: NPE сохраняется из компаратора или `List.copyOf`, меняется только текст сообщения). |
| 2026-10-22 | Фаза 3 (3.1–3.4) | `LedgerProperties` расширен: 5 новых `@Property` и 1 `@Example` (класс теперь 10 свойств и 1 пример), по 250 попыток. 3.1: `everyOperationKindPostsBalancedEntriesAndKeepsTheLedgerInvariants` и `everyOperationKindRejectsInvalidInputsWithoutChangingTheLedger` с генератором на каждый из 8 `OperationKind` (sealed `KindCase`, исчерпывающий `switch`; `@Example` детерминированно проверяет покрытие всех видов). 3.2: `longTaxCorrectionChainsStayNonNegativeAppendOnlyAndConsistent` (цепочки до 40 шагов в RUB и USD; модель допуска, точный возврат до нуля, отказ при переплате, коррекция коррекции, только cash и TAX_WITHHELD меняются, история только растет). 3.3: `replayOfTheAcceptedJournalReproducesTheSameLedgerStateAndBalances`, `restoringAtAnyCutAndReplayingTheTailReproducesTheFinalState`. В случайную последовательность добавлены амортизация и налоговая проводка продажи. Код продукта не менялся; расхождений со спецификацией не найдено (единственное падение при первом прогоне было ошибкой порядка дат в тестовой установке). PIT: до 599 из 617 (97,1%), после 599 из 617 (97,1%). Два мутанта `core.bond` сменили `TIMED_OUT` на `KILLED` (убиты старым `CouponScheduleProperties`, не новыми свойствами; детект тот же). Выжившие в `ledger` после прогона, набор идентичен фазе 2 (11): `Ledger.operationDate` λ 368 (SURVIVED), `Ledger.validateTaxCorrection` λ 341 (NO_COVERAGE), `Ledger.validateTaxCorrectionPosting` λ 459 (NO_COVERAGE), `LedgerChart.intersects` 138 (SURVIVED), `BondOperations.buy` 88 и `sell` 197 (void-вызовы, SURVIVED), `BondOperations.min` 314 (SURVIVED), `BondOperations.proportional` 298, 299 (×2), 307 (SURVIVED). Новые свойства ни один из них не убили; разбор и эквивалентность — в 3б. `mutationThreshold` 95 оставлен (запас 2,1 п.). JaCoCo `core.ledger`: LINE 0,90 → 0,93, BRANCH 0,77 → 0,80 (факт 95,3% и 83,3%, запас 2,3 и 3,3 п.); модульные пороги `:core` 0,95/0,85 (факт 96,96% и 88,17%) не менялись. `:core:check` и `:core:pitest` успешны. |
| 2026-10-22 | Фаза 3б (3б.1–3б.7) | Разобраны все 18 не обнаруженных мутантов (16 SURVIVED, 2 NO_COVERAGE) по коду. Ни один не меняет наблюдаемое поведение на достижимых входах, поэтому новые тесты не добавлялись (фиктивные не писались). Ошибок в коде и расхождений со спецификацией не найдено; production-код и тесты не менялись. Мертвый код: лямбды `Ledger` 341 и 459 (гарантии `OperationResult` и вызовов). Для трех `forEach(requireNonNull)` меняется только текст NPE, контракт R5.4 задает тип. Классификация по каждому мутанту: `docs/plans/verification.md`, раздел «Эквивалентные и мертвые мутанты». Исключения в `core/build.gradle` не добавлялись: методные исключения скрыли бы обнаруживаемые мутанты. PIT: до 599 из 617 (97,1%), после 599 из 617 (97,1%); `mutationThreshold` 95 → 97 (целая часть 97,08%, запас 0,08 п.; при потере мутанта, который убивается только свойством jqwik со случайными входами, порог снизить до 96). `:core:check` успешен (UP-TO-DATE: входы не менялись с последнего зеленого прогона); `:core:pitest` — BUILD SUCCESSFUL. Оставшиеся эквивалентные (18): `BondOperations` `proportional` 298, 299 (×2), 307, `min` 314, `buy` 88, `sell` 197; `BondCalculations.floatingRate` 285, 288; `HolidayCalendar.shift` 39; `LedgerChart.intersects` 138; `Ledger` λ `operationDate` 368, λ 341 и λ 459 (NO_COVERAGE, мертвый код); `BondDefinition.<init>` 31; `CouponPeriods` 18, `Principal` 23, `RedemptionOptions` 25 (только текст NPE). |
| 2026-10-22 | Переписывание лямбд `Ledger`; тесты поправок налога | `validateTaxCorrection` и `validateTaxCorrectionPosting`: `orElseThrow` с лямбдами заменен явной проверкой `Optional.isEmpty()` (те же исключения и сообщения); `operationDate`: `anyMatch` заменен вложенным циклом (то же значение). Поведение не менялось. Лямбда-мутанты `validateTaxCorrection` 341 и `validateTaxCorrectionPosting` 459 (NO_COVERAGE) и `operationDate` 368 больше не существуют, из таблицы эквивалентных удалены. В `LedgerInvariantContractTests` добавлены 3 примера: (1) поправка раньше исходной операции при более ранней операции отвергается ISE `Backdated operation 2026-01-02 before 2026-01-03`, а не «precedes»: проверка обратной датировки в `apply` выполняется раньше (строки 65–68), поэтому ветка «precedes» через `apply` недостижима; (2) поправка в дату исходной операции принимается (контроль); (3) восстановленная история, где исходная операция единственная, принимает поправку: этот тест убивает новый мутант `operationDate` строка 372 (`equals` инвертирован). Production-код не менялся. PIT: до 599 из 617 (97,1%), после 600 из 615 (97,6%); не обнаружены 15 мутантов, все SURVIVED и эквивалентны (список в `verification.md`). `mutationThreshold` 97 без изменений. `:core:check` (тесты перезапущены) и `:core:pitest` — BUILD SUCCESSFUL. |
| 2026-10-22 | Поправка к записи 3б (порог) | Формулировки «порог 97 — целая часть 97,08%» и «потеря одного мутанта уронит гейт» неверны. PIT сравнивает округленную до целого процента долю (`PercentageCalculator`, HALF_UP, `thresholdPrecision` = 0; `MutationCoverageReport.throwErrorIfScoreBelowMutationThreshold`), а не отброшенную. Для 599/617 гейт 97 падал бы при 595 и меньше обнаруженных (запас 3 мутанта); для 600/615 падает при 593 и меньше (запас 6). Значение порога не меняется: 97 не превышает фактический скор. Исправлены `verification.md` и комментарий в `core/build.gradle`. |
| 2026-10-22 | Удаление мертвой ветки `Ledger` (решение владельца) | Удалены ветка «Tax correction precedes or lacks its original operation» в `validateTaxCorrection` и метод `operationDate(OperationId)` (`Ledger.java`). Проверка по коду: `apply` отвергает корректировку датой раньше последней даты истории до вызова `validateTaxCorrection` (строки 65–68 и 69); `apply` добавляет операцию в `operationIds` вместе с ее проводками, `restore` проверяет совпадение `operationIds` с операциями из проводок (строка 317). Поэтому исходная операция всегда есть в истории и не позже даты корректировки, и условие всегда ложно. Тесты: удален `correctionIsAcceptedWhenTheOriginalIsTheOnlyRestoredOperation` (смысл был только в мутанте `operationDate` 372, который удален вместе с методом); комментарий в `correctionDatedBeforeTheOriginal…` и сообщение в `correctionOnTheOriginalDate…` обновлены; оба примера оставлены. Расхождение со спецификацией: `docs/specs/double-entry/v1.0.0.md`, §10.2, шаг 3, описывает сообщение «Tax correction precedes…»; реализация его не выдает ни до, ни после удаления, условие обеспечивает R7.4 («Backdated operation»). Версия v1.0.0 не переписана; выровнять текст можно только новой версией спецификации с согласованием владельца. Наблюдение, код не менялся: проверка `link.isEmpty()` в `validateTaxCorrection` и `validateTaxCorrectionPosting` недостижима по инварианту `OperationResult` (строки 38–42), кандидат на следующее решение. PIT: до 600 из 615 (97,56%), после 596 из 611 (97,55%); четыре убитых мутанта удаленного кода ушли из выборки, выжило 15 (все SURVIVED, эквивалентны). `mutationThreshold` 97 без изменений: floor(97,55%) = 97, гейт падает при 589 и меньше. `:core:check` (тесты перезапущены) и `:core:pitest` — BUILD SUCCESSFUL. |
| 2026-10-22 | Удаление избыточных защитных проверок (решение владельца) | (a) `Ledger`: `link.isEmpty()` в `validateTaxCorrection` и `validateTaxCorrectionPosting` заменены на `adjustsOperation().orElseThrow()`. Проверка путей вызова до правки: `validateTaxCorrection` получает связь от `OperationResult` (инвариант, строки 38–42); `validateTaxCorrectionPosting` вызывается из `taxWithheldForOperation` (строка 213, под `filter(...).isPresent()`), из `validateTaxCorrection` и из `validateTaxCorrectionHistory` при `restore` (строки 368 и 398, под `adjustsOperation().isPresent()`). Недоверенный восстановленный state попадает в ветку поправки только со связью, поэтому оставлять проверку в `validateTaxCorrectionPosting` не нужно. Сообщения «missing its original operation link» и «has no adjusted operation» тестами не проверяются и в спецификации не описаны. (b) `BondOperations.buy` (стр. 88) и `sell` (стр. 197): удалены `requireNonNegative(commissions.otherCommission(), …)`; брокерские проверки оставлены. `Commissions` гарантирует одну валюту и неотрицательность обеих компонент (спецификация R8.19). Тесты не зависят от текста «otherCommission must not be negative» (в `core/src/test` вхождений нет); добавлен прямой тест `Commissions` на отрицательную прочую комиссию в `commissionComponentsAreValidatedAndZeroFeesAreOmitted`, поскольку удаленная проверка полагалась на него. Для достижимых входов поведение не меняется; в недостижимых ветках исключение теперь `NoSuchElementException`. PIT: до 596 из 611 (97,55%), после 594 из 607 (97,86%); выжило 13 (все SURVIVED, эквивалентны); мутанты `buy` 88, `sell` 197 и две проверки `link.isEmpty()` ушли из выборки. `mutationThreshold` 97 без изменений: floor(97,86%) = 97, гейт падает при 585 и меньше. `:core:check` (тесты перезапущены) и `:core:pitest` — BUILD SUCCESSFUL. |
