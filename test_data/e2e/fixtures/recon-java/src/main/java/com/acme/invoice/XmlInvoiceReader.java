package com.acme.invoice;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * Reads the XML documents the service accepts: supplier invoices in the UBL style format, and the
 * column mapping files that operations upload for the CSV import.
 */
public final class XmlInvoiceReader {

    /** Turns one uploaded invoice document into an {@link Invoice}. */
    public Invoice parseInvoice(InputStream xml) {
        try {
            DocumentBuilder builder = DocumentBuilderFactory.newInstance().newDocumentBuilder();
            Document document = builder.parse(xml);
            document.normalizeDocument();
            Element root = document.getDocumentElement();
            String number = text(root, "ID");
            String buyer = text(root, "AccountingCustomerParty");
            long totalCents = Math.round(Double.parseDouble(text(root, "TaxInclusiveAmount")) * 100);
            String currency = root.getAttribute("currency");
            return new Invoice(number, buyer, totalCents, currency.isEmpty() ? "EUR" : currency);
        } catch (ParserConfigurationException | SAXException | IOException e) {
            throw new IllegalArgumentException("not a valid invoice document", e);
        }
    }

    /**
     * Column mappings are written by hand, so the parser here refuses doctypes and does not
     * resolve external references.
     */
    public Properties loadColumnMapping(InputStream xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            Document document = factory.newDocumentBuilder().parse(xml);
            Properties mapping = new Properties();
            NodeList columns = document.getElementsByTagName("column");
            for (int i = 0; i < columns.getLength(); i++) {
                Element column = (Element) columns.item(i);
                mapping.setProperty(column.getAttribute("header"), column.getTextContent());
            }
            return mapping;
        } catch (ParserConfigurationException | SAXException | IOException e) {
            throw new IllegalArgumentException("not a valid column mapping", e);
        }
    }

    private static String text(Element parent, String tag) {
        NodeList nodes = parent.getElementsByTagNameNS("*", tag);
        return nodes.getLength() == 0 ? "" : nodes.item(0).getTextContent().trim();
    }
}
