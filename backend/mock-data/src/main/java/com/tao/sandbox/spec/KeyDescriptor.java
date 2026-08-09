package com.tao.sandbox.spec;

import com.tao.sandbox.runtime.match.KeySpec;

/**
 * One declared identity field, described for the dashboard.
 *
 * <p>The {@code name} is the whole reason this is a record rather than the raw
 * {@code source:expression} declaration. A dashboard offering inputs for a mock needs to label
 * them {@code tickerSymbol}, and needs to send them back to {@code POST /__tao/mocks/name} under
 * that same label — but the declaration is
 * {@code xpath:/soapenv:Envelope/soapenv:Body/sq:TradePriceRequest/sq:tickerSymbol}. Deriving the
 * leaf from that is {@link KeySpec}'s job, done once, server-side. Handing the dashboard the raw
 * expression would make it do the deriving, which is the same drift that keeping filename
 * construction on the server exists to prevent.
 */
public record KeyDescriptor(String name, String source, String expression) {

    public static KeyDescriptor of(KeySpec spec) {
        return new KeyDescriptor(spec.name(), spec.source().name(), spec.expression());
    }
}
