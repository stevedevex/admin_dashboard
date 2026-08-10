package com.tao.sandbox.spec.wsdl;

import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import org.w3c.dom.ls.LSInput;

/**
 * The minimum {@link LSInput} JAXP needs: a system id, so a nested reference resolved from
 * inside this content can be looked up in turn, and the content itself as a character stream.
 * Every other property is meaningless for text already in memory.
 */
final class InMemoryLSInput implements LSInput {
    private final String systemId;
    private final String content;

    InMemoryLSInput(String systemId, String content) {
        this.systemId = systemId;
        this.content = content;
    }

    @Override
    public Reader getCharacterStream() {
        return new StringReader(content);
    }

    @Override
    public void setCharacterStream(Reader characterStream) {}

    @Override
    public InputStream getByteStream() {
        return null;
    }

    @Override
    public void setByteStream(InputStream byteStream) {}

    @Override
    public String getStringData() {
        return null;
    }

    @Override
    public void setStringData(String stringData) {}

    @Override
    public String getSystemId() {
        return systemId;
    }

    @Override
    public void setSystemId(String systemId) {}

    @Override
    public String getPublicId() {
        return null;
    }

    @Override
    public void setPublicId(String publicId) {}

    @Override
    public String getBaseURI() {
        return null;
    }

    @Override
    public void setBaseURI(String baseURI) {}

    @Override
    public String getEncoding() {
        return "UTF-8";
    }

    @Override
    public void setEncoding(String encoding) {}

    @Override
    public boolean getCertifiedText() {
        return false;
    }

    @Override
    public void setCertifiedText(boolean certifiedText) {}
    }
