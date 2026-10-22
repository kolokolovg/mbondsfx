# План реализации double-entry v1.0.0

Дата: 2026-10-22. Срез кода: `6b6bc30e240eb0284792f8f4bfaf19f0afa9ad51`.

**Исполнение завершено 2026-10-22** после отдельного указания пользователя.
Результаты и статусы — в [реестре спецификаций](../specs/double-entry/README.md).
Ниже сохранен исходный план; NOT_STARTED относится к моменту его подготовки.

## Основание и статус

- Нормативный источник — [спецификация v1.0.0](../specs/double-entry/v1.0.0.md),
  прежде всего разделы 5–17. Этот план задает порядок работ, а не новые требования.
- По [реестру](../specs/double-entry/README.md) версия имеет статусы
  **APPROVED / NOT_STARTED**. Устаревшая DRAFT-шапка файла версии не определяет
  актуальный статус. Файл утвержденной спецификации не редактируется.
- Подготовка плана не начинает реализацию и не отменяет ранее зафиксированное
  указание пользователя пока не реализовывать спецификацию. Перед исполнением
  требуется отдельное разрешение на начало работ.
- При обнаружении необходимости изменить требования остановить соответствующую
  работу: новая версия полной спецификации и согласование предшествуют реализации.

## Цель и границы

Убрать словарь счетов из движка: `AccountCode` вместо `AccountKind`, роли в
`LedgerChart`, chart внутри `LedgerState`, одна `BondAccountingPolicy` для
генератора операций и application-сервисов. Финансовые формулы, округления,
append-only учет и CAS сохранить; единственное специально согласованное изменение
read-поведения — отказ `balance()` для неизвестного кода или неверного scoping.

Не добавлять зависимости, пакеты, модули, DSL, совместимый static-фасад,
сериализацию, миграцию хранимых данных или смену chart существующего ledger.
`OperationKind`, включая TAX_CORRECTION-диспетчеризацию, остается enum.
Разновалютные номинал/расчеты, автоматический налог и тарифы вне задачи.

## Организация поставки

Подготовительные characterization-тесты можно поставить отдельно на старом API.
Сама миграция — **один неделимый change-set для ревью/merge**: production-код,
миграция старых тестов, новая матрица, guards, документация и AGENTS вместе.
Промежуточные шаги ниже не являются самостоятельными поставками.

Основной порядок: **0 → 1 → 2 → 3 → 4 → 5 → 6**. После шага 2 production-правка
memory не зависит от завершения application; допустимое распараллеливание описано ниже.
Тесты соответствующего слоя пишутся
и мигрируют вместе с ним; шаг 5 закрывает сквозные сценарии и пробелы, а не
откладывает всю проверку до конца. До миграции всех потребителей API общий build
может не компилироваться — это ожидаемое временное состояние внутри change-set.

### 0. Подготовить контрольные сценарии на текущем API

1. После разрешения на реализацию проверить рабочее дерево, JDK 25, реестр и
   неизменность утвержденной спецификации относительно commit из реестра.
   Сохранить чужие и staged-изменения; не выполнять автоматический commit.
2. Сопоставить существующие `LedgerContractTests`, `LedgerProperties` и
   application/memory-тесты с матрицей раздела 13 спецификации.
3. Только там, где проверки отсутствуют, добавить characterization-тесты:
   отрицательный cash с обеими policy-настройками; cash/tax views; комиссия
   отдельно от базовой проводки; signed-коррекции в обе стороны и возврат до
   нуля; replay и порядок исходного удержания/коррекции внутри одной даты.
4. Сохранить точные суммы/знаки postings и снимки позиций как регрессионные
   ожидания. Не фиксировать тексты исключений. Новую семантику `balance()`
   проверять только после миграции, а не требовать от старого кода.

**Выход:** известные финансовые инварианты защищены на старой архитектуре;
измененные тесты проходят целевым task, например:

```bash
./gradlew :core:test --tests '*LedgerContractTests'
```

Если тесты не менялись, этот подготовительный запуск не требуется.

### 1. Ввести value-типы chart и bond-policy

Область записи: `core/src/main/java/ru/onlychill/mbondsfx/core/ledger/` — новые
`AccountCode.java`, `PositionMeasure.java`, `LedgerChart.java`,
`BondAccountingPolicy.java`; соответствующие core-тесты.

- `AccountCode`: record, ASCII-формат `^[A-Z][A-Z0-9_]{0,31}$`, value-equality,
  без natural ordering.
- `LedgerChart`: defensive copies, сортировка коллекций по `code.value()`,
  неизменяемые accessors, equals/hashCode по всем компонентам.
- Полностью реализовать матрицу R5.4–R5.10: known покрывает роли; ровно один
  spendable; noNegative пуст или singleton-подмножество spendable;
  adjustable/counterpart совместимы с ролями; bindings покрывают ровно
  securityScoped и обе меры, каждую ровно один раз. Дополнительные пассивные
  плоские коды разрешены.
- `BondAccountingPolicy`: девять обязательных попарно различных слотов,
  `standard()` — единственный источник стандартного словаря; `chart()` —
  чистая производная; `operations()` — configured instance. Не вводить
  дополнительную инфраструктуру кеширования.
- Отделить NPE для null от IAE для семантических нарушений, включая
  `positionBinding(nonScopedCode)`.

**Выход:** DE-P-01 и конструкторские/accessor-проверки DE-N-01/02/14/15;
часть `operations()` завершается вместе с instance-переходом шага 2.

### 2. Перевести core на chart без изменения финансовых формул

Область записи: тот же production-пакет — `Account.java`, `LedgerState.java`,
`Ledger.java`, `BondOperations.java`; удалить `AccountKind.java`.

1. В `Account` заменить `kind` на `code`; сохранить null/pseudo-currency
   guards, убрать проверку security-scoping: без chart value-объект ее не знает.
2. Добавить chart вторым компонентом `LedgerState`, сохранить все defensive
   copies. Глубокая валидация остается в restore, equality включает chart.
3. Заменить обе старые фабрики единственной
   `Ledger.open(portfolio, chart, allowNegativeCash)`; добавить `chart()`.
   Провести неизменный chart через `apply`, `state`, `restore`.
4. В `apply` сохранить нормативный порядок R7.3–R7.11. До извлечения security
   проверять known/scoping. Position mirrors проверять через bindings,
   noNegative — по каждому счету и только при `!allowNegativeCash`.
5. Перевести cash/tax views и transfer-пару correction на роли chart.
   Имена read-методов и enum-диспетчеризацию не менять. В `balance()` добавить
   IAE для unknown/scoping; для корректного отсутствующего счета оставить zero.
6. В restore проверять known/scoping и в balances, и во всех entries истории
   до dereference security; сохранить replay, zero-omission, positions и
   точный порядок posting-ов внутри date-bucket. Это проверка структурной
   согласованности, не доказательство исторической достижимости.
7. Перевести `BondOperations` в instance с policy, включая все overload-ы;
   создавать счета только внутренними фабриками со слотами policy.
   Суммы, знаки, HALF_UP/cap, Math.addExact, комиссии/налоги отдельными
   postings и quantity при амортизации не менять.
8. Мигрировать `LedgerContractTests`, `LedgerProperties` и затронутые места
   `DomainContractTests`, сохраняя смысл assertions и точные суммы.

**Выход:** `:core:check` проходит; стандартный словарь сохраняет результаты,
chart восстанавливается из state, malformed Account не вызывает
`NoSuchElementException`. Частичные прогоны не требуют сборки application.

### 3. Перевести application на constructor injection policy

Область записи: `application/.../port/LedgerCommit.java`,
`application/.../service/{LedgerApplicationService,TaxCorrectionService,
CouponReceiptService}.java`, application-тесты.

- `LedgerCommit`: явный guard равенства chart before/after. Не менять shape
  порта, revalidation и определение пустого before: наличие chart не делает
  пустое состояние непустым.
- `LedgerApplicationService(repository, policy)`: открывать ledger с
  `policy.chart()`; загруженный state проверять на равенство chart **по value**,
  не по ссылке, наряду с портфелем/cash-policy. Несовпадение — ISE до commit.
- `TaxCorrectionService(repository, policy)`: та же консистентность;
  генератор из policy. Сохранить corrected total → delta, один snapshot,
  append-only CAS без retry. Ошибки прямого запроса удержания остаются IAE,
  не превращаются в ISE application-сервиса.
- `CouponReceiptService(accounts, ledgers, policy)`: внутренние генератор и
  `LedgerApplicationService` используют ту же policy. Сохранить обязательные
  фактические gross/tax/net, проверки registry и возвращаемый `CouponReceipt`.
- Мигрировать `ApplicationContractTests` и `InvestmentAccountContractTests`.
  Для DE-N-05 проверить application-контекст ошибок без реального storage.

**Выход:** `:application:check` проходит. Policy не передается per-call,
конфигурационный дрейф отклоняется, учетная и investment-account модели не
смешиваются.

### 4. Адаптировать in-memory CAS и его контрактные тесты

Область записи:
`persistence-memory/src/main/java/ru/onlychill/mbondsfx/persistence/memory/InMemoryLedgerRepository.java`
и memory-тесты.

- Единственная production-правка — реконструкция пустого before через
  `Ledger.open(commit.portfolio(), commit.before().chart(),
  commit.before().allowNegativeCash())`.
- Не добавлять отдельную конфигурацию chart репозитория. Первый успешный
  commit выбирает chart; последующие записи проверяются по точному before.
- Мигрировать `PersistenceMemoryContractTests` и
  `TaxCorrectionMemoryContractTests`, сохранив проверки sequence, snapshot,
  journal, duplicate, независимости registry и CAS.
- Проверить два заранее подготовленных валидных первых commit с разными
  policy, разными operation ID и version 0: при конкурентной подаче побеждает ровно один, второй
  получает `LedgerConcurrencyException`. Координировать потоками/барьером,
  не задержками sleep; сверять итоговый snapshot и journal с победителем.

**Выход:** `:persistence-memory:check` проходит; CAS включает chart через
equality state, но не обещает аутентификацию chart первого писателя.

### 5. Закрыть новую матрицу и архитектурные guards

Каждый ID DE-P/DE-N из раздела 13 должен иметь явную привязку к тесту
(имя/комментарий); старый тест засчитывается, только если его assertions
покрывают все части соответствующего сценария.

| Владелец проверки | Матрица и решающее доказательство |
|---|---|
| Core: `LedgerChartContractTests` | DE-P-01; DE-N-01/02/14 и chart-часть DE-N-15: полный набор ограничений, immutable copies, равенство/порядок независимо от вставки |
| Core: `LedgerContractTests` и существующие регрессионные/property-тесты | DE-P-02…12, 15…17 (core-часть); DE-N-03…06 (core-часть), 11…13, 15 (операции), 16: точные postings восьми операций, signed-коррекции, restore после каждой операции, две позиции, роли/views, ошибки и same-date порядок |
| Core: `ArchitectureContractTests` | DE-N-10: движок не знает policy/стандартных литеральных AccountCode; bond-фабрики берут коды из policy; старые package/dependency guards сохранены |
| Application: контрактные тесты сервисов | Application-часть DE-N-05: ошибки запроса в TaxCorrectionService передаются как IAE; конструкторы и прежние сервисные инварианты |
| Memory: контрактные/сквозные тесты | DE-P-13/14, репозиторная часть DE-P-16; DE-N-07…09: реальный repository, chart-drift, запрет смены chart в commit, конкурентные первые записи |
| Root: существующий `ModulePathContractTests` | Произведенный distribution сохраняет JPMS/ServiceLoader-границы; classpath-тесты это доказательство не заменяют |

Ключевой приемочный сценарий DE-P-14: policy с девятью нестандартными кодами →
deposit → buy → coupon с удержанием через `CouponReceiptService` → sell →
`TaxCorrectionService.correct` → views/накопленное удержание → restore.
Переходы фиксируются реальным in-memory CAS. Проверять точные суммы и коды,
не только отсутствие исключений; raw postings вместо реальных операций
этот сценарий не заменяют.

Отдельно проверить пустой noNegative и `allowNegativeCash=true`, дополнительные
пассивные коды, корректный отсутствующий счет → zero, а unknown/scoping → IAE.
DE-N-10 охватывает как минимум все пять файлов, перечисленных в спецификации;
не запрещает ссылки на `OperationKind`. Для литералов использовать небольшой
source/AST guard в существующем сьюте без новых библиотек.

**Выход:** нет непокрытых ID матрицы; подтверждены условия и типы исключений,
без assertions на тексты сообщений. У каждой проверки один модуль-владелец;
повторение одного сценария в нескольких слоях без новой границы не требуется.

### 6. Согласовать документацию и выполнить единую приемку

1. Вместе с кодом обновить `docs/guides/core-accounting.md`,
   `docs/plans/architecture-plan.md`, `docs/guides/investment-accounts.md`,
   `docs/methodology/bond-tax-and-iis3.md`, а также root/core/application/memory `AGENTS.md`.
   Зафиксировать chart в state, freeze, policy как источник словаря, constructor
   injection и единственную точку создания policy в composition root.
   Не менять `Main`: сервисы учета в нем сейчас не создаются.
2. Проверить diff на scope: новых production-файлов четыре, `AccountKind`
   удален; `Posting`, `OperationResult`, `OperationKind`, `Position`,
   `Commissions`, `CouponReceipt`, shape repository-портов, JPMS descriptors,
   провайдеры, integrations и build-зависимости не изменены. Поиск старого API
   вести в production/tests и актуальной документации; исторические заметки
   и неизменяемая спецификация могут его упоминать.
3. После объединения всех работ один раз запустить на JDK 25:

   ```bash
   ./gradlew clean build jacocoAggregateReport
   ```

   Root `test` зависит от `installDist` и уже включает
   `ru.onlychill.mbondsfx.ModulePathContractTests`; отдельный повтор suite
   не нужен. Проверить XML/HTML результатов root suite, а не только успешную
   компиляцию. В одном вызове Gradle общие test tasks исполняются один раз.
4. Сохранить evidence: revision/состав diff, команду и результат, JDK,
   соответствие DE-ID тестам и пути отчетов. JaCoCo — отчет покрытия, не
   замена assertions; root module-path subprocesses им не инструментированы.
5. Только после успешной приемки отметить `IMPLEMENTED` в реестре спецификаций
   и приложить evidence. `IN_PROGRESS` ставится при фактическом начале
   реализации, не при подготовке этого плана. Commit-хэш реализации внести
   после его появления; агент сам commit не создает. Спецификацию не менять.

**Definition of done:** весь change-set и обязательные документы согласованы,
матрица раздела 13 полностью закрыта, стандартные финансовые результаты
сохранены, custom-policy работает через реальные сервисы/CAS/restore,
полный build и module-path suite зеленые, статусы в реестре подкреплены evidence.

## Разделение работ при исполнении

- Последовательный владелец core: шаги 1–2, core-тесты и core AGENTS.
- После фиксации новых core-сигнатур application и memory могут работать
  параллельно в непересекающихся каталогах; memory использует точный новый
  API, сквозные тесты запускаются после завершения application.
- Каждый исполнитель проверяет только свой модуль и передает diff/evidence;
  владелец общей интеграции сверяет изменения, обновляет общие документы и
  выполняет финальный полный прогон один раз после всех исполнителей.
- Не запускать параллельные Gradle clean/build в общем рабочем дереве.
  Для этой связанной миграции отдельные worktrees не обязательны; важнее
  непересекающиеся области записи и согласованные сигнатуры.

## Основные риски и остановки

| Риск | Мера контроля |
|---|---|
| Неполная миграция breaking API | Один change-set; поиск всех вызовов, миграция jqwik/property-тестов наряду с JUnit |
| Scoping снят с Account, но потерян на границе | Явные guards до security dereference в apply/restore/balance; DE-N-13 |
| Изменение equality нарушило CAS | Value-equality chart, DE-P-13 и DE-N-08/09 на реальном repository |
| Незаметное изменение формул/порядка коррекций | Точные регрессионные postings, signed-примеры, обе хронологии одной даты |
| В движке остался стандартный словарь | Guard DE-N-10 плюс полноценный custom-policy DE-P-14 |
| Переоценка гарантий | Не обещать подлинность первого chart, происхождение операции или достижимость restore |

Если срез кода изменился, перед исполнением повторить проверку затронутых API
и тестов. Если выяснятся внешние потребители, persisted-формат или потребность
в дополнительных ролях/валютах, не добавлять обходной механизм: согласовать
изменившиеся предпосылки и новую спецификацию.
