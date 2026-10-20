package ru.onlychill.mbondsfx.core.bond;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Data-only redemption terms; no call, put, or conversion execution is provided. */
public record RedemptionOptions(List<OptionExercise> callDates, List<OptionExercise> putDates,
                               Optional<ConvertibleData> convertible) {
    public RedemptionOptions {
        callDates = sortedCopy(callDates, "callDates");
        putDates = sortedCopy(putDates, "putDates");
        Objects.requireNonNull(convertible, "convertible");
    }

    public static RedemptionOptions none() {
        return new RedemptionOptions(List.of(), List.of(), Optional.empty());
    }

    private static List<OptionExercise> sortedCopy(List<OptionExercise> options, String name) {
        Objects.requireNonNull(options, name);
        List<OptionExercise> copy = new ArrayList<>(options);
        copy.forEach(option -> Objects.requireNonNull(option, "option"));
        copy.sort(Comparator.comparing(OptionExercise::date));
        return List.copyOf(copy);
    }
}
