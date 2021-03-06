/*
 * JBoss, Home of Professional Open Source
 * Copyright 2013 Red Hat Inc. and/or its affiliates and other contributors
 * as indicated by the @authors tag. All rights reserved.
 */

package org.searchisko.mbox.parser;

import com.ibm.icu.text.CharsetDetector;
import com.ibm.icu.text.CharsetMatch;
import com.sun.xml.messaging.saaj.packaging.mime.MessagingException;
import com.sun.xml.messaging.saaj.packaging.mime.internet.MimeUtility;
import org.apache.commons.io.IOUtils;
import org.apache.james.mime4j.dom.*;
import org.apache.james.mime4j.message.BodyPart;
import org.apache.tika.Tika;
import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.searchisko.mbox.dto.MailAttachment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Represents the content of parsed mail body (no headers).
 *
 * @author Lukáš Vlček (lvlcek@redhat.com)
 */
public class MessageBodyParser {

    private static Logger log = LoggerFactory.getLogger(MessageBodyParser.class);

    /**
     * Supported message body subtypes.
     */
    public enum SupportedMultiPartType {
        ALTERNATIVE("alternative"), MIXED("mixed"), RELATED("related"), SIGNED("signed"),
        /*,TODO: APPLEDOUBLE("appledouble")*/
        UNKNOWN("");

        private final String value;

        private SupportedMultiPartType(String value) {
            this.value = value;
        }

        @Override
        public String toString() {
            return this.value;
        }

        public static SupportedMultiPartType getValue(String value) {
            try {
                return valueOf(value.replaceAll("-", "_").toUpperCase());
            } catch (Exception e) {
                return UNKNOWN;
            }
        }
    }


    /**
     * Represents parsed message body content.
     */
    public static class MailBodyContent {

        private String messageId;
        private String firstTextContent;
        private String firstTextContentWithoutQuotes;
        private String firstHtmlContent;
        private List<String> textMessages = new ArrayList<>();
        private List<String> htmlMessages = new ArrayList<>();
        private List<MailAttachment> attachments = new ArrayList<>();

        public void setMessageId(String id) { this.messageId = id; }
        public String getMessageId() { return this.messageId; }

        public void setFirstTextContent(String content) { this.firstTextContent = content; }
        public String getFirstTextContent() { return this.firstTextContent; }

        public void setFirstTextContentWithoutQuotes(String content) { this.firstTextContentWithoutQuotes = content; }
        public String getFirstTextContentWithoutQuotes() { return this.firstTextContentWithoutQuotes; }

        public void setFirstHtmlContent(String content) { this.firstHtmlContent = content; }
        public String getFirstHtmlContent() { return this.firstHtmlContent; }

        public void setTextMessages(List<String> textMessages) { this.textMessages = textMessages; }
        public List<String> getTextMessages() { return this.textMessages; }

        public void setHtmlMessages(List<String> htmlMessages) { this.htmlMessages = htmlMessages; }
        public List<String> getHtmlMessages() { return this.htmlMessages; }

        public List<MailAttachment> getAttachments() { return this.attachments; }
    }

    private static Tika tika;

    /**
     * Lazy initialize Tika instance.
     * @return Tika instance
     */
    public static Tika getTika() {
        Tika t = tika;
        if (t == null) {
            synchronized (MessageParser.class) {
                t = tika;
                if (t == null) {
                    tika = new Tika();
                    t = tika;
                }
            }
        }
        return t;
    }

    /**
     *
     * @param message
     * @return
     */
    public static MailBodyContent parse(Entity message) throws MessageParseException, IOException {

        MailBodyContent content = new MailBodyContent();
        return parse(content, message);
    }

    private static MailBodyContent parse(MailBodyContent content, Entity message) throws MessageParseException, IOException {

        Body body = message.getBody();
        String mimeType = message.getMimeType().toLowerCase();
        String contentTransferEncoding = message.getContentTransferEncoding();
        String charset = message.getCharset();
        String filename = message.getFilename();

        if ("x-gbk".equalsIgnoreCase(charset)) {
            // hardcoded fix for java.io.UnsupportedEncodingException: x-gbk
            log.warn("Unsupported encoding found: 'x-gbk', using 'gbk' instead.");
            charset = "gbk";
        }

        if (log.isTraceEnabled()) {
            log.trace("parsing Entity, mimeType: '{}', filename: '{}'", new Object[]{mimeType, filename});
            log.trace("contentTransferEncoding: '{}'", contentTransferEncoding);
            log.trace("charset: '{}'", charset);
        }

        if (body instanceof Multipart) {
            parseMultipartBody(content, (Multipart)body);
        } else
        if (body instanceof TextBody) {
            parseTextBody(content, (TextBody)body, mimeType, contentTransferEncoding, charset, filename);
        } else
        if (body instanceof BinaryBody) {
            parseBinaryBody(content, (BinaryBody)body, mimeType, contentTransferEncoding, charset, filename);
        } else
        if (body instanceof Message) {
            parseMessage(content, (Message)body);
        } else {
            throw new MessageParseException("Message body of type [" + body.getClass().getSimpleName() + "] is not supported.");
        }

        return content;
    }

    private static MailBodyContent parseMultipartBody(MailBodyContent content, Multipart body) throws MessageParseException, IOException {

        String subType = body.getSubType().toLowerCase();
        switch(SupportedMultiPartType.getValue(subType)) {
            case UNKNOWN:
                throw new MessageParseException(subType + " is unsupported body multipart subtype.");
            case ALTERNATIVE:
                BodyPart thePart = null;
                for (Entity part : body.getBodyParts()) {
                    if (part.getMimeType().toLowerCase().equals("text/plain")) {
                        thePart = (BodyPart)part;
                    }
                }
                if (thePart == null) {
                    for (Entity part : body.getBodyParts()) {
                        if (part.getMimeType().toLowerCase().equals("text/html")) {
                            thePart = (BodyPart)part;
                        }
                    }
                }
                if (thePart != null)
                    return parseTextBody(content, (TextBody) thePart.getBody(), thePart.getMimeType(), thePart.getContentTransferEncoding(), thePart.getCharset(), thePart.getFilename());
                else {
                    for (Entity part : body.getBodyParts()) {
                        if (part.getBody() instanceof Entity) {
                            parse(content, (Entity)part.getBody());
                        } else
                        if (part.getBody() instanceof Multipart) {
                            parseMultipartBody(content, (Multipart)part.getBody());
                        } else {
                            log.warn("Body of type [{}] not supported! Ignoring.", part.getBody().getClass().getCanonicalName());
                        }
                    }
                }
                break;
            default:
                for (Entity part : body.getBodyParts()) {
                    parse(content, part);
                }
                break;
        }

        return content;
    }

    private static MailBodyContent parseMessage(MailBodyContent content, Message message) throws MessageParseException, IOException {

        String mimeType = message.getMimeType().toLowerCase();
        String contentTransferEncoding = message.getContentTransferEncoding();
        String charset = message.getCharset();
        String filename = message.getFilename();

        Body body = message.getBody();
        if (body instanceof Multipart) {
            parseMultipartBody(content, (Multipart)body);
        } else
        if (body instanceof TextBody) {
            parseTextBody(content, (TextBody)body, mimeType, contentTransferEncoding, charset, filename);
        } else
        if (body instanceof BinaryBody) {
            parseBinaryBody(content, (BinaryBody)body, mimeType, contentTransferEncoding, charset, filename);
        } else
        if (body instanceof Message) {
            parseMessage(content, (Message)body);
        } else {
            throw new MessageParseException("Body of type [" + body.getClass().getSimpleName() + "] is not supported.");
        }
        return content;
    }

    private static MailBodyContent parseTextBody(MailBodyContent bodyContent, TextBody body, String mimeType, String contentTransferEncoding, String charset, String filename) throws IOException {

        if (log.isTraceEnabled()) {
            log.trace("parsing text body, mimeType: '{}', contentTransferEncoding: '{}', charset: '{}', filename: '{}'",
                    new Object[]{mimeType, contentTransferEncoding, charset, filename});
        }

        if (filename != null) {
            addAttachment(bodyContent, body, mimeType, filename);
        } else {

            String content = null;
            InputStream output = null;

            if (contentTransferEncoding != null && contentTransferEncoding.length() > 0) {
                if (log.isTraceEnabled()) {
                    log.trace("decoding: '{}'", contentTransferEncoding);
                    log.trace("charset: '{}'", charset);
                }
                try {
                    // com.sun.xml.messaging.saaj.packaging.mime.util.BASE64DecoderStream.decode() seems to be buggy
                    if ("base64".equalsIgnoreCase(contentTransferEncoding)) {
                        output = body.getInputStream();
                    } else {
                        output = MimeUtility.decode(body.getInputStream(), contentTransferEncoding.toLowerCase());
                    }

                    // check for 'ISO-8859'* and aliases
                    if (charset.toUpperCase().startsWith("ISO-8859") || charset.toUpperCase().startsWith("ISO8859")) {
                        CharsetMatch detectedCharset = detectCharset(output);
                        if (detectedCharset != null) {
                            int conf = detectedCharset.getConfidence();
                            if (conf >= 80) {
                                log.trace("Heuristics: overriding charset from '{}' to '{}' with confidence: {}",
                                        new Object[]{detectedCharset.getName(), charset, conf});
                                charset = detectedCharset.getName();
                            }
                        }
                    }

                    StringWriter writer = new StringWriter();
                    IOUtils.copy(output, writer, charset);
                    content = writer.toString();
                } catch (MessagingException e) {
                    log.trace("Error decoding transfer coding.", e);
                    content = getTextBodyContent(body.getReader()).replaceAll("=\n","");
                }
            } else {
                content = getTextBodyContent(body.getReader()).replaceAll("=\n", "");
            }

            if (mimeType.equals("text/plain")) {
                content = content
//                        .replaceAll(">","&gt;")
//                        .replaceAll("<", "&lt;")
                        .replaceAll("^>From","From");
                if (bodyContent.getFirstTextContent() == null && bodyContent.getFirstHtmlContent() == null) {
                    bodyContent.setFirstTextContentWithoutQuotes(filterOutQuotedContent(content));
//                    if (bodyContent.getFirstTextContentWithoutQuotes().length() > 0) {
//                        bodyContent.setFirstTextContentWithoutQuotes(bodyContent.getFirstTextContentWithoutQuotes().replaceAll(">","&gt;"));
//                    }
                    bodyContent.setFirstTextContent(content/*.replaceAll(">","&gt;")*/);

                } else {
                    bodyContent.getTextMessages().add(content/*.replaceAll(">","&gt;")*/);
                }
            } else if (mimeType.equals("text/html")) {
                // TODO clean possible html tags?
                if (bodyContent.getFirstTextContent() == null && bodyContent.getFirstHtmlContent() == null) {
                    bodyContent.setFirstHtmlContent(content);
                } else {
                    bodyContent.getHtmlMessages().add(content);
                }
            } else {
                // TODO just in case we are missing something (?)
                // text/richtext, text/xml, text/x-vhdl, text/x-vcard, text/x-patch, text/x-log, text/css, text/java, text/rtf, text/x-diff, text/x-java
                bodyContent.getTextMessages().add(content);
            }

        }
        return bodyContent;
    }

    private static CharsetMatch detectCharset(InputStream inputStream) throws IOException {
        CharsetDetector cd = new CharsetDetector();
        // CharDetector requires support of mark/reset
        inputStream = inputStream.markSupported() ? inputStream : new BufferedInputStream(inputStream);
        cd.setText(inputStream);
        cd.enableInputFilter(true);
        return cd.detect();
    }

    private static MailBodyContent parseBinaryBody(MailBodyContent content, BinaryBody body, String mimeType, String contentTransferEncoding, String charset, String filename) throws IOException {
        log.trace("parsing binary body, mimeType: '{}', contentTransferEncoding: '{}', charset: '{}', filename: '{}'", new Object[]{mimeType, contentTransferEncoding, charset, filename});
        if (mimeType != null &&
                !mimeType.equals("application/pgp-signature") &&
                !mimeType.equals("application/ms-tnef") &&
                !mimeType.startsWith("image/")) {
//            (!mimeType.startsWith("image/") || mimeType.equalsIgnoreCase("image/svg+xml"))) {
            if (filename != null) {
                addAttachment(content, body, mimeType, filename);
//            } else
//            if (mimeType.equalsIgnoreCase("application/pdf") /*|| mimeType.equalsIgnoreCase("image/svg+xml")*/) {
                // fix for malformed filename > see "filename*0=" in msgs
                // image/svg+xml commented out because binary body content needs to be first decoded using transfer encoding.
//                addAttachment(body, mimeType, null);
//                log.info("adding binary body, mimeType: {}, contentTransferEncoding: {}, charset: {}, filename: {}", new Object[]{mimeType, contentTransferEncoding, charset, filename});
            } else {
                log.trace("Ignoring binary mimeType: '{}', contentTransferEncoding: '{}', charset: '{}', filename: '{}'",
                        new Object[]{mimeType, contentTransferEncoding, charset, filename});
            }
        } else {
            log.trace("Ignoring binary mimeType: '{}', contentTransferEncoding: '{}', charset: '{}', filename: '{}'",
                    new Object[]{mimeType, contentTransferEncoding, charset, filename});
        }
        return null;
    }

    /**
     * Silently fail if Tika fails parsing the content.
     * @param bodyContent
     * @param content
     * @param mimeType
     * @param filename
     * @throws IOException
     */
    private static void addAttachment(MailBodyContent bodyContent, SingleBody content, String mimeType, String filename) throws IOException {

        log.trace("processing attachment: Mime-Type='{}', filename='{}'", new Object[]{mimeType, filename});

        MailAttachment attachment = new MailAttachment();
        attachment.setContentType(mimeType);
        attachment.setFileName(filename);

        if (content instanceof BinaryBody)  {
            Metadata metadata = new Metadata();
            // TODO: add length limit
            String fileContent = null;
            try {
                fileContent = removeWhiteSpaces(getTika().parseToString(content.getInputStream(), metadata, 100000));
                attachment.setContent(fileContent);
                bodyContent.getAttachments().add(attachment);
            } catch (TikaException e) {
                log.warn("ignoring attachment: parsing error", e);
            }
        } else if (content instanceof TextBody) {
            Metadata metadata = new Metadata();
            // TODO: add length limit
            String fileContent = null;
            try {
                fileContent = removeWhiteSpaces(getTika().parseToString(content.getInputStream(), metadata, 100000));
                attachment.setContent(fileContent);
                bodyContent.getAttachments().add(attachment);
            } catch (TikaException e) {
                log.warn("ignoring attachment: parsing error", e);
            }
        } else {
            log.warn("ignoring attachment: unsupported attachment type: '{}'", content.getClass().getCanonicalName());
        }
    }

    private static String removeWhiteSpaces(String input) {
        return input.replaceAll("\r\n"," ").replaceAll("\n"," ").replaceAll("\\s+"," ").trim();
    }

    private static String filterOutQuotedContent(String content) {
        StringBuilder noQuotes = new StringBuilder();
        for (String line : content.split("\n")) {
            line = line.trim();
            if (line.length() > 0 && !line.startsWith(">")) {
                noQuotes.append(line).append(" ");
            }
        }
        String result = noQuotes.toString().trim();
        return result;
    }

    private static String getTextBodyContent(Reader reader) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = reader.read()) != -1) {
            sb.append((char) c);
        }
        reader.close();
        return sb.toString();
    }

}
