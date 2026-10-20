# Адаптеры рыночных данных

Фактическая Gradle-топология: `:application` (`api :core`); shared `:integrations`
(`api :application`, транзитивно `:core`); `:integrations-moex` и `:integrations-cbr`
(`implementation :integrations`); `:persistence-memory` (`implementation :application`). Root
compile-зависимости — core/application/shared integrations, а providers и memory подключены как
`runtimeOnly`. JPMS descriptors настроены; module-path blackbox validation остается отдельным
acceptance этой фазы. `MarketDataPort` принадлежит `:integrations-moex` и находится в пакете
`ru.onlychill.mbondsfx.integrations.marketdata`; `BondMarketData` и вложенные raw rows — там же в
`ru.onlychill.mbondsfx.integrations.marketdata.model`. Общий `Transport` остается в
`:integrations` (`ru.onlychill.mbondsfx.integrations.transport`). Внешние `IndexFixing`, `IndexFixingSource` и
`MissingFixingException` находятся в `ru.onlychill.mbondsfx.integrations.index`; `IndexId` — это
доменный тип `ru.onlychill.mbondsfx.core.id`. Расчеты `:core` принимают только доменный
`IndexValueSource` из `ru.onlychill.mbondsfx.core.bond.calculation.port`, а не внешний fixing DTO
или source. Публичные endpoints MOEX и ЦБ РФ не требуют API-ключа;
адаптеры не добавляют аутентификацию. В production по умолчанию используется HTTPS. Контрактные
тесты работают с fake-транспортами в памяти и не обращаются к сети.

## Выбор capabilities в root application

Root selects the public typed provider contracts through `ServiceLoader`; it does not import vendor
adapters, raw DTOs, shared transport/XML, or the generic fixing bridge. CLI construction creates the
selected provider capabilities and in-memory repository but does not fetch data:

```bash
./gradlew run --args='--profile=all'
# profile=all bond=moex index=cbr repository=memory
./gradlew run --args='--profile=moex'
# profile=moex bond=moex index=none repository=memory
```

`--bond-provider=`, `--index-provider=`, and `--repository-provider=` explicitly select IDs; defaults
are `moex`, `cbr`, and `memory`. IDs are provider-owned and duplicate or missing selected IDs fail with
a diagnostic. Direct adapter constructors and raw MOEX rows remain internal to their provider modules.

## Передача внешних фиксаций в расчеты `:core`

`IndexValueSourceProvider` — shared public capability SPI; его factory создает доменный
`IndexValueSource` через internal fixing adapter. Root может использовать возвращенный domain port,
но JPMS не экспортирует provider implementation или fixing/transport packages ему. Не передавайте
`IndexFixing`/`IndexFixingSource` непосредственно в `BondCalculations`.

Аналогично оборачивается `StaticFixings.of(...)`. Мост сверяет ID и дату в DTO с запрошенными
значениями; null DTO или несовпадение метаданных также приводит к доменной ошибке. Он переводит
внешний `MissingFixingException` и некорректный/отсутствующий DTO в `MissingIndexValueException`
с запрошенными `IndexId` и датой, а `BigDecimal` возвращает без изменения. Другие транспортные и
ошибки разбора не маскируются как отсутствие значения. Forward-fill, интерполяции и поиска другой
даты нет. `MissingFixingException` остается ошибкой внешнего API и находится в `integrations.index`.

Shared `HttpTransport` доступен только provider modules через qualified export. Для него provider
явно передаёт `HttpClient` и положительный `Duration` таймаута
каждого запроса. Транспорт отправляет и читает тела в UTF-8, добавляет User-Agent, поддерживает
GET и POST, сообщает о статусах вне диапазона 2xx через `TransportException`, а перед сообщением
о прерывании запроса восстанавливает флаг interrupt потока. `TransportException` находится в
`ru.onlychill.mbondsfx.integrations.transport`. У внутренних адаптеров есть конструкторы с
настраиваемым base URI/endpoint для фикстур; production factory использует официальные HTTPS endpoints.

Сигнатуры внутренних fixture-конструкторов: `new MoexBondAdapter(transport, URI)` принимает base URI
ISS (например, `URI.create("https://iss.moex.com/iss")`), а `new CbrIndexAdapter(transport, URI)`
принимает полный URL DailyInfo SOAP endpoint (например,
`URI.create("https://www.cbr.ru/DailyInfoWebServ/DailyInfo.asmx")`). В тестах URI можно заменить
на fixture URI и передать fake `Transport`; `HttpTransport` не создаёт соединение при создании.

## Сырые данные облигации из MOEX ISS

`MoexBondAdapter` из `:integrations-moex`: `bond(SecurityId)` читает
`https://iss.moex.com/iss/securities/{SECID}.xml?iss.meta=off&iss.only=description` и возвращает
карту исходных атрибутов description и исходные строки купонов, амортизаций и оферт
из `https://iss.moex.com/iss/securities/{SECID}/bondization.xml`. SECID принимается только как
один безопасный path-токен (`A-Z`, `a-z`, цифры, `.`, `_`, `-`); слеши, percent-escape,
разделители query/fragment и другие формы подмены пути отклоняются до отправки запроса.

Адаптер независимо запрашивает `iss.only=coupons`, `iss.only=amortizations` и `iss.only=offers`,
с `limit=20` и начальным offset 0. Для каждого блока offset увеличивается на фактическое число
полученных строк; чтение завершается только после пустой страницы этого блока. Повтор страницы
или превышение заданного предела страниц/строк приводит к ошибке, а не к возврату неполного
расписания. Пустой контейнер description/rows означает неизвестную бумагу (`Optional.empty()`);
ошибочный XML, отсутствующий/дублирующийся блок или некорректное обязательное поле строки — это
ошибки, а не неизвестная бумага.

Строки остаются данными MOEX и не преобразуются в предположительную `BondDefinition`. Имена и
значения description сохраняются буквально, включая незнакомые поля и пустые строки. Пустые
числовые атрибуты отображаются в `Optional.empty()` (не в ноль); отсутствующие даты реестра и
необязательные даты-сентинелы `0000-00-00` также становятся пустыми. Десятичные строки
преобразуются в `BigDecimal` без изменения значения. `data_source` амортизации и тип оферты
сохраняются как в источнике. Неизвестные формулы купона и семантика оферт не нормализуются.

Ссылки на официальные источники, зафиксированные в исследовании адаптеров (проверены 2026-10-20):

- Документация MOEX ISS: <https://iss.moex.com/iss/reference/?lang=en>
- Пример расписания облигации: <https://iss.moex.com/iss/statistics/engines/stock/markets/bonds/bondization/RU000A0JVSY3>

## Фиксинги ключевой ставки ЦБ РФ и RUONIA

`CbrIndexAdapter` из `:integrations-cbr` поддерживает только `RU_KEY_RATE` и `RUONIA`, используя официальный SOAP
endpoint `https://www.cbr.ru/DailyInfoWebServ/DailyInfo.asmx` и методы `KeyRateXML` и `RuoniaXML`.
Каждый вызов `series(index, from, to)` отправляет заданные явно включительные даты в SOAP POST;
текущая дата не используется. Запрос содержит `SOAPAction` с именем метода. SOAP Fault
представляется отдельным `SoapFaultException`; ошибки XML, отсутствующий result-блок и
отсутствующий точный fixing не считаются нулём. Отсутствие точной внешней записи обозначается
`ru.onlychill.mbondsfx.integrations.index.MissingFixingException`; при проходе через
`IndexValueSourceAdapter` это переводится в доменный
`ru.onlychill.mbondsfx.core.bond.calculation.MissingIndexValueException`.

ЦБ публикует ставки в процентах: `14.00` преобразуется в десятичную долю `0.14`; RUONIA `13.8400`
— в `0.138400`. `series` возвращает неизменяемый список в порядке источника. `fixing(index, date)`
запрашивает только явно заданную дату и требует точную запись в ответе. Для выходного или
праздничного дня без записи ЦБ возникает `MissingFixingException`; перенос вперёд не выполняется.
Неподдерживаемые индексы, в том числе CPI, отклоняются через `IllegalArgumentException`.

Документация официальных источников:

- Сервис DailyInfo и методы ЦБ РФ: <https://www.cbr.ru/DailyInfoWebServ/DailyInfo.asmx>
- Документация XML/SOAP ЦБ РФ: <https://cbr.ru/development/SXML/> и
  <https://cbr.ru/development/DWS/>
- Метод ключевой ставки: <https://www.cbr.ru/DailyInfoWebServ/DailyInfo.asmx?op=KeyRateXML>

## Фиксинги от вызывающего кода и ограничения

`ru.onlychill.mbondsfx.integrations.adapter.StaticFixings.of(
ru.onlychill.mbondsfx.integrations.index.IndexFixing...)` и
`StaticFixings.of(Map<IndexId, Map<LocalDate,
BigDecimal>>)` создают неизменяемые таблицы с точным поиском по дате. Переданные значения уже
являются десятичными долями: проценты не конвертируются (для 14% укажите `0.14`, а не `14`). При
отсутствии даты выбрасывается `MissingFixingException`.

`StaticFixings` и `IndexValueSourceAdapter` принадлежат shared module, но их package export
ограничен provider modules; использовать их напрямую из root module нельзя.

Для этой реализации не выбран проверенный автоматический источник фиксаций CPI или официальный
машиночитаемый календарь праздников. Данные CPI и календаря остаются явными входными данными
вызывающего кода; это не утверждение об отсутствии подобных источников вообще. Адаптер не
предполагает текущий CPI, переносы на выходных или праздники: он возвращает только фактические
записи ЦБ.
