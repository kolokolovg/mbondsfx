package ru.onlychill.mbondsfx.integrations.cbr.internal;

import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import ru.onlychill.mbondsfx.core.id.IndexId;
import ru.onlychill.mbondsfx.integrations.index.IndexFixing;
import ru.onlychill.mbondsfx.integrations.index.IndexFixingSource;
import ru.onlychill.mbondsfx.integrations.index.MissingFixingException;
import ru.onlychill.mbondsfx.integrations.transport.HttpStatusException;
import ru.onlychill.mbondsfx.integrations.transport.Transport;
import ru.onlychill.mbondsfx.integrations.transport.TransportException;
import ru.onlychill.mbondsfx.integrations.xml.SecureXml;

/** Exact-date CBR key-rate and RUONIA fixings from DailyInfoWebServ SOAP. */
public final class CbrIndexAdapter implements IndexFixingSource {
    public static final IndexId RU_KEY_RATE = new IndexId("RU_KEY_RATE");
    public static final IndexId RUONIA = new IndexId("RUONIA");
    public static final URI DEFAULT_ENDPOINT = URI.create(
            "https://www.cbr.ru/DailyInfoWebServ/DailyInfo.asmx");
    private static final SecureXml.Limits DEFAULT_XML_LIMITS = new SecureXml.Limits(
            CbrProviderFactory.DEFAULT_MAX_XML_DEPTH,
            CbrProviderFactory.DEFAULT_MAX_XML_ATTRIBUTES,
            CbrProviderFactory.DEFAULT_MAX_XML_ATTRIBUTE_LENGTH,
            CbrProviderFactory.DEFAULT_MAX_XML_TEXT_LENGTH);

    private final Transport transport;
    private final URI endpoint;
    private final long maxResponseBytes;
    private final SecureXml.Limits xmlLimits;
    private final SecureXml.Limits soapXmlLimits;
    private final int maxSeriesRows;

    public CbrIndexAdapter(Transport transport) {
        this(transport, DEFAULT_ENDPOINT);
    }

    /** Custom HTTP(S) endpoint is useful for fixture-backed transports; official default is HTTPS. */
    public CbrIndexAdapter(Transport transport, URI endpoint) {
        this(transport, endpoint, CbrProviderFactory.DEFAULT_MAX_RESPONSE_BYTES,
                DEFAULT_XML_LIMITS, CbrProviderFactory.DEFAULT_MAX_SERIES_ROWS);
    }

    /** Creates an adapter with finite response, XML, and fixing-row budgets. */
    public CbrIndexAdapter(
            Transport transport,
            URI endpoint,
            long maxResponseBytes,
            SecureXml.Limits xmlLimits,
            int maxSeriesRows) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        this.xmlLimits = Objects.requireNonNull(xmlLimits, "xmlLimits");
        if (maxResponseBytes <= 0) {
            throw new IllegalArgumentException("maxResponseBytes must be positive");
        }
        if (maxSeriesRows <= 0) {
            throw new IllegalArgumentException("maxSeriesRows must be positive");
        }
        this.maxResponseBytes = maxResponseBytes;
        this.maxSeriesRows = maxSeriesRows;
        int soapTextLimit = maxResponseBytes >= Integer.MAX_VALUE
                ? Integer.MAX_VALUE : (int) maxResponseBytes;
        this.soapXmlLimits = new SecureXml.Limits(xmlLimits.maxDepth(), xmlLimits.maxAttributes(),
                xmlLimits.maxAttributeLength(), soapTextLimit);
        if (!("https".equalsIgnoreCase(endpoint.getScheme()) || "http".equalsIgnoreCase(endpoint.getScheme()))
                || endpoint.getHost() == null || endpoint.getQuery() != null || endpoint.getFragment() != null) {
            throw new IllegalArgumentException("endpoint must be an HTTP(S) URL without query/fragment");
        }
    }

    /** Fetches an immutable source-ordered fixing series for an explicit inclusive date range. */
    public List<IndexFixing> series(IndexId index, LocalDate from, LocalDate to) {
        Objects.requireNonNull(index, "index");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        if (from.isAfter(to)) {
            throw new IllegalArgumentException("from must not be after to");
        }
        String method;
        String action;
        String rowName;
        String dateField;
        String rateField;
        if (RU_KEY_RATE.equals(index)) {
            method = "KeyRateXML";
            action = "http://web.cbr.ru/KeyRateXML";
            rowName = "KR";
            dateField = "DT";
            rateField = "Rate";
        } else if (RUONIA.equals(index)) {
            method = "RuoniaXML";
            action = "http://web.cbr.ru/RuoniaXML";
            rowName = "ro";
            dateField = "D0";
            rateField = "ruo";
        } else {
            throw new IllegalArgumentException("Unsupported CBR index: " + index.value());
        }
        String body = "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                + "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\">"
                + "<soap:Body><" + method + " xmlns=\"http://web.cbr.ru/\"><fromDate>"
                + from + "</fromDate><ToDate>" + to + "</ToDate></" + method
                + "></soap:Body></soap:Envelope>";
        Transport.Request request = new Transport.Request("POST", endpoint,
                java.util.Map.of("Content-Type", "text/xml; charset=utf-8", "SOAPAction", '"' + action + '"'), body);
        String response;
        try {
            response = transport.send(request);
        } catch (TransportException transportFailure) {
            if (!(transportFailure.getCause() instanceof HttpStatusException statusFailure)) {
                throw transportFailure;
            }
            ensureResponseWithinLimit(statusFailure.responseBody());
            SoapFaultException fault = soapFaultIfPresent(statusFailure.responseBody(), method);
            if (fault != null) {
                throw fault;
            }
            throw transportFailure;
        }
        ensureResponseWithinLimit(response);
        Document envelope = SecureXml.parse(response, soapXmlLimits);
        List<Element> faults = SecureXml.descendants(envelope.getDocumentElement(), "Fault", 1);
        if (!faults.isEmpty()) {
            SecureXml.validate(envelope.getDocumentElement(), xmlLimits);
            throw new SoapFaultException("CBR SOAP fault for " + method + ": " + boundedText(faults.getFirst()));
        }
        Element result = findResult(envelope, method + "Result");
        Element data = unwrapResult(result);
        SecureXml.validate(envelope.getDocumentElement(), xmlLimits);
        SecureXml.validate(data, xmlLimits);
        List<Element> entries = SecureXml.descendants(data, rowName, maxSeriesRows);
        List<IndexFixing> resultRows = new ArrayList<>(entries.size());
        for (Element entry : entries) {
            LocalDate date = parseCbrDate(SecureXml.childText(entry, dateField), dateField);
            BigDecimal percent = parseDecimal(SecureXml.childText(entry, rateField), rateField);
            resultRows.add(new IndexFixing(index, date, percent.movePointLeft(2)));
        }
        return List.copyOf(resultRows);
    }

    @Override
    public IndexFixing fixing(IndexId index, LocalDate date) {
        Objects.requireNonNull(date, "date");
        return series(index, date, date).stream()
                .filter(fixing -> fixing.date().equals(date))
                .findFirst()
                .orElseThrow(() -> new MissingFixingException(index, date));
    }

    private static Element findResult(Document envelope, String resultName) {
        List<Element> results = SecureXml.descendants(envelope.getDocumentElement(), resultName, 1);
        if (results.size() != 1) {
            throw new SecureXml.MissingBlockException("Missing or duplicate CBR SOAP result: " + resultName);
        }
        return results.getFirst();
    }

    private SoapFaultException soapFaultIfPresent(String response, String method) {
        if (response.isBlank()) {
            return null;
        }
        Document document;
        try {
            document = SecureXml.parse(response, soapXmlLimits);
        } catch (SecureXml.MalformedXmlException malformedOrOverLimit) {
            return null;
        }
        List<Element> faults = SecureXml.descendants(document.getDocumentElement(), "Fault", 1);
        if (faults.isEmpty()) {
            return null;
        }
        SecureXml.validate(document.getDocumentElement(), xmlLimits);
        return new SoapFaultException("CBR SOAP fault for " + method + ": " + boundedText(faults.getFirst()));
    }

    private Element unwrapResult(Element result) {
        boolean hasElements = false;
        for (org.w3c.dom.Node child = result.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element) {
                hasElements = true;
                break;
            }
        }
        if (hasElements) {
            return result;
        }
        String nestedXml = result.getTextContent().trim();
        if (nestedXml.isEmpty()) {
            throw new SecureXml.MissingBlockException("Empty nested CBR SOAP result: " + result.getTagName());
        }
        Document nested = SecureXml.parse(nestedXml, xmlLimits);
        result.setTextContent("");
        return nested.getDocumentElement();
    }

    private String boundedText(Element element) {
        StringBuilder text = new StringBuilder(Math.min(xmlLimits.maxTextLength(), 128));
        Deque<Node> pending = new ArrayDeque<>();
        pushChildrenInDocumentOrder(element, pending);
        while (!pending.isEmpty()) {
            Node node = pending.pop();
            short type = node.getNodeType();
            if (type == Node.TEXT_NODE || type == Node.CDATA_SECTION_NODE) {
                String value = node.getNodeValue();
                if (value.length() > xmlLimits.maxTextLength() - text.length()) {
                    throw new IllegalArgumentException("CBR SOAP Fault text exceeds configured XML text limit of "
                            + xmlLimits.maxTextLength() + " characters");
                }
                text.append(value);
            } else {
                pushChildrenInDocumentOrder(node, pending);
            }
        }
        return text.toString().trim();
    }

    private static void pushChildrenInDocumentOrder(Node parent, Deque<Node> pending) {
        for (Node child = parent.getLastChild(); child != null; child = child.getPreviousSibling()) {
            pending.push(child);
        }
    }

    private void ensureResponseWithinLimit(String response) {
        Objects.requireNonNull(response, "response");
        if (utf8LengthExceeds(response, maxResponseBytes)) {
            throw new IllegalArgumentException("CBR response exceeds configured byte limit of "
                    + maxResponseBytes + " bytes");
        }
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
                // The JDK UTF-8 encoder replaces an unpaired surrogate with one byte.
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

    private static LocalDate parseCbrDate(String value, String field) {
        try {
            if (value.length() < 10) {
                throw new DateTimeParseException("Expected ISO timestamp", value, 0);
            }
            return LocalDate.parse(value.substring(0, 10));
        } catch (DateTimeParseException failure) {
            throw new IllegalArgumentException("Invalid CBR date in " + field + ": " + value, failure);
        }
    }

    private static BigDecimal parseDecimal(String value, String field) {
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException("Invalid CBR decimal in " + field + ": " + value, failure);
        }
    }

    public static final class SoapFaultException extends IllegalStateException {
        public SoapFaultException(String message) {
            super(message);
        }
    }
}
