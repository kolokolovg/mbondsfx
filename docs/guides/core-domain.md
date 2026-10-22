# Домен облигаций

Агрегат `BondDefinition` и его value objects находятся в `ru.onlychill.mbondsfx.core.bond`;
доменные расчеты — в `ru.onlychill.mbondsfx.core.bond.calculation`. `BondCalculations` — сервис,
а `AccruedInterest` — вычисленный результат, не агрегат и не исходное условие выпуска. Все даты и
денежные величины передаются явно; ставки и расчеты используют `BigDecimal`. `IndexId` находится
в `ru.onlychill.mbondsfx.core.id`; внешний `IndexFixing` DTO не является типом домена.

## Создание модели

Ниже — только иллюстративные параметры, не условия конкретного выпуска.

### Фиксированный купон

```java
Currency rub = Currency.getInstance("RUB");
LocalDate issue = LocalDate.of(2025, 1, 1);
LocalDate maturity = LocalDate.of(2026, 1, 1);
DatedPeriod period = new DatedPeriod(
    issue, LocalDate.of(2025, 7, 1), LocalDate.of(2025, 7, 1));
Coupon coupon = new FixedCoupon(new BigDecimal("0.10"), List.of(period));
Principal principal = new Principal(
    new Money(new BigDecimal("1000"), rub), List.of(), Optional.empty());
BondDefinition bond = new BondDefinition(
    new SecurityId("EXAMPLE"), rub, issue, Optional.of(maturity),
    Optional.of(coupon), principal, RedemptionOptions.none());
ru.onlychill.mbondsfx.core.bond.calculation.port.IndexValueSource indexValues = (index, date) -> {
    throw new ru.onlychill.mbondsfx.core.bond.calculation.MissingIndexValueException(index, date);
    // не вызывается для этой фиксированной облигации
};

Money amount = BondCalculations.couponAmount(
    bond, period, DayCounts.act365F(), indexValues);
```

Период начисления — полуоткрытый `[accrualStart, accrualEnd)`. Для фиксированного купона
сумма рассчитывается по сегментам номинала, разделенным внутренними амортизациями. Событие
на `accrualStart` применяется, событие на `accrualEnd` относится к следующему периоду.
Сегментные вклады сначала суммируются без округления, итог округляется один раз до валютного
масштаба `Money`.

### Плавающий купон

```java
IndexId index = new IndexId("EXAMPLE_RATE");
FloatingRateFormula formula = new FloatingRateFormula(
    index, 0, new BigDecimal("0.01"), DayCounts.act360(),
    Optional.of(BigDecimal.ZERO), Optional.empty());
Coupon coupon = new FloatingCoupon(formula, List.of(period));
```

Это модель single-reset: одно точное наблюдение на `accrualStart + fixingOffsetDays`
используется для всего периода; ставка равна fixing плюс spread с применением floor/cap.
Для полной суммы и accrued плавающего купона используется `DayCount` из
`FloatingRateFormula`; `AccruedInterest.dayCount()` возвращает этот фактически примененный базис.
Для фиксированного и индексированного купона, а также accrued `KnownCoupon` используется явно
переданный аргумент `dayCount`. `IndexValueSource.value(index, date)` вызывается с точными `IndexId`
и датой; он возвращает значение для этой пары или сигнализирует об отсутствии через
`MissingIndexValueException`; ее `index()` и `date()` содержат запрошенную пару. Интерполяции,
forward-fill и поиска «последнего значения» нет.
Внешние fixing DTO/API переводятся в этот доменный порт только явным адаптером на стороне
`:integrations`.
RUONIA averaging и multi-reset не реализованы. Отрицательная итоговая floating-ставка
допускается, если параметры floor/cap ее не ограничивают.

### Индексированный купон

```java
IndexationRule couponIndexation = new IndexationRule(
    new IndexId("EXAMPLE_INDEX"), new BigDecimal("100"), issue, 0);
Coupon coupon = new IndexLinkedCoupon(
    new BigDecimal("0.07"), couponIndexation,
    IndexLinkedCoupon.ReferenceDate.CALCULATION_DATE, List.of(period));
```

Это явно заданная общая формула, а не утверждение о правилах конкретного рынка или ОФЗ-ИН:
сегментированный неиндексированный номинал × годовая ставка × day-count fraction × коэффициент
индексации на выбранную дату. `PERIOD_START` и `PERIOD_END` используют границы периода;
`CALCULATION_DATE` использует дату конца периода для полного купона и дату расчета для accrued.
Для accrued наблюдение после даты расчета запрещено, даже если источник содержит его.

Индексация купона и индексация principal — независимые измерения. `Principal` допускает
амортизации и отдельное отношение `value(index, observationDate(on)) / baseIndexValue`.
`IndexationRule.baseDate` хранит переданную дату, относящуюся к `baseIndexValue`; расчет
не запрашивает fixing на `baseDate` и не выводит из нее значение автоматически. Коэффициент
использует только переданные `baseIndexValue`, lag и точное значение из `IndexValueSource` на
рассчитанную дату.

## Объявленные суммы и ограничения

- `KnownCoupon` хранит полный список периодов, включая периоды с пока неизвестной суммой.
  `couponAmount` для неизвестной суммы выбрасывает `UnsupportedCalculationException`, а не
  возвращает ноль. Accrued для известной суммы использует только явно выбранную линейную
  пропорцию year-fraction; это не универсальная формула НКД для всех выпусков.
- При индексированном principal формульные `FixedCoupon`, `FloatingCoupon` и
  `IndexLinkedCoupon` расчеты купона/accrued не поддерживаются: используйте известные
  объявленные суммы через `KnownCoupon`, а не предполагайте формулу конкретного эмитента.
- Для фиксированных и индексированных купонов годовая ставка должна быть неотрицательной.
  Плавающая ставка может быть отрицательной; при необходимости ограничьте ее floor/cap.
- Валюты без minor-unit scale отклоняются (`getDefaultFractionDigits() < 0`), включая `XXX`,
  `XDR`, `XTS`, `XAU` и другие такие коды. Валюты с нулевым scale, например JPY, поддерживаются;
  конструктор `Money` при этом сохраняет переданную точность.
- Даты выпусков, погашения, купонных периодов, амортизаций и опционов проверяются при
  создании модели. Списки периодов и денежных событий копируются и становятся неизменяемыми.
- Запрос даты расчета раньше выпуска или позже погашения завершается ошибкой. Внутри срока жизни
  облигации дата вне всех купонных периодов возвращает нулевое accrued и `Optional.empty()` периода.
- `30/360` и `30E/360` для положительного календарного интервала могут дать нулевую year-fraction
  (например, 30-е → 31-е число месяца). Такой сегмент купона или НКД дает нулевой вклад, в том числе
  нулевой НКД; ошибки для него нет. Нулевую долю допускают только эти два экземпляра `DayCounts`
  (сравнение по identity). Для остальных базисов, включая `30E+/360` и двойников, нулевая доля по-прежнему
  отклоняется fail-fast, а не превращается в нулевой денежный результат; это относится и к прошедшей части
  `KnownCoupon` после даты начала периода. Полный период `KnownCoupon` тоже строгий.
- НКД на дату начала купонного периода равен нулю для любой конвенции, и `DayCount` для этого не вызывается.
  Плавающий и индексированный купон по-прежнему требуют фиксинг, как и раньше: эта норма их не меняет. Правила базисов:
  [спецификация v1.0.0](../specs/day-count/v1.0.0.md), [v1.0.1](../specs/day-count/v1.0.1.md).
- Опционы call/put и данные конвертации — только данные; исполнение не рассчитывается.
- Нет YTM/PV, прайсинга, налоговой ставки, FX, календарного источника или issuer-specific
  формул. Для индексированного principal и купонов требуются явные issue terms и fixings.

Проверка домена выполняется командой `./gradlew :core:check` (см. также [инструкции `:core`](../../core/AGENTS.md)).
