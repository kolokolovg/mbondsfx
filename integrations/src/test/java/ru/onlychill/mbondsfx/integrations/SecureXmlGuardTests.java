package ru.onlychill.mbondsfx.integrations;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import ru.onlychill.mbondsfx.integrations.xml.SecureXml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SecureXmlGuardTests {
    @Test
    void limitsRequirePositiveValues() {
        assertThrows(IllegalArgumentException.class, () -> new SecureXml.Limits(0, 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new SecureXml.Limits(1, 0, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new SecureXml.Limits(1, 1, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new SecureXml.Limits(1, 1, 1, 0));
    }

    @Test
    void parsingEnforcesDepthAttributeCountAndAttributeLengthAtBoundaries() {
        SecureXml.Limits limits = new SecureXml.Limits(2, 2, 3, 10);
        SecureXml.parse("<root><child a='xyz' b='2'/></root>", limits);
        assertThrows(SecureXml.MalformedXmlException.class,
                () -> SecureXml.parse("<root><child><leaf/></child></root>", limits));
        assertThrows(SecureXml.MalformedXmlException.class,
                () -> SecureXml.parse("<root><child a='1' b='2' c='3'/></root>", limits));
        assertThrows(SecureXml.MalformedXmlException.class,
                () -> SecureXml.parse("<root><child a='four'/></root>", limits));
    }

    @Test
    void textLimitCountsCombinedDirectTextAndCdata() {
        SecureXml.Limits exact = new SecureXml.Limits(4, 4, 20, 3);
        SecureXml.parse("<root>a<![CDATA[b]]>c<child>ok</child></root>", exact);
        assertThrows(SecureXml.MalformedXmlException.class,
                () -> SecureXml.parse("<root>a<![CDATA[b]]>cd</root>", exact));
    }

    @Test
    void validateTraversesDeepSuppliedDomIteratively() {
        Document document = SecureXml.parse("<root/>");
        Element current = document.getDocumentElement();
        int childCount = 12_000;
        for (int index = 0; index < childCount; index++) {
            Element child = document.createElement("node");
            current.appendChild(child);
            current = child;
        }

        SecureXml.validate(document.getDocumentElement(),
                new SecureXml.Limits(childCount + 1, 1, 1, 1));
    }

    @Test
    void descendantAndRowBoundsFailInsteadOfTruncatingAndKeepPreorder() {
        Document document = SecureXml.parse("<root><branch><match id='1'/><match id='2'/></branch>"
                + "<match id='3'/><rows><row id='a'/><row id='b'/><row id='c'/></rows></root>");
        Element root = document.getDocumentElement();

        List<Element> matches = SecureXml.descendants(root, "match", 3);
        assertEquals(List.of("1", "2", "3"), matches.stream().map(e -> e.getAttribute("id")).toList());
        assertThrows(IllegalArgumentException.class, () -> SecureXml.descendants(root, "match", 2));

        assertEquals(List.of("a", "b", "c"), SecureXml.rows(root, "sample", 3).stream()
                .map(e -> e.getAttribute("id")).toList());
        assertThrows(IllegalArgumentException.class, () -> SecureXml.rows(root, "sample", 2));
    }

    @Test
    void documentTypesRemainRejected() {
        assertThrows(SecureXml.MalformedXmlException.class,
                () -> SecureXml.parse("<!DOCTYPE root [<!ENTITY x SYSTEM 'file:///etc/passwd'>]>"
                        + "<root>&x;</root>"));
    }
}
