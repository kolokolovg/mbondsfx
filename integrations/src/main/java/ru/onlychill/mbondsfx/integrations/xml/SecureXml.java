package ru.onlychill.mbondsfx.integrations.xml;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

/** Namespace-insensitive DOM helpers with DTD and external access disabled. */
public final class SecureXml {
    private SecureXml() {
    }

    public static Document parse(String xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
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
            return builder.parse(new InputSource(new StringReader(xml)));
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
        List<Element> rowContainers = directChildren(data, "rows");
        if (rowContainers.size() != 1) {
            throw new MissingBlockException("Missing or duplicate rows container for block: " + blockId);
        }
        return directChildren(rowContainers.getFirst(), "row");
    }

    public static List<Element> descendants(Element parent, String name) {
        List<Element> result = new ArrayList<>();
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element element) {
                if (name.equals(localName(element))) {
                    result.add(element);
                }
                result.addAll(descendants(element, name));
            }
        }
        return result;
    }

    public static String childText(Element parent, String childName) {
        List<Element> children = directChildren(parent, childName);
        if (children.size() != 1) {
            throw new MissingBlockException("Missing or duplicate XML element: " + childName);
        }
        return children.getFirst().getTextContent().trim();
    }

    private static List<Element> directChildren(Element parent, String name) {
        List<Element> result = new ArrayList<>();
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element element && name.equals(localName(element))) {
                result.add(element);
            }
        }
        return result;
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

    static class MalformedXmlException extends IllegalArgumentException {
        MalformedXmlException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static class MissingBlockException extends IllegalStateException {
        public MissingBlockException(String message) {
            super(message);
        }
    }
}
