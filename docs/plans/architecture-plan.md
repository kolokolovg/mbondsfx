# Архитектурный план: гексагональные модули, JPMS и будущая персистентность (rev.3)

Статус: **ФАЗЫ 1–4 ЗАВЕРШЕНЫ** (rev.3 от 2026-10-20); gate1–gate4 APPROVED.
Чистая сборка, offline checks и 18 module-path/distribution проверок прошли;
итоговые доказательства — `.slim/deepwork/hexagonal-modules-implementation/final-validation.md`. Фактическое состояние — в §1;
SQLite и compaction остаются будущими отдельными gates. Родительские артефакты
`.slim/deepwork/hexagonal-modules-plan/{recon.md,research.md}`; предыдущие ревизии заменены этой.

## 1. Текущее состояние (факты из кода)

- Текущие Gradle-модули: `:core` — чистый домен; `:application` (`api :core`) — use cases и
  outbound ports; `:integrations` (`api :application`) — общие fixing DTO/API, provider SPI,
  transport/XML и generic bridge; `:integrations-moex` и `:integrations-cbr`
  (`implementation :integrations`) — vendor adapters, MOEX raw-контракт находится в первом;
  `:persistence-memory` (`implementation :application`) — in-memory ledger repository и investment-account
  registry. Root compile-зависимости — core/application/shared integrations, runtimeOnly — два provider
  modules и memory; root compile также включает SLF4J API (его требует JPMS descriptor).
- Текущий toolchain всех семи проектов — JDK 25; root JPMS blackbox suite запускает `java`/`javac` JDK 25,
  а обе compile-fixture команды используют `--release 25`. Wrapper использует Gradle 9.8.2 с закрепленным
  `distributionSha256Sum`; JaCoCo tool version — 0.8.15.
- Все семь JPMS descriptors настроены. Root module `ru.onlychill.mbondsfx` использует четыре typed
  service contracts и при старте создает ledger и investment-account repositories с совпадающим provider ID;
  CLI `--profile=all|moex|cbr` создает только выбранные market-data capabilities и memory, не выполняя fetch.
  Module-path blackbox suite проверяет произведенный `installDist`.
  Остаются process-local memory, полная история `Ledger` без compaction, SQLite/migration stack вне
  scope. Исторические JavaExec main-runner suites мигрированы на JUnit Jupiter; проверки сохранены,
  а тестовая компиляция и whitebox execution по-прежнему остаются classpath-only.
- `:core` accounting distinguishes spendable per-currency `CASH` from non-spendable
  `TAX_WITHHELD` in the standard `BondAccountingPolicy`; the engine uses `LedgerChart` roles,
  not account names. `AccountCode` replaces the former enum; chart is frozen in `LedgerState`.
  Instance `BondOperations` and accounting services receive one composition-root policy.
  Explicit nonzero commissions and withholding use separate postings. `TAX_CORRECTION`
  postings append a linked withholding adjustment, and application `TaxCorrectionService` commits
  an explicitly supplied corrected total without rewriting the original receipt or journal. Details:
  [accounting](core-accounting.md) and [investment accounts](investment-accounts.md).

## 2. Целевая топология и направление зависимостей

| Модуль (Gradle / JPMS) | Роль | Зависимости (Gradle / JPMS) |
|---|---|---|
| `:core` / `…core` | агрегаты, расчеты, учет, доменный `IndexValueSource` | нет |
| `:application` / `…application` | inbound use-cases + outbound порты: sparse reference data, ledger repository/journal, investment-account repository/factory; coupon receipt and tax correction services | `api :core` / `requires transitive …core` |
| `:integrations` / `…integrations` | generic-контракты: ОБА typed capability-provider SPI, общий transport/xml/fixing DTO, мост | `api :application` / `requires transitive …application` (+`…core` транзитивно) |
| `:integrations-moex` / `…integrations.moex` | MOEX-провайдер: vendor-internal адаптер raw→sparse | `implementation :integrations` (+app/core транзитивно) / `requires` |
| `:integrations-cbr` / `…integrations.cbr` | CBR-провайдер значений индексов | аналогично moex |
| `:persistence-memory` / `…persistence.memory` | in-memory ledger repository и investment-account registry | `implementation :application` / `requires` |
| `:persistence-sqlite` / `…persistence.sqlite` | **БУДУЩЕЕ**: JDBC, миграции, WAL | `:application` (+xerial, отдельный gate) |
| root / `ru.onlychill.mbondsfx` | composition root | compile: `core`,`application`,`integrations`,`slf4j-api` (только контракты/API); runtimeOnly: ОБА провайдера + `persistence-memory` |

Инварианты: `:core` ни от чего не зависит; `:application` НЕ зависит от `:integrations` и
провайдеров; `:integrations`→`:application`→`:core` (ациклично); root — единственный, кто знает все.
Root runtime-distribution включает ОБА провайдера (явное требование пользователя); выбор —
конфигурируемый профиль `providerId`, оба профиля покрыты фикстурами.

## 3. Отображение классов: исходное размещение → реализованное разделение

| Исходно (до split) | Текущее размещение после фазы 3 | Примечание |
|---|---|---|
| `marketdata.MarketDataPort`, `marketdata.model.BondMarketData` | `:integrations-moex`, packages сохранены | vendor raw-контракт |
| `adapter.MoexBondAdapter` | `:integrations-moex`, `…integrations.moex.internal` | raw→sparse адаптация |
| `adapter.CbrIndexAdapter` | `:integrations-cbr`, `…integrations.cbr.internal` | CBR provider implementation |
| `adapter.SecureXml` | `:integrations`, `…integrations.xml`, public | qualified export только providers MOEX/CBR |
| `adapter.IndexValueSourceAdapter`, `adapter.StaticFixings` | `:integrations`, остаются | generic bridge/fixtures |
| `index.*`, `transport.*` | `:integrations`, остаются | includes transport `HttpStatusException` contract |
| `…integrations.provider.{ProviderContext,IndexValueSourceProvider,BondReferenceDataSourceProvider}` | `:integrations` | shared typed provider contracts |
| `…application.port.*` + application service | `:application` (`api :core`) | §4.2–4.4 |
| `Ledger.state()` + `Ledger.restore(LedgerState)` | `:core` | recovery pair §4.5 |

## 4. Контрактные решения

### 4.1 Shared typed capability-provider SPI и bootstrap

ОБА capability-SPI — в generic `:integrations` (уточнение попытки 1: SPI не в модуле
реализации). Типизированные, без `get(Class)`-локатора; boot-layer `ServiceLoader`;
ModuleLayer/hot-reload — вне scope. Ниже — эскизы API (намеренно неполные, не буквально
компилируемый Java; сигнатуры уточняются фазой 1):

```java
// :integrations, пакет …integrations.provider (публичный экспорт)
public record ProviderContext(Map<String, String> settings) { /* валидация null/blank, копия */ }
public interface IndexValueSourceProvider {          // CBR-возможность
    String providerId(); core.bond.calculation.port.IndexValueSource create(ProviderContext ctx);
}
public interface BondReferenceDataSourceProvider {   // MOEX-возможность
    String providerId(); application.port.BondReferenceDataSource create(ProviderContext ctx); // app-порт, НЕ raw-типы
}
```

- Оба провайдера независимо обнаруживаемы и взаимозаменяемы через compile-зависимости ТОЛЬКО от
  стабильных контрактов; провайдер может реализовать несколько возможностей. `ProviderContext` —
  только настройки; провайдер строит `HttpTransport` внутри своего модуля; root НЕ видит
  транспортных типов вообще. Тестовые швы сохраняются: адаптеры провайдеров принимают fake
  `Transport` через внутренние конструкторы (существующий паттерн тестов).
- Bootstrap: `ServiceLoader.load(...)`, `stream()`, выбор по `providerId()` из конфига;
  отсутствующий ID — ясная ошибка; дубликат ID — ошибка; сеть только при первом вызове
  capability, не при discovery/конструировании. «Без явных импортов реализаций»:
  core/application не содержат импортов `integrations.*`; root импортирует только контракты
  (типы `:core`, порты `:application`, SPI `:integrations.provider`); импорты контрактов
  законны и не «устраняются».

### 4.2 Sparse read-контракт bond-data (application-owned, минимальный)

Выбран минимальный source-neutral контракт в `:application` (без него MOEX-возможность
непотребима, а пользователь явно запросил обоих провайдеров в runtime). Эскиз, не финальная
сигнатура:

```java
// :application, пакет …application.port — эскиз
public interface BondReferenceDataSource { Optional<BondReference> reference(SecurityId security); }
// BondReference: SecurityId; provenance: sourceId — ОБЯЗАТЕЛЬНЫЙ идентификатор источника;
// дата наблюдения — Optional, ТОЛЬКО если источник явно сообщает её или она передана явным
// параметром запроса (никаких скрытых clock/«latest»/подстановки даты по умолчанию);
// числовые поля — только в фактически сообщенных единицах (faceValuePerUnit вместе с
// faceUnit как reported; при отсутствии — пустое Optional); следующий купон как reported.
```

Границы (обязательные): адаптация raw→sparse выполняется ВНУТРИ `:integrations-moex`;
переносятся только поля, явно присутствующие и дословно подтверждаемые в raw-строках;
неизвестное → пустое `Optional` (provenance сохраняется), никаких придуманных `BondDefinition`,
формул выпуска, молчаливой нормализации, скрытых дат/часов; mapper-фичи НЕТ — ровно один
метод; расширение контракта — только явным требованием.

### 4.3 SecureXml и транспортный статус

`SecureXml` → `…integrations.xml`, public-класс, пакет доступен ТОЛЬКО moex/cbr через qualified
export (не безусловно public, root не видит). `CbrIndexAdapter` переключается на контрактный
статус-тип в `integrations.transport` (вместо конкретного вложенного
`HttpTransport.HttpStatusException`) — предусловие фазы 1.

### 4.4 Хранилище учета: snapshot + journal (без event-sourcing мандата)

```java
// :application, пакет …application.port — эскиз (не полная реализация интерфейсов)
public interface LedgerRepository extends LedgerJournal {   // read-запросы из того же экземпляра
    Optional<LedgerSnapshot> load(PortfolioId portfolio);   // снапшот + ожидаемая версия
    void commit(LedgerCommit commit);  // АТОМАРНО: CAS + состояние + журнал + дедуп + sequence
}
public interface LedgerJournal { List<OperationRecord> operations(PortfolioId portfolio); } // read-only read-model, порядок (date, sequence)
public interface LedgerRepositoryFactory { LedgerRepository create(RepositoryConfig cfg); }  // фабрика возвращает репозиторий, несущий read-запросы
```

- `OperationRecord` — read-model публичных полей `OperationResult` (kind/operation/date/
  postings/positionBefore/After; `Posting`/`Position` — public core-типы); журнал НЕ обязан
  восстанавливать применимый `OperationResult`; реконструкция через package-private конструктор
  `OperationResult` (`OperationResult.java:20`) отсутствует, unrestricted-конструктор запрещен.
- `LedgerRepository extends LedgerJournal` — минимальный явный доступ к read-запросам из
  service-discovered репозитория (без отдельного модуля/универсального локатора): фабрика
  возвращает репозиторий, несущий read-запросы журнала. Контракт `LedgerCommit` включает
  `expectedVersion` и проверку соответствия снапшота журналу операций (репозиторий НЕ
  принимает не связанные между собой снапшот и журнал; дубль `(portfolio, operationId)` —
  отказ, CAS — атомарно, семантики §4.6 сохранены).
- Репозиторий выбирается ServiceLoader-фабрикой (замена memory→sqlite без перекомпиляции
  core/application/root). Атомарные семантики (CAS, дедуп, sequence) определены в контракте
  фазы 1, проверяются реализацией фазы 2 — НЕ откладываются в «hardening» (§4.6, §6).
- В текущем application API также есть отдельные `InvestmentAccountRepository` и
  `InvestmentAccountRepositoryFactory`: инвестиционный account metadata не является `AccountCode` и не
  меняет ledger. Root загружает эту фабрику с тем же provider ID, что и ledger repository; memory provider
  предоставляет обе реализации. `CouponReceiptService` записывает только вручную подтвержденные
  gross/tax/net, а `TaxCorrectionService` добавляет коррекцию только по caller-supplied corrected total.

### 4.5 `Ledger.state()`/`restore()`: граница доверия и пределы валидации

Существующий `Ledger` НЕ экспонирует `portfolio`/`allowNegativeCash` (геттеров нет), поэтому
нужно ПАРНОЕ API — только `restore` недостаточно:

```java
// :core, core.ledger — эскиз; поведение существующих методов не меняется
public LedgerState state();                   // глубокая неизменяемая копия ВСЕГО состояния
public static Ledger restore(LedgerState s);  // полная ревалидация; вход НЕ доверенный
// LedgerState: portfolio, chart, allowNegativeCash, balances, positions, postingsByDate, operationIds
```

Пределы и обязательные утверждения:
- `state()` — глубокая копия (защита от изменяемых представлений); `restore()` считает вход
  недоверенным и ревалидирует структурные инварианты: каждый posting сбалансирован по каждой
  валюте; позиции сверены с балансами по chart position bindings (в стандартной policy —
  `SECURITIES`/`ACCRUED_INTEREST`); identity/date-согласованность
  postings; `portfolio` согласован со всеми `Account`; операция МОЖЕТ иметь несколько postings
  (валидация без предположения 1:1).
- Дубликаты `OperationId`: `Set` молча дедуплицирует — canonical-конструктор `LedgerState` НЕ
  может их обнаружить, и план этого НЕ заявляет; обнаружение дублей — ДО преобразования в Set,
  при decode журнала в репозитории (уникальность групп по `(portfolio, operationId)`).
- Структурная согласованность НЕ доказывает историческую финансовую достижимость состояния —
  план этого НЕ заявляет; гарантируется только внутренняя непротиворечивость. Финансовая
  семантика домена не меняется. В double-entry v1.0.0 `BondOperations` стал instance,
  а `apply` использует роли chart; контракт `OperationResult` сохранен.
- Chart — часть самодостаточного state и CAS-equality, неизменен после
  `Ledger.open(portfolio, chart, allowNegativeCash)`. Сервисы получают policy в
  конструкторе и отвергают другой persisted chart по value-equality. `LedgerCommit`
  явно запрещает смену chart. Первый успешный commit выбирает chart без гарантии
  подлинности; генератор и валидатор согласуются wiring, не проверкой происхождения.
  `Main` accounting-сервисы пока не создает; при их сборке policy создается только
  composition root. `OperationKind` остается enum, новых exports/модулей нет.
- Стоимость: `state()` и каждый commit копируют полную историю — O(операций); обёртка-репозиторий
  НЕ извлекает неограниченную историю из in-memory `Ledger`. ЯВНАЯ отдельная будущая стадия
  (фаза 5b): extraction/compaction — вынос истории/дедупликации в репозиторий с переносом
  ответственности и parity-тестами; начальный полный snapshot — осознанный промежуточный выбор,
  целевая граница зафиксирована явно, не спрятана.

### 4.6 Идемпотентность, порядок, конкурентность

Дубль `(portfolio, operationId)` → отказ (decode-уникальность в репозитории + дубль-check в
`Ledger.apply`; НЕ возврат прежнего результата молча). CAS: `commit` принимает
`expectedVersion`, применение только при совпадении (SQLite — rowcount `UPDATE … WHERE
version = ?`). Порядок: явный монотонный per-portfolio `sequence`; same-date postings
упорядочены `(date, sequence)`; backdate-инвариант `Ledger` сохраняется. Сериализация: одна
точка на portfolio (memory — lock per `PortfolioId`; SQLite — один writer на файл + IMMEDIATE);
параллельные читатели разрешены; конкурирующие commit'ы — детерминированная фикстура фазы 2
(ровно один отклонен). Асинхронность: сейчас всё синхронно; будущий фасад — bounded writer
executor + ограниченные readers (`CompletableFuture` только dispatch). JDK 25: JEP 491 снимает pinning
виртуальных потоков на `synchronized`, однако native/foreign calls всё ещё могут pin. «Виртуальные
потоки = неблокируемость» НЕ заявляем: JDBC остается блокирующим; соединение на поток без шаринга;
неоднозначный commit
автоматически НЕ повторяется. Сетевой параллелизм — внутри провайдера, bounded (JDK
HttpClient), в домен не экспонируется.

### 4.7 JPMS descriptors и выравнивание Gradle

Соответствие: `requires`↔`implementation`, `requires transitive`↔`api`, `requires static`↔
`compileOnly`; Gradle согласованность не проверяет, поэтому architecture guard сопоставляет Gradle
project edges с descriptors. Ниже — текущий снимок фактических `module-info.java`; module-path behavior
доказывает отдельный blackbox runner на произведенном distribution и остается обязательной проверкой
границ, независимо от истории фаз.

```
module ru.onlychill.mbondsfx.core {
    exports ru.onlychill.mbondsfx.core.bond; exports ru.onlychill.mbondsfx.core.bond.calculation;
    exports ru.onlychill.mbondsfx.core.bond.calculation.port; exports ru.onlychill.mbondsfx.core.id;
    exports ru.onlychill.mbondsfx.core.ledger; exports ru.onlychill.mbondsfx.core.money; }

module ru.onlychill.mbondsfx.application {
    requires transitive ru.onlychill.mbondsfx.core;
    exports ru.onlychill.mbondsfx.application.port; exports ru.onlychill.mbondsfx.application.service; }

module ru.onlychill.mbondsfx.integrations {
    requires transitive ru.onlychill.mbondsfx.application;   // SPI возвращает app-порт в подписи
    requires java.net.http;      // HttpTransport (JDK HttpClient)
    requires java.xml;           // SecureXml (javax.xml / org.w3c.dom / org.xml.sax)
    exports ru.onlychill.mbondsfx.integrations.provider;     // единственный blanket-экспорт
    exports ru.onlychill.mbondsfx.integrations.transport to ru.onlychill.mbondsfx.integrations.moex, ru.onlychill.mbondsfx.integrations.cbr;
    exports ru.onlychill.mbondsfx.integrations.xml to ru.onlychill.mbondsfx.integrations.moex, ru.onlychill.mbondsfx.integrations.cbr;
    exports ru.onlychill.mbondsfx.integrations.index to ru.onlychill.mbondsfx.integrations.moex, ru.onlychill.mbondsfx.integrations.cbr;
    exports ru.onlychill.mbondsfx.integrations.adapter to ru.onlychill.mbondsfx.integrations.moex, ru.onlychill.mbondsfx.integrations.cbr; }

module ru.onlychill.mbondsfx.integrations.moex {   // НЕТ exports: реализация vendor-internal
    requires ru.onlychill.mbondsfx.integrations;
    requires java.net.http; requires java.xml;
    provides ru.onlychill.mbondsfx.integrations.provider.BondReferenceDataSourceProvider
        with ru.onlychill.mbondsfx.integrations.moex.internal.MoexProviderFactory; }

module ru.onlychill.mbondsfx.integrations.cbr {    // НЕТ exports
    requires ru.onlychill.mbondsfx.integrations;
    requires java.net.http; requires java.xml;
    provides ru.onlychill.mbondsfx.integrations.provider.IndexValueSourceProvider
        with ru.onlychill.mbondsfx.integrations.cbr.internal.CbrProviderFactory; }

module ru.onlychill.mbondsfx.persistence.memory {  // НЕТ exports
    requires ru.onlychill.mbondsfx.application;
    provides ru.onlychill.mbondsfx.application.port.LedgerRepositoryFactory
        with ru.onlychill.mbondsfx.persistence.memory.InMemoryRepositoryFactory;
    provides ru.onlychill.mbondsfx.application.port.InvestmentAccountRepositoryFactory
        with ru.onlychill.mbondsfx.persistence.memory.InMemoryRepositoryFactory; }

module ru.onlychill.mbondsfx {
    requires org.slf4j;
    requires ru.onlychill.mbondsfx.core; requires ru.onlychill.mbondsfx.application;
    requires ru.onlychill.mbondsfx.integrations;
    uses ru.onlychill.mbondsfx.integrations.provider.IndexValueSourceProvider;
    uses ru.onlychill.mbondsfx.integrations.provider.BondReferenceDataSourceProvider;
    uses ru.onlychill.mbondsfx.application.port.LedgerRepositoryFactory;
    uses ru.onlychill.mbondsfx.application.port.InvestmentAccountRepositoryFactory; } // + mainClass Main
```

Обязательные пункты:
- Провайдерские модули НЕ экспортируют пакеты: только `provides` + unexported `internal`.
- Qualified exports называют ТОЛЬКО владельцев поддержки (moex/cbr), НЕ root; root никогда не
  получает транспорт/xml/index/adapter. Расширение friend-списка при провайдере N+1 —
  преднамеренное документируемое односторонее изменение ДЕСКРИПТОРА `:integrations`, БЕЗ
  изменений core/application/root.
- Нет split-package: shared владеет `…integrations.{provider,transport,xml,index,adapter}`;
  moex — `…integrations.moex.*` + перенесенные `…integrations.marketdata(.model)`; cbr —
  `…integrations.cbr.*`. Временное дублирование классов в фазе 2 (копии в новых модулях +
  оригиналы в shared) существует только на classpath и устраняется в фазе 3 ДО module-info
  (иначе split-package нарушит запуск).
- Фикстуры обязаны РАЗЛИЧАТЬ runtime module path и runtime classpath: доказательство JPMS —
  только запуск по module path (положительный: service binding обоих провайдеров;
  отрицательный: рефлексивный/компиляционный доступ к `…integrations.xml`/`.internal`
  падает). Обычные JUnit tests, запущенные Gradle на classpath, — whitebox, доказательством НЕ являются.
  Smoke дистрибутива: запуск ПРОИЗВЕДЕННОГО дистрибутива (`installDist`/start-скрипт
  application plugin, module path), не только `gradlew run`.

### 4.8 Миграции и SQLite (фаза 5a, БУДУЩЕЕ — отдельный gate при старте)

`org.xerial.sqlitejdbc` — единственная ПРЕДЛАГАЕМАЯ внешняя зависимость (permissive:
Apache-2.0 + Zentus-notice + SQLite public domain); добавление ТОЛЬКО после верификации
точного релиза по [AGENTS.md](../../AGENTS.md): координаты/версия (не master), дескриптор,
natives, транзитивные notices, temp-каталог extraction, проверка `transaction_mode=IMMEDIATE`
релиза против raw `BEGIN`. Миграции: маленький версионированный SQL-runner; таблица
`schema_migrations` (version, checksum SHA-256; `user_version` опционально зеркально;
`schema_version` не трогать). Порядок: один writer, `BEGIN IMMEDIATE` → перечитать версию →
ожидающие скрипты по порядку → обновить таблицу → COMMIT; ошибка — ROLLBACK без смены версии;
версия БД новее кода — отказ. PRAGMAs per-connection вне транзакций (`busy_timeout`;
`foreign_keys` OFF по умолчанию!); `journal_mode=WAL` персистентно вне транзакции;
`synchronous=FULL` (документированный tradeoff `NORMAL`); WAL = один host/один writer/
параллельные читатели, сетевые ФС запрещены.

## 5. Политика зависимостей/лицензий (точная отсылка)

Политика — корневой [AGENTS.md](../../AGENTS.md) «Внешние зависимости и лицензии» (обновлена
параллельно; план её отражает, не заменяет): тяжёлые фреймворки вне UI — НЕ выбор по умолчанию,
не категорический запрет (обоснованные одобренные исключения возможны); сначала JDK 25 или
малый проектный компонент; лёгкие поддерживаемые permissive-библиотеки допустимы при доказанной
необходимости (не blanket); до добавления — точные координаты/версия, лицензия, транзитивные
зависимости, natives/notices, JPMS-имя; copyleft/proprietary — отдельное явное одобрение;
`java.sql` — только API, драйвер никогда в `:core`; JPMS descriptors настроены фазой 4, но их
  encapsulation подтверждается только module-path blackbox fixtures, не classpath tests. Production
  code использует JDK без сторонних библиотек, кроме root-only `slf4j-api` для логирования;
  `slf4j-simple` — его runtime backend. JUnit Jupiter 5.12.2 и JUnit Platform 1.12.2 добавлены
  только в test configurations.

## 6. Фазы (порядок Oracle-gate обязателен; координатор — единственный владелец settings.gradle, root build и удалений в shared `:integrations`)

**Фаза 1 — контракты и швы (freeze; РЕАЛИЗОВАНО, gate1 APPROVED).** Писатели с несвязанными областями: (A) `:core`
(парное `state()`/`restore()` §4.5) + новый `:application` (порты §4.2/4.4 с АТОМАРНЫМИ
семантиками CAS/дедуп/sequence в контракте; read-only `LedgerJournal`; минимальный сервис);
(B) shared `:integrations` (оба SPI §4.1, `SecureXml`→`xml` public, контрактный статус-тип +
переключение CBR). Включение `:application` в settings — координатор. Placement меняется →
локальный `AGENTS.md` `:application` и затронутые строки root/core/integrations AGENTS
обновляются В ЭТОЙ фазе. Acceptance: существующие runner'ы зелёные по СЕМАНТИКЕ (assertion'ы
домена без изменений; карта architecture-guard обновляется преднамеренно); новые
`ApplicationContractTests` (атомарность против inline-файка); расширенный arch-guard.
Gate: Oracle — до split; предусловия (статус-тип, SecureXml) уже в коде.

**Фаза 2 — параллельные лейны (РЕАЛИЗОВАНО, gate2 APPROVED; несвязанные директории модулей; лейны НЕ трогают settings.gradle,
root build, shared `:integrations`).** Порядок: (1) лейны создают директории модулей со скелетами
build-файлов и кодом (MOEX: vendor-классы копируются, `internal`-фабрика реализует shared SPI,
raw→sparse адаптация §4.2, локальный AGENTS, runner; CBR: `provides IndexValueSourceProvider`;
Memory: production-реализация с АТОМАРНЫМ поведением — CAS, decode-дедуп, sequence, полный набор
сценариев + конкурирующие commit'ы + journal query); (2) координатор — ЕДИНСТВЕННЫЙ — кратким
последовательным handshake регистрирует новые модули в settings.gradle ПОСЛЕ появления
директорий, ДО валидации (фаза 2 НЕ предполагает валидацию незарегистрированных модулей);
(3) адресные проверки лейнов (`:integrations-moex:check` и т.п.); (4) Oracle-gate; (5) фаза 3 —
удаление shared. Acceptance: сценарии репозитория зелёные раньше «hardening»; оба провайдера
whitebox зелёные. Gate: Oracle — disjointness и сохранность семантики.
**Фаза 3 — сверка и завершение split (РЕАЛИЗОВАНА, gate3 APPROVED).** Vendor-классы удалены из
shared `:integrations`; assertions распределены без потери покрытия; root/shared/MOEX rules и docs
обновлены; dependency-free source+Gradle direction guard покрывает шесть модулей. Acceptance
подтверждена отсутствием split packages/duplicate test FQCN и успешными offline checks всех шести
модулей (см. `phase3-cleanup.md`).

**Фаза 4 — JPMS + модульный bootstrap + smoke дистрибутива (РЕАЛИЗОВАНА, gate4 APPROVED).** Семь
descriptors, Gradle alignment, root `mainModule`/`mainClass`, `uses`×3 и runtimeOnly providers/memory
настроены; root CLI строит typed capabilities без fetch. Подтверждены компиляция, classpath whitebox
checks и `installDist`. Независимые fixtures запускают произведенный distribution на module path и
проверяют service binding профилей, missing/duplicate IDs, provider substitution и denied access к
qualified/non-exported packages. Все 18 blackbox проверок прошли в чистой и offline сборках;
classpath tests отдельно не считаются JPMS proof. Позднее исходные JavaExec main-runner'ы были
мигрированы в стандартные JUnit tests; root `test` зависит от `installDist`, а 18 module-path
проверок запускаются отдельными java/javac subprocesses. `compileTestJava` и JUnit runner остаются
на classpath (`inferModulePath = false`), без `opens`/`--add-opens`. См. `gate4.md` и
`final-validation.md` как исторические gate-доказательства.

**Фаза 5 — БУДУЩЕЕ, отдельные gate при явном старте.** (a) `:persistence-sqlite`: JDBC/xerial
после верификации §4.8, миграции, WAL, фикстуры отказа БД (rollback + reopen), roundtrip
`BigDecimal`/`Currency` ISO-код, общие с memory сценарии. (b) Extraction/compaction §4.5:
вынос неограниченной истории/дедупликации из in-memory `Ledger` в репозиторий с переносом
ответственности и parity-тестами. Сейчас НЕ реализуется; начальный полный snapshot — осознанный промежуточный выбор.

## 7. План проверок и фикстур (offline, без сети)

- Фаза 3 добавляет `ApplicationArchitectureContractTests` с явными путями source
  roots/build files: Java imports и Gradle edges проверяются для root→core, application→core,
  integrations→application/core, moex/cbr→integrations/application/core,
  persistence-memory→application/core. Negative fixtures проверяют forbidden edges; core
  `ArchitectureContractTests` также запрещает application и integration imports. Числовые и
  metadata-guard assertion'ы домена не менялись.
- Репозиторий (memory сейчас, те же сценарии для SQLite в фазе 5a): restore-равенство с прямым
  `apply` (через парное `state()`/`restore()`); дубль `(portfolio, operationId)` → отказ на
  decode ДО Set; CAS mismatch → отказ; same-date монотонный sequence; backdate → отказ как
  сегодня; конкурирующие commit'ы — ровно один отклонен; `LedgerJournal` запрос возвращает
  порядок `(date, sequence)`; reopen/load→restore→равенство.
- JPMS (фаза 4, текущая JUnit suite): positive — module-path запуск + service binding; negative — доступ к
  неэкспортированным `…integrations.xml`/`.internal` (рефлексия/компиляция) падает; missing
  provider → ясная ошибка; duplicate providerId → ошибка; замена провайдера (dependency
  substitution) без перекомпиляции core/application; smoke произведенного дистрибутива
  с обоими профилями.
- Фаза 5a (будущее): fresh DB; последовательные версии; checksum-mismatch → отказ; версия
  новее кода → отказ; падение скрипта → rollback и неизменная версия; два одновременных
  открытия; WAL один writer/параллельные readers; отказ БД (rollback + reopen); roundtrip
  `BigDecimal`/ISO-код `Currency`.

## 8. Ключевые официальные источники

JLS SE 25 гл.7 (https://docs.oracle.com/javase/specs/jls/se25/html/jls-7.html);
ServiceLoader/ModuleLayer (https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/ServiceLoader.html ,
https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/ModuleLayer.html);
java.sql/DriverManager (https://docs.oracle.com/en/java/javase/25/docs/api/java.sql/module-summary.html ,
https://docs.oracle.com/en/java/javase/25/docs/api/java.sql/java/sql/DriverManager.html);
JEP 444/491 (https://openjdk.org/jeps/444 , https://openjdk.org/jeps/491); Gradle
application/java library/testing (https://docs.gradle.org/9.8.2/userguide/application_plugin.html ,
https://docs.gradle.org/9.8.2/userguide/java_library_plugin.html ,
https://docs.gradle.org/9.8.2/userguide/java_testing.html); SQLite (https://www.sqlite.org/wal.html ,
https://www.sqlite.org/lang_transaction.html , https://www.sqlite.org/pragma.html ,
https://www.sqlite.org/threadsafe.html , https://www.sqlite.org/copyright.html);
xerial/sqlite-jdbc (https://github.com/xerial/sqlite-jdbc).

## 9. Осторожные открытые выборы (не блокируют)

Имена SPI/портов/записей и раскладка `internal`; набор ключей `ProviderContext`; объем демо
`Main` (минимальный wiring без сети, оба профиля); формат blackbox-фикстур; зеркальный
`user_version` — всё в рамках контрактов §4; фиксирует исполнитель фазы с Oracle-gate.
