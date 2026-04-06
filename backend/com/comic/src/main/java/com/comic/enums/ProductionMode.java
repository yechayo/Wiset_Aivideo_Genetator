package com.comic.enums;

/**
 * 项目制作模式，持久化在 projectInfo.productionMode。
 */
public enum ProductionMode {
    REALTIME_ANIMATION("realtime_animation"),
    COMIC_COMMENTARY("comic_commentary");

    private final String code;

    ProductionMode(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    /**
     * @return 匹配到的模式；未知或空白返回 null
     */
    public static ProductionMode fromCode(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        for (ProductionMode m : values()) {
            if (m.code.equals(raw.trim())) {
                return m;
            }
        }
        return null;
    }
}
