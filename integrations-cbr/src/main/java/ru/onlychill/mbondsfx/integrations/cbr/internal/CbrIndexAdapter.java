package ru.onlychill.mbondsfx.integrations.cbr.internal;

import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
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

    private final Transport transport;
    private final URI endpoint;

    public CbrIndexAdapter(Transport transport) {
        this(transport, DEFAULT_ENDPOINT);
    }

    /** Custom HTTP(S) endpoint is useful for fixture-backed transports; official default is HTTPS. */
    public CbrIndexAdapter(Transport transport, URI endpoint) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
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
            SoapFaultException fault = soapFaultIfPresent(statusFailure.responseBody(), method);
            if (fault != null) {
                throw fault;
            }
            throw transportFailure;
        }
        Document envelope = SecureXml.parse(response);
        if (!SecureXml.descendants(envelope.getDocumentElement(), "Fault").isEmpty()) {
            throw new SoapFaultException("CBR SOAP fault for " + method + ": "
                    + SecureXml.descendants(envelope.getDocumentElement(), "Fault").getFirst().getTextContent().trim());
        }
        Element result = findResult(envelope, method + "Result");
        Element data = unwrapResult(result);
        List<Element> entries = SecureXml.descendants(data, rowName);
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
        List<Element> results = SecureXml.descendants(envelope.getDocumentElement(), resultName);
        if (results.size() != 1) {
            throw new SecureXml.MissingBlockException("Missing or duplicate CBR SOAP result: " + resultName);
        }
        return results.getFirst();
    }

    private static SoapFaultException soapFaultIfPresent(String response, String method) {
        if (response.isBlank()) {
            return null;
        }
        try {
            Document document = SecureXml.parse(response);
            List<Element> faults = SecureXml.descendants(document.getDocumentElement(), "Fault");
            if (faults.isEmpty()) {
                return null;
            }
            return new SoapFaultException("CBR SOAP fault for " + method + ": "
                    + faults.getFirst().getTextContent().trim());
        } catch (RuntimeException notXmlOrUnsafe) {
            return null;
        }
    }

    private static Element unwrapResult(Element result) {
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
        Document nested = SecureXml.parse(nestedXml);
        return nested.getDocumentElement();
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
