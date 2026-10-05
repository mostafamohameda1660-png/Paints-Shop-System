package com.shop.utils;

import java.text.DecimalFormat;

public final class NumberUtil {

    private NumberUtil() {
    }

    private static final DecimalFormat FORMAT =
            new DecimalFormat("#,##0.00");

    public static String format(double value) {
        return FORMAT.format(value);
    }

    public static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    /**
     * تحويل النص إلى رقم مع دعم كل من النقطة (.) والفاصلة (,) كفاصل عشري.
     * يتم تحويل الفاصلة إلى نقطة تلقائياً قبل التحليل.
     * @throws IllegalArgumentException إذا كان النص فارغاً أو غير رقمي
     */
    public static double parseNumber(String text) {
        if (text == null || text.trim().isEmpty())
            throw new IllegalArgumentException("أدخل رقمًا صحيحًا");
        String cleaned = text.trim().replace(',', '.');
        try {
            return Double.parseDouble(cleaned);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("أدخل رقمًا صحيحًا");
        }
    }

}