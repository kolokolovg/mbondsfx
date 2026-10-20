package ru.onlychill.mbondsfx.core.bond;

import java.util.Currency;
import ru.onlychill.mbondsfx.core.id.SecurityId;

public interface Security {
    SecurityId id();

    Currency currency();
}
