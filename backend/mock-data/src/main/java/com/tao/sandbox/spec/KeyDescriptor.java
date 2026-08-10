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
 *
 * @param aliasOf the field's own name, when {@code name} is a short one chosen with {@code as};
 *     null when the two are the same. An aliased key is the one case where the name in a filename
 *     says nothing about what it reads — {@code brid} is a choice somebody made, not a field any
 *     schema mentions — so the dashboard is handed the name it stands for rather than left to
 *     present an abbreviation with no origin.
 */
public record KeyDescriptor(String name, String source, String expression, String aliasOf) {

    public static KeyDescriptor of(KeySpec spec) {
        String derived = spec.derivedName();
        return new KeyDescriptor(
                spec.name(),
                spec.source().name(),
                spec.expression(),
                derived.equalsIgnoreCase(spec.name()) ? null : derived);
    }
}
