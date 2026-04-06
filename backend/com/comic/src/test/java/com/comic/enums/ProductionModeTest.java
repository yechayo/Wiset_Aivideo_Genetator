package com.comic.enums;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ProductionModeTest {

    @Test
    void fromCode_matches_known_values() {
        assertSame(ProductionMode.REALTIME_ANIMATION, ProductionMode.fromCode("realtime_animation"));
        assertSame(ProductionMode.COMIC_COMMENTARY, ProductionMode.fromCode("comic_commentary"));
    }

    @Test
    void fromCode_null_or_blank_returns_null() {
        assertNull(ProductionMode.fromCode(null));
        assertNull(ProductionMode.fromCode(""));
        assertNull(ProductionMode.fromCode("   "));
    }

    @Test
    void fromCode_unknown_returns_null() {
        assertNull(ProductionMode.fromCode("other"));
    }

    @Test
    void getCode_roundTrip() {
        assertEquals("realtime_animation", ProductionMode.REALTIME_ANIMATION.getCode());
        assertEquals("comic_commentary", ProductionMode.COMIC_COMMENTARY.getCode());
    }
}
