package ru.onlychill.mbondsfx.integrations.xml;

import java.io.StringReader;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

/** Namespace-insensitive DOM helpers with DTD and external access disabled. */
public final class SecureXml {
    private static final Limits DEFAULT_LIMITS = new Limits(32, 32, 8192, 2_097_152);
    private static final String MAX_ELEMENT_DEPTH = "jdk.xml.maxElementDepth";
    private static final String ELEMENT_ATTRIBUTE_LIMIT = "jdk.xml.elementAttributeLimit";

    private SecureXml() {
    }

    public static Document parse(String xml) {
        return parse(xml, DEFAULT_LIMITS);
    }

    public static Document parse(String xml, Limits limits) {
        Objects.requireNonNull(limits, "limits");
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newDefaultInstance();
            factory.setNamespaceAware(true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setAttribute(MAX_ELEMENT_DEPTH, Integer.toString(limits.maxDepth()));
            factory.setAttribute(ELEMENT_ATTRIBUTE_LIMIT, Integer.toString(limits.maxAttributes()));
            var builder = factory.newDocumentBuilder();
            builder.setEntityResolver((publicId, systemId) -> new InputSource(new StringReader("")));
            builder.setErrorHandler(new DefaultHandler() {
                @Override
                public void warning(org.xml.sax.SAXParseException exception) throws SAXException {
                    throw exception;
                }

                @Override
                public void error(org.xml.sax.SAXParseException exception) throws SAXException {
                    throw exception;
                }

                @Override
                public void fatalError(org.xml.sax.SAXParseException exception) throws SAXException {
                    throw exception;
                }
            });
            Document document = builder.parse(new InputSource(new StringReader(xml)));
            validate(document.getDocumentElement(), limits);
            return document;
        } catch (ParserConfigurationException | SAXException | java.io.IOException | RuntimeException failure) {
            throw new MalformedXmlException("Malformed or unsafe XML document", failure);
        }
    }

    public static Element block(Document document, String blockId) {
        List<Element> matches = descendants(document.getDocumentElement(), "data").stream()
                .filter(element -> blockId.equals(element.getAttribute("id")))
                .toList();
        if (matches.isEmpty()) {
            throw new MissingBlockException("Missing XML data block: " + blockId);
        }
        if (matches.size() != 1) {
            throw new MissingBlockException("Duplicate XML data block: " + blockId);
        }
        return matches.getFirst();
    }

    public static List<Element> rows(Element data, String blockId) {
        return rows(data, blockId, Integer.MAX_VALUE);
    }

    public static List<Element> rows(Element data, String blockId, int maxRows) {
        requireNonNegativeLimit(maxRows, "maxRows");
        List<Element> rowContainers = directChildren(data, "rows");
        if (rowContainers.size() != 1) {
            throw new MissingBlockException("Missing or duplicate rows container for block: " + blockId);
        }
        return directChildren(rowContainers.getFirst(), "row", maxRows);
    }

    public static List<Element> descendants(Element parent, String name) {
        return descendants(parent, name, Integer.MAX_VALUE);
    }

    public static List<Element> descendants(Element parent, String name, int maxMatches) {
        Objects.requireNonNull(parent, "parent");
        Objects.requireNonNull(name, "name");
        requireNonNegativeLimit(maxMatches, "maxMatches");
        List<Element> result = new ArrayList<>();
        Deque<Element> pending = new ArrayDeque<>();
        pushChildrenInReverse(parent, pending);
        while (!pending.isEmpty()) {
            Element element = pending.pop();
            if (name.equals(localName(element))) {
                if (result.size() >= maxMatches) {
                    throw new IllegalArgumentException("XML descendant count exceeds maxMatches: " + maxMatches);
                }
                result.add(element);
            }
            pushChildrenInReverse(element, pending);
        }
        return result;
    }

    /** Validates an already-built DOM iteratively against the supplied per-element limits. */
    public static void validate(Element root, Limits limits) {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(limits, "limits");
        Deque<ElementDepth> pending = new ArrayDeque<>();
        pending.push(new ElementDepth(root, 1));
        while (!pending.isEmpty()) {
            ElementDepth current = pending.pop();
            Element element = current.element();
            if (current.depth() > limits.maxDepth()) {
                throw new IllegalArgumentException("XML element depth exceeds maxDepth: " + limits.maxDepth());
            }

            NamedNodeMap attributes = element.getAttributes();
            if (attributes.getLength() > limits.maxAttributes()) {
                throw new IllegalArgumentException("XML attribute count exceeds maxAttributes: "
                        + limits.maxAttributes());
            }
            for (int index = 0; index < attributes.getLength(); index++) {
                Node attribute = attributes.item(index);
                if (attribute.getNodeValue().length() > limits.maxAttributeLength()) {
                    throw new IllegalArgumentException("XML attribute value exceeds maxAttributeLength: "
                            + limits.maxAttributeLength());
                }
            }

            long directTextLength = 0;
            for (Node child = element.getFirstChild(); child != null; child = child.getNextSibling()) {
                short type = child.getNodeType();
                if (type == Node.TEXT_NODE || type == Node.CDATA_SECTION_NODE) {
                    directTextLength += child.getNodeValue().length();
                    if (directTextLength > limits.maxTextLength()) {
                        throw new IllegalArgumentException("XML direct text exceeds maxTextLength: "
                                + limits.maxTextLength());
                    }
                } else if (child instanceof Element childElement) {
                    pending.push(new ElementDepth(childElement, current.depth() + 1));
                }
            }
        }
    }

    public static String childText(Element parent, String childName) {
        List<Element> children = directChildren(parent, childName);
        if (children.size() != 1) {
            throw new MissingBlockException("Missing or duplicate XML element: " + childName);
        }
        return children.getFirst().getTextContent().trim();
    }

    private static List<Element> directChildren(Element parent, String name) {
        return directChildren(parent, name, Integer.MAX_VALUE);
    }

    private static List<Element> directChildren(Element parent, String name, int maxMatches) {
        List<Element> result = new ArrayList<>();
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element element && name.equals(localName(element))) {
                if (result.size() >= maxMatches) {
                    throw new IllegalArgumentException("XML direct child count exceeds limit: " + maxMatches);
                }
                result.add(element);
            }
        }
        return result;
    }

    private static void pushChildrenInReverse(Element parent, Deque<Element> pending) {
        for (Node child = parent.getLastChild(); child != null; child = child.getPreviousSibling()) {
            if (child instanceof Element element) {
                pending.push(element);
            }
        }
    }

    private static void requireNonNegativeLimit(int limit, String name) {
        if (limit < 0) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
    }

    private static String localName(Element element) {
        String local = element.getLocalName();
        if (local != null) {
            return local;
        }
        String name = element.getTagName();
        int colon = name.indexOf(':');
        return colon < 0 ? name : name.substring(colon + 1);
    }

    public record Limits(int maxDepth, int maxAttributes, int maxAttributeLength, int maxTextLength) {
        public Limits {
            if (maxDepth <= 0 || maxAttributes <= 0 || maxAttributeLength <= 0 || maxTextLength <= 0) {
                throw new IllegalArgumentException("XML limits must be positive");
            }
        }
    }

    private record ElementDepth(Element element, int depth) {
    }

    public static class MalformedXmlException extends IllegalArgumentException {
        public MalformedXmlException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static class MissingBlockException extends IllegalStateException {
        public MissingBlockException(String message) {
            super(message);
        }
    }
}
