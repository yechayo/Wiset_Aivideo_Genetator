package com.comic.util;

/**
 * 圈码数字格式化工具
 * 支持 ①-㊿（1-50）范围
 */
public class NumberFormatter {

    private static final String[] CIRCLED_NUMBERS = {
        "①", "②", "③", "④", "⑤", "⑥", "⑦", "⑧", "⑨", "⑩",
        "⑪", "⑫", "⑬", "⑭", "⑮", "⑯", "⑰", "⑱", "⑲", "⑳",
        "㉑", "㉒", "㉓", "㉔", "㉕", "㉖", "㉗", "㉘", "㉙", "㉚",
        "㉛", "㉜", "㉝", "㉞", "㉟", "㊱", "㊲", "㊳", "㊴", "㊵",
        "㊶", "㊷", "㊸", "㊹", "㊺", "㊻", "㊼", "㊽", "㊾", "㊿",
    };

    /**
     * 将数字转为圈码格式（1-based）
     * @param num 1-50
     * @return 圈码字符串，超出范围返回普通数字
     */
    public static String toCircled(int num) {
        if (num >= 1 && num <= CIRCLED_NUMBERS.length) {
            return CIRCLED_NUMBERS[num - 1];
        }
        return String.valueOf(num);
    }
}
