package com.shop.utils;

public final class CodeGenerator {

    private CodeGenerator() {
    }

    public static String generateProductCode(int id) {

        return String.format("PT%04d", id);

    }

}