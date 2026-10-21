package ru.onlychill.mbondsfx.integrations.moex.internal;

import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import ru.onlychill.mbondsfx.core.id.SecurityId;
import ru.onlychill.mbondsfx.integrations.marketdata.model.BondMarketData;
import ru.onlychill.mbondsfx.integrations.marketdata.MarketDataPort;
import ru.onlychill.mbondsfx.integrations.transport.Transport;
import ru.onlychill.mbondsfx.integrations.xml.SecureXml;

/** Raw-data-only adapter for the public MOEX ISS bond endpoints. */
public final class MoexBondAdapter implements MarketDataPort {
    private static final URI DEFAULT_BASE_URI = URI.create("http://iss.moex.com/iss");
    private static final Pattern SECID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
    private static final int PAGE_SIZE = 20;
    private static final int MAX_PAGES_PER_BLOCK = 1_000;
    private static final int MAX_ROWS_PER_BLOCK = 20_000;
    private static final List<String> SCHEDULE_BLOCKS = List.of("coupons", "amortizations", "offers");

    private final Transport transport;
    private final URI baseUri;
    private final long maxResponseBytes;
    private final SecureXml.Limits xmlLimits;
    private final int maxDescriptionRows;
    private final int maxPageRows;

    public MoexBondAdapter(Transport transport) {
        this(transport, DEFAULT_BASE_URI);
    }

    /** Custom base URI is useful for deterministic fixtures; production defaults to the official ISS endpoint. */
    public MoexBondAdapter(Transport transport, URI baseUri) {
        this(transport, baseUri, MoexProviderSettings.DEFAULT_MAX_RESPONSE_BYTES,
                defaultXmlLimits(), MoexProviderSettings.DEFAULT_MAX_DESCRIPTION_ROWS,
                MoexProviderSettings.DEFAULT_MAX_PAGE_ROWS);
    }

    /** Explicit response and XML budgets; the legacy constructors use finite MOEX defaults. */
    public MoexBondAdapter(
            Transport transport,
            URI baseUri,
            long maxResponseBytes,
            SecureXml.Limits xmlLimits,
            int maxDescriptionRows,
            int maxPageRows) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.baseUri = validateBaseUri(baseUri);
        if (maxResponseBytes <= 0 || maxDescriptionRows <= 0 || maxPageRows <= 0) {
            throw new IllegalArgumentException("MOEX response limits must be positive");
        }
        this.maxResponseBytes = maxResponseBytes;
        this.xmlLimits = Objects.requireNonNull(xmlLimits, "xmlLimits");
        this.maxDescriptionRows = maxDescriptionRows;
        this.maxPageRows = maxPageRows;
    }

    @Override
    public Optional<BondMarketData> bond(SecurityId moexSecid) {
        Objects.requireNonNull(moexSecid, "moexSecid");
        String secid = validateSecid(moexSecid.value());
        String descriptionXml = response(transport.send(Transport.Request.get(uri(
                "/securities/" + secid + ".xml?iss.meta=off&iss.only=description"))));
        Element descriptionBlock = SecureXml.block(SecureXml.parse(descriptionXml, xmlLimits), "description");
        List<Element> descriptionRows = SecureXml.rows(descriptionBlock, "description", maxDescriptionRows);
        if (descriptionRows.isEmpty()) {
            return Optional.empty();
        }

        Map<String, String> description = new LinkedHashMap<>();
        for (Element row : descriptionRows) {
            String name = requiredAttribute(row, "name");
            if (!row.hasAttribute("value")) {
                throw new IllegalArgumentException("MOEX description row " + name + " has no value attribute");
            }
            description.put(name, row.getAttribute("value"));
        }

        List<BondMarketData.CouponRow> coupons = new ArrayList<>();
        List<BondMarketData.AmortizationRow> amortizations = new ArrayList<>();
        List<BondMarketData.OfferRow> offers = new ArrayList<>();
        for (String block : SCHEDULE_BLOCKS) {
            for (Element row : fetchBlockRows(secid, block)) {
                switch (block) {
                    case "coupons" -> coupons.add(parseCoupon(row));
                    case "amortizations" -> amortizations.add(parseAmortization(row));
                    case "offers" -> offers.add(parseOffer(row));
                    default -> throw new IllegalStateException("Unexpected block " + block);
                }
            }
        }
        return Optional.of(new BondMarketData(description, coupons, amortizations, offers));
    }

    private List<Element> fetchBlockRows(String secid, String block) {
        List<Element> allRows = new ArrayList<>();
        Set<List<Map<String, String>>> seenPages = new LinkedHashSet<>();
        int offset = 0;
        int pages = 0;
        while (true) {
            if (pages >= MAX_PAGES_PER_BLOCK) {
                throw new IllegalStateException("MOEX " + block + " exceeded maximum page count");
            }
            URI pageUri = uri("/securities/" + secid + "/bondization.xml?iss.meta=off&iss.only="
                    + block + "&limit=" + PAGE_SIZE + "&start=" + offset);
            String xml = response(transport.send(Transport.Request.get(pageUri)));
            Document document = SecureXml.parse(xml, xmlLimits);
            List<Element> pageRows = SecureXml.rows(SecureXml.block(document, block), block, maxPageRows);
            pages++;
            if (pageRows.isEmpty()) {
                return allRows;
            }
            List<Map<String, String>> pageSignature = pageRows.stream()
                    .map(MoexBondAdapter::attributes)
                    .toList();
            if (!seenPages.add(pageSignature)) {
                throw new IllegalStateException("MOEX repeated " + block + " page at offset " + offset);
            }
            if (pageRows.size() > MAX_ROWS_PER_BLOCK - allRows.size()) {
                throw new IllegalStateException("MOEX " + block + " exceeded maximum row count");
            }
            allRows.addAll(pageRows);
            try {
                offset = Math.addExact(offset, pageRows.size());
            } catch (ArithmeticException overflow) {
                throw new IllegalStateException("MOEX " + block + " page offset overflow", overflow);
            }
        }
    }

    private static Map<String, String> attributes(Element row) {
        Map<String, String> result = new java.util.TreeMap<>();
        var attrs = row.getAttributes();
        for (int index = 0; index < attrs.getLength(); index++) {
            var attr = attrs.item(index);
            result.put(attr.getNodeName(), attr.getNodeValue());
        }
        return Map.copyOf(result);
    }

    private String response(String xml) {
        Objects.requireNonNull(xml, "MOEX transport response");
        if (utf8LengthExceeds(xml, maxResponseBytes)) {
            throw new IllegalArgumentException("MOEX response exceeded maximum byte count of " + maxResponseBytes);
        }
        return xml;
    }

    private static boolean utf8LengthExceeds(String value, long limit) {
        long bytes = 0;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character <= 0x7f) {
                bytes++;
            } else if (character <= 0x7ff) {
                bytes += 2;
            } else if (Character.isHighSurrogate(character) && index + 1 < value.length()
                    && Character.isLowSurrogate(value.charAt(index + 1))) {
                bytes += 4;
                index++;
            } else if (Character.isSurrogate(character)) {
                // The JDK UTF-8 encoder's replacement for an unpaired surrogate is one byte.
                bytes++;
            } else {
                bytes += 3;
            }
            if (bytes > limit) {
                return true;
            }
        }
        return false;
    }

    private static SecureXml.Limits defaultXmlLimits() {
        return new SecureXml.Limits(MoexProviderSettings.DEFAULT_MAX_XML_DEPTH,
                MoexProviderSettings.DEFAULT_MAX_XML_ATTRIBUTES,
                MoexProviderSettings.DEFAULT_MAX_XML_ATTRIBUTE_LENGTH,
                MoexProviderSettings.DEFAULT_MAX_XML_TEXT_LENGTH);
    }

    private static BondMarketData.CouponRow parseCoupon(Element row) {
        return new BondMarketData.CouponRow(
                requiredDate(row, "coupondate"),
                optionalDate(row, "startdate"),
                optionalDate(row, "recorddate"),
                requiredDecimal(row, "facevalue"),
                requiredAttribute(row, "faceunit"),
                optionalDecimal(row, "value"),
                optionalDecimal(row, "valueprc"),
                optionalDecimal(row, "value_rub"));
    }

    private static BondMarketData.AmortizationRow parseAmortization(Element row) {
        return new BondMarketData.AmortizationRow(
                requiredDate(row, "amortdate"),
                requiredDecimal(row, "facevalue"),
                requiredDecimal(row, "initialfacevalue"),
                requiredAttribute(row, "faceunit"),
                optionalDecimal(row, "valueprc"),
                optionalDecimal(row, "value"),
                optionalDecimal(row, "value_rub"),
                row.hasAttribute("data_source") ? row.getAttribute("data_source") : "");
    }

    private static BondMarketData.OfferRow parseOffer(Element row) {
        return new BondMarketData.OfferRow(
                optionalDate(row, "offerdate"),
                optionalDate(row, "offerdatestart"),
                optionalDate(row, "offerdateend"),
                optionalDecimal(row, "price"),
                optionalDecimal(row, "value"),
                optionalString(row, "agent"),
                optionalString(row, "offertype"));
    }

    private static String validateSecid(String secid) {
        if (!SECID.matcher(secid).matches()) {
            throw new IllegalArgumentException("Invalid MOEX SECID path value");
        }
        return secid;
    }

    private URI uri(String endpoint) {
        return URI.create(baseUri.toString().replaceAll("/+$", "") + endpoint);
    }

    private static URI validateBaseUri(URI uri) {
        Objects.requireNonNull(uri, "baseUri");
        String scheme = uri.getScheme();
        if (!("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))
                || uri.getHost() == null || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("baseUri must be an HTTP(S) origin or path without query/fragment");
        }
        return uri;
    }

    private static String requiredAttribute(Element row, String name) {
        if (!row.hasAttribute(name) || row.getAttribute(name).isBlank()) {
            throw new IllegalArgumentException("MOEX row is missing required " + name + " attribute");
        }
        return row.getAttribute(name);
    }

    private static BigDecimal requiredDecimal(Element row, String name) {
        return decimal(requiredAttribute(row, name), name);
    }

    private static Optional<BigDecimal> optionalDecimal(Element row, String name) {
        if (!row.hasAttribute(name) || row.getAttribute(name).isBlank()) {
            return Optional.empty();
        }
        return Optional.of(decimal(row.getAttribute(name), name));
    }

    private static BigDecimal decimal(String value, String field) {
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException("Invalid MOEX decimal in " + field + ": " + value, failure);
        }
    }

    private static LocalDate requiredDate(Element row, String name) {
        String value = requiredAttribute(row, name);
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException failure) {
            throw new IllegalArgumentException("Invalid required MOEX date in " + name + ": " + value, failure);
        }
    }

    private static Optional<LocalDate> optionalDate(Element row, String name) {
        if (!row.hasAttribute(name)) {
            return Optional.empty();
        }
        String value = row.getAttribute(name).trim();
        if (value.isEmpty() || "0000-00-00".equals(value)) {
            return Optional.empty();
        }
        try {
            return Optional.of(LocalDate.parse(value));
        } catch (DateTimeParseException failure) {
            throw new IllegalArgumentException("Invalid optional MOEX date in " + name + ": " + value, failure);
        }
    }

    private static Optional<String> optionalString(Element row, String name) {
        if (!row.hasAttribute(name) || row.getAttribute(name).isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(row.getAttribute(name));
    }
}
