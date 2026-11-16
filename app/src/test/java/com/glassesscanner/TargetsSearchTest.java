package com.glassesscanner;

import org.junit.Test;
import static org.junit.Assert.*;

public class TargetsSearchTest {
    @Test public void searchesAcrossNamesModelsAndIdentifiers() {
        DeviceCatalog.Entry g2 = DeviceCatalog.ENTRIES.stream()
                .filter(entry -> entry.label().equals("Even Realities G2")).findFirst().orElseThrow();
        assertTrue(TargetsEditor.matchesQuery("  eVeN   G2  ", g2.label(), g2.models(), g2.evidence()));
        assertTrue(TargetsEditor.matchesQuery(DeviceCatalog.G2_UUID.toUpperCase(java.util.Locale.ROOT),
                g2.label(), g2.models(), g2.evidence()));
        assertTrue(TargetsEditor.matchesQuery("제조사 060c", "제조사 ID", "0x060C"));
        assertFalse(TargetsEditor.matchesQuery("Even G3", g2.label(), g2.models(), g2.evidence()));
    }

    @Test public void supportsEmptyQueriesUnicodeAndLiteralPunctuation() {
        assertTrue(TargetsEditor.matchesQuery("", "Frame"));
        assertTrue(TargetsEditor.matchesQuery(" \t\n ", "Frame"));
        assertTrue(TargetsEditor.matchesQuery("Ｇ１", "Even Realities G1"));
        assertFalse(TargetsEditor.matchesQuery("[.*", "Frame"));
        assertTrue(TargetsEditor.matchesQuery("Glasses_4097", "사용자 요청 · 관측 예시 Glasses_4097"));
    }
}
