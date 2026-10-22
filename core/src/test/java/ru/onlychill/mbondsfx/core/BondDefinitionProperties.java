package ru.onlychill.mbondsfx.core;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Provide;
import net.jqwik.api.Property;
import org.junit.jupiter.api.function.Executable;
import ru.onlychill.mbondsfx.core.bond.AmortizationEvent;
import ru.onlychill.mbondsfx.core.bond.BondDefinition;
import ru.onlychill.mbondsfx.core.bond.ConvertibleData;
import ru.onlychill.mbondsfx.core.bond.Coupon;
import ru.onlychill.mbondsfx.core.bond.DatedPeriod;
import ru.onlychill.mbondsfx.core.bond.FixedCoupon;
import ru.onlychill.mbondsfx.core.bond.FloatingCoupon;
import ru.onlychill.mbondsfx.core.bond.FloatingRateFormula;
import ru.onlychill.mbondsfx.core.bond.IndexLinkedCoupon;
import ru.onlychill.mbondsfx.core.bond.IndexationRule;
import ru.onlychill.mbondsfx.core.bond.KnownCoupon;
import ru.onlychill.mbondsfx.core.bond.OptionExercise;
import ru.onlychill.mbondsfx.core.bond.Principal;
import ru.onlychill.mbondsfx.core.bond.RedemptionOptions;
import ru.onlychill.mbondsfx.core.money.DayCounts;
import ru.onlychill.mbondsfx.core.money.Money;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static ru.onlychill.mbondsfx.core.BondCalculationSupport.*;

/**
 * Aggregate invariants of {@link BondDefinition} and of the money-bearing terms it owns. A dated bond needs a maturity after
 * its issue date; every coupon period, amortization date and option date lies inside the bond window (perpetual bonds have no
 * upper bound); every money term carries the bond currency; amortization never exceeds the initial face; the term lists are
 * sorted by date, stable for equal dates, and read-only; and a missing component is a {@link NullPointerException}. Boundary
 * dates (the day before issue, issue, the day after, and the days around maturity) are enumerated for every random bond, so a
 * boundary mutant cannot hide behind sampling. Every property runs 250 tries.
 */
final class BondDefinitionProperties {
    private static final Currency USD = Currency.getInstance("USD");
    private static final Currency EUR = Currency.getInstance("EUR");
    private static final Currency JPY = Currency.getInstance("JPY");
    /** Three minor-unit digits, so the currencies cover minor-unit scales of 0, 2 and 3. */
    private static final Currency BHD = Currency.getInstance("BHD");
    private static final List<Currency> MONEY_CURRENCIES = List.of(RUB, USD, EUR, JPY, BHD);
    /** The rejection of each money term outside the bond currency: face, amortization, known coupon amount, option price. */
    private static final List<String> MONEY_TERM_MESSAGES = List.of(
            "initial face currency must match bond currency",
            "Amortization currency must match initial face currency",
            "known coupon amount currency must match bond currency",
            "option price currency must match bond currency");
    private static final AmortizationEvent FIXED_EVENT = new AmortizationEvent(BASE.plusDays(1), money(1L));
    private static final OptionExercise FIXED_EXERCISE = new OptionExercise(BASE.plusDays(1), Optional.empty());

    /** A dated bond needs a maturity strictly after issue; a perpetual bond has no maturity and accepts any issue date. */
    @Property(tries = 250)
    void maturityMustFollowIssueExceptForPerpetualBonds(@ForAll("dates") LocalDate issue, @ForAll("spans") long span) {
        Principal face = principal(1000L, List.of());
        for (long offset : new long[] {-1L, 0L, 1L, span}) {
            LocalDate maturity = issue.plusDays(offset);
            if (offset > 0) {
                assertEquals(Optional.of(maturity), bondIn(RUB, issue, Optional.of(maturity), Optional.empty(), face,
                        RedemptionOptions.none()).maturityDate(), "a maturity after issue is accepted");
            } else {
                IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class,
                        () -> bondIn(RUB, issue, Optional.of(maturity), Optional.empty(), face,
                                RedemptionOptions.none()),
                        "a maturity on or before issue is rejected");
                assertEquals("maturityDate must be after issueDate", rejected.getMessage());
            }
        }
        assertEquals(Optional.empty(), bondIn(RUB, issue, Optional.empty(), Optional.empty(), face,
                RedemptionOptions.none()).maturityDate(), "a perpetual bond has no maturity");
    }

    /**
     * A coupon period must not start before the issue date; on a dated bond it must also end and pay on or before maturity. The
     * rule is the same for every coupon kind, and an accepted coupon keeps exactly the period it was given.
     */
    @Property(tries = 250)
    void couponPeriodsMustLieInsideTheBondWindowForEveryCouponKind(@ForAll("couponKinds") int kind,
            @ForAll("dates") LocalDate issue, @ForAll("spans") long span) {
        LocalDate maturity = issue.plusDays(span);
        for (boolean perpetual : new boolean[] {false, true}) {
            Optional<LocalDate> bondMaturity = perpetual ? Optional.empty() : Optional.of(maturity);
            for (LocalDate start : List.of(issue.minusDays(1), issue, issue.plusDays(1))) {
                for (LocalDate end : List.of(maturity.minusDays(1), maturity, maturity.plusDays(1))) {
                    for (LocalDate payment : List.of(end, end.plusDays(1), maturity, maturity.plusDays(1))) {
                        if (!start.isBefore(end) || payment.isBefore(end)) {
                            continue;
                        }
                        DatedPeriod period = new DatedPeriod(start, end, payment);
                        Coupon coupon = couponOfKind(kind, period);
                        Principal face = principal(1000L, List.of());
                        String expected = windowRejection(issue, bondMaturity, period);
                        if (expected == null) {
                            BondDefinition bond = bondIn(RUB, issue, bondMaturity, Optional.of(coupon), face,
                                    RedemptionOptions.none());
                            assertEquals(List.of(period), bond.coupon().orElseThrow().periods(),
                                    "an accepted coupon keeps exactly its period");
                        } else {
                            IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class,
                                    () -> bondIn(RUB, issue, bondMaturity, Optional.of(coupon), face,
                                            RedemptionOptions.none()),
                                    "a coupon period outside the bond window is rejected");
                            assertEquals(expected, rejected.getMessage(), "the first failing window rule is named");
                        }
                    }
                }
            }
        }
    }

    /**
     * An amortization must fall strictly after issue and, on a dated bond, not after maturity. The bond keeps an accepted
     * amortization with its date.
     */
    @Property(tries = 250)
    void amortizationDatesAreAfterIssueAndNotAfterMaturity(@ForAll("dates") LocalDate issue,
            @ForAll("spans") long span) {
        LocalDate maturity = issue.plusDays(span);
        for (boolean perpetual : new boolean[] {false, true}) {
            Optional<LocalDate> bondMaturity = perpetual ? Optional.empty() : Optional.of(maturity);
            for (LocalDate date : boundaryDates(issue, maturity)) {
                AmortizationEvent amortization = new AmortizationEvent(date, money(10L));
                Principal face = principal(1000L, List.of(amortization));
                String expected = amortizationRejection(issue, bondMaturity, date);
                if (expected == null) {
                    BondDefinition bond = bondIn(RUB, issue, bondMaturity, Optional.empty(), face,
                            RedemptionOptions.none());
                    assertEquals(List.of(amortization), bond.principal().events(),
                            "an accepted amortization keeps its date");
                } else {
                    IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class,
                            () -> bondIn(RUB, issue, bondMaturity, Optional.empty(), face, RedemptionOptions.none()),
                            "an amortization outside the bond window is rejected");
                    assertEquals(expected, rejected.getMessage(), "the failing window rule is named");
                }
            }
        }
    }

    /**
     * The summed amortization may bring the nominal to zero but never below it: equality with the initial face is accepted and
     * anything above is rejected. A boolean flag makes the face equal to the sum on about half of the tries, so the equality
     * boundary is sampled, not only the strict overflow.
     */
    @Property(tries = 250)
    void amortizationTotalMayNotExceedInitialFace(@ForAll("reductionLists") List<Long> reductions,
            @ForAll("faceMinors") long face, @ForAll boolean exact) {
        List<AmortizationEvent> events = new ArrayList<>();
        for (int index = 0; index < reductions.size(); index++) {
            events.add(new AmortizationEvent(BASE.plusDays(index + 1L), money(reductions.get(index))));
        }
        long total = reductions.stream().mapToLong(Long::longValue).sum();
        long initial = exact && total > 0 ? total : face;
        if (total <= initial) {
            assertEquals(events, principal(initial, events).events(), "every amortization within the face is kept");
        } else {
            IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class,
                    () -> principal(initial, events), "amortization above the initial face is rejected");
            assertEquals("Total amortization must not exceed initial face", rejected.getMessage());
        }
    }

    /**
     * Amortizations and call/put exercises are kept sorted by date, in the supplied order for equal dates, and the lists are
     * read-only: changes to the supplied lists after construction do not reach the terms.
     */
    @Property(tries = 250)
    void principalAndRedemptionListsAreSortedByDateAndDetached(@ForAll("shortOffsets") List<Long> eventDays,
            @ForAll("shortOffsets") List<Long> callDays, @ForAll("shortOffsets") List<Long> putDays) {
        List<AmortizationEvent> events = new ArrayList<>();
        for (int index = 0; index < eventDays.size(); index++) {
            events.add(new AmortizationEvent(BASE.plusDays(eventDays.get(index)), money(index + 1L)));
        }
        List<OptionExercise> calls = exercises(callDays);
        List<OptionExercise> puts = exercises(putDays);
        List<AmortizationEvent> eventSource = new ArrayList<>(events);
        List<OptionExercise> callSource = new ArrayList<>(calls);
        List<OptionExercise> putSource = new ArrayList<>(puts);
        Principal face = principal(100_000L, eventSource);
        RedemptionOptions options = new RedemptionOptions(callSource, putSource, Optional.empty());
        List<AmortizationEvent> sortedEvents = sortedByDate(events, AmortizationEvent::amortDate);
        List<OptionExercise> sortedCalls = sortedByDate(calls, OptionExercise::date);
        List<OptionExercise> sortedPuts = sortedByDate(puts, OptionExercise::date);
        assertEquals(sortedEvents, face.events(), "amortizations are sorted by date, stable for equal dates");
        assertEquals(sortedCalls, options.callDates(), "call dates are sorted by date, stable for equal dates");
        assertEquals(sortedPuts, options.putDates(), "put dates are sorted by date, stable for equal dates");

        eventSource.clear();
        callSource.clear();
        putSource.clear();
        assertEquals(sortedEvents, face.events(), "clearing the supplied amortizations changes nothing");
        assertEquals(sortedCalls, options.callDates(), "clearing the supplied calls changes nothing");
        assertEquals(sortedPuts, options.putDates(), "clearing the supplied puts changes nothing");
        assertThrows(UnsupportedOperationException.class, () -> face.events().add(FIXED_EVENT),
                "amortizations are read-only");
        assertThrows(UnsupportedOperationException.class, () -> options.callDates().add(FIXED_EXERCISE),
                "call dates are read-only");
        assertThrows(UnsupportedOperationException.class, () -> options.putDates().add(FIXED_EXERCISE),
                "put dates are read-only");
    }

    /**
     * A call or put date must not precede issue and, on a dated bond, must not follow maturity; a price, if any, must be in the
     * bond currency. Issue and maturity themselves are accepted. The first failing rule decides the message.
     */
    @Property(tries = 250)
    void optionDatesAndPricesFollowTheBondWindowAndCurrency(@ForAll("dates") LocalDate issue,
            @ForAll("spans") long span, @ForAll("pricing") int pricing, @ForAll boolean put) {
        LocalDate maturity = issue.plusDays(span);
        for (boolean perpetual : new boolean[] {false, true}) {
            Optional<LocalDate> bondMaturity = perpetual ? Optional.empty() : Optional.of(maturity);
            for (LocalDate date : boundaryDates(issue, maturity)) {
                OptionExercise exercise = new OptionExercise(date, priceOf(pricing));
                RedemptionOptions options = put
                        ? new RedemptionOptions(List.of(), List.of(exercise), Optional.empty())
                        : new RedemptionOptions(List.of(exercise), List.of(), Optional.empty());
                String expected = optionRejection(issue, bondMaturity, date, pricing);
                if (expected == null) {
                    BondDefinition bond = bondIn(RUB, issue, bondMaturity, Optional.empty(),
                            principal(1000L, List.of()), options);
                    assertEquals(options, bond.options(), "an accepted exercise keeps its terms");
                } else {
                    IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class,
                            () -> bondIn(RUB, issue, bondMaturity, Optional.empty(), principal(1000L, List.of()),
                                    options),
                            "an exercise outside the bond terms is rejected");
                    assertEquals(expected, rejected.getMessage(), "the first failing rule is named");
                }
            }
        }
    }

    /**
     * Every money term of a bond must carry the bond currency: the initial face, an amortization (checked against the face
     * first), a known coupon amount and an option price. A term in the bond currency is accepted; any other currency is rejected
     * with the message of the rule that fails.
     */
    @Property(tries = 250)
    void moneyTermsMustUseTheBondCurrency(@ForAll("moneyCurrencies") Currency bondCurrency) {
        for (int term = 0; term < MONEY_TERM_MESSAGES.size(); term++) {
            int kind = term;
            for (Currency termCurrency : MONEY_CURRENCIES) {
                if (bondCurrency.equals(termCurrency)) {
                    assertEquals(bondCurrency, moneyTermBond(kind, bondCurrency, termCurrency).currency(),
                            "a term in the bond currency is accepted");
                } else {
                    IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class,
                            () -> moneyTermBond(kind, bondCurrency, termCurrency),
                            "a term in another currency is rejected");
                    assertEquals(MONEY_TERM_MESSAGES.get(kind), rejected.getMessage(),
                            "the failing currency rule is named");
                }
            }
        }
    }

    /**
     * A bond currency must have a minor-unit scale: a currency without one is rejected by the bond before any term is checked,
     * and every other available currency carries a bond in that currency.
     */
    @Property(tries = 250)
    void bondCurrencyMustHaveAMinorUnitScale(@ForAll("allCurrencies") Currency currency) {
        Optional<LocalDate> maturity = Optional.of(BASE.plusDays(100));
        if (currency.getDefaultFractionDigits() < 0) {
            IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class,
                    () -> bondIn(currency, BASE, maturity, Optional.empty(), principal(1000L, List.of()),
                            RedemptionOptions.none()),
                    "a currency without a minor-unit scale is rejected");
            assertEquals("Currency without a minor-unit scale is not supported", rejected.getMessage());
        } else {
            Principal face = new Principal(new Money(new BigDecimal("1000"), currency), List.of(), Optional.empty());
            BondDefinition bond = bondIn(currency, BASE, maturity, Optional.empty(), face, RedemptionOptions.none());
            assertEquals(currency, bond.currency(), "a bond keeps its minor-unit currency");
        }
    }

    /** A missing required component of a bond is a NullPointerException, whichever component it is. */
    @Property(tries = 250)
    void missingRequiredBondComponentIsRejectedWithNullPointerException(@ForAll("bondFields") int field) {
        LocalDate issue = BASE;
        Optional<LocalDate> maturity = Optional.of(BASE.plusDays(100));
        Principal face = principal(1000L, List.of());
        List<Executable> attempts = List.of(
                () -> new BondDefinition(null, RUB, issue, maturity, Optional.empty(), face, RedemptionOptions.none()),
                () -> new BondDefinition(SECURITY, null, issue, maturity, Optional.empty(), face,
                        RedemptionOptions.none()),
                () -> new BondDefinition(SECURITY, RUB, null, maturity, Optional.empty(), face,
                        RedemptionOptions.none()),
                () -> new BondDefinition(SECURITY, RUB, issue, null, Optional.empty(), face, RedemptionOptions.none()),
                () -> new BondDefinition(SECURITY, RUB, issue, maturity, null, face, RedemptionOptions.none()),
                () -> new BondDefinition(SECURITY, RUB, issue, maturity, Optional.empty(), null,
                        RedemptionOptions.none()),
                () -> new BondDefinition(SECURITY, RUB, issue, maturity, Optional.empty(), face, null));
        assertThrows(NullPointerException.class, attempts.get(field), "a missing component is a NullPointerException");
    }

    /**
     * The small value objects of a term reject non-positive amounts and missing components: an amortization reduction, the
     * initial face and a conversion ratio must be positive; an exercise price must be non-negative, and zero is valid.
     */
    @Property(tries = 250)
    void termValueObjectsRejectNonPositiveAmountsAndNulls(@ForAll("signedAmounts") long units) {
        Money amount = money(units);
        BigDecimal ratio = BigDecimal.valueOf(units, 2);
        if (units > 0) {
            assertEquals(amount, new AmortizationEvent(BASE, amount).faceReductionPerUnit(),
                    "a positive reduction is kept");
            assertEquals(amount, new Principal(amount, List.of(), Optional.empty()).initialFacePerUnit(),
                    "a positive initial face is kept");
            assertEquals(ratio, new ConvertibleData(SECURITY, ratio).conversionRatio(), "a positive ratio is kept");
        } else {
            assertRejected("faceReductionPerUnit must be positive", () -> new AmortizationEvent(BASE, amount));
            assertRejected("initialFacePerUnit must be positive",
                    () -> new Principal(amount, List.of(), Optional.empty()));
            assertRejected("conversionRatio must be positive", () -> new ConvertibleData(SECURITY, ratio));
        }
        if (units < 0) {
            assertRejected("Exercise price must be non-negative", () -> new OptionExercise(BASE, Optional.of(amount)));
        } else {
            assertEquals(Optional.of(amount), new OptionExercise(BASE, Optional.of(amount)).price(),
                    "a non-negative exercise price is kept");
        }
        assertThrows(NullPointerException.class, () -> new AmortizationEvent(null, amount), "a date is required");
        assertThrows(NullPointerException.class, () -> new AmortizationEvent(BASE, null), "an amount is required");
        assertThrows(NullPointerException.class, () -> new Principal(null, List.of(), Optional.empty()),
                "the initial face is required");
        assertThrows(NullPointerException.class, () -> new OptionExercise(BASE, null), "a price option is required");
        assertThrows(NullPointerException.class, () -> new RedemptionOptions(null, List.of(), Optional.empty()),
                "the call list is required");
        assertThrows(NullPointerException.class, () -> new RedemptionOptions(List.of(), List.of(), null),
                "the convertible option is required");
    }

    /** A bond in {@code currency}; the security identity is the shared calculation fixture. */
    private static BondDefinition bondIn(Currency currency, LocalDate issue, Optional<LocalDate> maturity,
            Optional<Coupon> coupon, Principal principal, RedemptionOptions options) {
        return new BondDefinition(SECURITY, currency, issue, maturity, coupon, principal, options);
    }

    /** One coupon of the given kind over {@code period}; the rate and the index rule are fixed fixtures. */
    private static Coupon couponOfKind(int kind, DatedPeriod period) {
        List<DatedPeriod> periods = List.of(period);
        return switch (kind) {
            case 0 -> new FixedCoupon(new BigDecimal("0.1000"), periods);
            case 1 -> new FloatingCoupon(new FloatingRateFormula(RATE_INDEX, 0L, BigDecimal.ZERO, DayCounts.act365F(),
                    Optional.empty(), Optional.empty()), periods);
            case 2 -> new IndexLinkedCoupon(new BigDecimal("0.1000"),
                    new IndexationRule(LINK_INDEX, new BigDecimal("100"), BASE, 0L),
                    IndexLinkedCoupon.ReferenceDate.PERIOD_START, periods);
            default -> new KnownCoupon(periods, List.of(new KnownCoupon.Amount(period, money(1000L))));
        };
    }

    /** Dates beside both bond boundaries: the day before issue, issue, the day after issue, and the days around maturity. */
    private static List<LocalDate> boundaryDates(LocalDate issue, LocalDate maturity) {
        return List.of(issue.minusDays(1), issue, issue.plusDays(1), maturity.minusDays(1), maturity,
                maturity.plusDays(1));
    }

    /** The first window rule that a coupon period breaks, in the order the bond checks them; null when none is broken. */
    private static String windowRejection(LocalDate issue, Optional<LocalDate> maturity, DatedPeriod period) {
        if (period.accrualStart().isBefore(issue)) {
            return "Coupon accrualStart must not precede issueDate";
        }
        if (maturity.isPresent() && period.accrualEnd().isAfter(maturity.orElseThrow())) {
            return "coupon accrualEnd must not be after maturityDate";
        }
        if (maturity.isPresent() && period.paymentDate().isAfter(maturity.orElseThrow())) {
            return "coupon paymentDate must not be after maturityDate";
        }
        return null;
    }

    private static String amortizationRejection(LocalDate issue, Optional<LocalDate> maturity, LocalDate date) {
        if (!date.isAfter(issue)) {
            return "amortDate must be after issueDate";
        }
        if (maturity.isPresent() && date.isAfter(maturity.orElseThrow())) {
            return "amortDate must not be after maturityDate";
        }
        return null;
    }

    /** The first option rule that the exercise breaks; pricing 2 is the USD price, which no RUB bond accepts. */
    private static String optionRejection(LocalDate issue, Optional<LocalDate> maturity, LocalDate date, int pricing) {
        if (date.isBefore(issue)) {
            return "Option date must not precede issueDate";
        }
        if (maturity.isPresent() && date.isAfter(maturity.orElseThrow())) {
            return "option date must not be after maturityDate";
        }
        if (pricing == 2) {
            return "option price currency must match bond currency";
        }
        return null;
    }

    /** No price, a price in the bond currency, or a price in USD, which a RUB bond rejects. */
    private static Optional<Money> priceOf(int pricing) {
        return switch (pricing) {
            case 0 -> Optional.empty();
            case 1 -> Optional.of(money(500L));
            default -> Optional.of(new Money(new BigDecimal("5"), USD));
        };
    }

    /** Exercises on the given day offsets; every other one carries a price, so equal dates still differ. */
    private static List<OptionExercise> exercises(List<Long> days) {
        List<OptionExercise> exercises = new ArrayList<>();
        for (int index = 0; index < days.size(); index++) {
            Optional<Money> price = index % 2 == 0 ? Optional.empty() : Optional.of(money(index + 1L));
            exercises.add(new OptionExercise(BASE.plusDays(days.get(index)), price));
        }
        return exercises;
    }

    private static <T> List<T> sortedByDate(List<T> items, Function<T, LocalDate> date) {
        return items.stream().sorted(Comparator.comparing(date)).toList();
    }

    /** A bond on [BASE, BASE + 100 days] in {@code bondCurrency} whose one term is in {@code termCurrency}. */
    private static BondDefinition moneyTermBond(int term, Currency bondCurrency, Currency termCurrency) {
        LocalDate issue = BASE;
        Optional<LocalDate> maturity = Optional.of(BASE.plusDays(100));
        DatedPeriod period = new DatedPeriod(BASE, BASE.plusDays(10), BASE.plusDays(10));
        Money termMoney = new Money(new BigDecimal("10"), termCurrency);
        Money faceMoney = new Money(new BigDecimal("1000"), bondCurrency);
        return switch (term) {
            case 0 -> bondIn(bondCurrency, issue, maturity, Optional.empty(),
                    new Principal(termMoney, List.of(), Optional.empty()), RedemptionOptions.none());
            case 1 -> bondIn(bondCurrency, issue, maturity, Optional.empty(),
                    new Principal(faceMoney, List.of(new AmortizationEvent(BASE.plusDays(1), termMoney)),
                            Optional.empty()),
                    RedemptionOptions.none());
            case 2 -> bondIn(bondCurrency, issue, maturity,
                    Optional.of(new KnownCoupon(List.of(period), List.of(new KnownCoupon.Amount(period, termMoney)))),
                    new Principal(faceMoney, List.of(), Optional.empty()), RedemptionOptions.none());
            default -> bondIn(bondCurrency, issue, maturity, Optional.empty(),
                    new Principal(faceMoney, List.of(), Optional.empty()),
                    new RedemptionOptions(List.of(new OptionExercise(BASE.plusDays(1), Optional.of(termMoney))),
                            List.of(), Optional.empty()));
        };
    }

    private static void assertRejected(String message, Executable attempt) {
        IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class, attempt,
                "a term outside its domain is rejected");
        assertEquals(message, rejected.getMessage(), "the failing rule is named");
    }

    @Provide
    Arbitrary<LocalDate> dates() {
        return dateValues();
    }

    /** Spans of three to sixty days: the smallest one keeps every period of the boundary grid non-empty. */
    @Provide
    Arbitrary<Long> spans() {
        return Arbitraries.longs().between(3L, 60L);
    }

    @Provide
    Arbitrary<Integer> couponKinds() {
        return Arbitraries.integers().between(0, 3);
    }

    @Provide
    Arbitrary<Integer> pricing() {
        return Arbitraries.integers().between(0, 2);
    }

    @Provide
    Arbitrary<Currency> moneyCurrencies() {
        return Arbitraries.of(MONEY_CURRENCIES);
    }

    @Provide
    Arbitrary<Currency> allCurrencies() {
        return Arbitraries.of(Currency.getAvailableCurrencies().stream()
                .sorted(Comparator.comparing(Currency::getCurrencyCode)).toList());
    }

    @Provide
    Arbitrary<Long> faceMinors() {
        return Arbitraries.longs().between(1_000L, 100_000L);
    }

    @Provide
    Arbitrary<List<Long>> reductionLists() {
        return Arbitraries.longs().between(1L, 30_000L).list().ofMinSize(0).ofMaxSize(4);
    }

    /** Day offsets from the base date with many repeats, so that equal dates occur and the stable order is observed. */
    @Provide
    Arbitrary<List<Long>> shortOffsets() {
        return Arbitraries.longs().between(0L, 20L).list().ofMinSize(0).ofMaxSize(5);
    }

    @Provide
    Arbitrary<Integer> bondFields() {
        return Arbitraries.integers().between(0, 6);
    }

    /** Amounts around zero, so that a zero amount and a one-minor-unit amount occur on every run. */
    @Provide
    Arbitrary<Long> signedAmounts() {
        return Arbitraries.oneOf(Arbitraries.longs().between(-1_000L, 1_000L), Arbitraries.of(-1L, 0L, 1L));
    }
}
